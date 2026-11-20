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

    /**
     * Pure-Kotlin converter mapping a full [GeoBypassEngine.AccessProbeReport] into an [EndpointCheck].
     * Strictly requires [GeoBypassEngine.AccessProbeReport.targetReady] to return [Status.REACHABLE];
     * raw HTTP 200 responses from blocked/unverified countries or missing shells become [Status.GEO_RESTRICTED].
     */
    fun endpointCheckFromAccessProbe(
        name: String,
        urlStr: String,
        report: GeoBypassEngine.AccessProbeReport,
        target: GeoBypassEngine.TargetService,
        resolvedIpOverride: String = ""
    ): EndpointCheck {
        val ready = report.targetReady(target)
        val probe = if (target == GeoBypassEngine.TargetService.GOOGLE_FLOW) report.flow else report.aiStudio
        val ccOk = if (target == GeoBypassEngine.TargetService.GOOGLE_FLOW) {
            report.flowCountrySupported
        } else {
            report.aiStudioCountrySupported
        }
        val resolved = report.egress?.ip?.takeIf { it.isNotBlank() } ?: resolvedIpOverride
        val status = when {
            ready -> Status.REACHABLE
            probe.unsupportedRegion ||
                report.egress == null ||
                !ccOk ||
                (target == GeoBypassEngine.TargetService.GOOGLE_FLOW && probe.reachable && !probe.appShellOk) ->
                Status.GEO_RESTRICTED
            !report.accountsReachable && probe.reachable -> Status.HTTP_RESTRICTED
            probe.httpCode > 0 -> classifyHttpCode(probe.httpCode)
            else -> Status.TLS_OR_IP_BLOCKED
        }
        val detail = when {
            ready -> "Geo-clear (${report.egress?.countryCode ?: "?"} · ${probe.latencyMs}ms)"
            report.egress == null && probe.httpCode > 0 ->
                "Geo-unverified (HTTP ${probe.httpCode}; public exit country unknown)"
            report.egress != null && !ccOk ->
                "Geo-restricted in ${report.egress.countryCode} (${report.egress.ip})"
            probe.unsupportedRegion -> probe.detail
            target == GeoBypassEngine.TargetService.GOOGLE_FLOW && probe.reachable && !probe.appShellOk ->
                "Geo/App shell unverified (AiSandboxAngularFrontend missing · HTTP ${probe.httpCode})"
            !report.accountsReachable && probe.reachable ->
                "accounts.google.com blocked (sign-in unavailable)"
            else -> probe.detail
        }
        return EndpointCheck(
            name = name,
            url = urlStr,
            status = status,
            httpCode = probe.httpCode,
            latencyMs = probe.latencyMs,
            resolvedIp = resolved,
            detail = detail
        )
    }

    /** Verifies real DNS + TLS + HTTP + true non-geoblock clearance for a single HTTPS URL (optionally through [proxy]). */
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
            val route = GeoBypassEngine.RouteResolution(
                shouldStartDastyabVpn = false,
                shouldStartLocalBridge = false,
                webViewHttpProxyPort = null,
                javaNetProxy = proxy,
                routeLabel = if (proxy == Proxy.NO_PROXY) "Direct" else "Proxy",
                externalVpnActive = false,
                targetService = if (isFlow) GeoBypassEngine.TargetService.GOOGLE_FLOW else GeoBypassEngine.TargetService.AI_STUDIO
            )
            val report = GeoBypassEngine.runAccessProbe(route, timeoutMs)
            return endpointCheckFromAccessProbe(
                name = name,
                urlStr = urlStr,
                report = report,
                target = if (isFlow) GeoBypassEngine.TargetService.GOOGLE_FLOW else GeoBypassEngine.TargetService.AI_STUDIO,
                resolvedIpOverride = resolvedIp
            )
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

    /** Verifies general internet + strict non-geoblocked Google AI Studio + Google Flow in parallel. */
    fun verifyAll(timeoutMs: Int = 3500, proxy: Proxy = Proxy.NO_PROXY): FullVerification {
        val pool = Executors.newFixedThreadPool(2)
        return try {
            val fInternet = pool.submit(Callable { verifyHttpsEndpoint("Internet", URL_INTERNET_CHECK, timeoutMs, proxy) })
            val syntheticRoute = GeoBypassEngine.RouteResolution(
                shouldStartDastyabVpn = false,
                shouldStartLocalBridge = false,
                webViewHttpProxyPort = null,
                javaNetProxy = proxy,
                routeLabel = if (proxy == Proxy.NO_PROXY) "Direct" else "Proxy",
                externalVpnActive = false
            )
            val fGeoProbe = pool.submit(Callable { GeoBypassEngine.runAccessProbe(syntheticRoute, timeoutMs) })

            val internet = runCatching { fInternet.get(timeoutMs + 2500L, TimeUnit.MILLISECONDS) }.getOrElse {
                EndpointCheck("Internet", URL_INTERNET_CHECK, Status.TLS_OR_IP_BLOCKED, -1, -1, "", "Verification timed out")
            }
            val report = runCatching { fGeoProbe.get(timeoutMs + 4500L, TimeUnit.MILLISECONDS) }.getOrNull()
            if (report == null) {
                val fail = EndpointCheck("Check", "", Status.GEO_RESTRICTED, -1, -1, "", "Geo verification timed out")
                FullVerification(internet, fail.copy(name = "AI Studio", url = URL_AISTUDIO), fail.copy(name = "Google Flow", url = URL_FLOW))
            } else {
                FullVerification(
                    internet = internet,
                    aiStudio = endpointCheckFromAccessProbe("AI Studio", URL_AISTUDIO, report, GeoBypassEngine.TargetService.AI_STUDIO),
                    flow = endpointCheckFromAccessProbe("Google Flow", URL_FLOW, report, GeoBypassEngine.TargetService.GOOGLE_FLOW)
                )
            }
        } catch (_: Exception) {
            val fail = EndpointCheck("Check", "", Status.TLS_OR_IP_BLOCKED, -1, -1, "", "Verification timed out")
            FullVerification(fail.copy(name = "Internet"), fail.copy(name = "AI Studio"), fail.copy(name = "Google Flow"))
        } finally {
            pool.shutdownNow()
        }
    }
}
