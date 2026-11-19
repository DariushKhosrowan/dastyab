package com.dastyab.app

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * GeoBypassEngine — Android adaptation of `dastyab-aistudio.py` and `dastyab-flow.py`.
 *
 * Combines:
 *  • Google Flow's official 151-country ISO-3166 allowlist (`FLOW_SUPPORTED_COUNTRIES`)
 *  • Real-time local proxy port discovery (v2rayNG, NekoBox, Hiddify, Clash, Orbot, DastYab Bridge)
 *  • Egress IP & country verification executed **through the exact selected proxy/VPN path**
 *  • Live `aistudio.google.com` & `flow.google.com` verification including `/unsupported-country`
 *    redirect & HTML marker detection
 *  • Multi-layer VPN + Internal App Proxy + Local Proxy Port strategy resolution so Lantern or
 *    another system VPN can stay active on top without OS `VpnService` conflicts.
 */
object GeoBypassEngine {

    /**
     * Google's official "Where you can use Google Flow" country list (`support.google.com/flow/answer/16353544`),
     * matching `dastyab-flow.py` (`SUPPORTED_COUNTRIES`).
     */
    val FLOW_SUPPORTED_COUNTRIES: Set<String> = setOf(
        "AL", "DZ", "AS", "AO", "AG", "AR", "AM", "AW", "AU", "AT", "AZ", "BS", "BH", "BD", "BE",
        "BZ", "BJ", "BM", "BO", "BA", "BW", "BR", "BG", "BF", "CV", "KH", "CM", "CA", "KY", "CL",
        "CO", "CR", "CI", "HR", "CY", "CZ", "DK", "DO", "EC", "EG", "SV", "EE", "FJ", "FI", "FR",
        "GA", "DE", "GH", "GI", "GR", "GU", "GT", "GW", "HT", "HN", "HK", "HU", "IS", "IN", "ID",
        "IQ", "IE", "IL", "IT", "JM", "JP", "JO", "KZ", "KE", "KW", "KG", "LA", "LV", "LB", "LI",
        "LT", "LU", "MO", "MY", "MV", "ML", "MT", "MU", "MX", "MD", "MC", "MA", "MZ", "NA", "NP",
        "NL", "NZ", "NI", "NE", "NG", "MK", "MP", "NO", "OM", "PK", "PW", "PA", "PG", "PY", "PE",
        "PH", "PL", "PT", "PR", "QA", "RO", "RW", "SM", "SA", "SN", "RS", "SC", "SL", "SG", "SK",
        "SI", "ZA", "KR", "ES", "LK", "SE", "CH", "TW", "TJ", "TZ", "TH", "TG", "TO", "TT", "TN",
        "TR", "TM", "VI", "UG", "UA", "AE", "GB", "US", "UY", "UZ", "VE", "VN", "VG", "YE", "ZM",
        "ZW"
    )

    data class ProxyPreset(
        val id: String,
        val label: String,
        val scheme: TunnelConfig.ProxyScheme,
        val host: String,
        val port: Int,
        val description: String
    )

