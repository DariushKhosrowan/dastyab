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
        "IR", "CN", "RU", "KP", "BY", "SY", "CU", "SD", "AF", "HK", "MO", "MM", "SS", "XX"
    )

    val AISTUDIO_GATE_MARKERS: List<String> = listOf(
        "your region may not be supported",
        "not available in your country",
        "isn't available in your country",
        "not available in your region",
        "service is not available in your country",
        "user location is not supported",
        "location is not supported",
        "available-regions",
        "unsupported-country",
        "/unsupported",
        "country is not supported",
        "region is not supported"
    )

    val PREFERRED_CLEAN_COUNTRIES: Set<String> = setOf(
        "US", "CA", "GB", "DE", "NL", "CH", "JP", "SG", "FR", "SE", "FI", "AU", "IE", "IT", "ES", "NO", "DK", "BE", "AT", "PL"
    )

    val FLOW_GATE_MARKERS: List<String> = listOf(
        "unsupported-country",
        "unsupported-country-title",
        "not available in your country yet",
        "not available in your country",
        "isn't available in your country",
        "unsupported_country",
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
        val source: String,
        val asnOrg: String = "",
        val warpActive: Boolean = false,
        val reputationTier: BrutalAntiGeoblockEngine.ExitReputationTier =
            if (isProxyOrHosting) BrutalAntiGeoblockEngine.ExitReputationTier.HOSTING_HIGH_RISK
            else BrutalAntiGeoblockEngine.ExitReputationTier.CLEAN_NON_DC
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
        val firstHop = effectiveHops.firstOrNull()
        val isSingleLoopbackHttp = effectiveHops.size == 1 &&
            firstHop != null &&
            firstHop.scheme == TunnelConfig.ProxyScheme.HTTP &&
            firstHop.username.isEmpty() &&
            firstHop.password.isEmpty() &&
            TunnelConfig.isLoopbackHost(firstHop.host)
        val isSingleLoopbackSocks5 = effectiveHops.size == 1 &&
            firstHop != null &&
            firstHop.scheme == TunnelConfig.ProxyScheme.SOCKS5 &&
            firstHop.username.isEmpty() &&
            firstHop.password.isEmpty() &&
            TunnelConfig.isLoopbackHost(firstHop.host)
        val isMultiOrAuth = effectiveHops.isNotEmpty() && !isSingleLoopbackHttp
        val servicePrefix = targetService?.let { "[${it.displayName}] " }.orEmpty()
        val bridgeChainMismatch = runningBridgePort == null || LocalProxyService.activeChainSpec() != chainSpec

        fun singleOrMultiQuad(defaultLabelPrefix: String): Quad {
            return when {
                isSingleLoopbackSocks5 && firstHop != null -> {
                    Quad(
                        wvPort = preferredBridgePort,
                        needsBridge = bridgeChainMismatch,
                        proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(firstHop.host, firstHop.port)),
                        label = "$servicePrefix$defaultLabelPrefix SOCKS5 ${firstHop.host}:${firstHop.port} (Bridge :$preferredBridgePort)"
                    )
                }
                isMultiOrAuth -> {
                    val label = if (effectiveHops.size == 1 && firstHop != null) {
                        "$servicePrefix$defaultLabelPrefix ${firstHop.displayLabel()} (Bridge :$preferredBridgePort)"
                    } else {
                        "${servicePrefix}${effectiveHops.size}-Hop Chain (${effectiveHops.joinToString(" → ") { it.displayLabel() }})"
                    }
                    Quad(
                        wvPort = preferredBridgePort,
                        needsBridge = bridgeChainMismatch,
                        proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", preferredBridgePort)),
                        label = label
                    )
                }
                isSingleLoopbackHttp && firstHop != null -> {
                    Quad(
                        wvPort = firstHop.port,
                        needsBridge = false,
                        proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(firstHop.host, firstHop.port)),
                        label = "$servicePrefix$defaultLabelPrefix HTTP ${firstHop.host}:${firstHop.port}"
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
                    isSingleLoopbackHttp && firstHop != null ->
                        Proxy(Proxy.Type.HTTP, InetSocketAddress(firstHop.host, firstHop.port))
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
                    isSingleLoopbackHttp && firstHop != null -> firstHop.port
                    runningBridgePort != null -> runningBridgePort
                    else -> detectedListeningHttpPort
                }
                val needsBridge = isMultiOrAuth && bridgeChainMismatch
                val javaProxy = when {
                    isMultiOrAuth -> Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", preferredBridgePort))
                    isSingleLoopbackHttp && firstHop != null ->
                        Proxy(Proxy.Type.HTTP, InetSocketAddress(firstHop.host, firstHop.port))
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
        // 1. Fast Cloudflare TLS trace (never rate-limits shared VPN exits; exposes WARP active flag)
        var traceIp = ""
        var traceCc = ""
        var traceWarp = "off"
        runCatching {
            val conn = (URL("https://1.1.1.1/cdn-cgi/trace").openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                BrutalAntiGeoblockEngine.applyCleanBrowserHeaders(this, mobile = true)
            }
            try {
                if (conn.responseCode == 200) {
                    val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                    val trace = NetworkTools.parseCloudflareTrace(body)
                    if (trace.ip != "unknown" && trace.ip.isNotBlank()) traceIp = trace.ip
                    val cc = trace.location.uppercase().takeIf { it.length == 2 && it != "XX" } ?: ""
                    if (cc.isNotEmpty()) traceCc = cc
                    traceWarp = trace.warp
                }
            } finally {
                conn.disconnect()
            }
        }
        val warpActive = traceWarp.equals("on", ignoreCase = true) || traceWarp.equals("plus", ignoreCase = true)

        // 2. HTTPS JSON geo + deep ASN / hosting / proxy enrichment
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
                    BrutalAntiGeoblockEngine.applyCleanBrowserHeaders(this, countryCode = traceCc.ifBlank { "US" }, mobile = true)
                }
                try {
                    if (conn.responseCode == 200) {
                        val raw = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                        val json = JSONObject(raw)
                        if (json.has("success") && !json.optBoolean("success", true)) return@runCatching null
                        val ip = json.optString("ip", "").ifBlank { traceIp }
                        val jsonCc = json.optString("country_code", "")
                            .ifBlank { json.optString("country", "").takeIf { it.length == 2 }.orEmpty() }
                            .uppercase()
                            .ifBlank { traceCc }
                        val cc = if (
                            traceCc.length == 2 &&
                            traceCc != jsonCc &&
                            (
                                (!isAiStudioCountrySupported(traceCc) && isAiStudioCountrySupported(jsonCc)) ||
                                    (!isFlowCountrySupported(traceCc) && isFlowCountrySupported(jsonCc))
                                )
                        ) {
                            traceCc
                        } else {
                            jsonCc
                        }
                        val cname = if (cc != jsonCc && traceCc == cc) {
                            cc
                        } else {
                            json.optString("country_name", "")
                                .ifBlank { json.optString("country", "").takeIf { it.length > 2 }.orEmpty() }
                                .ifBlank { cc }
                        }
                        val sec = json.optJSONObject("security")
                        val connObj = json.optJSONObject("connection")
                        val orgField = listOf(
                            json.optString("org", ""),
                            connObj?.optString("org", "").orEmpty(),
                            connObj?.optString("isp", "").orEmpty(),
                            json.optString("isp", ""),
                            json.optString("asn", "")
                        ).firstOrNull { it.isNotBlank() }.orEmpty()
                        val ispField = connObj?.optString("isp", "").orEmpty().ifBlank { json.optString("isp", "") }
                        val tier = BrutalAntiGeoblockEngine.classifyExitReputation(
                            org = orgField,
                            isp = ispField,
                            isHosting = sec?.optBoolean("hosting", false) == true,
                            isProxy = sec?.optBoolean("proxy", false) == true,
                            isVpn = sec?.optBoolean("vpn", false) == true,
                            isTor = sec?.optBoolean("tor", false) == true,
                            warpStatus = traceWarp
                        )
                        if (ip.isNotBlank() && cc.length == 2) {
                            EgressInfo(
                                ip = ip,
                                countryCode = cc,
                                countryName = cname,
                                isProxyOrHosting = tier.isHighRisk,
                                source = URL(u).host,
                                asnOrg = orgField,
                                warpActive = warpActive,
                                reputationTier = tier
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
            val tier = if (warpActive) {
                BrutalAntiGeoblockEngine.ExitReputationTier.WARP_OR_TOR
            } else {
                BrutalAntiGeoblockEngine.ExitReputationTier.CLEAN_NON_DC
            }
            return EgressInfo(
                ip = traceIp,
                countryCode = traceCc,
                countryName = traceCc,
                isProxyOrHosting = warpActive,
                source = "cloudflare-trace",
                asnOrg = if (warpActive) "Cloudflare WARP" else "",
                warpActive = warpActive,
                reputationTier = tier
            )
        }

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
                        BrutalAntiGeoblockEngine.applyCleanBrowserHeaders(this, mobile = true)
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

    /**
     * Secondary server-side AI Studio geo-gate detector: checks Gemini API location error text
     * and `gemini.google.com` region redirects so CDN-served Angular SPA shells never hide a geo-block.
     * Never fails on expected 403 `"unregistered callers"` from `generativelanguage`.
     */
    fun probeAiStudioApiAndGeminiGate(
        proxy: Proxy = Proxy.NO_PROXY,
        timeoutMs: Int = 3500
    ): ServiceProbeResult? {
        val apiMarkers = listOf(
            "location is not supported",
            "not available in your country",
            "not available in your region",
            "region is not supported",
            "country is not supported",
            "user location"
        )
        // 1. generativelanguage API location check
        val apiBlocked = runCatching {
            val url = "https://generativelanguage.googleapis.com/v1beta/models"
            val start = System.nanoTime()
            val conn = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "DastYab/1.9")
                BrutalAntiGeoblockEngine.applyBrutalHeaders(this)
            }
            try {
                val code = conn.responseCode
                val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start).coerceAtLeast(1L)
                val body = readSnippetBounded(conn, 16384).lowercase()
                if (code == 451 || apiMarkers.any { body.contains(it) }) {
                    ServiceProbeResult(
                        reachable = false,
                        httpCode = code,
                        latencyMs = ms,
                        unsupportedRegion = true,
                        finalLocation = url,
                        detail = "Geo-blocked by Gemini API (generativelanguage HTTP $code)",
                        appShellOk = false
                    )
                } else {
                    null
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
        if (apiBlocked != null) return apiBlocked

        // 2. gemini.google.com redirect/body region check
        return runCatching {
            val start = System.nanoTime()
            var cur = "https://gemini.google.com/"
            for (hop in 0 until 4) {
                val baseUrl = URL(cur)
                val conn = (baseUrl.openConnection(proxy) as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                    )
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                    BrutalAntiGeoblockEngine.applyBrutalHeaders(this)
                }
                try {
                    val code = conn.responseCode
                    val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start).coerceAtLeast(1L)
                    val loc = conn.getHeaderField("Location").orEmpty()
                    if (code in 300..399 && loc.isNotBlank()) {
                        val nextUrl = runCatching { URL(baseUrl, loc).toString() }.getOrDefault(loc)
                        if (isAiStudioUnsupportedResponse(code, nextUrl, loc, "") ||
                            nextUrl.contains("/sorry/", ignoreCase = true)
                        ) {
                            return@runCatching ServiceProbeResult(
                                reachable = false,
                                httpCode = code,
                                latencyMs = ms,
                                unsupportedRegion = true,
                                finalLocation = nextUrl,
                                detail = "Geo-blocked on gemini.google.com (redirect → $nextUrl · HTTP $code)",
                                appShellOk = false,
                                redirectHops = hop
                            )
                        }
                        cur = nextUrl
                        continue
                    }
                    val body = if (code in 200..499) readSnippetBounded(conn, 65536) else ""
                    if (isAiStudioUnsupportedResponse(code, cur, loc, body)) {
                        return@runCatching ServiceProbeResult(
                            reachable = false,
                            httpCode = code,
                            latencyMs = ms,
                            unsupportedRegion = true,
                            finalLocation = cur,
                            detail = "Geo-blocked on gemini.google.com (HTTP $code)",
                            appShellOk = false,
                            redirectHops = hop
                        )
                    }
                    return@runCatching null
                } finally {
                    conn.disconnect()
                }
            }
            null
        }.getOrNull()
    }

    /** Executes full L3/L4/L5 geo + reachability probe inspired by `dastyab-flow.py`. */
    fun runAccessProbe(route: RouteResolution, timeoutMs: Int = 4500): AccessProbeReport {
        val pool = Executors.newFixedThreadPool(5)
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
            val fAiSecondary = pool.submit(Callable {
                probeAiStudioApiAndGeminiGate(route.javaNetProxy, timeoutMs.coerceAtMost(3500))
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
            val aiRaw = runCatching { fAi.get(timeoutMs + 3000L, TimeUnit.MILLISECONDS) }.getOrElse {
                ServiceProbeResult(false, -1, -1, false, "", "Timed out", appShellOk = false)
            }
            val aiSecondary = runCatching { fAiSecondary.get(timeoutMs + 2000L, TimeUnit.MILLISECONDS) }.getOrNull()
            val ai = if (aiSecondary != null && aiSecondary.unsupportedRegion && !aiRaw.unsupportedRegion) {
                aiRaw.copy(
                    reachable = false,
                    unsupportedRegion = true,
                    finalLocation = aiSecondary.finalLocation.ifBlank { aiRaw.finalLocation },
                    detail = aiSecondary.detail
                )
            } else {
                aiRaw
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
        val egress = report.egress
        val notProxy = egress != null && !egress.isProxyOrHosting
        val latency = probe.latencyMs.takeIf { it > 0 } ?: 10_000L

        var score = 0L
        if (!ready) score += 1_000_000L
        if (!countryOk) score += 200_000L
        if (probe.unsupportedRegion) score += 100_000L
        if (target == TargetService.GOOGLE_FLOW && !probe.appShellOk) score += 50_000L
        if (!report.accountsReachable) score += 20_000L
        if (egress?.reputationTier == BrutalAntiGeoblockEngine.ExitReputationTier.WARP_OR_TOR) {
            score += if (target == TargetService.GOOGLE_FLOW) 35_000L else 12_000L
        } else if (egress?.reputationTier == BrutalAntiGeoblockEngine.ExitReputationTier.RESIDENTIAL_ISP) {
            score -= 400L
        }
        if (target == TargetService.GOOGLE_FLOW) {
            val cc = egress?.countryCode?.uppercase().orEmpty()
            if (!FlowWarriorEngine.WARRIOR_TIER1_COUNTRIES.contains(cc)) score += 2_500L
            if (!notProxy) score += 15_000L
        } else if (!notProxy) {
            score += 5_000L
        }
        score += latency.coerceAtMost(4_999L)
        return score
    }

    /**
     * Builds progressive multi-layer candidate chains grouped by hop count:
     * Stage 0 (0 extra layers / saved), Stage 1 (1-hop), Stage 2 (2-hop in both `A -> B` & `B -> A` orders),
     * Stage 3 (3-hop cascades), Stage 4 (4-hop cascades), Stage 5 (5..6-hop deep cascades),
     * and Stage 6 (7..8-hop ultra-deep cascades).
     */
    fun buildProgressiveLayerStages(
        existingHops: List<TunnelConfig.ProxyHop>,
        listening: List<ListeningProxy>,
        harvestedHops: List<TunnelConfig.ProxyHop>,
        preferredEntries: List<TunnelConfig.ProxyHop> = emptyList(),
        preferredExits: List<TunnelConfig.ProxyHop> = emptyList(),
        maxTotalCandidates: Int = 64
    ): List<List<List<TunnelConfig.ProxyHop>>> {
        val seen = HashSet<String>()
        var totalAdded = 0

        fun addTo(stage: MutableList<List<TunnelConfig.ProxyHop>>, hops: List<TunnelConfig.ProxyHop>, stageLimit: Int) {
            if (totalAdded >= maxTotalCandidates || stage.size >= stageLimit) return
            val distinctHops = hops.distinctBy { "${it.host}:${it.port}" }
            if (distinctHops.size != hops.size) return
            val key = distinctHops.joinToString("->") { "${it.scheme.name}:${it.host}:${it.port}" }
            if (seen.add(key)) {
                stage.add(distinctHops)
                totalAdded++
            }
        }

        val stage0 = ArrayList<List<TunnelConfig.ProxyHop>>()
        val stage1 = ArrayList<List<TunnelConfig.ProxyHop>>()
        val stage2 = ArrayList<List<TunnelConfig.ProxyHop>>()
        val stage3 = ArrayList<List<TunnelConfig.ProxyHop>>()
        val stage4 = ArrayList<List<TunnelConfig.ProxyHop>>()
        val stage5 = ArrayList<List<TunnelConfig.ProxyHop>>()
        val stage6 = ArrayList<List<TunnelConfig.ProxyHop>>()

        // Stage 0: Layer 0 (Direct / Active External Lantern VPN) + existing saved service chain
        addTo(stage0, emptyList(), 4)
        if (existingHops.isNotEmpty()) {
            addTo(stage0, existingHops, 4)
        }

        val listenHops = listening.map {
            TunnelConfig.ProxyHop(it.preset.scheme, it.preset.host, it.preset.port)
        }

        // Order harvested hops so preferred exits / entries appear up front while preserving all nodes
        val orderedHarvested = LinkedHashMap<String, TunnelConfig.ProxyHop>()
        for (h in preferredExits + harvestedHops + preferredEntries) {
            orderedHarvested.putIfAbsent("${h.scheme.name}:${h.host}:${h.port}", h)
        }
        val pool = orderedHarvested.values.toList()
        val entriesPool = LinkedHashMap<String, TunnelConfig.ProxyHop>().apply {
            for (h in preferredEntries + pool) putIfAbsent("${h.scheme.name}:${h.host}:${h.port}", h)
        }.values.toList()
        val exitsPool = LinkedHashMap<String, TunnelConfig.ProxyHop>().apply {
            for (h in preferredExits + pool) putIfAbsent("${h.scheme.name}:${h.host}:${h.port}", h)
        }.values.toList()

        // Stage 1: Single-hop routes (listening local proxies + top fast alive harvested hops)
        for (h in listenHops) addTo(stage1, listOf(h), 14)
        for (h in pool) addTo(stage1, listOf(h), 12)

        // Stage 2: Two-hop cascades in BOTH orders (listen -> harvested, listenA <-> listenB, A <-> B)
        for (b in exitsPool.take(8)) {
            for (a in listenHops) {
                addTo(stage2, listOf(a, b), 18)
            }
        }
        for (i in listenHops.indices) {
            for (j in i + 1 until listenHops.size) {
                addTo(stage2, listOf(listenHops[i], listenHops[j]), 18)
                addTo(stage2, listOf(listenHops[j], listenHops[i]), 18)
            }
        }
        // Interleave [A, B] and [B, A] across pairs so one bad entry never starves reverse orders
        val cap2 = entriesPool.size.coerceAtMost(6)
        val cap2Exit = exitsPool.size.coerceAtMost(6)
        for (i in 0 until cap2) {
            for (j in 0 until cap2Exit) {
                val a = entriesPool[i]
                val b = exitsPool[j]
                addTo(stage2, listOf(a, b), 18)
                addTo(stage2, listOf(b, a), 18)
            }
        }

        // Stage 3: Three-hop deep cascades across diverse entry/middle/exit permutations
        val e3 = entriesPool.take(5)
        val x3 = exitsPool.take(5)
        for (a in listenHops.take(2)) {
            for (i in e3.indices) {
                for (j in x3.indices) {
                    addTo(stage3, listOf(a, e3[i], x3[j]), 14)
                    addTo(stage3, listOf(a, x3[j], e3[i]), 14)
                }
            }
        }
        if (listenHops.size >= 2) {
            for (x in x3.take(3)) {
                addTo(stage3, listOf(listenHops[0], listenHops[1], x), 14)
                addTo(stage3, listOf(listenHops[1], listenHops[0], x), 14)
            }
        }
        for (i in e3.indices) {
            for (j in e3.indices) {
                for (k in x3.indices) {
                    addTo(stage3, listOf(e3[i], e3[j], x3[k]), 14)
                    addTo(stage3, listOf(e3[j], e3[i], x3[k]), 14)
                }
            }
        }

        // Stage 4: Four-hop ultimate fallback cascades for stubborn multi-layer geo-blocks
        if (pool.size >= 3) {
            for (a in listenHops.take(2)) {
                for (i in 0 until pool.size.coerceAtMost(4)) {
                    for (j in 0 until pool.size.coerceAtMost(4)) {
                        for (k in 0 until pool.size.coerceAtMost(4)) {
                            addTo(stage4, listOf(a, pool[i], pool[j], pool[k]), 10)
                        }
                    }
                }
            }
        }
        if (pool.size >= 4) {
            for (shift in 0 until pool.size.coerceAtMost(5)) {
                val a = pool[shift]
                val b = pool[(shift + 1) % pool.size]
                val c = pool[(shift + 2) % pool.size]
                val d = pool[(shift + 3) % pool.size]
                addTo(stage4, listOf(a, b, c, d), 10)
                addTo(stage4, listOf(d, c, b, a), 10)
            }
        }

        // Stage 5: Five-hop & Six-hop deep cascades combining local bridges + diverse relays + exits
        val combinedPool = LinkedHashMap<String, TunnelConfig.ProxyHop>().apply {
            for (h in listenHops + entriesPool + exitsPool) {
                putIfAbsent("${h.host}:${h.port}", h)
            }
        }.values.toList()
        if (combinedPool.size >= 5) {
            for (shift in 0 until combinedPool.size.coerceAtMost(6)) {
                val deep5 = (0 until 5).map { k -> combinedPool[(shift + k) % combinedPool.size] }
                addTo(stage5, deep5, 10)
                if (combinedPool.size >= 6) {
                    val deep6 = (0 until 6).map { k -> combinedPool[(shift + k) % combinedPool.size] }
                    addTo(stage5, deep6, 10)
                    addTo(stage5, deep5.reversed(), 10)
                    addTo(stage5, deep6.reversed(), 10)
                } else {
                    addTo(stage5, deep5.reversed(), 10)
                }
            }
        }

        // Stage 6: Seven-hop & Eight-hop ultra-deep cascades for extreme geo-censorship firewalls
        if (combinedPool.size >= 7) {
            for (shift in 0 until combinedPool.size.coerceAtMost(4)) {
                val deep7 = (0 until 7).map { k -> combinedPool[(shift + k) % combinedPool.size] }
                addTo(stage6, deep7, 8)
                if (combinedPool.size >= 8) {
                    val deep8 = (0 until 8).map { k -> combinedPool[(shift + k) % combinedPool.size] }
                    addTo(stage6, deep8, 8)
                    addTo(stage6, deep7.reversed(), 8)
                    addTo(stage6, deep8.reversed(), 8)
                } else {
                    addTo(stage6, deep7.reversed(), 8)
                }
            }
        }

        return listOf(stage0, stage1, stage2, stage3, stage4, stage5, stage6)
    }

    /** Flat view of [buildProgressiveLayerStages] ordered 0 -> 1 -> 2 -> 3 -> 4 -> 5..6 -> 7..8 hops. */
    fun buildMultiLayerCandidateChains(
        existingHops: List<TunnelConfig.ProxyHop>,
        listening: List<ListeningProxy>,
        harvestedHops: List<TunnelConfig.ProxyHop>,
        maxCandidates: Int = 56
    ): List<List<TunnelConfig.ProxyHop>> = buildProgressiveLayerStages(
        existingHops = existingHops,
        listening = listening,
        harvestedHops = harvestedHops,
        maxTotalCandidates = maxCandidates
    ).flatten().take(maxCandidates)

    data class QualifiedHopPool(
        val cleanExits: List<TunnelConfig.ProxyHop>,
        val relays: List<TunnelConfig.ProxyHop>,
        val resolvedByHopKey: Map<String, LocalProxyServer.HopTraceResult> = emptyMap()
    )

    /** Pure-Kotlin eligibility gate ensuring a candidate both unlocks [targetService] AND rotates away from blocked IPs. */
    fun isEligibleRouteReport(
        report: AccessProbeReport,
        targetService: TargetService,
        excludedEgressIps: Set<String> = emptySet()
    ): Boolean {
        if (!report.targetReady(targetService)) return false
        val ip = report.egress?.ip?.trim().orEmpty()
        if (ip.isEmpty()) return false
        return !excludedEgressIps.contains(ip)
    }

    /**
     * Tests Layer 0 (when not excluded) and autonomously runs multi-round cycles of:
     *   1) Fresh proxy pool harvesting (GitHub + jsDelivr + Telegram) across untried `/16` subnets
     *   2) Parallel real CONNECT + TLS `/cdn-cgi/trace` qualification & exit-country discovery
     *   3) Progressive 1 -> 2 -> 3 -> 4 layer cascading & IP rotation
     *   4) Strict non-blocked egress IP verification
     * until [targetService] unlocks cleanly on a working, non-georestricted new exit IP.
     */
    fun discoverAndChainBestRoute(
        context: Context,
        targetService: TargetService,
        allowHarvest: Boolean = true,
        maxCandidates: Int = 48,
        excludedEgressIps: Set<String> = emptySet(),
        excludeCurrentRoute: Boolean = false,
        excludedChainSpecs: Set<String> = emptySet(),
        maxRounds: Int = 3,
        onStep: (String) -> Unit = {}
    ): ChainDiscoveryResult {
        val settings = TunnelConfig.load(context)
        val extVpn = NetworkUtils.hasActiveExternalVpn(context)
        val existingHops = TunnelConfig.effectiveUpstreamHops(settings, targetService.key, settings.localBridgePort)

        onStep("Scanning local proxy ports & harvested vault files (.npv/.ovpn/.conf/.json/.yaml)…")
        val listening = discoverListeningProxies(timeoutMs = 150)
        val vaultHops = runCatching { ConfigFileHarvester.extractVaultHops(context) }.getOrDefault(emptyList())
        val initialPool = (vaultHops + TunnelConfig.loadChainableProxyPool(context))
            .distinctBy { "${it.scheme.name}:${it.host}:${it.port}" }

        val result = discoverBestRouteLoop(
            targetService = targetService,
            externalVpnActive = extVpn,
            existingHops = existingHops,
            listening = listening,
            initialPool = initialPool,
            allowHarvest = allowHarvest,
            maxCandidates = maxCandidates,
            excludedEgressIps = excludedEgressIps,
            excludeCurrentRoute = excludeCurrentRoute,
            excludedChainSpecs = excludedChainSpecs,
            maxRounds = maxRounds,
            hopQualifier = { batch ->
                qualifyProxyHops(
                    hops = batch,
                    targetService = targetService,
                    excludedEgressIps = excludedEgressIps,
                    timeoutMs = 1800
                )
            },
            harvester = { round, testedHosts, testedSubnets ->
                onStep("Round $round/$maxRounds: Harvesting multi-format feeds & SOCKS5/HTTP pools (GitHub → Mirror → Telegram)…")
                val freshVault = runCatching { ConfigFileHarvester.extractVaultHops(context) }.getOrDefault(emptyList())
                val poolHops = runCatching {
                    ConfigHarvester.harvestProxyChainPool(
                        context = context,
                        maxHops = 260,
                        excludedHosts = testedHosts,
                        excludedSubnets16 = testedSubnets
                    ) { msg -> onStep("  $msg") }
                }.getOrDefault(emptyList())
                val resilientHops = if (round >= 2) {
                    runCatching {
                        val r = ConfigHarvester.harvestResilient(context)
                        UniversalConfig.extractChainableProxyHops(r.configs)
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                (freshVault + poolHops + resilientHops)
                    .distinctBy { "${it.scheme.name}:${it.host}:${it.port}" }
            },
            routeProber = { hops, label ->
                if (hops.isEmpty()) {
                    val directRoute = RouteResolution(
                        shouldStartDastyabVpn = false,
                        shouldStartLocalBridge = false,
                        webViewHttpProxyPort = null,
                        javaNetProxy = Proxy.NO_PROXY,
                        routeLabel = label,
                        externalVpnActive = extVpn,
                        targetService = targetService
                    )
                    runAccessProbe(directRoute, timeoutMs = 4500) to null
                } else {
                    probeChainThroughEphemeralBridgeWithError(hops, label, extVpn, targetService, timeoutMs = 4500)
                }
            },
            onStep = onStep
        )

        if (result.success) {
            TunnelConfig.saveServiceProxyChain(
                context = context,
                targetServiceKey = targetService.key,
                hops = result.bestHops,
                preserveExternalVpn = true
            )
            // Keep winning hops at the front of the persistent proxy pool for fast warm reconnects
            val savedPool = (result.bestHops + TunnelConfig.loadChainableProxyPool(context))
                .distinctBy { "${it.scheme.name}:${it.host}:${it.port}" }
            TunnelConfig.saveHarvestedProxyHops(context, savedPool)
            if (result.bestHops.isNotEmpty()) {
                LocalProxyService.startSafely(context, targetService.key)
            }
        }
        return result
    }

    /**
     * Pure-Kotlin multi-round auto-chain & IP-rotation orchestrator (fully deterministic and
     * unit-testable on the plain JVM without Android runtime stubs).
     */
    fun discoverBestRouteLoop(
        targetService: TargetService,
        externalVpnActive: Boolean,
        existingHops: List<TunnelConfig.ProxyHop>,
        listening: List<ListeningProxy>,
        initialPool: List<TunnelConfig.ProxyHop>,
        allowHarvest: Boolean = true,
        maxCandidates: Int = 48,
        excludedEgressIps: Set<String> = emptySet(),
        excludeCurrentRoute: Boolean = false,
        excludedChainSpecs: Set<String> = emptySet(),
        maxRounds: Int = 3,
        hopQualifier: (List<TunnelConfig.ProxyHop>) -> QualifiedHopPool,
        harvester: (round: Int, excludedHosts: Set<String>, excludedSubnets16: Set<String>) -> List<TunnelConfig.ProxyHop>,
        routeProber: (hops: List<TunnelConfig.ProxyHop>, label: String) -> Pair<AccessProbeReport, String?>,
        onStep: (String) -> Unit = {}
    ): ChainDiscoveryResult {
        fun hopKey(hop: TunnelConfig.ProxyHop): String = "${hop.scheme.name}:${hop.host}:${hop.port}"
        fun chainKey(hops: List<TunnelConfig.ProxyHop>): String = hops.joinToString("->") { hopKey(it) }

        val initialBlockedIps = excludedEgressIps.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val blockedEgressIps = LinkedHashSet<String>(initialBlockedIps)
        val blockedChainSpecs = LinkedHashSet<String>()
        for (rawSpec in excludedChainSpecs) {
            val parsed = TunnelConfig.parseProxyChain(rawSpec)
            if (parsed.isNotEmpty()) blockedChainSpecs.add(chainKey(parsed))
        }
        if (excludeCurrentRoute && existingHops.isNotEmpty()) {
            blockedChainSpecs.add(chainKey(existingHops))
        }

        val attempts = ArrayList<ChainAttempt>()
        val probedKeys = HashSet<String>()
        val failedEntryKeys = HashSet<String>()
        val triedQualifyHosts = HashSet<String>()
        val triedQualifySubnets = HashSet<String>()
        val knownCleanExits = LinkedHashMap<String, TunnelConfig.ProxyHop>()
        val knownRelays = LinkedHashMap<String, TunnelConfig.ProxyHop>()
        val reachableBridgeHops = ArrayList<TunnelConfig.ProxyHop>()
        val masterPool = LinkedHashMap<String, TunnelConfig.ProxyHop>()
        for (h in initialPool) {
            masterPool.putIfAbsent(hopKey(h), h)
        }

        var bestEligibleAttempt: ChainAttempt? = null
        var bestFallbackAttempt: ChainAttempt? = null
        var testedCount = 0

        fun recordProbe(hops: List<TunnelConfig.ProxyHop>, stageNum: Int): ChainAttempt {
            val label = if (hops.isEmpty()) {
                if (externalVpnActive) "Layer 0 · Active External VPN (Lantern)" else "Layer 0 · Direct Network"
            } else {
                "Layer ${hops.size} · ${hops.joinToString(" → ") { it.displayLabel() }}" +
                    if (externalVpnActive) " + External VPN" else ""
            }
            testedCount++
            onStep("[L$stageNum #$testedCount] Probing ${targetService.displayName}: $label…")

            val (report, bridgeError) = routeProber(hops, label)

            // If this egress IP fails country/service checks, automatically blacklist it for subsequent rounds
            report.egress?.let { eg ->
                val svcProbe = if (targetService == TargetService.GOOGLE_FLOW) report.flow else report.aiStudio
                val ccSupported = if (targetService == TargetService.GOOGLE_FLOW) report.flowCountrySupported else report.aiStudioCountrySupported
                if (!ccSupported || svcProbe.unsupportedRegion || (targetService == TargetService.GOOGLE_FLOW && svcProbe.reachable && !svcProbe.appShellOk)) {
                    blockedEgressIps.add(eg.ip)
                }
            }

            if (hops.isNotEmpty()) {
                val firstHop = hops.first()
                val firstKey = hopKey(firstHop)
                val hop1Died = (report.egress == null && !report.aiStudio.reachable && !report.flow.reachable) &&
                    (hops.size == 1 || bridgeError?.startsWith("Hop 1/") == true || bridgeError?.contains("Chain startup") == true)
                if (hop1Died) {
                    failedEntryKeys.add(firstKey)
                    knownCleanExits.remove(firstKey)
                } else if (hops.size == 1 && report.egress != null) {
                    reachableBridgeHops.add(firstHop)
                    val eg = report.egress
                    val ccOk = if (targetService == TargetService.GOOGLE_FLOW) isFlowCountrySupported(eg.countryCode) else isAiStudioCountrySupported(eg.countryCode)
                    if (ccOk && !blockedEgressIps.contains(eg.ip)) {
                        knownCleanExits.putIfAbsent(firstKey, firstHop)
                    } else {
                        knownCleanExits.remove(firstKey)
                        knownRelays.putIfAbsent(firstKey, firstHop)
                    }
                }
            }

            val ck = chainKey(hops)
            val eligible = !(excludeCurrentRoute && hops.isEmpty()) &&
                !blockedChainSpecs.contains(ck) &&
                isEligibleRouteReport(report, targetService, blockedEgressIps)
            val score = gateScore(report, targetService)
            val attempt = ChainAttempt(label, hops, report, score)
            attempts.add(attempt)

            if (eligible) {
                if (bestEligibleAttempt == null || score < bestEligibleAttempt!!.score) {
                    bestEligibleAttempt = attempt
                }
            }
            if (bestFallbackAttempt == null || score < bestFallbackAttempt!!.score) {
                bestFallbackAttempt = attempt
            }
            return attempt
        }

        // Baseline inspection when excludeCurrentRoute = true (only to capture blocked egress IP, NEVER accepted)
        if (excludeCurrentRoute && blockedEgressIps.isEmpty()) {
            onStep("Recording current blocked exit IP before rotating VPN/proxy layers…")
            val (baselineReport, _) = routeProber(existingHops, "Baseline Current Route")
            baselineReport.egress?.ip?.takeIf { it.isNotBlank() }?.let { baseIp ->
                blockedEgressIps.add(baseIp)
                onStep("  • Excluding current blocked IP $baseIp — seeking new clean egress…")
            }
        } else if (!excludeCurrentRoute) {
            val stage0Chains = listOfNotNull(
                if (!blockedChainSpecs.contains("")) emptyList<TunnelConfig.ProxyHop>() else null,
                existingHops.takeIf { it.isNotEmpty() && !blockedChainSpecs.contains(chainKey(it)) }
            )
            for (hops in stage0Chains) {
                if (testedCount >= maxCandidates) break
                val ck = chainKey(hops)
                if (!probedKeys.add(ck)) continue
                val attempt = recordProbe(hops, 0)
                if (bestEligibleAttempt != null && attempt.report.egress?.isProxyOrHosting == false) {
                    break
                }
            }
        }

        val effectiveRounds = maxRounds.coerceIn(1, 6)
        for (round in 1..effectiveRounds) {
            if (bestEligibleAttempt != null && bestEligibleAttempt!!.report.egress?.isProxyOrHosting == false) {
                break
            }
            if (testedCount >= maxCandidates) break

            val untestedBeforeHarvest = masterPool.values.filterNot { triedQualifyHosts.contains(it.host) }
            if (allowHarvest && (round > 1 || untestedBeforeHarvest.size < 16)) {
                val newlyHarvested = harvester(round, triedQualifyHosts.toSet(), triedQualifySubnets.toSet())
                for (h in newlyHarvested) {
                    masterPool.putIfAbsent(hopKey(h), h)
                }
            }

            val untestedAll = masterPool.values.filterNot { triedQualifyHosts.contains(it.host) }
            val batch = UniversalConfig.selectDiverseProxyHops(
                hops = untestedAll,
                limit = 48,
                excludedHosts = triedQualifyHosts,
                excludedSubnets16 = triedQualifySubnets
            )
            val roundCleanExits = ArrayList<TunnelConfig.ProxyHop>()
            if (batch.isNotEmpty()) {
                for (h in batch) {
                    triedQualifyHosts.add(h.host)
                    triedQualifySubnets.add(UniversalConfig.subnet16Of(h.host))
                }
                onStep("Round $round/$effectiveRounds: Qualifying ${batch.size} diverse hops (CONNECT + TLS geo-trace)…")
                val q = hopQualifier(batch)
                for (h in q.cleanExits) {
                    roundCleanExits.add(h)
                    knownCleanExits.putIfAbsent(hopKey(h), h)
                }
                for (h in q.relays) knownRelays.putIfAbsent(hopKey(h), h)
                onStep("  • Qualified ${q.cleanExits.size} clean exit(s), ${q.relays.size} relay hop(s)")
            }

            // Prioritize newly qualified clean exits from this round ahead of older relays
            val orderedCleanExits = (roundCleanExits + knownCleanExits.values)
                .distinctBy { hopKey(it) }
            val aliveOrdered = UniversalConfig.selectDiverseProxyHops(
                hops = orderedCleanExits + knownRelays.values.toList(),
                limit = 24
            )
            if (aliveOrdered.isEmpty() && listening.isEmpty()) {
                continue
            }

            val roundStages = buildProgressiveLayerStages(
                existingHops = if (excludeCurrentRoute) emptyList() else existingHops,
                listening = listening,
                harvestedHops = aliveOrdered,
                preferredEntries = reachableBridgeHops + knownRelays.values.toList(),
                preferredExits = orderedCleanExits,
                maxTotalCandidates = (maxCandidates * 2).coerceAtLeast(96)
            )

            // Allocate budget so earlier rounds leave slots for fresh harvests in subsequent rounds
            val roundsRemainingAfterThis = (effectiveRounds - round)
            val reserveForNextRounds = if (roundsRemainingAfterThis > 0 && allowHarvest) (roundsRemainingAfterThis * 10).coerceAtMost(maxCandidates / 2) else 0
            val roundBudgetLimit = (maxCandidates - reserveForNextRounds).coerceAtLeast(testedCount + 6).coerceAtMost(maxCandidates)

            var foundCleanInRound = false
            // Run Stages 1 -> 2 -> 3 -> 4 -> 5 -> 6 (up to 8 hops) for this round
            for (sIdx in 1 until roundStages.size) {
                if (foundCleanInRound || testedCount >= roundBudgetLimit) break
                val candidates = roundStages[sIdx]
                if (candidates.isEmpty()) continue
                if (sIdx >= 2) {
                    onStep("[Round $round] Escalating to Layer $sIdx (${candidates.size} cascade candidate(s))…")
                }
                var nonResidentialGraceLeft = -1
                for (hops in candidates) {
                    if (testedCount >= roundBudgetLimit) break
                    if (hops.isNotEmpty() && failedEntryKeys.contains(hopKey(hops.first()))) continue
                    val ck = chainKey(hops)
                    if (blockedChainSpecs.contains(ck) || !probedKeys.add(ck)) continue

                    val attempt = recordProbe(hops, sIdx)
                    val eligible = isEligibleRouteReport(attempt.report, targetService, blockedEgressIps)
                    if (eligible) {
                        if (attempt.report.egress?.isProxyOrHosting == false) {
                            foundCleanInRound = true
                            break
                        }
                        nonResidentialGraceLeft = if (nonResidentialGraceLeft < 0) 2 else (nonResidentialGraceLeft - 1)
                        if (nonResidentialGraceLeft <= 0) {
                            foundCleanInRound = true
                            break
                        }
                    } else if (nonResidentialGraceLeft > 0) {
                        nonResidentialGraceLeft--
                        if (nonResidentialGraceLeft == 0) {
                            foundCleanInRound = true
                            break
                        }
                    }
                }
                if (bestEligibleAttempt != null && bestEligibleAttempt!!.report.egress?.isProxyOrHosting == false) {
                    foundCleanInRound = true
                    break
                }
            }
        }

        val winner = bestEligibleAttempt ?: bestFallbackAttempt ?: run {
            val (fallbackRep, _) = routeProber(emptyList(), "Direct")
            ChainAttempt("Direct", emptyList(), fallbackRep, Long.MAX_VALUE)
        }

        val passed = bestEligibleAttempt != null &&
            isEligibleRouteReport(winner.report, targetService, initialBlockedIps)
        val egressTag = winner.report.egress?.let { "${it.countryCode} (${it.ip})" } ?: "unknown egress"
        val summary = if (passed) {
            "Unlocked ${targetService.displayName} via ${winner.label} · New Clean Egress $egressTag"
        } else {
            val detail = if (targetService == TargetService.GOOGLE_FLOW) winner.report.flow.detail else winner.report.aiStudio.detail
            "Tested ${attempts.size} layer(s) across $effectiveRounds round(s) excluding ${blockedEgressIps.size} blocked IP(s) — " +
                "no clean exit unlocked ${targetService.displayName} (best egress: $egressTag · $detail)."
        }

        return ChainDiscoveryResult(
            targetService = targetService,
            success = passed,
            externalVpnActive = externalVpnActive,
            bestHops = winner.hops,
            bestChainLabel = winner.label,
            report = winner.report,
            attempts = attempts,
            summary = summary
        )
    }

    /**
     * Qualifies proxy hops in parallel via real HTTP/SOCKS5 CONNECT + TLS `/cdn-cgi/trace`,
     * separating clean non-blocked exits from usable relay hops.
     */
    fun qualifyProxyHops(
        hops: List<TunnelConfig.ProxyHop>,
        targetService: TargetService,
        excludedEgressIps: Set<String> = emptySet(),
        timeoutMs: Int = 1800
    ): QualifiedHopPool {
        if (hops.isEmpty()) return QualifiedHopPool(emptyList(), emptyList())
        val pool = Executors.newFixedThreadPool(hops.size.coerceAtMost(10))
        return try {
            val tasks = hops.map { hop ->
                Callable {
                    LocalProxyServer.probeHopTrace(hop, timeoutMs = timeoutMs)
                }
            }
            val futures = pool.invokeAll(tasks, timeoutMs + 2200L, TimeUnit.MILLISECONDS)
            val results = futures.mapNotNull { runCatching { it.get() }.getOrNull() }
            classifyQualifiedTraces(results, targetService, excludedEgressIps)
        } catch (_: Exception) {
            QualifiedHopPool(emptyList(), emptyList())
        } finally {
            pool.shutdownNow()
        }
    }

    /** Pure-Kotlin classification of traced hop results into clean preferred exits and relays. */
    fun classifyQualifiedTraces(
        traces: List<LocalProxyServer.HopTraceResult>,
        targetService: TargetService,
        excludedEgressIps: Set<String> = emptySet()
    ): QualifiedHopPool {
        val clean = ArrayList<LocalProxyServer.HopTraceResult>()
        val relays = ArrayList<LocalProxyServer.HopTraceResult>()
        val byKey = LinkedHashMap<String, LocalProxyServer.HopTraceResult>()

        for (t in traces) {
            val k = "${t.hop.scheme.name}:${t.hop.host}:${t.hop.port}"
            byKey[k] = t
            val isWarp = t.warpStatus.equals("on", ignoreCase = true) ||
                t.warpStatus.equals("plus", ignoreCase = true)
            val ccOk = if (targetService == TargetService.GOOGLE_FLOW) {
                isFlowCountrySupported(t.countryCode)
            } else {
                isAiStudioCountrySupported(t.countryCode)
            }
            if (ccOk && !isWarp && !excludedEgressIps.contains(t.exitIp)) {
                clean.add(t)
            } else {
                relays.add(t)
            }
        }

        // Prioritize Tier-1 Warrior countries for Flow or preferred clean countries for AI Studio, then latency, then /16 spread
        val sortedClean = clean.sortedWith(
            compareBy<LocalProxyServer.HopTraceResult> {
                val tierMatch = if (targetService == TargetService.GOOGLE_FLOW) {
                    FlowWarriorEngine.WARRIOR_TIER1_COUNTRIES.contains(it.countryCode)
                } else {
                    PREFERRED_CLEAN_COUNTRIES.contains(it.countryCode)
                }
                if (tierMatch) 0 else 1
            }.thenBy { it.latencyMs }
        ).map { it.hop }
        val diverseClean = UniversalConfig.selectDiverseProxyHops(sortedClean, limit = sortedClean.size.coerceAtLeast(1))

        val sortedRelays = relays.sortedBy { it.latencyMs }.map { it.hop }
        val diverseRelays = UniversalConfig.selectDiverseProxyHops(sortedRelays, limit = sortedRelays.size.coerceAtLeast(1))
        return QualifiedHopPool(
            cleanExits = diverseClean,
            relays = diverseClean + diverseRelays,
            resolvedByHopKey = byKey
        )
    }

    private fun probeChainThroughEphemeralBridge(
        hops: List<TunnelConfig.ProxyHop>,
        label: String,
        externalVpnActive: Boolean,
        targetService: TargetService,
        timeoutMs: Int
    ): AccessProbeReport = probeChainThroughEphemeralBridgeWithError(
        hops = hops,
        label = label,
        externalVpnActive = externalVpnActive,
        targetService = targetService,
        timeoutMs = timeoutMs
    ).first

    private fun probeChainThroughEphemeralBridgeWithError(
        hops: List<TunnelConfig.ProxyHop>,
        label: String,
        externalVpnActive: Boolean,
        targetService: TargetService,
        timeoutMs: Int
    ): Pair<AccessProbeReport, String?> {
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
            val report = runAccessProbe(route, timeoutMs)
            report to ephemeral.stats().lastError
        } catch (e: Exception) {
            val errMsg = e.message ?: ephemeral.stats().lastError ?: "Chain startup failed"
            val errProbe = ServiceProbeResult(
                reachable = false,
                httpCode = -1,
                latencyMs = -1,
                unsupportedRegion = false,
                finalLocation = "",
                detail = "Chain startup failed ($errMsg)",
                appShellOk = false
            )
            AccessProbeReport(
                routeLabel = label,
                egress = null,
                flowCountrySupported = false,
                aiStudio = errProbe,
                flow = errProbe,
                headline = "Chain Failed ($label)",
                recommendation = errMsg,
                accountsReachable = false,
                aiStudioCountrySupported = false
            ) to "Chain startup: $errMsg"
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
