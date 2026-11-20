package com.dastyab.app

import android.content.Context
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * FlowWarriorEngine — Independent "Brutal Eagle" Anti-Geoblock Algorithm exclusively for Google Flow.
 *
 * Completely decoupled from Google AI Studio (`aiStudio` proxy chains, bandit context, and probes),
 * because `flow.google.com` enforces far stricter ASN/datacenter reputation, 151-country allowlists,
 * `labs.google` geo-cookies, and Angular app-shell gates.
 *
 * Warrior Architecture:
 *  1. Isolated Namespace (`flow_warrior:<netKey>`) + persistent blocked-egress IP blacklist.
 *  2. Tier-1 Clean Residential Exit Priority (heavy bonus for clean residential US/CA/GB/DE/NL/CH/JP/SG/AU/IE).
 *  3. Zero-Mercy Double-Confirm Gate (country allowlist + accounts + Angular shell + secondary host check).
 *  4. Brutal 4-Round Progressive Multi-Hop Escalation (up to 72 candidates, 1..4-hop cascades pairing
 *     censorship-punching entry relays with strict Tier-1 non-datacenter exits).
 */
object FlowWarriorEngine {

    const val WARRIOR_TITLE = "Flow Warrior (Brutal Eagle)"
    const val WARRIOR_BADGE_PREFIX = "⚔ Warrior"
    private const val PREFS_NAME = "dastyab_flow_warrior"
    private const val KEY_BLACKLIST_IPS = "flow_warrior_blacklist_ips_v1"
    private const val KEY_LAST_CLEAN_CHAIN = "flow_warrior_last_clean_chain_v1"
    private const val MAX_BLACKLIST_IPS = 64

    /** Tier-1 low-block countries favored by Google Flow Warrior's Brutal Eagle selector. */
    val WARRIOR_TIER1_COUNTRIES: Set<String> = setOf(
        "US", "CA", "GB", "DE", "NL", "CH", "JP", "SG", "AU", "IE", "SE", "NO", "DK", "FI", "FR"
    )

    data class WarriorReport(
        val conquered: Boolean,
        val routeLabel: String,
        val exitIp: String?,
        val exitCountry: String?,
        val isResidentialClean: Boolean,
        val latencyMs: Long,
        val attemptsCount: Int,
        val chainSpec: String,
        val headline: String,
        val recommendation: String,
        val probeReport: GeoBypassEngine.AccessProbeReport,
        val logLines: List<String>
    )

    fun warriorContextKey(networkKey: String): String = "flow_warrior:$networkKey"

    /** Pure-Kotlin strict Brutal Eagle readiness verdict (never shared with AI Studio). */
    fun isWarriorConfirmed(
        report: GeoBypassEngine.AccessProbeReport,
        blacklistedIps: Set<String> = emptySet(),
        requireNonHostingOrDoubleConfirmed: Boolean = false,
        doubleConfirmedClean: Boolean = false
    ): Boolean {
        val egress = report.egress ?: return false
        val ip = egress.ip.trim()
        if (ip.isEmpty() || blacklistedIps.contains(ip)) return false
        if (!GeoBypassEngine.isFlowCountrySupported(egress.countryCode)) return false
        if (!report.flowCountrySupported || !report.accountsReachable) return false
        if (!report.flow.reachable || report.flow.unsupportedRegion || !report.flow.appShellOk) return false
        if (requireNonHostingOrDoubleConfirmed && egress.isProxyOrHosting && !doubleConfirmedClean) {
            return false
        }
        return true
    }