    val KNOWN_LOCAL_PROXY_PRESETS: List<ProxyPreset> = listOf(
        ProxyPreset("v2rayng-http", "v2rayNG (HTTP :10809)", TunnelConfig.ProxyScheme.HTTP, "127.0.0.1", 10809, "Default v2rayNG local HTTP proxy"),
        ProxyPreset("v2rayng-socks", "v2rayNG (SOCKS5 :10808)", TunnelConfig.ProxyScheme.SOCKS5, "127.0.0.1", 10808, "Default v2rayNG local SOCKS5 proxy"),
        ProxyPreset("nekobox-socks", "NekoBox (SOCKS5 :2080)", TunnelConfig.ProxyScheme.SOCKS5, "127.0.0.1", 2080, "Default NekoBox / Sing-Box mixed SOCKS5"),
        ProxyPreset("nekobox-http", "NekoBox (HTTP :2081)", TunnelConfig.ProxyScheme.HTTP, "127.0.0.1", 2081, "NekoBox local HTTP proxy"),
        ProxyPreset("hiddify-mixed", "Hiddify (HTTP :12334)", TunnelConfig.ProxyScheme.HTTP, "127.0.0.1", 12334, "Default Hiddify mixed proxy port"),
        ProxyPreset("clash-mixed", "Clash / Mihomo (:7890)", TunnelConfig.ProxyScheme.HTTP, "127.0.0.1", 7890, "Standard Clash / Meta mixed proxy port"),
        ProxyPreset("orbot-http", "Orbot / Tor (HTTP :8118)", TunnelConfig.ProxyScheme.HTTP, "127.0.0.1", 8118, "Tor / Orbot HTTP tunnel port"),
        ProxyPreset("orbot-socks", "Orbot / Tor (SOCKS5 :9050)", TunnelConfig.ProxyScheme.SOCKS5, "127.0.0.1", 9050, "Tor / Orbot SOCKS5 proxy port")
    )

    data class ListeningProxy(
        val preset: ProxyPreset,
        val latencyMs: Long
    )

    data class EgressInfo(
        val ip: String,
        val countryCode: String,
        val countryName: String,
        val isProxyOrHosting: Boolean,
        val source: String
    )

    data class ServiceProbeResult(
        val reachable: Boolean,
        val httpCode: Int,
        val latencyMs: Long,
        val unsupportedRegion: Boolean,
        val finalLocation: String,
        val detail: String
    )

    data class AccessProbeReport(
        val routeLabel: String,
        val egress: EgressInfo?,
        val flowCountrySupported: Boolean,
        val aiStudio: ServiceProbeResult,
        val flow: ServiceProbeResult,
        val headline: String,
        val recommendation: String
    ) {
        val readyForAiStudio: Boolean get() = aiStudio.reachable
        val readyForFlow: Boolean get() = flow.reachable && !flow.unsupportedRegion &&
            (egress == null || flowCountrySupported)
    }

    data class RouteResolution(
        val shouldStartDastyabVpn: Boolean,
        val shouldStartLocalBridge: Boolean,
        val webViewHttpProxyPort: Int?,
        val javaNetProxy: Proxy,
        val routeLabel: String,
        val externalVpnActive: Boolean
    )

    fun isFlowCountrySupported(countryCode: String?): Boolean {
        val norm = countryCode?.trim()?.uppercase().orEmpty()
        return norm.length == 2 && FLOW_SUPPORTED_COUNTRIES.contains(norm)
    }

    /** Checks redirect Location + page body markers matching `dastyab-flow.py` GATE_MARKERS. */
    fun isFlowUnsupportedResponse(httpCode: Int, locationHeader: String?, bodySnippet: String? = null): Boolean {
        val loc = locationHeader?.lowercase().orEmpty()
        if (loc.contains("/unsupported-country") || loc.contains("unsupported-country")) {
            return true
        }
        val body = bodySnippet?.lowercase().orEmpty()
        if (body.contains("unsupported-country-title") ||
            body.contains("not available in your country yet") ||
            body.contains("flow is not available in your country")
        ) {
            return true
        }
        return httpCode == 451
    }

    fun formatV2rayNgSocksUri(host: String = "127.0.0.1", port: Int = TunnelConfig.DEFAULT_LOCAL_BRIDGE_PORT, tag: String = "DastYab-Local-Bridge"): String {
        val cleanHost = TunnelConfig.sanitizeProxyHost(host)
        val cleanPort = TunnelConfig.clampPort(port, TunnelConfig.DEFAULT_LOCAL_BRIDGE_PORT, min = 1)
        return "socks://$cleanHost:$cleanPort#$tag"
    }

