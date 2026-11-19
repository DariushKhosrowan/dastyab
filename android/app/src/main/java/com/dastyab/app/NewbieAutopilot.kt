package com.dastyab.app

import android.content.Context
import android.net.VpnService

/**
 * NewbieAutopilot — zero-effort, 1-tap automated engine for non-technical users.
 *
 * Automatically executes the complete end-to-end recovery & connection pipeline:
 *  1. Checks live reachability & active External VPN (Lantern) or local proxy ports.
 *  2. Scouts the fastest working encrypted DoH + clean UDP DNS resolver on this network.
 *  3. Preserves an active External VPN (Lantern) or uses a listening local proxy port without
 *     stealing Android's single OS VPN slot; starts DastYab VPN only when safe and needed.
 *  4. Re-verifies real internet health — auto-rolls back if anything breaks general web access.
 *  5. Auto-harvests fresh proxy configs (GitHub -> jsDelivr Mirror -> Telegram), benchmarks them
 *     in parallel, saves the fastest live config, and promotes it to an "Autopilot Best" VPN layer.
 *  6. Re-verifies AI Studio & Google Flow and produces a crystal-clear next-step action.
 */
object NewbieAutopilot {

    enum class Outcome {
        READY_DIRECT,
        READY_WITH_VPN,
        NEEDS_VPN_CONSENT,
        NEEDS_FULL_PROXY_APP,
        OFFLINE_OR_BLACKOUT
    }

    enum class ExternalRoutePlan {
        USE_EXTERNAL_VPN,
        USE_DETECTED_LOCAL_PROXY,
        USE_DASTYAB_VPN,
        NEEDS_EXTERNAL_VPN_CONNECT
    }

    data class AutopilotReport(
        val outcome: Outcome,
        val headline: String,
        val summary: String,
        val verification: ReachabilityVerifier.FullVerification,
        val bestDohLabel: String?,
        val bestHarvestedUri: String?,
        val bestHarvestedLatencyMs: Long,
        val harvestedTotal: Int,
        val installedProxyApps: List<ConfigHarvester.FallbackTool>,
        val logLines: List<String>
    )

    data class ExternalAutopilotResult(
        val plan: ExternalRoutePlan,
        val targetReady: Boolean,
        val routeLabel: String,
        val exitCountry: String?,
        val exitIp: String?,
        val latencyMs: Long,
        val flowCountrySupported: Boolean,
        val needsLanternConnect: Boolean,
        val lanternPackage: String?,
        val installedVpnApps: List<ConfigHarvester.FallbackTool>,
        val headline: String,
        val recommendation: String,
        val logLines: List<String>
    )

    /** Pure-Kotlin outcome decision helper (unit-testable on the JVM). */
    fun decideOutcome(
        verification: ReachabilityVerifier.FullVerification,
        vpnConnected: Boolean,
        vpnConsentNeeded: Boolean
    ): Outcome = when {
        verification.anyGoogleReady && vpnConnected -> Outcome.READY_WITH_VPN
        verification.anyGoogleReady -> Outcome.READY_DIRECT
        vpnConsentNeeded -> Outcome.NEEDS_VPN_CONSENT
        verification.internet.isOk -> Outcome.NEEDS_FULL_PROXY_APP
        else -> Outcome.OFFLINE_OR_BLACKOUT
    }

    /** Pure-Kotlin External VPN / Lantern autopilot plan selector (unit-testable on the JVM). */
    fun decideExternalAutopilotPlan(
        externalVpnActive: Boolean,
        listeningProxyPort: Int?,
        dastyabVpnActive: Boolean,
        targetReachable: Boolean
    ): ExternalRoutePlan = when {
        externalVpnActive -> ExternalRoutePlan.USE_EXTERNAL_VPN
        listeningProxyPort != null && listeningProxyPort in 1..65535 -> ExternalRoutePlan.USE_DETECTED_LOCAL_PROXY
        dastyabVpnActive && targetReachable -> ExternalRoutePlan.USE_DASTYAB_VPN
        targetReachable -> ExternalRoutePlan.USE_EXTERNAL_VPN
        else -> ExternalRoutePlan.NEEDS_EXTERNAL_VPN_CONNECT
    }

