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
 * Strictly separates [TargetService.AI_STUDIO] and [TargetService.GOOGLE_FLOW], checks
 * real geo-restriction gates (egress country + multi-hop redirect + HTML DOM markers +
 * `accounts.google.com` + Flow `AiSandboxAngularFrontend` shell), and automatically
 * chains multi-layer proxy hops (`Hop 1 -> Hop 2 -> ...`) ON TOP of an active external
 * VPN such as Lantern without stealing Android's single `VpnService` slot.
 */
object GeoBypassEngine {

    enum class TargetService(val key: String, val displayName: String) {
        AI_STUDIO("aistudio", "Google AI Studio"),
        GOOGLE_FLOW("flow", "Google Flow");

        companion object {
            fun fromUrl(url: String?): TargetService {
                val lower = url?.lowercase().orEmpty()
                return if (lower.contains("flow.google.com")) GOOGLE_FLOW else AI_STUDIO
            }
        }
    }

    const val URL_ACCOUNTS = "https://accounts.google.com/"

    /** Countries/territories where Google AI Studio is blocked/embargoed. */
    val AISTUDIO_BLOCKED_COUNTRIES: Set<String> = setOf(
        "IR", "CN", "RU", "KP", "BY", "SY", "CU", "SD", "AF"
    )

    val AISTUDIO_GATE_MARKERS: List<String> = listOf(
        "your region may not be supported",
        "not available in your country",
        "isn't available in your country",
        "available-regions",
        "unsupported-country",
        "/unsupported",
        "country is not supported",
        "region is not supported"
    )

    val FLOW_GATE_MARKERS: List<String> = listOf(
        "unsupported-country",
        "unsupported-country-title",
        "not available in your country yet",
        "flow is not available in your country",
        "country is not supported",
        "region is not supported"
    )