    /** Pure-Kotlin planner determining how AI Studio / Flow + Local Proxy should route traffic. */
    fun planRoute(
        settings: TunnelConfig.Settings,
        externalVpnActive: Boolean,
        dastyabVpnActive: Boolean,
        runningBridgePort: Int?,
        detectedListeningHttpPort: Int?
    ): RouteResolution {
        val upstreamConfigured = settings.upstreamProxyScheme != TunnelConfig.ProxyScheme.DIRECT &&
            settings.upstreamProxyPort in 1..65535
        val upstreamHost = TunnelConfig.sanitizeProxyHost(settings.upstreamProxyHost)
        val upstreamPort = settings.upstreamProxyPort

        return when (settings.googleAccessMode) {
            TunnelConfig.GoogleAccessMode.EXTERNAL_VPN -> {
                RouteResolution(
                    shouldStartDastyabVpn = false,
                    shouldStartLocalBridge = false,
                    webViewHttpProxyPort = null,
                    javaNetProxy = Proxy.NO_PROXY,
                    routeLabel = if (externalVpnActive) "External VPN / Lantern" else "Direct (Start Lantern / VPN)",
                    externalVpnActive = externalVpnActive
                )
            }
            TunnelConfig.GoogleAccessMode.IN_APP_PROXY -> {
                val (wvPort, needsBridge, javaProxy, label) = when {
                    settings.upstreamProxyScheme == TunnelConfig.ProxyScheme.HTTP -> {
                        val p = upstreamPort
                        Quad(p, false, Proxy(Proxy.Type.HTTP, InetSocketAddress(upstreamHost, p)), "App Proxy HTTP $upstreamHost:$p")
                    }
                    settings.upstreamProxyScheme == TunnelConfig.ProxyScheme.SOCKS5 -> {
                        val bridge = runningBridgePort ?: settings.localBridgePort
                        Quad(bridge, runningBridgePort == null, Proxy(Proxy.Type.SOCKS, InetSocketAddress(upstreamHost, upstreamPort)), "App Proxy SOCKS5 $upstreamHost:$upstreamPort (Bridge :$bridge)")
                    }
                    detectedListeningHttpPort != null -> {
                        Quad(detectedListeningHttpPort, false, Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", detectedListeningHttpPort)), "Auto Local Proxy :$detectedListeningHttpPort")
                    }
                    else -> {
                        val bridge = runningBridgePort ?: settings.localBridgePort
                        Quad(bridge, runningBridgePort == null, Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", bridge)), "DastYab Internal Bridge :$bridge")
                    }
                }
                RouteResolution(
                    shouldStartDastyabVpn = false,
                    shouldStartLocalBridge = needsBridge,
                    webViewHttpProxyPort = wvPort,
                    javaNetProxy = javaProxy,
                    routeLabel = if (externalVpnActive) "$label + External VPN" else label,
                    externalVpnActive = externalVpnActive
                )
            }
            TunnelConfig.GoogleAccessMode.LOCAL_BRIDGE -> {
                val bridgePort = runningBridgePort ?: settings.localBridgePort
                val javaProxy = when {
                    upstreamConfigured && settings.upstreamProxyScheme == TunnelConfig.ProxyScheme.HTTP ->
                        Proxy(Proxy.Type.HTTP, InetSocketAddress(upstreamHost, upstreamPort))
                    upstreamConfigured && settings.upstreamProxyScheme == TunnelConfig.ProxyScheme.SOCKS5 ->
                        Proxy(Proxy.Type.SOCKS, InetSocketAddress(upstreamHost, upstreamPort))
                    else ->
                        Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", bridgePort))
                }
                RouteResolution(
                    shouldStartDastyabVpn = false,
                    shouldStartLocalBridge = runningBridgePort == null,
                    webViewHttpProxyPort = bridgePort,
                    javaNetProxy = javaProxy,
                    routeLabel = "Local Proxy Port 127.0.0.1:$bridgePort",
                    externalVpnActive = externalVpnActive
                )
            }
            TunnelConfig.GoogleAccessMode.VPN_CHAIN_PROXY -> {
                val httpPort = when {
                    settings.upstreamProxyScheme == TunnelConfig.ProxyScheme.HTTP -> upstreamPort
                    settings.upstreamProxyScheme == TunnelConfig.ProxyScheme.SOCKS5 -> runningBridgePort ?: settings.localBridgePort
                    runningBridgePort != null -> runningBridgePort
                    else -> detectedListeningHttpPort
                }
                val needsBridge = settings.upstreamProxyScheme == TunnelConfig.ProxyScheme.SOCKS5 && runningBridgePort == null
                val javaProxy = when {
                    settings.upstreamProxyScheme == TunnelConfig.ProxyScheme.HTTP ->
                        Proxy(Proxy.Type.HTTP, InetSocketAddress(upstreamHost, upstreamPort))
                    settings.upstreamProxyScheme == TunnelConfig.ProxyScheme.SOCKS5 ->
                        Proxy(Proxy.Type.SOCKS, InetSocketAddress(upstreamHost, upstreamPort))
                    httpPort != null ->
                        Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpPort))
                    else -> Proxy.NO_PROXY
                }
                RouteResolution(
                    shouldStartDastyabVpn = !dastyabVpnActive,
                    shouldStartLocalBridge = needsBridge,
                    webViewHttpProxyPort = httpPort,
                    javaNetProxy = javaProxy,
                    routeLabel = if (httpPort != null) "DastYab VPN + Proxy :$httpPort" else "DastYab VPN",
                    externalVpnActive = externalVpnActive
                )
            }
            TunnelConfig.GoogleAccessMode.AUTO_SMART -> {
                when {
                    // Never kill an already active external VPN like Lantern!
                    externalVpnActive -> {
                        val wvPort = when {
                            upstreamConfigured && settings.upstreamProxyScheme == TunnelConfig.ProxyScheme.HTTP -> upstreamPort
                            upstreamConfigured && settings.upstreamProxyScheme == TunnelConfig.ProxyScheme.SOCKS5 -> runningBridgePort ?: settings.localBridgePort
                            else -> null
                        }
                        val needsBridge = upstreamConfigured && settings.upstreamProxyScheme == TunnelConfig.ProxyScheme.SOCKS5 && runningBridgePort == null
                        val javaProxy = when {
                            upstreamConfigured && settings.upstreamProxyScheme == TunnelConfig.ProxyScheme.HTTP ->
                                Proxy(Proxy.Type.HTTP, InetSocketAddress(upstreamHost, upstreamPort))
                            upstreamConfigured && settings.upstreamProxyScheme == TunnelConfig.ProxyScheme.SOCKS5 ->
                                Proxy(Proxy.Type.SOCKS, InetSocketAddress(upstreamHost, upstreamPort))
                            else -> Proxy.NO_PROXY
                        }
                        RouteResolution(
                            shouldStartDastyabVpn = false,
                            shouldStartLocalBridge = needsBridge,
                            webViewHttpProxyPort = wvPort,
                            javaNetProxy = javaProxy,
                            routeLabel = if (wvPort != null) "External VPN + Proxy :$wvPort" else "External VPN (Lantern / System)",
                            externalVpnActive = true
                        )
                    }
                    // User configured an explicit upstream local proxy (v2rayNG / NekoBox / Orbot / Clash)
                    upstreamConfigured -> {
                        val wvPort = if (settings.upstreamProxyScheme == TunnelConfig.ProxyScheme.HTTP) {
                            upstreamPort
                        } else {
                            runningBridgePort ?: settings.localBridgePort
                        }
                        val needsBridge = settings.upstreamProxyScheme == TunnelConfig.ProxyScheme.SOCKS5 && runningBridgePort == null
                        val javaProxy = if (settings.upstreamProxyScheme == TunnelConfig.ProxyScheme.HTTP) {
                            Proxy(Proxy.Type.HTTP, InetSocketAddress(upstreamHost, upstreamPort))
                        } else {
                            Proxy(Proxy.Type.SOCKS, InetSocketAddress(upstreamHost, upstreamPort))
                        }
                        RouteResolution(
                            shouldStartDastyabVpn = false,
                            shouldStartLocalBridge = needsBridge,
                            webViewHttpProxyPort = wvPort,
                            javaNetProxy = javaProxy,
                            routeLabel = "Local Proxy ${settings.upstreamProxyScheme.name} $upstreamHost:$upstreamPort",
                            externalVpnActive = false
                        )
                    }
                    // Local HTTP proxy already listening on loopback (e.g. v2rayNG / Clash / Hiddify)
                    detectedListeningHttpPort != null -> {
                        RouteResolution(
                            shouldStartDastyabVpn = false,
                            shouldStartLocalBridge = false,
                            webViewHttpProxyPort = detectedListeningHttpPort,
                            javaNetProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", detectedListeningHttpPort)),
                            routeLabel = "Detected Proxy 127.0.0.1:$detectedListeningHttpPort",
                            externalVpnActive = false
                        )
                    }
                    // Otherwise use DastYab VPN if auto-connect enabled
                    else -> {
                        RouteResolution(
                            shouldStartDastyabVpn = settings.autoVpnForGoogle && !dastyabVpnActive,
                            shouldStartLocalBridge = settings.autoStartLocalBridge && runningBridgePort == null,
                            webViewHttpProxyPort = runningBridgePort,
                            javaNetProxy = if (runningBridgePort != null) {
                                Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", runningBridgePort))
                            } else {
                                Proxy.NO_PROXY
                            },
                            routeLabel = if (dastyabVpnActive) "DastYab VPN (${settings.mode.label})" else "Direct / DastYab DoH",
                            externalVpnActive = false
                        )
                    }
                }
            }
        }
    }