    fun run(
        context: Context,
        onStep: (String) -> Unit = {}
    ): AutopilotReport {
        val log = mutableListOf<String>()
        fun emit(msg: String) {
            log.add(msg)
            onStep(msg)
        }

        val externalVpnActive = NetworkUtils.hasActiveExternalVpn(context)
        val listeningProxies = GeoBypassEngine.discoverListeningProxies(timeoutMs = 150)

        emit("Step 1/5: Checking live reachability for AI Studio & Google Flow…")
        if (externalVpnActive) {
            emit("  • Detected active External VPN (Lantern / System) — preserving OS VPN slot.")
        } else if (listeningProxies.isNotEmpty()) {
            val top = listeningProxies.first()
            emit("  • Detected listening local proxy: ${top.preset.label} (${top.latencyMs}ms)")
        }

        var check = ReachabilityVerifier.verifyAll(timeoutMs = 3000)
        emit("  • Internet  : ${check.internet.detail}")
        emit("  • AI Studio : ${check.aiStudio.detail}")
        emit("  • Flow      : ${check.flow.detail}")

        val installedProxies = ConfigHarvester.detectFallbackTools(context).filter { it.isInstalled }
        if (check.allReady) {
            emit("OK: Everything is already reachable! Harvesting backup configs in background…")
            val quickHarvest = ConfigHarvester.harvestResilient(context)
            val best = HarvestSourceStore.benchmarkHarvestedConfigs(quickHarvest.configs, maxToTest = 10).firstOrNull()
            return AutopilotReport(
                outcome = if (DastyabVpnService.isConnected() || externalVpnActive) Outcome.READY_WITH_VPN else Outcome.READY_DIRECT,
                headline = "AI Studio & Google Flow Are Ready!",
                summary = "Both Google AI Studio (${check.aiStudio.latencyMs}ms) and Google Flow (${check.flow.latencyMs}ms) are reachable right now.",
                verification = check,
                bestDohLabel = TunnelConfig.load(context).dohUrl,
                bestHarvestedUri = best?.uri,
                bestHarvestedLatencyMs = best?.latencyMs ?: -1L,
                harvestedTotal = quickHarvest.configs.size,
                installedProxyApps = installedProxies,
                logLines = log
            )
        }

        // Step 2: Find fastest unpoisoned DoH & Clean DNS
        emit("Step 2/5: Scouting fastest encrypted DoH & Clean DNS resolvers…")
        val dnsResults = CleanEndpointScout.scoutCleanDnsAndDoh(timeoutMs = 2200)
        val bestDoh = dnsResults.firstOrNull { it.kind == CleanEndpointScout.Kind.DOH }
        val bestUdp = dnsResults.firstOrNull { it.kind == CleanEndpointScout.Kind.UDP_DNS }
        if (bestDoh != null || bestUdp != null) {
            TunnelConfig.saveDohAndDns(context, bestDoh?.address, bestUdp?.address)
            emit("  ✓ Best DoH: ${bestDoh?.label ?: "default"} (${bestDoh?.latencyMs ?: -1}ms), UDP DNS: ${bestUdp?.address ?: "1.1.1.1"}")
        } else {
            emit("  ✗ External DoH/DNS probes timed out — keeping default Cloudflare DoH.")
        }

        // Step 3: Preserve External VPN / Local Proxy if active, otherwise start DastYab Smart VPN
        val vpnConsentNeeded = VpnService.prepare(context) != null
        var activeVpnOrExternal = externalVpnActive || DastyabVpnService.isConnected()
        when {
            externalVpnActive -> {
                emit("Step 3/5: External VPN (Lantern / System) is active — preserving OS VPN slot without overwriting it.")
                val cur = TunnelConfig.load(context)
                if (cur.googleAccessMode == TunnelConfig.GoogleAccessMode.VPN_CHAIN_PROXY) {
                    TunnelConfig.saveGoogleProxyConfig(
                        context = context,
                        accessMode = TunnelConfig.GoogleAccessMode.AUTO_SMART,
                        scheme = TunnelConfig.ProxyScheme.DIRECT,
                        host = cur.upstreamProxyHost,
                        port = cur.upstreamProxyPort
                    )
                }
            }
            listeningProxies.isNotEmpty() -> {
                val bestProxy = listeningProxies.first().preset
                emit("Step 3/5: Using detected local proxy ${bestProxy.label} via Internal App Proxy (OS VPN slot kept free).")
                val cur = TunnelConfig.load(context)
                val needsSocksBridge = bestProxy.scheme == TunnelConfig.ProxyScheme.SOCKS5
                TunnelConfig.saveGoogleProxyConfig(
                    context = context,
                    accessMode = TunnelConfig.GoogleAccessMode.IN_APP_PROXY,
                    scheme = bestProxy.scheme,
                    host = bestProxy.host,
                    port = bestProxy.port,
                    localBridgePort = cur.localBridgePort,
                    autoStartBridge = needsSocksBridge
                )
                if (needsSocksBridge) {
                    LocalProxyService.startSafely(context)
                }
                val route = GeoBypassEngine.resolveCurrentRoute(context)
                val probe = GeoBypassEngine.runAccessProbe(route, timeoutMs = 3200)
                if (probe.readyForAiStudio || probe.readyForFlow) {
                    check = ReachabilityVerifier.FullVerification(
                        internet = check.internet,
                        aiStudio = ReachabilityVerifier.EndpointCheck(
                            "Google AI Studio",
                            ReachabilityVerifier.URL_AISTUDIO,
                            if (probe.readyForAiStudio) ReachabilityVerifier.Status.REACHABLE else ReachabilityVerifier.Status.TLS_OR_IP_BLOCKED,
                            probe.aiStudio.httpCode,
                            probe.aiStudio.latencyMs,
                            probe.egress?.ip ?: "-",
                            probe.aiStudio.detail
                        ),
                        flow = ReachabilityVerifier.EndpointCheck(
                            "Google Flow",
                            ReachabilityVerifier.URL_FLOW,
                            if (probe.readyForFlow) ReachabilityVerifier.Status.REACHABLE else ReachabilityVerifier.Status.TLS_OR_IP_BLOCKED,
                            probe.flow.httpCode,
                            probe.flow.latencyMs,
                            probe.egress?.ip ?: "-",
                            probe.flow.detail
                        )
                    )
                    activeVpnOrExternal = true
                }
            }
            !vpnConsentNeeded -> {
                emit("Step 3/5: Activating DastYab Smart Encrypted DoH Tunnel…")
                TunnelConfig.saveMode(context, TunnelConfig.Mode.SMART)
                DastyabVpnService.start(context)
                try { Thread.sleep(900) } catch (_: InterruptedException) {}

                val afterVpn = ReachabilityVerifier.verifyAll(timeoutMs = 3000)
                emit("  • Post-VPN Internet  : ${afterVpn.internet.detail}")
                emit("  • Post-VPN AI Studio : ${afterVpn.aiStudio.detail}")
                emit("  • Post-VPN Flow      : ${afterVpn.flow.detail}")

                if (!afterVpn.internet.isOk && check.internet.isOk) {
                    emit("  ⚠ Safety Guard: Disconnecting VPN because general internet dropped.")
                    DastyabVpnService.stop(context)
                } else {
                    check = afterVpn
                    activeVpnOrExternal = DastyabVpnService.isConnected()
                }
            }
            else -> {
                emit("Step 3/5: Android VPN permission needed once (will prompt you next).")
            }
        }

        // Step 4: Auto-Harvest & Benchmark Configs
        emit("Step 4/5: Auto-harvesting fresh configs (GitHub → Mirror → Telegram)…")
        val harvestReport = ConfigHarvester.harvestResilient(context) { line ->
            emit("  $line")
        }
        val pool = if (harvestReport.configs.isNotEmpty()) {
            harvestReport.configs
        } else {
            TunnelConfig.loadHarvestedConfigs(context)
        }

        var bestUri: String? = null
        var bestMs = -1L
        if (pool.isNotEmpty()) {
            emit("Step 5/5: Benchmarking top ${pool.size.coerceAtMost(20)} harvested configs in parallel…")
            val live = HarvestSourceStore.benchmarkHarvestedConfigs(pool, maxToTest = 20, timeoutMs = 1800)
            val fastest = live.firstOrNull()
            if (fastest != null) {
                bestUri = fastest.uri
                bestMs = fastest.latencyMs
                emit("  ✓ Fastest live config: [${fastest.protocol.uppercase()}] ${fastest.host}:${fastest.port} (${fastest.latencyMs}ms)")
                val layer = VpnLayerStore.VpnLayer(
                    id = "layer-autopilot-best",
                    name = "Autopilot Best (${fastest.host})",
                    mode = TunnelConfig.Mode.SMART,
                    configUri = fastest.uri,
                    dnsPrimary = bestUdp?.address ?: TunnelConfig.DEFAULT_DNS_PRIMARY,
                    dohUrl = bestDoh?.address ?: TunnelConfig.DEFAULT_DOH_URL
                )
                VpnLayerStore.addOrUpdate(context, layer)
                // Never overwrite an active external VPN (Lantern) on step 5!
                VpnLayerStore.activateLayer(
                    context,
                    layer,
                    restartIfConnected = !vpnConsentNeeded && !externalVpnActive
                )
            } else {
                emit("  ✗ Harvested configs did not respond on TCP — keeping Encrypted DoH layer.")
            }
        } else {
            emit("Step 5/5: No configs harvested yet (GitHub & Telegram unreachable).")
        }

        // Final decision
        val finalOutcome = decideOutcome(check, activeVpnOrExternal || DastyabVpnService.isConnected(), vpnConsentNeeded && !externalVpnActive && listeningProxies.isEmpty())
        val (headline, summary) = when (finalOutcome) {
            Outcome.READY_WITH_VPN ->
                "Autopilot Connected & Verified!" to
                    "Tunnel / VPN is active and AI Studio / Google Flow are verified reachable."
            Outcome.READY_DIRECT ->
                "AI Studio & Flow Reachable!" to
                    "Your connection reaches AI Studio / Google Flow cleanly. You can open them right now."
            Outcome.NEEDS_VPN_CONSENT ->
                "Tap Allow to Start Autopilot VPN" to
                    "Autopilot found your best DoH/DNS settings (${bestDoh?.label ?: "1.1.1.1"}) and ${pool.size} configs. Grant Android VPN permission once to connect."
            Outcome.NEEDS_FULL_PROXY_APP ->
                "ISP Blocks Google IP/SNI — Fastest Config Ready!" to
                    if (bestUri != null) {
                        "Your internet works and DNS is clean, but your ISP blocks Google AI Studio / Flow at the IP/SNI level. " +
                            "Autopilot found & verified a fast ${ConfigHarvester.protocolOf(bestUri).uppercase()} config (${bestMs}ms)! " +
                            "Tap below to copy it and launch a full-tunnel app (Lantern / v2rayNG / Hiddify / NekoBox / WhiteVPN) — DastYab will automatically chain with it."
                    } else {
                        "Your ISP blocks Google AI Studio / Flow at the IP/SNI level. " +
                            "Connect Lantern or an installed fallback VPN (WhiteVPN / SlipNet / Tor / Hiddify) or import a working config."
                    }
            Outcome.OFFLINE_OR_BLACKOUT ->
                "Severe Blackout or No Internet Detected" to
                    "Direct HTTPS failed. Activate Emergency DNS Blackout VPN, run Clean IP Scout, or launch Lantern / WhiteDNS / SlipNet / Tor."
        }

        emit("\n=== AUTOPILOT COMPLETE: $headline ===")
        return AutopilotReport(
            outcome = finalOutcome,
            headline = headline,
            summary = summary,
            verification = check,
            bestDohLabel = bestDoh?.label,
            bestHarvestedUri = bestUri,
            bestHarvestedLatencyMs = bestMs,
            harvestedTotal = pool.size,
            installedProxyApps = installedProxies,
            logLines = log
        )
    }