    /**
     * Pure-Kotlin Brutal Eagle scoring (higher = stronger Warrior candidate).
     * Rewards Tier-1 countries & clean residential ASNs; harshly penalizes datacenter flags,
     * geo-errors, missing Angular shell, and blacklisted IPs.
     */
    fun scoreWarriorCandidate(
        report: GeoBypassEngine.AccessProbeReport,
        blacklistedIps: Set<String> = emptySet()
    ): Long {
        val egress = report.egress ?: return -1_000_000L
        val ip = egress.ip.trim()
        if (ip.isEmpty() || blacklistedIps.contains(ip)) return -900_000L

        var score = 0L
        val cc = egress.countryCode.trim().uppercase()
        score += when {
            WARRIOR_TIER1_COUNTRIES.contains(cc) -> 350L
            GeoBypassEngine.isFlowCountrySupported(cc) -> 120L
            else -> -800_000L
        }
        score += if (!egress.isProxyOrHosting) 450L else -180L
        score += if (report.flow.reachable && !report.flow.unsupportedRegion && report.flow.appShellOk) 500L else -500_000L
        score += if (report.accountsReachable) 150L else -100_000L
        val lat = report.flow.latencyMs.takeIf { it > 0 } ?: 5_000L
        score -= (lat / 25L).coerceAtMost(200L)
        return score
    }