    private data class Quad(val wvPort: Int?, val needsBridge: Boolean, val proxy: Proxy, val label: String)

    /** Resolves current launch plan on Android using live VPN/bridge/port detection. */
    fun resolveCurrentRoute(context: Context, settings: TunnelConfig.Settings = TunnelConfig.load(context)): RouteResolution {
        val extVpn = NetworkUtils.hasActiveExternalVpn(context)
        val dastyabVpn = DastyabVpnService.isConnected()
        val bridgePort = LocalProxyService.activePort()
        val detectedHttp = detectFirstHttpProxyPort()
        return planRoute(settings, extVpn, dastyabVpn, bridgePort, detectedHttp)
    }

    /** Probes all known local proxy ports in parallel (~150ms total) to surface running apps. */
    fun discoverListeningProxies(timeoutMs: Int = 140): List<ListeningProxy> {
        val pool = Executors.newFixedThreadPool(KNOWN_LOCAL_PROXY_PRESETS.size.coerceAtMost(8))
        return try {
            val tasks = KNOWN_LOCAL_PROXY_PRESETS.map { preset ->
                Callable {
                    val ms = CleanEndpointScout.probeTcpLatency(preset.host, preset.port, timeoutMs)
                    if (ms >= 0) ListeningProxy(preset, ms) else null
                }
            }
            pool.invokeAll(tasks, 1200, TimeUnit.MILLISECONDS)
                .mapNotNull { runCatching { it.get() }.getOrNull() }
                .sortedBy { it.latencyMs }
        } catch (_: Exception) {
            emptyList()
        } finally {
            pool.shutdownNow()
        }
    }