    /**
     * 1-Tap Lantern / External VPN Autopilot for Google AI Studio & Google Flow.
     *
     * Eliminates the manual 4-step workflow ("turn on Lantern -> Route & Proxy -> External VPN
     * -> Terminal -> aistudio"):
     *  1. Detects active External VPN (Lantern / system VPN) or listening local proxy ports.
     *  2. Ensures External VPN pass-through or Internal App Proxy is configured WITHOUT killing Lantern.
     *  3. Verifies exit IP, Flow 151-country support, and live HTTPS reachability for [targetUrl].
     *  4. Reports exact readiness or offers 1-tap Lantern / VPN launch if none is active.
     */
    fun runExternalVpnAutopilot(
        context: Context,
        targetName: String,
        targetUrl: String,
        onStep: (String) -> Unit = {}
    ): ExternalAutopilotResult {
        val log = mutableListOf<String>()
        fun emit(msg: String) {
            log.add(msg)
            onStep(msg)
        }

        emit("1/4 Scanning VPN transport & local proxy ports for $targetName…")
        if (DastyabVpnService.isConnected()) {
            // If user explicitly invoked External VPN / Lantern Autopilot while DastYab's own VpnService is running
            // and another proxy isn't active, keep DastYab VPN unless an external proxy is preferred.
            emit("  • DastYab VPN is currently active.")
        }

        val externalVpnActive = NetworkUtils.hasActiveExternalVpn(context)
        val listeningProxies = GeoBypassEngine.discoverListeningProxies(timeoutMs = 150)
        val installedTools = ConfigHarvester.detectFallbackTools(context).filter { it.isInstalled }
        val lanternTool = installedTools.firstOrNull {
            it.packageNames.contains("org.getlantern.lantern")
        }
        val currentSettings = TunnelConfig.load(context)

        if (externalVpnActive) {
            emit("  ✓ External VPN detected (Lantern / System VPN) — setting External VPN pass-through.")
            TunnelConfig.saveGoogleProxyConfig(
                context = context,
                accessMode = TunnelConfig.GoogleAccessMode.EXTERNAL_VPN,
                scheme = TunnelConfig.ProxyScheme.DIRECT,
                host = currentSettings.upstreamProxyHost,
                port = currentSettings.upstreamProxyPort,
                localBridgePort = currentSettings.localBridgePort,
                autoStartBridge = false
            )
        } else if (listeningProxies.isNotEmpty()) {
            val fastest = listeningProxies.first()
            emit("  ✓ Detected local proxy ${fastest.preset.label} (${fastest.latencyMs}ms) — enabling Internal App Proxy.")
            val needsBridge = fastest.preset.scheme == TunnelConfig.ProxyScheme.SOCKS5
            TunnelConfig.saveGoogleProxyConfig(
                context = context,
                accessMode = TunnelConfig.GoogleAccessMode.IN_APP_PROXY,
                scheme = fastest.preset.scheme,
                host = fastest.preset.host,
                port = fastest.preset.port,
                localBridgePort = currentSettings.localBridgePort,
                autoStartBridge = needsBridge
            )
            if (needsBridge) {
                LocalProxyService.startSafely(context)
                var waited = 0
                while (LocalProxyService.activePort() == null && waited < 900) {
                    try { Thread.sleep(60) } catch (_: InterruptedException) { break }
                    waited += 60
                }
            }
        } else {
            emit("  • No active External VPN (Lantern) or local proxy port detected yet — probing active route.")
        }

        emit("2/4 Resolving route & verifying exit IP + country…")
        val route = GeoBypassEngine.resolveCurrentRoute(context)
        val probe = GeoBypassEngine.runAccessProbe(route, timeoutMs = 4000)
        val isFlowTarget = targetUrl.contains("flow.google.com", ignoreCase = true)
        val targetProbe = if (isFlowTarget) probe.flow else probe.aiStudio
        val targetReady = if (isFlowTarget) probe.readyForFlow else probe.readyForAiStudio

        val cc = probe.egress?.countryCode
        val ip = probe.egress?.ip
        emit("3/4 Exit IP: ${ip ?: "unknown"} (${cc ?: "??"}) · ${targetName}: ${targetProbe.detail}")

        val plan = decideExternalAutopilotPlan(
            externalVpnActive = externalVpnActive,
            listeningProxyPort = listeningProxies.firstOrNull()?.preset?.port,
            dastyabVpnActive = DastyabVpnService.isConnected(),
            targetReachable = targetReady
        )
        val needsLanternConnect = !externalVpnActive && listeningProxies.isEmpty() && !targetReady

        emit("4/4 Plan: ${plan.name} · Ready=$targetReady")

        return ExternalAutopilotResult(
            plan = plan,
            targetReady = targetReady,
            routeLabel = probe.routeLabel,
            exitCountry = cc,
            exitIp = ip,
            latencyMs = targetProbe.latencyMs,
            flowCountrySupported = probe.flowCountrySupported,
            needsLanternConnect = needsLanternConnect,
            lanternPackage = lanternTool?.installedPackage,
            installedVpnApps = installedTools,
            headline = probe.headline,
            recommendation = probe.recommendation,
            logLines = log
        )
    }
}
