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

    /**
     * Pure-Kotlin blackout escalation gate: when web connectivity fails or DoH/UDP probes fail
     * and the OS VPN slot is available, automatically escalate to Emergency DNS Blackout VPN.
     */
    fun shouldAutoEscalateToDnsBlackout(
        internetOk: Boolean,
        dohResultsEmpty: Boolean,
        externalVpnActive: Boolean,
        vpnConsentNeeded: Boolean
    ): Boolean {
        if (externalVpnActive || vpnConsentNeeded) return false
        return !internetOk || dohResultsEmpty
    }

    /**
     * Pure-Kotlin selector picking the fastest DoH URL + display label across both regional
     * multi-region benchmarks and general DoH scouting (strongly favoring sub-300ms PoPs).
     */
    fun selectPreferredDohCandidate(
        dnsResults: List<CleanEndpointScout.EndpointResult>,
        regionBenchmarks: List<CleanEndpointScout.RegionBenchmarkResult>
    ): Pair<String?, String?> {
        val fastRegionDoh = regionBenchmarks.firstOrNull {
            it.reachable && !it.target.dohUrl.isNullOrBlank() && it.within300Ms
        }
        val bestScoutDoh = dnsResults.firstOrNull { it.kind == CleanEndpointScout.Kind.DOH && it.reachable }
        return when {
            fastRegionDoh != null && (bestScoutDoh == null || fastRegionDoh.latencyMs <= bestScoutDoh.latencyMs + 25L) ->
                fastRegionDoh.target.dohUrl to "${fastRegionDoh.target.label} (${fastRegionDoh.latencyMs}ms)"
            bestScoutDoh != null -> bestScoutDoh.address to "${bestScoutDoh.label} (${bestScoutDoh.latencyMs}ms)"
            else -> regionBenchmarks.firstOrNull { !it.target.dohUrl.isNullOrBlank() && it.reachable }?.let {
                it.target.dohUrl to "${it.target.label} (${it.latencyMs}ms)"
            } ?: (null to null)
        }
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
        onStep: (String) -> Unit = {},
        intent: AutopilotBrain.AutopilotIntent = AutopilotBrain.getSavedIntent(context)
    ): AutopilotReport {
        val log = mutableListOf<String>()
        fun emit(msg: String) {
            log.add(msg)
            onStep(msg)
        }

        AutopilotBrain.ensureLoaded(context)
        val networkKey = AutopilotBrain.detectNetworkContextKey(context)
        val intentTitle = when (intent) {
            AutopilotBrain.AutopilotIntent.BALANCED_WEB -> "Balanced Web"
            AutopilotBrain.AutopilotIntent.AI_STUDIO -> "AI Studio"
            AutopilotBrain.AutopilotIntent.GOOGLE_FLOW -> "Google Flow"
            AutopilotBrain.AutopilotIntent.BLACKOUT_SURVIVAL -> "Blackout Survival"
        }

        AutopilotBrain.updateSession {
            it.copy(
                phase = AutopilotBrain.SessionPhase.SENSING,
                intent = intent,
                networkContextKey = networkKey,
                stepIndex = 1,
                stepHeadline = "Step 1/3 · Sensing network ($networkKey) & checking routes…"
            )
        }

        // Complete independence: Google Flow runs its own dedicated Brutal Eagle Warrior Engine!
        if (intent == AutopilotBrain.AutopilotIntent.GOOGLE_FLOW) {
            val warrior = FlowWarriorEngine.runFlowWarriorAutopilot(
                context = context,
                onStep = onStep
            )
            val webOk = ReachabilityVerifier.verifyAll(timeoutMs = 2000).internet
            val flowEp = ReachabilityVerifier.EndpointCheck(
                name = FlowWarriorEngine.WARRIOR_TITLE,
                url = ReachabilityVerifier.URL_FLOW,
                status = if (warrior.conquered) ReachabilityVerifier.Status.REACHABLE else ReachabilityVerifier.Status.GEO_RESTRICTED,
                httpCode = warrior.probeReport.flow.httpCode,
                latencyMs = warrior.latencyMs,
                resolvedIp = warrior.exitIp ?: "-",
                detail = warrior.probeReport.flow.detail
            )
            val aiEp = ReachabilityVerifier.EndpointCheck(
                name = "Google AI Studio",
                url = ReachabilityVerifier.URL_AISTUDIO,
                status = ReachabilityVerifier.Status.REACHABLE,
                httpCode = 200,
                latencyMs = warrior.probeReport.aiStudio.latencyMs,
                resolvedIp = warrior.exitIp ?: "-",
                detail = "Isolated from Flow Warrior"
            )
            val verification = ReachabilityVerifier.FullVerification(internet = webOk, aiStudio = aiEp, flow = flowEp)
            val outcome = if (warrior.conquered) {
                if (DastyabVpnService.isConnected() || NetworkUtils.hasActiveExternalVpn(context)) Outcome.READY_WITH_VPN else Outcome.READY_DIRECT
            } else if (webOk.isOk) {
                Outcome.NEEDS_FULL_PROXY_APP
            } else {
                Outcome.OFFLINE_OR_BLACKOUT
            }
            return AutopilotReport(
                outcome = outcome,
                headline = warrior.headline,
                summary = "Exit: ${warrior.exitCountry ?: "?"} (${warrior.exitIp ?: "-"}) · ${warrior.routeLabel}\n${warrior.recommendation}",
                verification = verification,
                bestDohLabel = TunnelConfig.load(context).dohUrl,
                bestHarvestedUri = null,
                bestHarvestedLatencyMs = warrior.latencyMs,
                harvestedTotal = warrior.attemptsCount,
                installedProxyApps = ConfigHarvester.detectFallbackTools(context).filter { it.isInstalled },
                logLines = warrior.logLines
            )
        }

        val externalVpnActive = NetworkUtils.hasActiveExternalVpn(context)
        val listeningProxies = GeoBypassEngine.discoverListeningProxies(timeoutMs = 150)

        emit("Step 1/3: Sensing network [$networkKey] for intent [$intentTitle]…")
        if (externalVpnActive) {
            emit("  • Detected active External VPN (Lantern / System) — preserving OS VPN slot.")
        } else if (listeningProxies.isNotEmpty()) {
            val top = listeningProxies.first()
            emit("  • Detected listening local proxy: ${top.preset.label} (${top.latencyMs}ms)")
        }

        var check = ReachabilityVerifier.verifyAll(timeoutMs = 3000)
        emit("  • Internet  : ${check.internet.detail}")
        emit("  • AI Studio : ${check.aiStudio.detail}")

        val installedProxies = ConfigHarvester.detectFallbackTools(context).filter { it.isInstalled }

        val initialAiRoute = startBridgeForRouteIfNeeded(
            context,
            GeoBypassEngine.resolveCurrentRoute(context, targetService = GeoBypassEngine.TargetService.AI_STUDIO),
            GeoBypassEngine.TargetService.AI_STUDIO
        )
        var aiProbe = GeoBypassEngine.runAccessProbe(initialAiRoute, timeoutMs = 4000)
        val flowProbe = aiProbe

        check = ReachabilityVerifier.FullVerification(
            internet = check.internet,
            aiStudio = toEndpointCheck("Google AI Studio", ReachabilityVerifier.URL_AISTUDIO, aiProbe.readyForAiStudio, aiProbe.aiStudio, aiProbe, isFlow = false),
            flow = toEndpointCheck("Google Flow", ReachabilityVerifier.URL_FLOW, aiProbe.readyForFlow, aiProbe.flow, aiProbe, isFlow = true)
        )

        fun intentSatisfied(v: ReachabilityVerifier.FullVerification, ai: GeoBypassEngine.AccessProbeReport, fl: GeoBypassEngine.AccessProbeReport): Boolean {
            if (!v.internet.isOk) return false
            return when (intent) {
                AutopilotBrain.AutopilotIntent.BALANCED_WEB ->
                    DastyabVpnService.isConnected() || externalVpnActive || ai.readyForAiStudio
                AutopilotBrain.AutopilotIntent.AI_STUDIO -> ai.readyForAiStudio
                AutopilotBrain.AutopilotIntent.GOOGLE_FLOW -> fl.readyForFlow
                AutopilotBrain.AutopilotIntent.BLACKOUT_SURVIVAL ->
                    (DastyabVpnService.isConnected() || externalVpnActive) && (ai.readyForAiStudio || v.internet.isOk)
            }
        }

        if (intentSatisfied(check, aiProbe, flowProbe) && intent != AutopilotBrain.AutopilotIntent.BLACKOUT_SURVIVAL) {
            emit("OK: Current route clears [$intentTitle] gate! Refreshing backup standby pool…")
            val quickHarvest = ConfigHarvester.harvestResilient(context)
            val bestList = HarvestSourceStore.benchmarkHarvestedConfigs(quickHarvest.configs, maxToTest = 10)
            val best = bestList.firstOrNull()
            AutopilotBrain.saveStandbyUris(context, bestList.take(3).map { it.uri })
            val standbyCount = AutopilotBrain.loadStandbyUris(context).size
            val quarantinedCount = AutopilotBrain.countQuarantined(networkKey)
            val activeLabel = initialAiRoute.routeLabel
            AutopilotBrain.updateSession {
                it.copy(
                    phase = AutopilotBrain.SessionPhase.CONNECTED,
                    intent = intent,
                    networkContextKey = networkKey,
                    stepIndex = 3,
                    stepHeadline = "Connected · $activeLabel",
                    activeArmLabel = activeLabel,
                    standbyCount = standbyCount,
                    quarantinedCount = quarantinedCount
                )
            }
            return AutopilotReport(
                outcome = if (DastyabVpnService.isConnected() || externalVpnActive) Outcome.READY_WITH_VPN else Outcome.READY_DIRECT,
                headline = "$intentTitle Route Ready & Verified!",
                summary = "AI Studio: ${aiProbe.aiStudio.latencyMs}ms (${aiProbe.egress?.countryCode ?: "?"}) · Flow: ${flowProbe.flow.latencyMs}ms (${flowProbe.egress?.countryCode ?: "?"})",
                verification = check,
                bestDohLabel = TunnelConfig.load(context).dohUrl,
                bestHarvestedUri = best?.uri,
                bestHarvestedLatencyMs = best?.latencyMs ?: -1L,
                harvestedTotal = quickHarvest.configs.size,
                installedProxyApps = installedProxies,
                logLines = log
            )
        }

        // Step 2: Parallel Multi-Region Latency Benchmark + DoH/DNS Scout + Predictive Multi-Path Race
        AutopilotBrain.updateSession {
            it.copy(
                phase = AutopilotBrain.SessionPhase.RACING,
                stepIndex = 2,
                stepHeadline = "Step 2/3 · Parallel multi-region benchmark & predictive race…"
            )
        }
        emit("Step 2/3: Running parallel multi-region benchmark & DoH/DNS scout…")
        val dnsResults = CleanEndpointScout.scoutCleanDnsAndDoh(timeoutMs = 2000)
        val regionBenchmarks = CleanEndpointScout.benchmarkMultiRegionParallel(timeoutMs = 1800)
        val regionDigest = CleanEndpointScout.formatRegionBenchmarkSummary(regionBenchmarks)
        if (regionBenchmarks.isNotEmpty()) {
            emit("  ✓ Multi-Region Latencies: $regionDigest")
            val prevSnap = FailoverWatchdog.getSnapshot()
            FailoverWatchdog.update(prevSnap.copy(fastestRegionLabel = regionDigest))
        }
        val bestDoh = dnsResults.firstOrNull { it.kind == CleanEndpointScout.Kind.DOH }
        val (preferredDohUrl, preferredDohLabel) = selectPreferredDohCandidate(dnsResults, regionBenchmarks)
        val bestUdp = dnsResults.firstOrNull { it.kind == CleanEndpointScout.Kind.UDP_DNS }
        if (preferredDohUrl != null || bestUdp != null) {
            TunnelConfig.saveDohAndDns(context, preferredDohUrl ?: bestDoh?.address, bestUdp?.address)
            val measuredMs = regionBenchmarks.firstOrNull { it.target.dohUrl == preferredDohUrl }?.latencyMs
                ?: bestDoh?.latencyMs ?: 120L
            preferredDohUrl?.let { url ->
                AutopilotBrain.recordOutcome(
                    contextKey = networkKey,
                    armId = "doh:$url",
                    protocolFamily = "doh",
                    success = true,
                    rttMs = measuredMs.toDouble(),
                    geoMaskObserved = AutopilotBrain.GEO_CLEAR_WEB,
                    persistContext = context
                )
            }
            emit("  ✓ Best DoH: ${preferredDohLabel ?: bestDoh?.label ?: "default"}, UDP DNS: ${bestUdp?.address ?: "1.1.1.1"}")
        } else {
            emit("  ✗ External DoH/DNS probes timed out — engaging Emergency DNS Blackout & Clean-IP scout.")
        }

        val knownSeeds = AutopilotBrain.getCleanIpSeeds(networkKey)
        if (intent == AutopilotBrain.AutopilotIntent.BLACKOUT_SURVIVAL || !check.internet.isOk) {
            val edgeResults = CleanEndpointScout.scoutSmartEdgeIps(
                seedIps = knownSeeds,
                totalBudget = 24,
                timeoutMs = 1500
            )
            edgeResults.take(3).forEach { res ->
                AutopilotBrain.recordCleanIpSeed(networkKey, res.address, persistContext = context)
            }
            if (edgeResults.isNotEmpty()) {
                emit("  ✓ /24 Edge Scout found ${edgeResults.size} reachable edge IPs (fastest ${edgeResults.first().address} · ${edgeResults.first().latencyMs}ms)")
            }
        }

        val vpnConsentNeeded = VpnService.prepare(context) != null
        var activeVpnOrExternal = externalVpnActive || DastyabVpnService.isConnected()
        val autoBlackoutEscalated = shouldAutoEscalateToDnsBlackout(
            internetOk = check.internet.isOk,
            dohResultsEmpty = dnsResults.isEmpty() && regionBenchmarks.isEmpty(),
            externalVpnActive = externalVpnActive,
            vpnConsentNeeded = vpnConsentNeeded
        )
        when {
            externalVpnActive || listeningProxies.isNotEmpty() -> {
                if (externalVpnActive) {
                    emit("  • External VPN (Lantern) active — chaining proxy layers ON TOP without killing OS VPN.")
                } else {
                    emit("  • Local proxy port detected — chaining proxy layers without occupying OS VPN slot.")
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

                if (!aiProbe.readyForAiStudio && (intent == AutopilotBrain.AutopilotIntent.AI_STUDIO || intent == AutopilotBrain.AutopilotIntent.BALANCED_WEB)) {
                    emit("  • Unleashing Brutal Lion Engine (64-candidate anti-DPI multi-hop) for AI Studio…")
                    val aiChain = BrutalAntiGeoblockEngine.unleashLionForAiStudio(
                        context = context,
                        excludedEgressIps = setOfNotNull(aiProbe.egress?.ip),
                        excludeCurrentRoute = true
                    ) { emit("    $it") }
                    aiProbe = aiChain.report
                    aiProbe.egress?.ip?.let { if (aiProbe.readyForAiStudio) AutopilotBrain.recordCleanIpSeed(networkKey, it, context) }
                    emit("  • AI Studio Lion result: ${aiChain.summary}")
                }

                check = ReachabilityVerifier.FullVerification(
                    internet = check.internet,
                    aiStudio = toEndpointCheck("Google AI Studio", ReachabilityVerifier.URL_AISTUDIO, aiProbe.readyForAiStudio, aiProbe.aiStudio, aiProbe, isFlow = false),
                    flow = check.flow
                )
                activeVpnOrExternal = true
            }
            !vpnConsentNeeded -> {
                val targetMode = if (intent == AutopilotBrain.AutopilotIntent.BLACKOUT_SURVIVAL || autoBlackoutEscalated) {
                    TunnelConfig.Mode.DNS_BLACKOUT
                } else {
                    TunnelConfig.Mode.SMART
                }
                if (autoBlackoutEscalated && intent != AutopilotBrain.AutopilotIntent.BLACKOUT_SURVIVAL) {
                    emit("  ⚡ Blackout Fallback: Auto-escalating tunnel to Emergency DNS Blackout VPN (DoH + WhiteDNS)…")
                } else {
                    emit("  • Activating DastYab ${targetMode.name} Tunnel…")
                }
                TunnelConfig.saveMode(context, targetMode)
                DastyabVpnService.start(context)
                try { Thread.sleep(850) } catch (_: InterruptedException) {}

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
                emit("  • Android VPN permission needed once (will prompt you).")
            }
        }

        // Harvest & run Happy Eyeballs v2 Protocol-Diverse Staggered Race via Bandit scores
        emit("  • Harvesting & ranking multi-protocol pool via Contextual UCB1 Bandit…")
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
        var topSummaryLines: List<String> = emptyList()
        if (pool.isNotEmpty()) {
            val candidates = pool.take(48).mapNotNull { uri ->
                HarvestSourceStore.extractHostAndPort(uri)?.let { (host, port) ->
                    val proto = ConfigHarvester.protocolOf(uri)
                    AutopilotBrain.CandidateArm(
                        armId = "$proto:$host:$port",
                        protocolFamily = proto,
                        basePriority = 0.5,
                        payload = uri
                    )
                }
            }
            val slate = AutopilotBrain.selectPredictiveSlate(
                contextKey = networkKey,
                candidates = candidates,
                intent = intent,
                maxSlots = 6
            )
            topSummaryLines = AutopilotBrain.formatTopScoredSummaries(networkKey, candidates, intent, limit = 3)
            emit("  • Predictive Multi-Path Slate (${slate.size} paths, <300ms SLA): ${topSummaryLines.joinToString(" | ")}")

            val raceResult = AutopilotBrain.raceMultiPathPredictive(
                slate = slate.map { it.candidate },
                latencyThresholdMs = FailoverWatchdog.LATENCY_SWITCH_THRESHOLD_MS,
                probeTimeoutMs = 2600L,
                measureLatencyMs = { res -> res.latencyMs }
            ) { arm ->
                val ep = HarvestSourceStore.extractHostAndPort(arm.payload) ?: return@raceMultiPathPredictive null
                val ms = CleanEndpointScout.probeTcpLatency(ep.first, ep.second, 1800)
                if (ms >= 0) {
                    AutopilotBrain.recordOutcome(
                        contextKey = networkKey,
                        armId = arm.armId,
                        protocolFamily = arm.protocolFamily,
                        success = true,
                        rttMs = ms.toDouble(),
                        geoMaskObserved = AutopilotBrain.GEO_CLEAR_WEB,
                        persistContext = context
                    )
                    AutopilotBrain.recordCleanIpSeed(networkKey, ep.first, persistContext = context)
                    HarvestSourceStore.ConfigProbeResult(
                        uri = arm.payload,
                        protocol = arm.protocolFamily,
                        host = ep.first,
                        port = ep.second,
                        latencyMs = ms,
                        reachable = true
                    )
                } else {
                    AutopilotBrain.recordOutcome(
                        contextKey = networkKey,
                        armId = arm.armId,
                        protocolFamily = arm.protocolFamily,
                        success = false,
                        rttMs = null,
                        persistContext = context
                    )
                    null
                }
            }

            val fastest = raceResult?.payload ?: HarvestSourceStore.benchmarkHarvestedConfigs(pool, maxToTest = 16, timeoutMs = 1800).firstOrNull()
            val standbyUris = mutableListOf<String>()
            raceResult?.standbyArms?.forEach { standbyUris.add(it.payload) }
            if (standbyUris.isEmpty()) {
                pool.filter { it != fastest?.uri }.take(2).forEach { standbyUris.add(it) }
            }
            AutopilotBrain.saveStandbyUris(context, standbyUris)

            if (fastest != null) {
                bestUri = fastest.uri
                bestMs = fastest.latencyMs
                val slaBadge = if (fastest.latencyMs <= FailoverWatchdog.LATENCY_SWITCH_THRESHOLD_MS) "<300ms SLA ✓" else ">300ms"
                emit("  ✓ Predictive Winner: [${fastest.protocol.uppercase()}] ${fastest.host}:${fastest.port} (${fastest.latencyMs}ms · $slaBadge)")
                val escalateBlackoutLayer = intent == AutopilotBrain.AutopilotIntent.BLACKOUT_SURVIVAL ||
                    shouldAutoEscalateToDnsBlackout(check.internet.isOk, dnsResults.isEmpty(), externalVpnActive, vpnConsentNeeded)
                val layer = VpnLayerStore.VpnLayer(
                    id = "layer-autopilot-best",
                    name = "Autopilot Best (${fastest.host})",
                    mode = if (escalateBlackoutLayer) TunnelConfig.Mode.DNS_BLACKOUT else TunnelConfig.Mode.SMART,
                    configUri = fastest.uri,
                    dnsPrimary = bestUdp?.address ?: TunnelConfig.DEFAULT_DNS_PRIMARY,
                    dohUrl = preferredDohUrl ?: bestDoh?.address ?: TunnelConfig.DEFAULT_DOH_URL
                )
                VpnLayerStore.addOrUpdate(context, layer)
                VpnLayerStore.activateLayer(
                    context,
                    layer,
                    restartIfConnected = !vpnConsentNeeded && !externalVpnActive
                )
            } else {
                emit("  ✗ Multi-path race timed out — keeping Encrypted DoH / Blackout shield.")
            }
        } else {
            emit("  • No harvested configs cached yet (GitHub & Telegram unreachable).")
        }

        // Step 3/3: Verify Intent Geo/Web Clearance & escalate multi-layer chaining if required
        AutopilotBrain.updateSession {
            it.copy(
                phase = AutopilotBrain.SessionPhase.VERIFYING,
                stepIndex = 3,
                stepHeadline = "Step 3/3 · Verifying $intentTitle clearance & standby pool…"
            )
        }
        emit("Step 3/3: Verifying [$intentTitle] clearance…")

        val needAiCheck = intent == AutopilotBrain.AutopilotIntent.AI_STUDIO || intent == AutopilotBrain.AutopilotIntent.BALANCED_WEB

        if (needAiCheck && !check.aiStudio.isOk) {
            emit("  • Running autonomous multi-layer geo-bypass chaining for AI Studio…")
            var anyChainUnlocked = false
            val blockedAiIps = listOfNotNull(
                aiProbe.egress?.ip,
                check.aiStudio.resolvedIp.takeIf { it.isNotBlank() && it != "-" && it != "via-proxy" }
            ).toSet()

            val retryAi = BrutalAntiGeoblockEngine.unleashLionForAiStudio(
                context = context,
                excludedEgressIps = blockedAiIps,
                excludeCurrentRoute = true
            ) { emit("  [lion-chain AI Studio] $it") }
            aiProbe = retryAi.report
            if (retryAi.success) anyChainUnlocked = true

            if (anyChainUnlocked && DastyabVpnService.isConnected() && !externalVpnActive) {
                DastyabVpnService.start(context)
                try { Thread.sleep(500) } catch (_: InterruptedException) {}
            }
            check = ReachabilityVerifier.FullVerification(
                internet = check.internet,
                aiStudio = toEndpointCheck("Google AI Studio", ReachabilityVerifier.URL_AISTUDIO, aiProbe.readyForAiStudio, aiProbe.aiStudio, aiProbe, isFlow = false),
                flow = check.flow
            )
        }

        val finalConnected = activeVpnOrExternal || DastyabVpnService.isConnected()
        val consentNeeded = vpnConsentNeeded && !externalVpnActive && listeningProxies.isEmpty()
        val rawOutcome = decideOutcome(check, finalConnected, consentNeeded)
        val finalOutcome = if (
            intent == AutopilotBrain.AutopilotIntent.BALANCED_WEB &&
            check.internet.isOk &&
            rawOutcome == Outcome.NEEDS_FULL_PROXY_APP
        ) {
            if (finalConnected) Outcome.READY_WITH_VPN else Outcome.READY_DIRECT
        } else {
            rawOutcome
        }

        val activeLabel = bestUri?.let {
            val proto = ConfigHarvester.protocolOf(it).uppercase()
            if (bestMs > 0) "$proto (${bestMs}ms)" else proto
        } ?: bestDoh?.label ?: initialAiRoute.routeLabel

        val standbyCount = AutopilotBrain.loadStandbyUris(context).size
        val quarantinedCount = AutopilotBrain.countQuarantined(networkKey)
        val finalPhase = if (finalOutcome == Outcome.READY_WITH_VPN || finalOutcome == Outcome.READY_DIRECT) {
            AutopilotBrain.SessionPhase.CONNECTED
        } else {
            AutopilotBrain.SessionPhase.NEEDS_ACTION
        }
        AutopilotBrain.updateSession {
            it.copy(
                phase = finalPhase,
                intent = intent,
                networkContextKey = networkKey,
                stepIndex = 3,
                stepHeadline = activeLabel,
                activeArmLabel = activeLabel,
                standbyCount = standbyCount,
                quarantinedCount = quarantinedCount,
                topScoredLines = topSummaryLines
            )
        }

        val bothClear = check.aiStudio.isOk && check.flow.isOk
        val (headline, summary) = when (finalOutcome) {
            Outcome.READY_WITH_VPN ->
                (when {
                    bothClear -> "Autopilot Connected · Web, AI Studio & Flow Clear!"
                    intent == AutopilotBrain.AutopilotIntent.BALANCED_WEB -> "Autopilot Protected & Web Connected ($activeLabel)"
                    check.aiStudio.isOk -> "Autopilot Ready · AI Studio Clear ✓"
                    else -> "Autopilot Ready · Flow Clear ✓"
                }) to "Active Route: $activeLabel\nAI Studio: ${check.aiStudio.detail}\nGoogle Flow: ${check.flow.detail}"
            Outcome.READY_DIRECT ->
                (when {
                    bothClear -> "Direct & Geo-Clear · AI Studio & Flow Ready!"
                    intent == AutopilotBrain.AutopilotIntent.BALANCED_WEB -> "Fast Web Verified ($activeLabel)"
                    check.aiStudio.isOk -> "AI Studio Geo-Clear ✓"
                    else -> "Google Flow Geo-Clear ✓"
                }) to "AI Studio: ${check.aiStudio.detail}\nGoogle Flow: ${check.flow.detail}"
            Outcome.NEEDS_VPN_CONSENT ->
                "Allow VPN Permission Once to Connect" to
                    "Best DoH (${bestDoh?.label ?: "1.1.1.1"}) and ${pool.size} routes are ready. Approve Android VPN permission once."
            Outcome.NEEDS_FULL_PROXY_APP ->
                "Geo-Restriction Persists — Fastest Route Ready" to
                    if (bestUri != null) {
                        "Autopilot found a fast ${ConfigHarvester.protocolOf(bestUri).uppercase()} route (${bestMs}ms). Copy it into Lantern / v2rayNG / NekoBox or open Blackout Rescue."
                    } else {
                        "Your active exit is still geo-blocked for $intentTitle. Switch Lantern exit or try Blackout Survival preset."
                    }
            Outcome.OFFLINE_OR_BLACKOUT ->
                "Severe Blackout or No Internet Detected" to
                    "Direct HTTPS failed. Activate Blackout Survival preset or open Blackout Rescue Hub."
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

        val isFlowPrimary = targetUrl.contains("flow.google.com", ignoreCase = true)
        var aiProbe: GeoBypassEngine.AccessProbeReport
        var flowProbe: GeoBypassEngine.AccessProbeReport

        if (isFlowPrimary) {
            emit("2/3 Unleashing Flow Warrior (Brutal Eagle) exclusively for Google Flow…")
            val warrior = FlowWarriorEngine.runFlowWarriorAutopilot(context = context) { step -> emit("  $step") }
            flowProbe = warrior.probeReport
            aiProbe = warrior.probeReport
        } else {
            emit("2/3 Verifying Google AI Studio route (isolated from Google Flow)…")
            val aiRoute = startBridgeForRouteIfNeeded(
                context,
                GeoBypassEngine.resolveCurrentRoute(context, targetService = GeoBypassEngine.TargetService.AI_STUDIO),
                GeoBypassEngine.TargetService.AI_STUDIO
            )
            aiProbe = GeoBypassEngine.runAccessProbe(aiRoute, timeoutMs = 4200)
            if (!aiProbe.readyForAiStudio) {
                emit("  ✗ AI Studio blocked/georestricted via ${aiProbe.routeLabel} — unleashing Brutal Lion Engine for AI Studio…")
                val discoveredAi = BrutalAntiGeoblockEngine.unleashLionForAiStudio(
                    context = context,
                    excludedEgressIps = setOfNotNull(aiProbe.egress?.ip),
                    excludeCurrentRoute = true
                ) { step -> emit("    $step") }
                aiProbe = discoveredAi.report
                emit("  → AI Studio (Lion): ${discoveredAi.summary}")
            } else {
                emit("  ✓ AI Studio verified via ${aiProbe.routeLabel} (${aiProbe.egress?.countryCode} ${aiProbe.egress?.ip})")
            }
            flowProbe = aiProbe
        }

        val primaryProbe = if (isFlowPrimary) flowProbe else aiProbe
        val aiReady = aiProbe.readyForAiStudio
        val flowReady = flowProbe.readyForFlow
        val targetReady = if (isFlowPrimary) flowReady else aiReady
        val anyReady = targetReady || aiReady
        val effectiveEgress = primaryProbe.egress
        val cc = effectiveEgress?.countryCode
        val ip = effectiveEgress?.ip

        emit("3/3 Result · AI Studio: ${if (aiReady) "READY ✓" else "isolated"} | Flow Warrior: ${if (flowReady) "CONQUERED ✓" else "run Flow Warrior separately"}")

        val plan = decideExternalAutopilotPlan(
            externalVpnActive = externalVpnActive,
            listeningProxyPort = listeningProxies.firstOrNull()?.preset?.port,
            dastyabVpnActive = DastyabVpnService.isConnected(),
            targetReachable = targetReady
        )
        val needsLanternConnect = !externalVpnActive && listeningProxies.isEmpty() && !anyReady

        val headline = when {
            isFlowPrimary && flowReady -> "${FlowWarriorEngine.WARRIOR_BADGE_PREFIX} Conquered Google Flow ($cc)"
            isFlowPrimary -> "${FlowWarriorEngine.WARRIOR_BADGE_PREFIX}: Harsh Flow Firewall (${flowProbe.egress?.countryCode ?: cc ?: "?"})"
            aiReady -> "AI Studio Ready ($cc) — Route Verified!"
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