    fun getBlacklistedFlowIps(context: Context): Set<String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BLACKLIST_IPS, "").orEmpty()
        return raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toCollection(LinkedHashSet())
    }

    fun blacklistFlowIp(context: Context, ip: String?) {
        val clean = ip?.trim().orEmpty()
        if (clean.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val list = getBlacklistedFlowIps(context).toMutableList()
        list.remove(clean)
        list.add(0, clean)
        while (list.size > MAX_BLACKLIST_IPS) list.removeAt(list.lastIndex)
        prefs.edit().putString(KEY_BLACKLIST_IPS, list.joinToString("\n")).apply()
    }

    fun clearWarriorMemory(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * Secondary zero-mercy check on `flow.google.com/create` & `labs.google` to ensure
     * datacenter-flagged exits don't pass home page only to block inside Flow Workspace.
     */
    fun doubleConfirmFlowWorkspace(proxy: Proxy, timeoutMs: Int = 3500): Boolean {
        val targets = listOf(
            "https://flow.google.com/about/",
            "https://labs.google/fx/tools/flow"
        )
        for (target in targets) {
            val ok = runCatching {
                val conn = (URL(target).openConnection(proxy) as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    instanceFollowRedirects = false
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    BrutalAntiGeoblockEngine.applyBrutalHeaders(this)
                }
                try {
                    val code = conn.responseCode
                    val loc = conn.getHeaderField("Location").orEmpty()
                    !GeoBypassEngine.isFlowUnsupportedResponse(code, loc, null) && code in 200..399
                } finally {
                    conn.disconnect()
                }
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    /**
     * Executes the complete, standalone **Flow Warrior (Brutal Eagle)** bypass pipeline.
     * Guaranteed NEVER to read, modify, or disturb AI Studio's active route or proxy chain!
     */
    fun runFlowWarriorAutopilot(
        context: Context,
        excludedEgressIps: Set<String> = emptySet(),
        excludedChainSpecs: Set<String> = emptySet(),
        excludeCurrentRoute: Boolean = false,
        onStep: (String) -> Unit = {}
    ): WarriorReport {
        val log = mutableListOf<String>()
        fun emit(msg: String) {
            log.add(msg)
            onStep(msg)
        }

        AutopilotBrain.ensureLoaded(context)
        val networkKey = AutopilotBrain.detectNetworkContextKey(context)
        val warriorNetKey = warriorContextKey(networkKey)
        val persistedBlacklist = getBlacklistedFlowIps(context)
        val combinedExcludedIps = (excludedEgressIps + persistedBlacklist).toMutableSet()

        AutopilotBrain.updateSession {
            it.copy(
                phase = AutopilotBrain.SessionPhase.RACING,
                intent = AutopilotBrain.AutopilotIntent.GOOGLE_FLOW,
                networkContextKey = warriorNetKey,
                stepIndex = 2,
                stepHeadline = "$WARRIOR_BADGE_PREFIX · Engaging Brutal Eagle bypass for Google Flow…"
            )
        }

        emit("⚔ [$WARRIOR_TITLE] Starting independent Google Flow offensive (Network: $networkKey)…")

        // Stage 1: Probe Flow's own isolated route first (unless caller explicitly demands rotation)
        val currentFlowRoute = GeoBypassEngine.resolveCurrentRoute(
            context = context,
            targetService = GeoBypassEngine.TargetService.GOOGLE_FLOW
        )
        if (currentFlowRoute.shouldStartLocalBridge) {
            LocalProxyService.startSafely(context, GeoBypassEngine.TargetService.GOOGLE_FLOW.key)
            var waited = 0
            while (LocalProxyService.activePort() == null && waited < 1000) {
                try { Thread.sleep(60) } catch (_: InterruptedException) { break }
                waited += 60
            }
        }

        val freshRoute = GeoBypassEngine.resolveCurrentRoute(
            context = context,
            targetService = GeoBypassEngine.TargetService.GOOGLE_FLOW
        )
        val initialProbe = GeoBypassEngine.runAccessProbe(freshRoute, timeoutMs = 4500)
        val initialDoubleConfirmed = if (initialProbe.readyForFlow && initialProbe.egress?.isProxyOrHosting == true) {
            doubleConfirmFlowWorkspace(freshRoute.javaNetProxy, 3200)
        } else {
            false
        }

        if (!excludeCurrentRoute && isWarriorConfirmed(
                initialProbe,
                blacklistedIps = combinedExcludedIps,
                requireNonHostingOrDoubleConfirmed = false,
                doubleConfirmedClean = initialDoubleConfirmed
            )
        ) {
            val eg = initialProbe.egress!!
            val label = freshRoute.routeLabel
            AutopilotBrain.recordOutcome(
                contextKey = warriorNetKey,
                armId = "flow:$label",
                protocolFamily = "flow-warrior",
                success = true,
                rttMs = initialProbe.flow.latencyMs.toDouble(),
                geoMaskObserved = AutopilotBrain.GEO_CLEAR_FLOW,
                persistContext = context
            )
            emit("⚔ [$WARRIOR_BADGE_PREFIX] Flow Conquered on current route! ${eg.countryCode} (${eg.ip}) · ${initialProbe.flow.latencyMs}ms")
            AutopilotBrain.updateSession {
                it.copy(
                    phase = AutopilotBrain.SessionPhase.CONNECTED,
                    networkContextKey = warriorNetKey,
                    stepIndex = 3,
                    stepHeadline = "$WARRIOR_BADGE_PREFIX ✓ ${eg.countryCode} · ${initialProbe.flow.latencyMs}ms",
                    activeArmLabel = "$label (${eg.countryCode})"
                )
            }
            return WarriorReport(
                conquered = true,
                routeLabel = label,
                exitIp = eg.ip,
                exitCountry = eg.countryCode,
                isResidentialClean = !eg.isProxyOrHosting,
                latencyMs = initialProbe.flow.latencyMs,
                attemptsCount = 1,
                chainSpec = freshRoute.upstreamChainSpec,
                headline = "$WARRIOR_BADGE_PREFIX Conquered Google Flow (${eg.countryCode} · ${initialProbe.flow.latencyMs}ms)",
                recommendation = initialProbe.recommendation,
                probeReport = initialProbe,
                logLines = log
            )
        }

        // Current route failed Flow's harsh gate — quarantine its IP in Flow Warrior memory ONLY
        initialProbe.egress?.ip?.takeIf { it.isNotBlank() }?.let { badIp ->
            combinedExcludedIps.add(badIp)
            blacklistFlowIp(context, badIp)
            AutopilotBrain.recordOutcome(
                contextKey = warriorNetKey,
                armId = "flow:$badIp",
                protocolFamily = "flow-warrior",
                success = false,
                rttMs = null,
                persistContext = context
            )
            emit("  ✗ Blocked/Poisoned Flow Exit [$badIp] (${initialProbe.egress.countryCode}) quarantined in Warrior blacklist.")
        }

        // Stage 2: Ensure TunnelConfig preserves external VPN chaining & run Brutal 4-Round 72-Candidate Cascade
        emit("⚔ [$WARRIOR_BADGE_PREFIX] Escalating 4-Round Brutal Eagle Cascade (up to 72 multi-hop candidates)…")
        val chainResult = GeoBypassEngine.discoverAndChainBestRoute(
            context = context,
            targetService = GeoBypassEngine.TargetService.GOOGLE_FLOW,
            allowHarvest = true,
            maxCandidates = 72,
            excludedEgressIps = combinedExcludedIps,
            excludeCurrentRoute = true,
            excludedChainSpecs = excludedChainSpecs,
            maxRounds = 4
        ) { step -> emit("  [Warrior] $step") }

        val finalProbe = chainResult.report
        val winnerEgress = finalProbe.egress
        val isClean = winnerEgress != null && !winnerEgress.isProxyOrHosting
        var conquered = chainResult.success && isWarriorConfirmed(
            finalProbe,
            blacklistedIps = emptySet(),
            requireNonHostingOrDoubleConfirmed = false
        )
        if (conquered && !isClean) {
            val resolvedAfter = GeoBypassEngine.resolveCurrentRoute(
                context = context,
                targetService = GeoBypassEngine.TargetService.GOOGLE_FLOW
            )
            val confirmed = doubleConfirmFlowWorkspace(resolvedAfter.javaNetProxy, 3000)
            emit("  • Secondary workspace probe on datacenter exit: ${if (confirmed) "PASSED ✓" else "blocked (kept as fallback)"}")
        }

        val latency = finalProbe.flow.latencyMs.takeIf { it > 0 } ?: 0L
        val chainSpec = TunnelConfig.formatProxyChain(chainResult.bestHops)
        if (conquered && winnerEgress != null) {
            AutopilotBrain.recordOutcome(
                contextKey = warriorNetKey,
                armId = "flow:${winnerEgress.ip}",
                protocolFamily = "warrior-${chainResult.bestHops.size}hop",
                success = true,
                rttMs = latency.toDouble().coerceAtLeast(1.0),
                geoMaskObserved = AutopilotBrain.GEO_CLEAR_FLOW,
                persistContext = context
            )
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_CLEAN_CHAIN, chainSpec).apply()
            emit("⚔ [$WARRIOR_BADGE_PREFIX VICTORY] Flow Conquered via ${chainResult.bestChainLabel} (${winnerEgress.countryCode} · ${winnerEgress.ip})")
        } else {
            winnerEgress?.ip?.let { blacklistFlowIp(context, it) }
            emit("⚔ [$WARRIOR_BADGE_PREFIX] Guard Held: ${chainResult.summary}")
        }

        AutopilotBrain.updateSession {
            it.copy(
                phase = if (conquered) AutopilotBrain.SessionPhase.CONNECTED else AutopilotBrain.SessionPhase.NEEDS_ACTION,
                networkContextKey = warriorNetKey,
                stepIndex = 3,
                stepHeadline = if (conquered) "$WARRIOR_BADGE_PREFIX ✓ ${winnerEgress?.countryCode ?: "?"} (${latency}ms)" else "Warrior Retry Needed",
                activeArmLabel = "${chainResult.bestChainLabel} (${winnerEgress?.countryCode ?: "?"})"
            )
        }

        val headline = if (conquered) {
            "$WARRIOR_BADGE_PREFIX Conquered Google Flow (${winnerEgress?.countryCode ?: "?"} · ${if (isClean) "Clean Exit" else "Verified"})"
        } else {
            "$WARRIOR_BADGE_PREFIX: Harsh Flow Firewall Blocked Tested Exits"
        }

        return WarriorReport(
            conquered = conquered,
            routeLabel = chainResult.bestChainLabel,
            exitIp = winnerEgress?.ip,
            exitCountry = winnerEgress?.countryCode,
            isResidentialClean = isClean,
            latencyMs = latency,
            attemptsCount = chainResult.attempts.size,
            chainSpec = chainSpec,
            headline = headline,
            recommendation = finalProbe.recommendation,
            probeReport = finalProbe,
            logLines = log
        )
    }
}
