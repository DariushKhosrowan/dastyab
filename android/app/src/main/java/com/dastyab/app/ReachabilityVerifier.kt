package com.dastyab.app

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ReachabilityVerifier — real end-to-end HTTPS/DNS reachability checker specifically
 * engineered for Google AI Studio, Google Flow, and general internet health (`generate_204`).
 *
 * Ensures DastYab never reports a tunnel as "working" when websites are unreachable,
 * and distinguishes between DNS poisoning, TLS/IP blocking, geo/HTTP blocking, and healthy access.
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
        HTTP_RESTRICTED
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

    /** Verifies real DNS + TLS + HTTP reachability for a single HTTPS URL. */
    fun verifyHttpsEndpoint(name: String, urlStr: String, timeoutMs: Int = 3500): EndpointCheck {
        val host = try {
            URL(urlStr).host
        } catch (_: Exception) {
            return EndpointCheck(name, urlStr, Status.DNS_FAILED, -1, -1, "", "Invalid URL")
        }

        // 1. Verify DNS resolution and check for ISP sinkhole/poisoned IPs
        val resolvedIp = try {
            InetAddress.getByName(host).hostAddress ?: ""
        } catch (_: Exception) {
            return EndpointCheck(name, urlStr, Status.DNS_FAILED, -1, -1, "", "DNS lookup failed for $host")
        }

        if (NetworkTools.isSinkholeOrPrivateIp(resolvedIp)) {
            return EndpointCheck(
                name = name,
                url = urlStr,
                status = Status.DNS_POISONED,
                httpCode = -1,
                latencyMs = -1,
                resolvedIp = resolvedIp,
                detail = "DNS poisoned ($host → $resolvedIp)"
            )
        }

        // 2. Real HTTPS GET/HEAD check
        val start = System.nanoTime()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
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

    /** Verifies general internet + Google AI Studio + Google Flow in parallel (~3.5s max). */
    fun verifyAll(timeoutMs: Int = 3500): FullVerification {
        val pool = Executors.newFixedThreadPool(3)
        return try {
            val fInternet = pool.submit(Callable { verifyHttpsEndpoint("Internet", URL_INTERNET_CHECK, timeoutMs) })
            val fAi = pool.submit(Callable { verifyHttpsEndpoint("AI Studio", URL_AISTUDIO, timeoutMs) })
            val fFlow = pool.submit(Callable { verifyHttpsEndpoint("Google Flow", URL_FLOW, timeoutMs) })
            FullVerification(
                internet = fInternet.get(timeoutMs + 1500L, TimeUnit.MILLISECONDS),
                aiStudio = fAi.get(timeoutMs + 1500L, TimeUnit.MILLISECONDS),
                flow = fFlow.get(timeoutMs + 1500L, TimeUnit.MILLISECONDS)
            )
        } catch (_: Exception) {
            val fail = EndpointCheck("Check", "", Status.TLS_OR_IP_BLOCKED, -1, -1, "", "Verification timed out")
            FullVerification(fail.copy(name = "Internet"), fail.copy(name = "AI Studio"), fail.copy(name = "Google Flow"))
        } finally {
            pool.shutdownNow()
        }
    }
}
