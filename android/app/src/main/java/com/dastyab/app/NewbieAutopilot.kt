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
        val logLines: List<String>,
        val aiStudioReady: Boolean = targetReady,
        val flowReady: Boolean = targetReady,
        val aiStudioRouteLabel: String = routeLabel,
        val flowRouteLabel: String = routeLabel
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

    private fun startBridgeForRouteIfNeeded(
        context: Context,
        route: GeoBypassEngine.RouteResolution,
        target: GeoBypassEngine.TargetService
    ): GeoBypassEngine.RouteResolution {
        if (!route.shouldStartLocalBridge) return route
        LocalProxyService.startSafely(context, target.key)
        var waited = 0
        while (
            (LocalProxyService.activePort() == null ||
                LocalProxyService.activeChainSpec() != route.upstreamChainSpec) &&
            waited < 1200
        ) {
            try { Thread.sleep(60) } catch (_: InterruptedException) { break }
            waited += 60
        }
        return GeoBypassEngine.resolveCurrentRoute(context, targetService = target)
    }

    private fun toEndpointCheck(
        name: String,
        url: String,
        ready: Boolean,
        probe: GeoBypassEngine.ServiceProbeResult,
        report: GeoBypassEngine.AccessProbeReport,
        isFlow: Boolean
    ): ReachabilityVerifier.EndpointCheck {
        val ccSupported = if (isFlow) report.flowCountrySupported else report.aiStudioCountrySupported
        val status = when {
            ready -> ReachabilityVerifier.Status.REACHABLE
            probe.unsupportedRegion || !ccSupported || (isFlow && probe.reachable && !probe.appShellOk) ->
                ReachabilityVerifier.Status.GEO_RESTRICTED
            probe.httpCode > 0 -> ReachabilityVerifier.classifyHttpCode(probe.httpCode)
            else -> ReachabilityVerifier.Status.TLS_OR_IP_BLOCKED
        }
        val detail = when {
            ready -> probe.detail
            !ccSupported -> "Geo-blocked country ${report.egress?.countryCode ?: "unknown"} (${probe.detail})"
            else -> probe.detail
        }
        return ReachabilityVerifier.EndpointCheck(
            name = name,
            url = url,
            status = status,
            httpCode = probe.httpCode,
            latencyMs = probe.latencyMs,
            resolvedIp = report.egress?.ip ?: "-",
            detail = detail
        )
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

        emit("Step 1/5: Checking deep geo & reachability for AI Studio and Google Flow separately…")
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

        // Even when general HTTP responds, verify deep geo-gates separately for AI Studio & Flow!
        val initialAiRoute = startBridgeForRouteIfNeeded(
            context,
            GeoBypassEngine.resolveCurrentRoute(context, targetService = GeoBypassEngine.TargetService.AI_STUDIO),
            GeoBypassEngine.TargetService.AI_STUDIO
        )
        var aiProbe = GeoBypassEngine.runAccessProbe(initialAiRoute, timeoutMs = 4000)

        val initialFlowRoute = startBridgeForRouteIfNeeded(
            context,
            GeoBypassEngine.resolveCurrentRoute(context, targetService = GeoBypassEngine.TargetService.GOOGLE_FLOW),
            GeoBypassEngine.TargetService.GOOGLE_FLOW
        )
        var flowProbe = GeoBypassEngine.runAccessProbe(initialFlowRoute, timeoutMs = 4000)

        check = ReachabilityVerifier.FullVerification(
            internet = check.internet,
            aiStudio = toEndpointCheck("Google AI Studio", ReachabilityVerifier.URL_AISTUDIO, aiProbe.readyForAiStudio, aiProbe.aiStudio, aiProbe, isFlow = false),
            flow = toEndpointCheck("Google Flow", ReachabilityVerifier.URL_FLOW, flowProbe.readyForFlow, flowProbe.flow, flowProbe, isFlow = true)
        )

        if (check.internet.isOk && aiProbe.readyForAiStudio && flowProbe.readyForFlow) {
            emit("OK: Both AI Studio & Google Flow passed strict geo-gate checks! Harvesting backup configs…")
            val quickHarvest = ConfigHarvester.harvestResilient(context)
            val best = HarvestSourceStore.benchmarkHarvestedConfigs(quickHarvest.configs, maxToTest = 10).firstOrNull()
            return AutopilotReport(
                outcome = if (DastyabVpnService.isConnected() || externalVpnActive) Outcome.READY_WITH_VPN else Outcome.READY_DIRECT,
                headline = "AI Studio & Google Flow Are Geo-Clear!",
                summary = "Google AI Studio (${aiProbe.aiStudio.latencyMs}ms via ${aiProbe.egress?.countryCode}) and Google Flow (${flowProbe.flow.latencyMs}ms via ${flowProbe.egress?.countryCode}) are both verified non-georestricted.",
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

        // Step 3: Preserve External VPN / stack multi-layer chains ON TOP of Lantern or local proxy
        val vpnConsentNeeded = VpnService.prepare(context) != null
        var activeVpnOrExternal = externalVpnActive || DastyabVpnService.isConnected()
        when {
            externalVpnActive || listeningProxies.isNotEmpty() -> {
                if (externalVpnActive) {
                    emit("Step 3/5: External VPN (Lantern) is active — chaining proxy layers ON TOP without killing OS VPN.")
                } else {
                    emit("Step 3/5: Local proxy port detected — chaining proxy layers without occupying OS VPN slot.")
                }
                val cur = TunnelConfig.load(context)
                if (cur.googleAccessMode == TunnelConfig.GoogleAccessMode.VPN_CHAIN_PROXY ||
                    cur.googleAccessMode == TunnelConfig.GoogleAccessMode.EXTERNAL_VPN
                ) {
                    TunnelConfig.saveGoogleProxyConfig(
                        context = context,
                        accessMode = TunnelConfig.GoogleAccessMode.AUTO_SMART,
                        scheme = cur.upstreamProxyScheme,
                        host = cur.upstreamProxyHost,
                        port = cur.upstreamProxyPort,
                        localBridgePort = cur.localBridgePort,
                        autoStartBridge = cur.autoStartLocalBridge,
                        upstreamProxyChain = cur.upstreamProxyChain
                    )
                }

                if (!aiProbe.readyForAiStudio) {
                    emit("  • AI Studio geo-restricted/blocked — auto-chaining layers for AI Studio…")
                    val aiChain = GeoBypassEngine.discoverAndChainBestRoute(
                        context = context,
                        targetService = GeoBypassEngine.TargetService.AI_STUDIO,
                        allowHarvest = true,
                        maxCandidates = 44,
                        excludedEgressIps = setOfNotNull(aiProbe.egress?.ip),
                        excludeCurrentRoute = true,
                        maxRounds = 3
                    ) { emit("    $it") }
                    aiProbe = aiChain.report
                    emit("  • AI Studio result: ${aiChain.summary}")
                }
                if (!flowProbe.readyForFlow) {
                    emit("  • Google Flow geo-restricted/blocked — auto-chaining stricter layers for Flow…")
                    val flowChain = GeoBypassEngine.discoverAndChainBestRoute(
                        context = context,
                        targetService = GeoBypassEngine.TargetService.GOOGLE_FLOW,
                        allowHarvest = true,
                        maxCandidates = 52,
                        excludedEgressIps = setOfNotNull(flowProbe.egress?.ip),
                        excludeCurrentRoute = true,
                        maxRounds = 3
                    ) { emit("    $it") }
                    flowProbe = flowChain.report
                    emit("  • Google Flow result: ${flowChain.summary}")
                }

                check = ReachabilityVerifier.FullVerification(
                    internet = check.internet,
                    aiStudio = toEndpointCheck("Google AI Studio", ReachabilityVerifier.URL_AISTUDIO, aiProbe.readyForAiStudio, aiProbe.aiStudio, aiProbe, isFlow = false),
                    flow = toEndpointCheck("Google Flow", ReachabilityVerifier.URL_FLOW, flowProbe.readyForFlow, flowProbe.flow, flowProbe, isFlow = true)
                )
                activeVpnOrExternal = true
            }
            !vpnConsentNeeded -> {
                emit("Step 3/5: Activating DastYab Smart Encrypted DoH Tunnel…")
                TunnelConfig.saveMode(context, TunnelConfig.Mode.SMART)
                DastyabVpnService.start(context)
                try { Thread.sleep(900) } catch (_: InterruptedException) {}

                val afterVpn = ReachabilityVerifier.verifyAll(timeoutMs = 3500)
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

        // If either AI Studio or Flow is still geo-blocked after VPN + harvest, run autonomous multi-round
        // proxy-hop harvesting + progressive 1..4-hop cascading & egress IP rotation!
        if (!check.aiStudio.isOk || !check.flow.isOk) {
            emit("Geo-restriction detected — autonomously harvesting & rotating multi-layer bypass IPs…")
            var anyChainUnlocked = false
            val blockedAiIps = listOfNotNull(
                aiProbe.egress?.ip,
                check.aiStudio.resolvedIp.takeIf { it.isNotBlank() && it != "-" && it != "via-proxy" }
            ).toSet()
            val blockedFlowIps = listOfNotNull(
                flowProbe.egress?.ip,
                check.flow.resolvedIp.takeIf { it.isNotBlank() && it != "-" && it != "via-proxy" }
            ).toSet()

            if (!check.aiStudio.isOk) {
                val retryAi = GeoBypassEngine.discoverAndChainBestRoute(
                    context = context,
                    targetService = GeoBypassEngine.TargetService.AI_STUDIO,
                    allowHarvest = true,
                    maxCandidates = 44,
                    excludedEgressIps = blockedAiIps,
                    excludeCurrentRoute = true,
                    maxRounds = 3
                ) { emit("  [auto-chain AI Studio] $it") }
                aiProbe = retryAi.report
                if (retryAi.success) anyChainUnlocked = true
            }
            if (!check.flow.isOk) {
                val retryFlow = GeoBypassEngine.discoverAndChainBestRoute(
                    context = context,
                    targetService = GeoBypassEngine.TargetService.GOOGLE_FLOW,
                    allowHarvest = true,
                    maxCandidates = 52,
                    excludedEgressIps = blockedFlowIps,
                    excludeCurrentRoute = true,
                    maxRounds = 3
                ) { emit("  [auto-chain Flow] $it") }
                flowProbe = retryFlow.report
                if (retryFlow.success) anyChainUnlocked = true
            }
            if (anyChainUnlocked && DastyabVpnService.isConnected() && !externalVpnActive) {
                DastyabVpnService.start(context)
                try { Thread.sleep(500) } catch (_: InterruptedException) {}
            }
            check = ReachabilityVerifier.FullVerification(
                internet = check.internet,
                aiStudio = toEndpointCheck("Google AI Studio", ReachabilityVerifier.URL_AISTUDIO, aiProbe.readyForAiStudio, aiProbe.aiStudio, aiProbe, isFlow = false),
                flow = toEndpointCheck("Google Flow", ReachabilityVerifier.URL_FLOW, flowProbe.readyForFlow, flowProbe.flow, flowProbe, isFlow = true)
            )
        }

        // Final decision (requires true non-geoblock clearance, never raw HTTP 200)
        val finalOutcome = decideOutcome(check, activeVpnOrExternal || DastyabVpnService.isConnected(), vpnConsentNeeded && !externalVpnActive && listeningProxies.isEmpty())
        val bothClear = check.aiStudio.isOk && check.flow.isOk
        val (headline, summary) = when (finalOutcome) {
            Outcome.READY_WITH_VPN ->
                (if (bothClear) {
                    "Autopilot Connected & Both Services Geo-Clear!"
                } else if (check.aiStudio.isOk) {
                    "Autopilot Partial: AI Studio Ready ✓ · Flow Geo-Blocked ✗"
                } else {
                    "Autopilot Partial: Flow Ready ✓ · AI Studio Geo-Blocked ✗"
                }) to
                    "AI Studio: ${check.aiStudio.detail}\nGoogle Flow: ${check.flow.detail}"
            Outcome.READY_DIRECT ->
                (if (bothClear) {
                    "AI Studio & Flow Verified Geo-Clear!"
                } else if (check.aiStudio.isOk) {
                    "AI Studio Geo-Clear ✓ · Flow Geo-Blocked ✗"
                } else {
                    "Google Flow Geo-Clear ✓ · AI Studio Geo-Blocked ✗"
                }) to
                    "AI Studio: ${check.aiStudio.detail}\nGoogle Flow: ${check.flow.detail}"
            Outcome.NEEDS_VPN_CONSENT ->
                "Tap Allow to Start Autopilot VPN" to
                    "Autopilot found your best DoH/DNS settings (${bestDoh?.label ?: "1.1.1.1"}) and ${pool.size} configs. Grant Android VPN permission once to connect."
            Outcome.NEEDS_FULL_PROXY_APP ->
                "Geo-Restriction / ISP Block Persists — Fastest Config Ready!" to
                    if (bestUri != null) {
                        "Your active exit is still blocked/georestricted for AI Studio or Flow. " +
                            "Autopilot found a fast ${ConfigHarvester.protocolOf(bestUri).uppercase()} config (${bestMs}ms)! " +
                            "Tap below to copy it and import into Lantern / v2rayNG / NekoBox — DastYab will chain on top automatically."
                    } else {
                        "Your active exit is blocked/georestricted for AI Studio or Flow. " +
                            "Switch Lantern region or run v2rayNG / NekoBox / Hiddify so DastYab can chain layers on top."
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
     * 1-Tap Lantern / External VPN + Multi-Layer Chaining Autopilot for Google AI Studio & Google Flow.
     *
     * Treats Lantern as Layer 0 (preserving Android's OS VPN slot), probes AI Studio & Google Flow
     * *separately* against strict geo-gates (exit country + multi-hop redirect + DOM markers + accounts +
     * Flow `AiSandboxAngularFrontend`), and automatically chains 1-, 2-, or 3-hop proxy layers on top
     * for whichever service is still georestricted!
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

        emit("1/5 Scanning Lantern / External VPN & local proxy ports ($targetName)…")
        if (DastyabVpnService.isConnected()) {
            emit("  • DastYab VPN is currently active.")
        }

        val externalVpnActive = NetworkUtils.hasActiveExternalVpn(context)
        val listeningProxies = GeoBypassEngine.discoverListeningProxies(timeoutMs = 150)
        val installedTools = ConfigHarvester.detectFallbackTools(context).filter { it.isInstalled }
        val lanternTool = installedTools.firstOrNull {
            it.packageNames.contains("org.getlantern.lantern")
        }
        val currentSettings = TunnelConfig.load(context)

        // Preserve AUTO_SMART so saved per-service proxy chains layer cleanly ON TOP of Lantern!
        TunnelConfig.saveGoogleProxyConfig(
            context = context,
            accessMode = TunnelConfig.GoogleAccessMode.AUTO_SMART,
            scheme = currentSettings.upstreamProxyScheme,
            host = currentSettings.upstreamProxyHost,
            port = currentSettings.upstreamProxyPort,
            localBridgePort = currentSettings.localBridgePort,
            autoStartBridge = currentSettings.autoStartLocalBridge,
            upstreamProxyChain = currentSettings.upstreamProxyChain
        )
        if (externalVpnActive) {
            emit("  ✓ External VPN detected (Lantern) — keeping OS VPN active as Layer 0.")
        } else if (listeningProxies.isNotEmpty()) {
            val fastest = listeningProxies.first()
            emit("  ✓ Detected local proxy ${fastest.preset.label} (${fastest.latencyMs}ms) as Layer 1.")
        } else {
            emit("  • No active External VPN (Lantern) or local proxy port detected yet.")
        }

        emit("2/5 Verifying Google AI Studio route (separate from Google Flow)…")
        val aiRoute = startBridgeForRouteIfNeeded(
            context,
            GeoBypassEngine.resolveCurrentRoute(context, targetService = GeoBypassEngine.TargetService.AI_STUDIO),
            GeoBypassEngine.TargetService.AI_STUDIO
        )
        var aiProbe = GeoBypassEngine.runAccessProbe(aiRoute, timeoutMs = 4200)
        if (!aiProbe.readyForAiStudio) {
            emit("  ✗ AI Studio blocked/georestricted via ${aiProbe.routeLabel} — auto-chaining layers for AI Studio…")
            val discoveredAi = GeoBypassEngine.discoverAndChainBestRoute(
                context = context,
                targetService = GeoBypassEngine.TargetService.AI_STUDIO,
                allowHarvest = true,
                maxCandidates = 44,
                excludedEgressIps = setOfNotNull(aiProbe.egress?.ip),
                excludeCurrentRoute = true,
                maxRounds = 3
            ) { step -> emit("    $step") }
            aiProbe = discoveredAi.report
            emit("  → AI Studio: ${discoveredAi.summary}")
        } else {
            emit("  ✓ AI Studio verified via ${aiProbe.routeLabel} (${aiProbe.egress?.countryCode} ${aiProbe.egress?.ip})")
        }

        emit("3/5 Verifying Google Flow route (stricter 151-country + app-shell target)…")
        val flowRoute = startBridgeForRouteIfNeeded(
            context,
            GeoBypassEngine.resolveCurrentRoute(context, targetService = GeoBypassEngine.TargetService.GOOGLE_FLOW),
            GeoBypassEngine.TargetService.GOOGLE_FLOW
        )
        var flowProbe = GeoBypassEngine.runAccessProbe(flowRoute, timeoutMs = 4500)
        if (!flowProbe.readyForFlow) {
            emit("  ✗ Google Flow blocked/georestricted via ${flowProbe.routeLabel} — auto-chaining deeper layers for Flow…")
            val discoveredFlow = GeoBypassEngine.discoverAndChainBestRoute(
                context = context,
                targetService = GeoBypassEngine.TargetService.GOOGLE_FLOW,
                allowHarvest = true,
                maxCandidates = 52,
                excludedEgressIps = setOfNotNull(flowProbe.egress?.ip),
                excludeCurrentRoute = true,
                maxRounds = 3
            ) { step -> emit("    $step") }
            flowProbe = discoveredFlow.report
            emit("  → Google Flow: ${discoveredFlow.summary}")
        } else {
            emit("  ✓ Google Flow verified via ${flowProbe.routeLabel} (${flowProbe.egress?.countryCode} ${flowProbe.egress?.ip})")
        }

        val isFlowPrimary = targetUrl.contains("flow.google.com", ignoreCase = true)
        val primaryProbe = if (isFlowPrimary) flowProbe else aiProbe
        val aiReady = aiProbe.readyForAiStudio
        val flowReady = flowProbe.readyForFlow
        val anyReady = aiReady || flowReady
        val targetReady = if (isFlowPrimary) flowReady else (aiReady && flowReady)
        val effectiveEgress = flowProbe.egress ?: aiProbe.egress
        val cc = effectiveEgress?.countryCode
        val ip = effectiveEgress?.ip

        emit("4/5 AI Studio: ${if (aiReady) "READY ✓" else "GEO-BLOCKED ✗"} (${aiProbe.routeLabel}) | Flow: ${if (flowReady) "READY ✓" else "GEO-BLOCKED ✗"} (${flowProbe.routeLabel})")

        val plan = decideExternalAutopilotPlan(
            externalVpnActive = externalVpnActive,
            listeningProxyPort = listeningProxies.firstOrNull()?.preset?.port,
            dastyabVpnActive = DastyabVpnService.isConnected(),
            targetReachable = anyReady
        )
        val needsLanternConnect = !externalVpnActive && listeningProxies.isEmpty() && !anyReady

        val headline = when {
            aiReady && flowReady -> "Ready (${cc ?: "?"}) — AI Studio & Google Flow Geo-Clear!"
            aiReady && !flowReady -> "AI Studio Ready ✓ · Google Flow Still Geo-Restricted (${flowProbe.egress?.countryCode ?: cc ?: "?"})"
            !aiReady && flowReady -> "Google Flow Ready ✓ · AI Studio Blocked"
            else -> primaryProbe.headline
        }
        val recommendation = buildString {
            if (!aiReady) append("AI Studio: ${aiProbe.recommendation}\n")
            if (!flowReady) append("Google Flow: ${flowProbe.recommendation}")
            if (aiReady && flowReady) append(flowProbe.recommendation)
        }.trim()

        emit("5/5 Plan: ${plan.name} · AI Studio=$aiReady · Flow=$flowReady")

        return ExternalAutopilotResult(
            plan = plan,
            targetReady = targetReady,
            routeLabel = if (isFlowPrimary) flowProbe.routeLabel else aiProbe.routeLabel,
            exitCountry = cc,
            exitIp = ip,
            latencyMs = if (isFlowPrimary) flowProbe.flow.latencyMs else aiProbe.aiStudio.latencyMs,
            flowCountrySupported = flowProbe.flowCountrySupported,
            needsLanternConnect = needsLanternConnect,
            lanternPackage = lanternTool?.installedPackage,
            installedVpnApps = installedTools,
            headline = headline,
            recommendation = recommendation,
            logLines = log,
            aiStudioReady = aiReady,
            flowReady = flowReady,
            aiStudioRouteLabel = aiProbe.routeLabel,
            flowRouteLabel = flowProbe.routeLabel
        )
    }
}