    fun detectFirstHttpProxyPort(timeoutMs: Int = 110): Int? {
        val httpCandidates = listOf(10809, 12334, 2081, 8118, 7890, 8080)
        for (port in httpCandidates) {
            if (CleanEndpointScout.probeTcpLatency("127.0.0.1", port, timeoutMs) >= 0) {
                return port
            }
        }
        return null
    }

    /** Fetches public egress IP & country ISO code **through [proxy]**. */
    fun fetchEgressGeo(proxy: Proxy = Proxy.NO_PROXY, timeoutMs: Int = 4000): EgressInfo? {
        // Primary: Cloudflare HTTPS trace gives instant IP + country code (loc=) cleanly over TLS
        val traceConnResult = runCatching {
            val conn = (URL("https://1.1.1.1/cdn-cgi/trace").openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "DastYab/1.9")
            }
            try {
                if (conn.responseCode == 200) {
                    val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                    val trace = NetworkTools.parseCloudflareTrace(body)
                    val cc = trace.location.uppercase().takeIf { it.length == 2 && it != "XX" } ?: ""
                    if (trace.ip != "unknown" && cc.isNotEmpty()) {
                        EgressInfo(
                            ip = trace.ip,
                            countryCode = cc,
                            countryName = cc,
                            isProxyOrHosting = false,
                            source = "cloudflare-trace"
                        )
                    } else null
                } else null
            } finally {
                conn.disconnect()
            }
        }.getOrNull()

