package com.dastyab.app

import android.content.Context
import android.net.VpnService

/**
 * NewbieAutopilot — zero-effort, 1-tap automated engine for non-technical users.
 *
 * Automatically executes the complete end-to-end recovery & connection pipeline:
 *  1. Checks live reachability of general internet, Google AI Studio, and Google Flow.
 *  2. Scouts the fastest working encrypted DoH + clean UDP DNS resolver on this network.
 *  3. Saves & activates DastYab's non-blackholing Encrypted DoH VPN (if VPN consent is granted).
 *  4. Re-verifies real internet health — auto-rolls back if anything breaks general web access.
 *  5. Auto-harvests fresh proxy configs (GitHub → jsDelivr Mirror → Telegram), benchmarks them
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

    fun run(
        context: Context,
        onStep: (String) -> Unit = {}
    ): AutopilotReport {
        val log = mutableListOf<String>()
        fun emit(msg: String) {
            log.add(msg)
            onStep(msg)
        }

        emit("Step 1/5: Checking live reachability for AI Studio & Google Flow…")
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
                outcome = if (DastyabVpnService.isConnected()) Outcome.READY_WITH_VPN else Outcome.READY_DIRECT,
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

        // Step 3: Start DastYab's non-blackholing Encrypted DoH VPN if consent is already granted
        val vpnConsentNeeded = VpnService.prepare(context) != null
        if (!vpnConsentNeeded) {
            emit("Step 3/5: Activating DastYab Smart Encrypted DoH Tunnel…")
            TunnelConfig.saveMode(context, TunnelConfig.Mode.SMART)
            DastyabVpnService.start(context)
            try { Thread.sleep(900) } catch (_: InterruptedException) {}

            // Safety check: confirm general websites remain reachable!
            val afterVpn = ReachabilityVerifier.verifyAll(timeoutMs = 3000)
            emit("  • Post-VPN Internet  : ${afterVpn.internet.detail}")
            emit("  • Post-VPN AI Studio : ${afterVpn.aiStudio.detail}")
            emit("  • Post-VPN Flow      : ${afterVpn.flow.detail}")

            if (!afterVpn.internet.isOk && check.internet.isOk) {
                emit("  ⚠ Safety Guard: Disconnecting VPN because general internet dropped.")
                DastyabVpnService.stop(context)
            } else {
                check = afterVpn
            }
        } else {
            emit("Step 3/5: Android VPN permission needed once (will prompt you next).")
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
                VpnLayerStore.activateLayer(context, layer, restartIfConnected = !vpnConsentNeeded)
            } else {
                emit("  ✗ Harvested configs did not respond on TCP — keeping Encrypted DoH layer.")
            }
        } else {
            emit("Step 5/5: No configs harvested yet (GitHub & Telegram unreachable).")
        }

        // Final decision
        val finalOutcome = decideOutcome(check, DastyabVpnService.isConnected(), vpnConsentNeeded)
        val (headline, summary) = when (finalOutcome) {
            Outcome.READY_WITH_VPN ->
                "Autopilot Connected & Verified!" to
                    "DastYab Encrypted Tunnel is active and AI Studio / Google Flow are verified reachable."
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
                            "Tap below to copy it and launch a full-tunnel app (v2rayNG / Hiddify / NekoBox / WhiteVPN) — DastYab will automatically chain with it."
                    } else {
                        "Your ISP blocks Google AI Studio / Flow at the IP/SNI level. " +
                            "Connect an installed fallback VPN (WhiteVPN / SlipNet / Lantern / Tor / Hiddify) or import a temporary working config."
                    }
            Outcome.OFFLINE_OR_BLACKOUT ->
                "Severe Blackout or No Internet Detected" to
                    "Direct HTTPS failed. Activate Emergency DNS Blackout VPN, run Clean IP Scout, or launch a fallback tool (WhiteDNS / SlipNet / Tor / Lantern)."
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
}