    val FLOW_UNAVAILABLE_MARKERS: List<String> = listOf(
        "unavailable-title",
        "don't have access to flow",
        "dont have access to flow",
        "do not have access to flow"
    )

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
        val detail: String,
        val appShellOk: Boolean = true,
        val accountGate: Boolean = false,
        val redirectHops: Int = 0
    )

    data class AccessProbeReport(
        val routeLabel: String,
        val egress: EgressInfo?,
        val flowCountrySupported: Boolean,
        val aiStudio: ServiceProbeResult,
        val flow: ServiceProbeResult,
        val headline: String,
        val recommendation: String,
        val accountsReachable: Boolean = true,
        val aiStudioCountrySupported: Boolean = egress != null && isAiStudioCountrySupported(egress.countryCode)
    ) {
        val readyForAiStudio: Boolean
            get() = egress != null &&
                aiStudioCountrySupported &&
                !aiStudio.unsupportedRegion &&
                aiStudio.reachable &&
                accountsReachable

        val readyForFlow: Boolean
            get() = egress != null &&
                flowCountrySupported &&
                !flow.unsupportedRegion &&
                flow.reachable &&
                flow.appShellOk &&
                accountsReachable

        fun targetReady(target: TargetService): Boolean = when (target) {
            TargetService.AI_STUDIO -> readyForAiStudio
            TargetService.GOOGLE_FLOW -> readyForFlow
        }
    }

    data class RouteResolution(
        val shouldStartDastyabVpn: Boolean,
        val shouldStartLocalBridge: Boolean,
        val webViewHttpProxyPort: Int?,
        val javaNetProxy: Proxy,
        val routeLabel: String,
        val externalVpnActive: Boolean,
        val upstreamChainSpec: String = "",
        val targetService: TargetService? = null
    )

    data class ChainAttempt(
        val label: String,
        val hops: List<TunnelConfig.ProxyHop>,
        val report: AccessProbeReport,
        val score: Long
    )

    data class ChainDiscoveryResult(
        val targetService: TargetService,
        val success: Boolean,
        val externalVpnActive: Boolean,
        val bestHops: List<TunnelConfig.ProxyHop>,
        val bestChainLabel: String,
        val report: AccessProbeReport,
        val attempts: List<ChainAttempt>,
        val summary: String
    )

    fun isAiStudioCountrySupported(countryCode: String?): Boolean {
        val norm = countryCode?.trim()?.uppercase().orEmpty()
        return norm.length == 2 && !AISTUDIO_BLOCKED_COUNTRIES.contains(norm)
    }

    fun isFlowCountrySupported(countryCode: String?): Boolean {
        val norm = countryCode?.trim()?.uppercase().orEmpty()
        return norm.length == 2 && FLOW_SUPPORTED_COUNTRIES.contains(norm)
    }

    /** Checks HTTP status, final URL, redirect Location, and HTML body markers for AI Studio geo-gates. */
    fun isAiStudioUnsupportedResponse(
        httpCode: Int,
        finalUrl: String?,
        locationHeader: String?,
        bodySnippet: String? = null
    ): Boolean {
        if (httpCode == 451 || httpCode == 403) return true
        val loc = locationHeader?.lowercase().orEmpty()
        val fUrl = finalUrl?.lowercase().orEmpty()
        if (loc.contains("/unsupported") || loc.contains("available-regions") || loc.contains("country-not-supported") ||
            fUrl.contains("/unsupported") || fUrl.contains("available-regions") || fUrl.contains("country-not-supported")
        ) {
            return true
        }
        val body = bodySnippet?.lowercase().orEmpty()
        return AISTUDIO_GATE_MARKERS.any { body.contains(it) }
    }

    /** Checks redirect Location + page body markers matching `dastyab-flow.py` GATE_MARKERS. */
    fun isFlowUnsupportedResponse(httpCode: Int, locationHeader: String?, bodySnippet: String? = null): Boolean {
        if (httpCode == 451 || httpCode == 403) return true
        val loc = locationHeader?.lowercase().orEmpty()
        if (loc.contains("/unsupported-country") || loc.contains("unsupported-country")) {
            return true
        }
        val body = bodySnippet?.lowercase().orEmpty()
        return FLOW_GATE_MARKERS.any { body.contains(it) }
    }

    fun hasFlowAppShell(bodySnippet: String?): Boolean =
        bodySnippet?.contains("AiSandboxAngularFrontend", ignoreCase = true) == true

    fun isFlowAccountGateResponse(bodySnippet: String?): Boolean {
        val body = bodySnippet?.lowercase().orEmpty()
        return FLOW_UNAVAILABLE_MARKERS.any { body.contains(it) }
    }

    fun formatV2rayNgSocksUri(
        host: String = "127.0.0.1",
        port: Int = TunnelConfig.DEFAULT_LOCAL_BRIDGE_PORT,
        tag: String = "DastYab-Local-Bridge"
    ): String {
        val cleanHost = TunnelConfig.sanitizeProxyHost(host)
        val cleanPort = TunnelConfig.clampPort(port, TunnelConfig.DEFAULT_LOCAL_BRIDGE_PORT, min = 1)
        return "socks://$cleanHost:$cleanPort#$tag"
    }

    /**
     * Pure-Kotlin planner determining how AI Studio / Flow + multi-layer Proxy Chains should route traffic.
     * Supports independent chains per [targetService] and multi-hop stacking on top of external Lantern VPN.
     */
    fun planRoute(
        settings: TunnelConfig.Settings,
        externalVpnActive: Boolean,
        dastyabVpnActive: Boolean,
        runningBridgePort: Int?,
        detectedListeningHttpPort: Int?,
        targetService: TargetService? = null
    ): RouteResolution {
        val preferredBridgePort = runningBridgePort ?: settings.localBridgePort
        val effectiveHops = TunnelConfig.effectiveUpstreamHops(settings, targetService?.key, preferredBridgePort)
        val chainSpec = TunnelConfig.formatProxyChain(effectiveHops)
        val hasChain = effectiveHops.isNotEmpty()
        val isMultiOrAuth = effectiveHops.size >= 2 ||
            effectiveHops.any { it.username.isNotEmpty() || it.password.isNotEmpty() }
        val firstHop = effectiveHops.firstOrNull()
        val servicePrefix = targetService?.let { "[${it.displayName}] " }.orEmpty()
        val bridgeChainMismatch = runningBridgePort == null || LocalProxyService.activeChainSpec() != chainSpec

        fun singleOrMultiQuad(defaultLabelPrefix: String): Quad {
            return when {
                isMultiOrAuth -> {
                    val label = "${servicePrefix}${effectiveHops.size}-Hop Chain (${effectiveHops.joinToString(" → ") { it.displayLabel() }})"
                    Quad(
                        wvPort = preferredBridgePort,
                        needsBridge = bridgeChainMismatch,
                        proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", preferredBridgePort)),
                        label = label
                    )
                }
                firstHop != null && firstHop.scheme == TunnelConfig.ProxyScheme.HTTP -> {
                    Quad(
                        wvPort = firstHop.port,
                        needsBridge = false,
                        proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(firstHop.host, firstHop.port)),
                        label = "$servicePrefix$defaultLabelPrefix HTTP ${firstHop.host}:${firstHop.port}"
                    )
                }
                firstHop != null && firstHop.scheme == TunnelConfig.ProxyScheme.SOCKS5 -> {
                    Quad(
                        wvPort = preferredBridgePort,
                        needsBridge = bridgeChainMismatch,
                        proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(firstHop.host, firstHop.port)),
                        label = "$servicePrefix$defaultLabelPrefix SOCKS5 ${firstHop.host}:${firstHop.port} (Bridge :$preferredBridgePort)"
                    )
                }
                else -> {
                    Quad(
                        wvPort = null,
                        needsBridge = false,
                        proxy = Proxy.NO_PROXY,
                        label = "Direct"
                    )
                }
            }
        }

        return when (settings.googleAccessMode) {
            TunnelConfig.GoogleAccessMode.EXTERNAL_VPN -> {
                RouteResolution(
                    shouldStartDastyabVpn = false,
                    shouldStartLocalBridge = false,
                    webViewHttpProxyPort = null,
                    javaNetProxy = Proxy.NO_PROXY,
                    routeLabel = if (externalVpnActive) "External VPN / Lantern" else "Direct (Start Lantern / VPN)",
                    externalVpnActive = externalVpnActive,
                    upstreamChainSpec = "",
                    targetService = targetService
                )
            }
            TunnelConfig.GoogleAccessMode.IN_APP_PROXY -> {
                val quad = when {
                    hasChain -> singleOrMultiQuad("App Proxy")
                    detectedListeningHttpPort != null -> Quad(
                        wvPort = detectedListeningHttpPort,
                        needsBridge = false,
                        proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", detectedListeningHttpPort)),
                        label = "${servicePrefix}Auto Local Proxy :$detectedListeningHttpPort"
                    )
                    else -> Quad(
                        wvPort = preferredBridgePort,
                        needsBridge = runningBridgePort == null,
                        proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", preferredBridgePort)),
                        label = "${servicePrefix}DastYab Internal Bridge :$preferredBridgePort"
                    )
                }
                RouteResolution(
                    shouldStartDastyabVpn = false,
                    shouldStartLocalBridge = quad.needsBridge,
                    webViewHttpProxyPort = quad.wvPort,
                    javaNetProxy = quad.proxy,
                    routeLabel = if (externalVpnActive) "${quad.label} + External VPN" else quad.label,
                    externalVpnActive = externalVpnActive,
                    upstreamChainSpec = chainSpec,
                    targetService = targetService
                )
            }
            TunnelConfig.GoogleAccessMode.LOCAL_BRIDGE -> {
                val javaProxy = when {
                    isMultiOrAuth -> Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", preferredBridgePort))
                    firstHop?.scheme == TunnelConfig.ProxyScheme.HTTP ->
                        Proxy(Proxy.Type.HTTP, InetSocketAddress(firstHop.host, firstHop.port))
                    firstHop?.scheme == TunnelConfig.ProxyScheme.SOCKS5 ->
                        Proxy(Proxy.Type.SOCKS, InetSocketAddress(firstHop.host, firstHop.port))
                    else -> Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", preferredBridgePort))
                }
                RouteResolution(
                    shouldStartDastyabVpn = false,
                    shouldStartLocalBridge = bridgeChainMismatch,
                    webViewHttpProxyPort = preferredBridgePort,
                    javaNetProxy = javaProxy,
                    routeLabel = if (effectiveHops.size >= 2) {
                        "${servicePrefix}Bridge :$preferredBridgePort → ${effectiveHops.size}-Hop Chain"
                    } else {
                        "${servicePrefix}Local Proxy Port 127.0.0.1:$preferredBridgePort"
                    },
                    externalVpnActive = externalVpnActive,
                    upstreamChainSpec = chainSpec,
                    targetService = targetService
                )
            }
            TunnelConfig.GoogleAccessMode.VPN_CHAIN_PROXY -> {
                val httpPort = when {
                    isMultiOrAuth -> preferredBridgePort
                    firstHop?.scheme == TunnelConfig.ProxyScheme.HTTP -> firstHop.port
                    firstHop?.scheme == TunnelConfig.ProxyScheme.SOCKS5 -> preferredBridgePort
                    runningBridgePort != null -> runningBridgePort
                    else -> detectedListeningHttpPort
                }
                val needsBridge = (isMultiOrAuth || firstHop?.scheme == TunnelConfig.ProxyScheme.SOCKS5) && bridgeChainMismatch
                val javaProxy = when {
                    isMultiOrAuth -> Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", preferredBridgePort))
                    firstHop?.scheme == TunnelConfig.ProxyScheme.HTTP ->
                        Proxy(Proxy.Type.HTTP, InetSocketAddress(firstHop.host, firstHop.port))
                    firstHop?.scheme == TunnelConfig.ProxyScheme.SOCKS5 ->
                        Proxy(Proxy.Type.SOCKS, InetSocketAddress(firstHop.host, firstHop.port))
                    httpPort != null -> Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpPort))
                    else -> Proxy.NO_PROXY
                }
                RouteResolution(
                    shouldStartDastyabVpn = !dastyabVpnActive,
                    shouldStartLocalBridge = needsBridge,
                    webViewHttpProxyPort = httpPort,
                    javaNetProxy = javaProxy,
                    routeLabel = if (httpPort != null) "${servicePrefix}DastYab VPN + Proxy :$httpPort" else "DastYab VPN",
                    externalVpnActive = externalVpnActive,
                    upstreamChainSpec = chainSpec,
                    targetService = targetService
                )
            }
            TunnelConfig.GoogleAccessMode.AUTO_SMART -> {
                when {
                    // Never kill an already active external VPN like Lantern!
                    externalVpnActive -> {
                        if (hasChain) {
                            val quad = singleOrMultiQuad("External VPN +")
                            RouteResolution(
                                shouldStartDastyabVpn = false,
                                shouldStartLocalBridge = quad.needsBridge,
                                webViewHttpProxyPort = quad.wvPort,
                                javaNetProxy = quad.proxy,
                                routeLabel = "${quad.label} (on External VPN)",
                                externalVpnActive = true,
                                upstreamChainSpec = chainSpec,
                                targetService = targetService
                            )
                        } else {
                            RouteResolution(
                                shouldStartDastyabVpn = false,
                                shouldStartLocalBridge = false,
                                webViewHttpProxyPort = null,
                                javaNetProxy = Proxy.NO_PROXY,
                                routeLabel = "External VPN (Lantern / System)",
                                externalVpnActive = true,
                                upstreamChainSpec = "",
                                targetService = targetService
                            )
                        }
                    }
                    hasChain -> {
                        val quad = singleOrMultiQuad("Local Proxy")
                        RouteResolution(
                            shouldStartDastyabVpn = false,
                            shouldStartLocalBridge = quad.needsBridge,
                            webViewHttpProxyPort = quad.wvPort,
                            javaNetProxy = quad.proxy,
                            routeLabel = quad.label,
                            externalVpnActive = false,
                            upstreamChainSpec = chainSpec,
                            targetService = targetService
                        )
                    }
                    detectedListeningHttpPort != null -> {
                        RouteResolution(
                            shouldStartDastyabVpn = false,
                            shouldStartLocalBridge = false,
                            webViewHttpProxyPort = detectedListeningHttpPort,
                            javaNetProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", detectedListeningHttpPort)),
                            routeLabel = "Detected Proxy 127.0.0.1:$detectedListeningHttpPort",
                            externalVpnActive = false,
                            upstreamChainSpec = "",
                            targetService = targetService
                        )
                    }
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
                            externalVpnActive = false,
                            upstreamChainSpec = "",
                            targetService = targetService
                        )
                    }
                }
            }
        }
    }

    private data class Quad(val wvPort: Int?, val needsBridge: Boolean, val proxy: Proxy, val label: String)

    /** Resolves current launch plan on Android using live VPN/bridge/port detection. */
    fun resolveCurrentRoute(
        context: Context,
        settings: TunnelConfig.Settings = TunnelConfig.load(context),
        targetService: TargetService? = null
    ): RouteResolution {
        val extVpn = NetworkUtils.hasActiveExternalVpn(context)
        val dastyabVpn = DastyabVpnService.isConnected()
        val bridgePort = LocalProxyService.activePort()
        val detectedHttp = detectFirstHttpProxyPort()
        return planRoute(settings, extVpn, dastyabVpn, bridgePort, detectedHttp, targetService)
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

    /**
     * Fetches public egress IP, country ISO code, and proxy/hosting flags **through [proxy]**
     * using resilient HTTPS-only providers (`network_security_config.xml` blocks external `http://`).
     */
    fun fetchEgressGeo(proxy: Proxy = Proxy.NO_PROXY, timeoutMs: Int = 4000): EgressInfo? {
        // 1. Fast Cloudflare TLS trace (never rate-limits shared VPN exits)
        var traceIp = ""
        var traceCc = ""
        runCatching {
            val conn = (URL("https://1.1.1.1/cdn-cgi/trace").openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "DastYab/1.9")
            }
            try {
                if (conn.responseCode == 200) {
                    val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                    val trace = NetworkTools.parseCloudflareTrace(body)
                    if (trace.ip != "unknown" && trace.ip.isNotBlank()) traceIp = trace.ip
                    val cc = trace.location.uppercase().takeIf { it.length == 2 && it != "XX" } ?: ""
                    if (cc.isNotEmpty()) traceCc = cc
                }
            } finally {
                conn.disconnect()
            }
        }

        // 2. HTTPS JSON geo + hosting/proxy enrichment
        val jsonProviders = listOf(
            "https://ipwho.is/",
            "https://ipapi.co/json/",
            "https://ipinfo.io/json"
        )
        for (u in jsonProviders) {
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
                        if (json.has("success") && !json.optBoolean("success", true)) return@runCatching null
                        val ip = json.optString("ip", "").ifBlank { traceIp }
                        val cc = json.optString("country_code", "")
                            .ifBlank { json.optString("country", "").takeIf { it.length == 2 }.orEmpty() }
                            .uppercase()
                            .ifBlank { traceCc }
                        val cname = json.optString("country_name", "")
                            .ifBlank { json.optString("country", "").takeIf { it.length > 2 }.orEmpty() }
                            .ifBlank { cc }
                        val sec = json.optJSONObject("security")
                        val org = json.optString("org", "").lowercase()
                        val orgHosting = listOf("datacenter", "hosting", "cloud", "amazon", "digitalocean", "hetzner", "ovh", "linode", "vultr", "cdn77", "m247", "leaseweb")
                            .any { org.contains(it) }
                        val hosting = (sec?.optBoolean("hosting", false) == true) ||
                            (sec?.optBoolean("proxy", false) == true) ||
                            orgHosting
                        if (ip.isNotBlank() && cc.length == 2) {
                            EgressInfo(
                                ip = ip,
                                countryCode = cc,
                                countryName = cname,
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

        if (traceIp.isNotBlank() && traceCc.length == 2) {
            return EgressInfo(
                ip = traceIp,
                countryCode = traceCc,
                countryName = traceCc,
                isProxyOrHosting = false,
                source = "cloudflare-trace"
            )
        }

        // 3. Last-resort ipify IP (no geo country — callers will see egress == null if country unknown)
        return null
    }

    /**
     * Probes HTTPS reachability + multi-hop redirect chain + HTML body geo-gate markers
     * + Flow `AiSandboxAngularFrontend` app shell through [proxy].
     */
    fun probeGoogleEndpoint(
        urlStr: String,
        proxy: Proxy = Proxy.NO_PROXY,
        timeoutMs: Int = 4000,
        inspectFlowGate: Boolean = false,
        inspectAiStudioGate: Boolean = false
    ): ServiceProbeResult {
        val start = System.nanoTime()
        val deepInspect = inspectFlowGate || inspectAiStudioGate
        var currentUrl = urlStr
        var redirectHops = 0
        val maxRedirects = if (deepInspect) 6 else 0

        try {
            while (true) {
                var conn: HttpURLConnection? = null
                try {
                    val baseUrl = URL(currentUrl)
                    conn = (baseUrl.openConnection(proxy) as HttpURLConnection).apply {
                        requestMethod = if (deepInspect) "GET" else "HEAD"
                        instanceFollowRedirects = false
                        connectTimeout = timeoutMs
                        readTimeout = timeoutMs
                        setRequestProperty(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                        )
                        setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                    }
                    val code = conn.responseCode
                    val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start).coerceAtLeast(1L)
                    val loc = conn.getHeaderField("Location").orEmpty()

                    // Handle HTTP 3xx redirects explicitly so every hop Location is inspected
                    if (code in 300..399 && loc.isNotBlank()) {
                        val nextUrl = runCatching { URL(baseUrl, loc).toString() }.getOrDefault(loc)
                        if (inspectFlowGate && isFlowUnsupportedResponse(code, nextUrl, "")) {
                            return ServiceProbeResult(
                                reachable = false,
                                httpCode = code,
                                latencyMs = ms,
                                unsupportedRegion = true,
                                finalLocation = nextUrl,
                                detail = "Geo-blocked (Flow redirected → $nextUrl · HTTP $code)",
                                appShellOk = false,
                                redirectHops = redirectHops
                            )
                        }
                        if (inspectAiStudioGate && isAiStudioUnsupportedResponse(code, nextUrl, loc, "")) {
                            return ServiceProbeResult(
                                reachable = false,
                                httpCode = code,
                                latencyMs = ms,
                                unsupportedRegion = true,
                                finalLocation = nextUrl,
                                detail = "Geo-blocked (AI Studio redirected → $nextUrl · HTTP $code)",
                                appShellOk = false,
                                redirectHops = redirectHops
                            )
                        }
                        if (redirectHops < maxRedirects && !nextUrl.contains("/sorry/", ignoreCase = true)) {
                            redirectHops++
                            currentUrl = nextUrl
                            continue
                        }
                        if (nextUrl.contains("/sorry/", ignoreCase = true)) {
                            return ServiceProbeResult(
                                reachable = false,
                                httpCode = code,
                                latencyMs = ms,
                                unsupportedRegion = true,
                                finalLocation = nextUrl,
                                detail = "Google flagged/CAPTCHA exit (/sorry · HTTP $code)",
                                appShellOk = false,
                                redirectHops = redirectHops
                            )
                        }
                        return ServiceProbeResult(
                            reachable = true,
                            httpCode = code,
                            latencyMs = ms,
                            unsupportedRegion = false,
                            finalLocation = nextUrl,
                            detail = "Reachable redirect (HTTP $code → $nextUrl · ${ms}ms)",
                            appShellOk = !inspectFlowGate,
                            redirectHops = redirectHops
                        )
                    }

                    val body = if (deepInspect && code in 200..499) {
                        readSnippetBounded(conn, maxChars = 131072)
                    } else {
                        ""
                    }
                    val flowUnsupported = inspectFlowGate && isFlowUnsupportedResponse(code, currentUrl, body)
                    val aiUnsupported = inspectAiStudioGate && isAiStudioUnsupportedResponse(code, currentUrl, loc, body)
                    val unsupported = flowUnsupported || aiUnsupported
                    val accountGate = inspectFlowGate && isFlowAccountGateResponse(body)
                    val appShellOk = if (inspectFlowGate) {
                        !unsupported && hasFlowAppShell(body)
                    } else {
                        true
                    }

                    val reachable = !unsupported && (code in 200..399 || (code in 400..499 && code != 403 && code != 451))
                    val detail = when {
                        unsupported -> "Geo-blocked ($currentUrl · HTTP $code)"
                        accountGate -> "Flow region clear · account eligibility gate"
                        inspectFlowGate && reachable && !appShellOk ->
                            "Flow responded without app shell (AiSandboxAngularFrontend missing · HTTP $code)"
                        reachable -> "Reachable (HTTP $code · ${ms}ms${if (redirectHops > 0) " · ${redirectHops}hops" else ""})"
                        else -> "Blocked HTTP $code (${ms}ms)"
                    }
                    return ServiceProbeResult(
                        reachable = reachable,
                        httpCode = code,
                        latencyMs = ms,
                        unsupportedRegion = unsupported,
                        finalLocation = if (loc.isNotBlank()) loc else currentUrl,
                        detail = detail,
                        appShellOk = appShellOk,
                        accountGate = accountGate,
                        redirectHops = redirectHops
                    )
                } finally {
                    conn?.disconnect()
                }
            }
        } catch (e: Exception) {
            return ServiceProbeResult(
                reachable = false,
                httpCode = -1,
                latencyMs = -1L,
                unsupportedRegion = false,
                finalLocation = currentUrl,
                detail = "Unreachable (${e.javaClass.simpleName}: ${e.message ?: "timeout"})",
                appShellOk = false,
                redirectHops = redirectHops
            )
        }
    }

    private fun readSnippetBounded(conn: HttpURLConnection, maxChars: Int): String {
        return runCatching {
            val stream = if (conn.responseCode >= 400) conn.errorStream ?: conn.inputStream else conn.inputStream
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(4096)
                while (sb.length < maxChars) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    sb.append(buf, 0, n.coerceAtMost(maxChars - sb.length))
                }
                sb.toString()
            }
        }.getOrDefault("")
    }

    /** Executes full L3/L4/L5 geo + reachability probe inspired by `dastyab-flow.py`. */
    fun runAccessProbe(route: RouteResolution, timeoutMs: Int = 4500): AccessProbeReport {
        val pool = Executors.newFixedThreadPool(4)
        return try {
            val fGeo = pool.submit(Callable { fetchEgressGeo(route.javaNetProxy, timeoutMs) })
            val fAi = pool.submit(Callable {
                probeGoogleEndpoint(
                    urlStr = ReachabilityVerifier.URL_AISTUDIO,
                    proxy = route.javaNetProxy,
                    timeoutMs = timeoutMs,
                    inspectFlowGate = false,
                    inspectAiStudioGate = true
                )
            })
            val fFlow = pool.submit(Callable {
                probeGoogleEndpoint(
                    urlStr = ReachabilityVerifier.URL_FLOW,
                    proxy = route.javaNetProxy,
                    timeoutMs = timeoutMs,
                    inspectFlowGate = true,
                    inspectAiStudioGate = false
                )
            })
            val fAccounts = pool.submit(Callable {
                probeGoogleEndpoint(URL_ACCOUNTS, route.javaNetProxy, timeoutMs, inspectFlowGate = false)
            })

            val egress = runCatching { fGeo.get(timeoutMs + 2500L, TimeUnit.MILLISECONDS) }.getOrNull()
            val ai = runCatching { fAi.get(timeoutMs + 3000L, TimeUnit.MILLISECONDS) }.getOrElse {
                ServiceProbeResult(false, -1, -1, false, "", "Timed out", appShellOk = false)
            }
            val flow = runCatching { fFlow.get(timeoutMs + 3000L, TimeUnit.MILLISECONDS) }.getOrElse {
                ServiceProbeResult(false, -1, -1, false, "", "Timed out", appShellOk = false)
            }
            val accounts = runCatching { fAccounts.get(timeoutMs + 2500L, TimeUnit.MILLISECONDS) }.getOrElse {
                ServiceProbeResult(false, -1, -1, false, "", "Timed out", appShellOk = false)
            }

            val aiCountryOk = egress != null && isAiStudioCountrySupported(egress.countryCode)
            val flowCountryOk = egress != null && isFlowCountrySupported(egress.countryCode)
            val (headline, advice) = buildVerdictSummary(route, egress, flowCountryOk, aiCountryOk, ai, flow, accounts.reachable)
            AccessProbeReport(
                routeLabel = route.routeLabel,
                egress = egress,
                flowCountrySupported = flowCountryOk,
                aiStudio = ai,
                flow = flow,
                headline = headline,
                recommendation = advice,
                accountsReachable = accounts.reachable,
                aiStudioCountrySupported = aiCountryOk
            )
        } finally {
            pool.shutdownNow()
        }
    }

    /** Ranks candidate probe reports for a target service (lower score is better). */
    fun gateScore(report: AccessProbeReport, target: TargetService): Long {
        val ready = report.targetReady(target)
        val countryOk = if (target == TargetService.GOOGLE_FLOW) report.flowCountrySupported else report.aiStudioCountrySupported
        val probe = if (target == TargetService.GOOGLE_FLOW) report.flow else report.aiStudio
        val notProxy = report.egress != null && !report.egress.isProxyOrHosting
        val latency = probe.latencyMs.takeIf { it > 0 } ?: 10_000L

        var score = 0L
        if (!ready) score += 1_000_000L
        if (!countryOk) score += 200_000L
        if (probe.unsupportedRegion) score += 100_000L
        if (target == TargetService.GOOGLE_FLOW && !probe.appShellOk) score += 50_000L
        if (!report.accountsReachable) score += 20_000L
        if (!notProxy) score += 5_000L
        score += latency.coerceAtMost(4_999L)
        return score
    }

    /** Builds candidate hop chains (0, 1, 2, and 3 layers) to stack on top of external VPN / Lantern. */
    fun buildMultiLayerCandidateChains(
        existingHops: List<TunnelConfig.ProxyHop>,
        listening: List<ListeningProxy>,
        harvestedHops: List<TunnelConfig.ProxyHop>,
        maxCandidates: Int = 20
    ): List<List<TunnelConfig.ProxyHop>> {
        val out = ArrayList<List<TunnelConfig.ProxyHop>>()
        val seen = HashSet<String>()

        fun addChain(hops: List<TunnelConfig.ProxyHop>) {
            if (out.size >= maxCandidates) return
            // Reject duplicate host:port within the same chain to avoid loops
            val distinctHops = hops.distinctBy { "${it.host}:${it.port}" }
            if (distinctHops.size != hops.size) return
            val key = distinctHops.joinToString("->") { "${it.scheme.name}:${it.host}:${it.port}" }
            if (seen.add(key)) {
                out.add(distinctHops)
            }
        }

        // Layer 0: Direct / Active External VPN (Lantern)
        addChain(emptyList())

        // Existing saved chain for this service
        if (existingHops.isNotEmpty()) {
            addChain(existingHops)
        }

        val listenHops = listening.map {
            TunnelConfig.ProxyHop(it.preset.scheme, it.preset.host, it.preset.port)
        }

        // Layer 1: Single-hop listening local proxies and harvested SOCKS5/HTTP nodes
        for (h in listenHops) addChain(listOf(h))
        for (h in harvestedHops) addChain(listOf(h))

        // Layer 2: Two-hop cascades (listening -> listening, listening -> harvested, harvested -> harvested)
        for (a in listenHops) {
            for (b in listenHops) {
                addChain(listOf(a, b))
            }
            for (b in harvestedHops.take(6)) {
                addChain(listOf(a, b))
            }
        }
        for (a in harvestedHops.take(4)) {
            for (b in harvestedHops.take(4)) {
                addChain(listOf(a, b))
            }
        }

        // Layer 3: Three-hop deep cascades (listening -> harvestedA -> harvestedB) for stubborn geo blocks
        for (a in listenHops.take(2)) {
            for (b in harvestedHops.take(4)) {
                for (c in harvestedHops.take(4)) {
                    addChain(listOf(a, b, c))
                }
            }
        }
        for (a in harvestedHops.take(3)) {
            for (b in harvestedHops.take(3)) {
                for (c in harvestedHops.take(3)) {
                    addChain(listOf(a, b, c))
                }
            }
        }

        return out.take(maxCandidates)
    }

    /**
     * Tests Layer 0 (External Lantern VPN) and progressively stacks 1-, 2-, and 3-hop proxy layers
     * on top until [targetService] passes ALL strict geo-restriction, HTTPS, account, and app-shell checks.
     * Persists winning routes separately for [TargetService.AI_STUDIO] vs [TargetService.GOOGLE_FLOW].
     */
    fun discoverAndChainBestRoute(
        context: Context,
        targetService: TargetService,
        allowHarvest: Boolean = true,
        maxCandidates: Int = 18,
        onStep: (String) -> Unit = {}
    ): ChainDiscoveryResult {
        val settings = TunnelConfig.load(context)
        val extVpn = NetworkUtils.hasActiveExternalVpn(context)
        val existingHops = TunnelConfig.effectiveUpstreamHops(settings, targetService.key, settings.localBridgePort)

        onStep("Scanning local proxy ports (v2rayNG, NekoBox, Hiddify, Clash, Orbot)…")
        val listening = discoverListeningProxies(timeoutMs = 150)

        var harvestedRaw = TunnelConfig.loadHarvestedConfigs(context)
        var proxyHops = UniversalConfig.extractChainableProxyHops(harvestedRaw)
        if (proxyHops.isEmpty() && allowHarvest) {
            onStep("Harvesting chainable SOCKS5 / HTTP / Telegram proxy nodes…")
            val harvested = runCatching { ConfigHarvester.harvestResilient(context) }.getOrNull()
            if (harvested != null && harvested.configs.isNotEmpty()) {
                TunnelConfig.saveHarvestedConfigs(context, harvested.configs)
                harvestedRaw = harvested.configs
                proxyHops = UniversalConfig.extractChainableProxyHops(harvestedRaw)
            }
        }

        // Pre-benchmark harvested external proxy hops quickly by TCP latency
        val aliveHarvested = if (proxyHops.isNotEmpty()) {
            onStep("Benchmarking ${proxyHops.size.coerceAtMost(24)} harvested proxy nodes…")
            benchmarkAliveHops(proxyHops.take(24), timeoutMs = 1100).take(8)
        } else {
            emptyList()
        }

        val candidates = buildMultiLayerCandidateChains(
            existingHops = existingHops,
            listening = listening,
            harvestedHops = aliveHarvested,
            maxCandidates = maxCandidates
        )

        val attempts = ArrayList<ChainAttempt>()
        var bestAttempt: ChainAttempt? = null

        for ((idx, hops) in candidates.withIndex()) {
            val label = if (hops.isEmpty()) {
                if (extVpn) "Layer 0 · Active External VPN (Lantern)" else "Layer 0 · Direct Network"
            } else {
                "Layer ${hops.size} · ${hops.joinToString(" → ") { it.displayLabel() }}" +
                    if (extVpn) " + External VPN" else ""
            }
            onStep("[${idx + 1}/${candidates.size}] Probing ${targetService.displayName}: $label…")

            val report = if (hops.isEmpty()) {
                val directRoute = RouteResolution(
                    shouldStartDastyabVpn = false,
                    shouldStartLocalBridge = false,
                    webViewHttpProxyPort = null,
                    javaNetProxy = Proxy.NO_PROXY,
                    routeLabel = label,
                    externalVpnActive = extVpn,
                    targetService = targetService
                )
                runAccessProbe(directRoute, timeoutMs = 4500)
            } else {
                probeChainThroughEphemeralBridge(hops, label, extVpn, targetService, timeoutMs = 4500)
            }

            val score = gateScore(report, targetService)
            val attempt = ChainAttempt(label, hops, report, score)
            attempts.add(attempt)

            if (bestAttempt == null || score < bestAttempt.score) {
                bestAttempt = attempt
            }

            // Early exit on a clean non-hosting verified pass!
            if (report.targetReady(targetService) && report.egress?.isProxyOrHosting == false) {
                break
            }
        }

        val winner = bestAttempt ?: ChainAttempt(
            label = "Direct",
            hops = emptyList(),
            report = runAccessProbe(resolveCurrentRoute(context, settings, targetService)),
            score = Long.MAX_VALUE
        )
        val passed = winner.report.targetReady(targetService)
        if (passed) {
            TunnelConfig.saveServiceProxyChain(
                context = context,
                targetServiceKey = targetService.key,
                hops = winner.hops,
                preserveExternalVpn = true
            )
            if (winner.hops.isNotEmpty()) {
                LocalProxyService.startSafely(context, targetService.key)
            }
        }

        val egressTag = winner.report.egress?.let { "${it.countryCode} (${it.ip})" } ?: "unknown egress"
        val summary = if (passed) {
            "Unlocked ${targetService.displayName} via ${winner.label} · Egress $egressTag"
        } else {
            val detail = if (targetService == TargetService.GOOGLE_FLOW) winner.report.flow.detail else winner.report.aiStudio.detail
            "No tested layer unlocked ${targetService.displayName} (best egress: $egressTag · $detail). " +
                "Connect a residential proxy / v2rayNG node or switch Lantern location."
        }

        return ChainDiscoveryResult(
            targetService = targetService,
            success = passed,
            externalVpnActive = extVpn,
            bestHops = winner.hops,
            bestChainLabel = winner.label,
            report = winner.report,
            attempts = attempts,
            summary = summary
        )
    }

    private fun benchmarkAliveHops(hops: List<TunnelConfig.ProxyHop>, timeoutMs: Int): List<TunnelConfig.ProxyHop> {
        if (hops.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(hops.size.coerceAtMost(8))
        return try {
            val tasks = hops.map { hop ->
                Callable {
                    val ms = CleanEndpointScout.probeTcpLatency(hop.host, hop.port, timeoutMs)
                    if (ms >= 0) hop to ms else null
                }
            }
            pool.invokeAll(tasks, 2500, TimeUnit.MILLISECONDS)
                .mapNotNull { runCatching { it.get() }.getOrNull() }
                .sortedBy { it.second }
                .map { it.first }
        } catch (_: Exception) {
            emptyList()
        } finally {
            pool.shutdownNow()
        }
    }

    private fun probeChainThroughEphemeralBridge(
        hops: List<TunnelConfig.ProxyHop>,
        label: String,
        externalVpnActive: Boolean,
        targetService: TargetService,
        timeoutMs: Int
    ): AccessProbeReport {
        val upstream = TunnelConfig.toLocalProxyUpstream(hops)
        val ephemeral = LocalProxyServer(requestedPort = 0, upstream = upstream)
        return try {
            val port = ephemeral.start()
            val route = RouteResolution(
                shouldStartDastyabVpn = false,
                shouldStartLocalBridge = false,
                webViewHttpProxyPort = port,
                javaNetProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)),
                routeLabel = label,
                externalVpnActive = externalVpnActive,
                upstreamChainSpec = TunnelConfig.formatProxyChain(hops),
                targetService = targetService
            )
            runAccessProbe(route, timeoutMs)
        } catch (e: Exception) {
            val errProbe = ServiceProbeResult(
                reachable = false,
                httpCode = -1,
                latencyMs = -1,
                unsupportedRegion = false,
                finalLocation = "",
                detail = "Chain startup failed (${e.message})",
                appShellOk = false
            )
            AccessProbeReport(
                routeLabel = label,
                egress = null,
                flowCountrySupported = false,
                aiStudio = errProbe,
                flow = errProbe,
                headline = "Chain Failed ($label)",
                recommendation = e.message ?: "Unable to bind ephemeral proxy bridge",
                accountsReachable = false,
                aiStudioCountrySupported = false
            )
        } finally {
            runCatching { ephemeral.close() }
        }
    }

    fun buildVerdictSummary(
        route: RouteResolution,
        egress: EgressInfo?,
        flowCountryOk: Boolean,
        aiCountryOk: Boolean = egress != null && isAiStudioCountrySupported(egress.countryCode),
        ai: ServiceProbeResult,
        flow: ServiceProbeResult,
        accountsOk: Boolean = true
    ): Pair<String, String> {
        val cc = egress?.countryCode ?: "??"
        return when {
            egress == null -> {
                "Exit Geo Unverified via ${route.routeLabel}" to
                    "Public exit IP / country lookup failed through '${route.routeLabel}', so geo-safety cannot be trusted. " +
                    "Tap Auto-Chain to stack proxy layers on top of Lantern."
            }
            !aiCountryOk && !flowCountryOk -> {
                "Blocked Region ($cc) for AI Studio & Flow" to
                    "Your current exit IP (${egress.ip}) is in $cc (${egress.countryName}), which Google geo-restricts. " +
                    "Run Auto-Chain to stack proxy hops on top of Lantern until a non-georestricted exit is reached."
            }
            !accountsOk -> {
                "Google Sign-In Blocked ($cc)" to
                    "accounts.google.com is unreachable through '${route.routeLabel}', so sign-in would fail."
            }
            ai.unsupportedRegion -> {
                "AI Studio Geo-Restricted ($cc)" to
                    "AI Studio returned a geo-restriction error (${ai.detail}) even on $cc (${egress.ip}). Auto-Chain a cleaner exit."
            }
            flow.unsupportedRegion || !flowCountryOk || !flow.appShellOk -> {
                val aiReadyStr = if (ai.reachable && aiCountryOk) "AI Studio Ready (${ai.latencyMs}ms)" else "AI Studio Blocked (${ai.detail})"
                val flowWhy = when {
                    !flowCountryOk -> "Country $cc is outside Flow's 151-country allowlist."
                    flow.unsupportedRegion -> "Flow redirected to /unsupported-country from $cc (${egress.ip})."
                    !flow.appShellOk -> "Flow did not return its app shell (${flow.detail})."
                    else -> flow.detail
                }
                "$aiReadyStr · Flow Geo-Restricted ($cc)" to
                    "$flowWhy Flow is stricter than AI Studio — Auto-Chain an extra proxy layer for Google Flow."
            }
            ai.reachable && flow.reachable && flow.appShellOk && aiCountryOk && flowCountryOk -> {
                val proxyNote = if (egress.isProxyOrHosting) " (datacenter/proxy flagged exit)" else " (clean exit)"
                "Ready ($cc$proxyNote) — AI Studio & Flow Verified" to
                    "Route '${route.routeLabel}' passed all HTTPS, accounts.google.com, Flow shell, and country gates (${egress.ip})."
            }
            else -> {
                "Google Services Blocked via ${route.routeLabel}" to
                    "AI Studio (${ai.detail}) · Flow (${flow.detail}). Run Auto-Chain Multi-Layer Route."
            }
        }
    }
}