        // Try HTTPS IP intelligence JSON (ipwho.is / ipapi.co) to enrich country name & hosting flag
        val jsonUrlCandidates = listOf(
            "https://ipwho.is/",
            "https://ipapi.co/json/"
        )
        for (u in jsonUrlCandidates) {
            val enriched = runCatching {
                val conn = (URL(u).openConnection(proxy) as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) DastYab/1.9")
                }
                try {
                    if (conn.responseCode == 200) {
                        val raw = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                        val json = JSONObject(raw)
                        val ip = json.optString("ip", "").ifBlank { traceConnResult?.ip.orEmpty() }
                        val cc = json.optString("country_code", "").uppercase()
                            .ifBlank { traceConnResult?.countryCode.orEmpty() }
                        val cname = json.optString("country", "").ifBlank { json.optString("country_name", cc) }
                        val sec = json.optJSONObject("security")
                        val hosting = (sec?.optBoolean("hosting", false) == true) ||
                            (sec?.optBoolean("proxy", false) == true)
                        if (ip.isNotBlank() && cc.isNotBlank()) {
                            EgressInfo(
                                ip = ip,
                                countryCode = cc,
                                countryName = cname.ifBlank { cc },
                                isProxyOrHosting = hosting,
                                source = URL(u).host
                            )
                        } else null
                    } else null
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            if (enriched != null) return enriched
        }

        return traceConnResult
    }

