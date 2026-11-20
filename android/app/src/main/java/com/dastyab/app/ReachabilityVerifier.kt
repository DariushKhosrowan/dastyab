package com.dastyab.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ReachabilityVerifier — real end-to-end HTTPS/DNS reachability checker specifically
 * engineered for Google AI Studio, Google Flow, and general internet health (`generate_204`).
 *
 * Ensures DastYab never reports a tunnel as "working" when websites are geo-restricted or blocked,
 * and distinguishes between DNS poisoning, TLS/IP blocking, geo-restriction, HTTP blocking, and healthy access.
 */
object ReachabilityVerifier {

    const val URL_INTERNET_CHECK = "https://www.google.com/generate_204"
    const val URL_AISTUDIO = "https://aistudio.google.com/"
    const val URL_FLOW = "https://flow.google.com/"

    enum class Status {
        REACHABLE,
        DNS_POISONED,
        DNS_FAILED,
        TLS_OR_IP_BLOCKED,
        HTTP_RESTRICTED,
        GEO_RESTRICTED
    }

    data class EndpointCheck(
        val name: String,
        val url: String,
        val status: Status,
        val httpCode: Int,
        val latencyMs: Long,
        val resolvedIp: String,
        val detail: String
    ) {
        val isOk: Boolean get() = status == Status.REACHABLE
    }

    data class FullVerification(
        val internet: EndpointCheck,
        val aiStudio: EndpointCheck,
        val flow: EndpointCheck
    ) {
        val anyGoogleReady: Boolean get() = aiStudio.isOk || flow.isOk
        val allReady: Boolean get() = internet.isOk && aiStudio.isOk && flow.isOk
    }

    /** Pure-Kotlin HTTP status classifier (unit-testable on the JVM). */
    fun classifyHttpCode(httpCode: Int): Status = when {
        httpCode in 200..399 -> Status.REACHABLE
        httpCode == 403 || httpCode == 451 -> Status.HTTP_RESTRICTED
        httpCode in 400..499 -> Status.REACHABLE // e.g. 401/404 still proves endpoint reached
        else -> Status.HTTP_RESTRICTED
    }

    /** Verifies real DNS + TLS + HTTP + geo-gate reachability for a single HTTPS URL (optionally through [proxy]). */
    fun verifyHttpsEndpoint(
        name: String,
        urlStr: String,
        timeoutMs: Int = 3500,
        proxy: Proxy = Proxy.NO_PROXY
    ): EndpointCheck {
        val host = try {
            URL(urlStr).host
        } catch (_: Exception) {
            return EndpointCheck(name, urlStr, Status.DNS_FAILED, -1, -1, "", "Invalid URL")
        }

        // 1. When connecting without an upstream proxy, check local DNS resolution & sinkhole IPs.
        val resolvedIp = if (proxy == Proxy.NO_PROXY) {
            val ip = try {
                InetAddress.getByName(host).hostAddress ?: ""
            } catch (_: Exception) {
                return EndpointCheck(name, urlStr, Status.DNS_FAILED, -1, -1, "", "DNS lookup failed for $host")
            }
            if (NetworkTools.isSinkholeOrPrivateIp(ip)) {
                return EndpointCheck(
                    name = name,
                    url = urlStr,
                    status = Status.DNS_POISONED,
                    httpCode = -1,
                    latencyMs = -1,
                    resolvedIp = ip,
                    detail = "DNS poisoned ($host → $ip)"
                )
            }
            ip
        } else {
            "via-proxy"
        }

        val isAiStudio = host.contains("aistudio.google.com", ignoreCase = true)
        val isFlow = host.contains("flow.google.com", ignoreCase = true)
        if (isAiStudio || isFlow) {
            val probe = GeoBypassEngine.probeGoogleEndpoint(
                urlStr = urlStr,
                proxy = proxy,
                timeoutMs = timeoutMs,
                inspectFlowGate = isFlow,
                inspectAiStudioGate = isAiStudio
            )
            val status = when {
                probe.unsupportedRegion -> Status.GEO_RESTRICTED
                isFlow && probe.reachable && !probe.appShellOk -> Status.GEO_RESTRICTED
                probe.reachable -> Status.REACHABLE
                probe.httpCode > 0 -> classifyHttpCode(probe.httpCode)
                else -> Status.TLS_OR_IP_BLOCKED
            }
            return EndpointCheck(name, urlStr, status, probe.httpCode, probe.latencyMs, resolvedIp, probe.detail)
        }

        // 2. Real HTTPS GET/HEAD check for generic endpoints (e.g. generate_204)
        val start = System.nanoTime()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) DastYab/1.9")
            }
            val code = conn.responseCode
            val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start).coerceAtLeast(1L)
            val status = classifyHttpCode(code)
            val detail = when (status) {
                Status.REACHABLE -> "Reachable (HTTP $code · ${ms}ms)"
                Status.HTTP_RESTRICTED -> "Blocked/Geo-restricted (HTTP $code · ${ms}ms)"
                else -> "HTTP $code (${ms}ms)"
            }
            EndpointCheck(name, urlStr, status, code, ms, resolvedIp, detail)
        } catch (e: Exception) {
            EndpointCheck(
                name = name,
                url = urlStr,
                status = Status.TLS_OR_IP_BLOCKED,
                httpCode = -1,
                latencyMs = -1,
                resolvedIp = resolvedIp,
                detail = "TLS/IP blocked (${e.javaClass.simpleName}: ${e.message ?: "timeout"})"
            )
        } finally {
            conn?.disconnect()
        }
    }

    /** Verifies general internet + Google AI Studio + Google Flow in parallel. */
    fun verifyAll(timeoutMs: Int = 3500, proxy: Proxy = Proxy.NO_PROXY): FullVerification {
        val pool = Executors.newFixedThreadPool(3)
        return try {
            val fInternet = pool.submit(Callable { verifyHttpsEndpoint("Internet", URL_INTERNET_CHECK, timeoutMs, proxy) })
            val fAi = pool.submit(Callable { verifyHttpsEndpoint("AI Studio", URL_AISTUDIO, timeoutMs, proxy) })
            val fFlow = pool.submit(Callable { verifyHttpsEndpoint("Google Flow", URL_FLOW, timeoutMs, proxy) })
            FullVerification(
                internet = fInternet.get(timeoutMs + 2500L, TimeUnit.MILLISECONDS),
                aiStudio = fAi.get(timeoutMs + 2500L, TimeUnit.MILLISECONDS),
                flow = fFlow.get(timeoutMs + 2500L, TimeUnit.MILLISECONDS)
            )
        } catch (_: Exception) {
            val fail = EndpointCheck("Check", "", Status.TLS_OR_IP_BLOCKED, -1, -1, "", "Verification timed out")
            FullVerification(fail.copy(name = "Internet"), fail.copy(name = "AI Studio"), fail.copy(name = "Google Flow"))
        } finally {
            pool.shutdownNow()
        }
    }
}