    /** Probes HTTPS reachability + geo-gate status through [proxy]. */
    fun probeGoogleEndpoint(
        urlStr: String,
        proxy: Proxy = Proxy.NO_PROXY,
        timeoutMs: Int = 4000,
        inspectFlowGate: Boolean = false
    ): ServiceProbeResult {
        val start = System.nanoTime()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = if (inspectFlowGate) "GET" else "HEAD"
                instanceFollowRedirects = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36")
            }
            val code = conn.responseCode
            val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start).coerceAtLeast(1L)
            val loc = conn.getHeaderField("Location").orEmpty()
            val snippet = if (inspectFlowGate && code in 200..399) {
                runCatching {
                    BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                        val buf = CharArray(8192)
                        val n = reader.read(buf)
                        if (n > 0) String(buf, 0, n) else ""
                    }
                }.getOrDefault("")
            } else {
                ""
            }
            val unsupported = isFlowUnsupportedResponse(code, loc, snippet)
            val reachable = !unsupported && (code in 200..399 || (code in 400..499 && code != 403 && code != 451))
            val detail = when {
                unsupported -> "Geo-blocked (/unsupported-country · HTTP $code)"
                reachable -> "Reachable (HTTP $code · ${ms}ms)"
                else -> "Blocked HTTP $code (${ms}ms)"
            }
            ServiceProbeResult(reachable, code, ms, unsupported, loc, detail)
        } catch (e: Exception) {
            ServiceProbeResult(
                reachable = false,
                httpCode = -1,
                latencyMs = -1L,
                unsupportedRegion = false,
                finalLocation = "",
                detail = "Unreachable (${e.javaClass.simpleName}: ${e.message ?: "timeout"})"
            )
        } finally {
            conn?.disconnect()
        }
    }

    /** Executes full L3/L4 geo + reachability probe inspired by `dastyab-flow.py`. */
    fun runAccessProbe(route: RouteResolution, timeoutMs: Int = 4000): AccessProbeReport {
        val pool = Executors.newFixedThreadPool(3)
        return try {
            val fGeo = pool.submit(Callable { fetchEgressGeo(route.javaNetProxy, timeoutMs) })
            val fAi = pool.submit(Callable { probeGoogleEndpoint(ReachabilityVerifier.URL_AISTUDIO, route.javaNetProxy, timeoutMs, inspectFlowGate = false) })
            val fFlow = pool.submit(Callable { probeGoogleEndpoint(ReachabilityVerifier.URL_FLOW, route.javaNetProxy, timeoutMs, inspectFlowGate = true) })

            val egress = runCatching { fGeo.get(timeoutMs + 1500L, TimeUnit.MILLISECONDS) }.getOrNull()
            val ai = runCatching { fAi.get(timeoutMs + 1500L, TimeUnit.MILLISECONDS) }.getOrElse {
                ServiceProbeResult(false, -1, -1, false, "", "Timed out")
            }
            val flow = runCatching { fFlow.get(timeoutMs + 1500L, TimeUnit.MILLISECONDS) }.getOrElse {
                ServiceProbeResult(false, -1, -1, false, "", "Timed out")
            }

            val countryOk = egress != null && isFlowCountrySupported(egress.countryCode)
            val (headline, advice) = buildVerdictSummary(route, egress, countryOk, ai, flow)
            AccessProbeReport(
                routeLabel = route.routeLabel,
                egress = egress,
                flowCountrySupported = countryOk,
                aiStudio = ai,
                flow = flow,
                headline = headline,
                recommendation = advice
            )
        } finally {
            pool.shutdownNow()
        }
    }

    fun buildVerdictSummary(
        route: RouteResolution,
        egress: EgressInfo?,
        countryOk: Boolean,
        ai: ServiceProbeResult,
        flow: ServiceProbeResult
    ): Pair<String, String> {
        val cc = egress?.countryCode ?: "??"
        return when {
            egress != null && !countryOk -> {
                "Blocked Region ($cc) for Flow / AI Studio" to
                    "Your current exit IP (${egress.ip}) is in $cc (${egress.countryName}), which Google geo-restricts. " +
                    "Connect Lantern or select a foreign v2rayNG / NekoBox local proxy port (e.g. :10809 / :10808)."
            }
            flow.unsupportedRegion -> {
                "Flow Region-Gated ($cc)" to
                    "Google Flow redirected to /unsupported-country even from $cc. Try switching VPN location (US / DE / GB / SG / JP) or avoid flagged datacenter nodes."
            }
            ai.reachable && flow.reachable && (egress == null || countryOk) -> {
                "Ready (${egress?.countryCode ?: "OK"}) — AI Studio & Flow Accessible" to
                    "Route '${route.routeLabel}' passed both geo (${egress?.ip ?: "verified"}) and live Google HTTPS checks."
            }
            ai.reachable && !flow.reachable -> {
                "AI Studio Ready · Flow Needs Clean Foreign IP" to
                    "AI Studio works (${ai.latencyMs}ms), while Flow reported: ${flow.detail}. If needed, switch Lantern/v2rayNG to US/DE/SG."
            }
            else -> {
                "Google Sites Unreachable via ${route.routeLabel}" to
                    "Both AI Studio (${ai.detail}) and Flow (${flow.detail}) failed through this path. " +
                    "Turn on Lantern (External VPN mode), point DastYab to a working local proxy port (:10809 / :10808), or run 1-Tap Autopilot."
            }
        }
    }
}
