package com.dastyab.app

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * FlowWarriorEngine — Independent "Brutal Eagle" Anti-Geoblock Algorithm exclusively for Google Flow.
 *
 * Completely decoupled from Google AI Studio (`aiStudio` proxy chains, bandit context, and probes),
 * because `flow.google.com` enforces far stricter ASN/datacenter reputation, WARP/Tor rejections,
 * 151-country allowlists, `labs.google` geo-cookies, and Angular app-shell gates.
 *
 * Heavy Warrior v2 Architecture:
 *  1. Isolated Namespace (`flow_warrior:<netKey>`) + persistent blocked-egress IP blacklist.
 *  2. Deep ASN & WARP/Tor Reputation Shield (heavily penalizes WARP & datacenter ASNs; favors
 *     genuine Tier-1 Residential/ISP exits in US/CA/GB/DE/NL/CH/JP/SG/AU/IE/etc.).
 *  3. True GET Zero-Mercy Double-Confirm Gate (`flow.google.com/about/` + `labs.google/fx/tools/flow`
 *     with coherent Chrome 131 headers & body marker inspection).
 *  4. Brutal 5-Round Progressive Multi-Hop Escalation (up to 96 candidates, 1..4-hop cascades pairing
 *     censorship-punching entry relays / WARP / Orbot / v2rayNG with clean Tier-1 residential exits).
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
        if (egress.warpActive || egress.reputationTier == BrutalAntiGeoblockEngine.ExitReputationTier.WARP_OR_TOR) {
            if (!doubleConfirmedClean) return false
        }
        if (requireNonHostingOrDoubleConfirmed && egress.isProxyOrHosting && !doubleConfirmedClean) {
            return false
        }
        return true
    }

    /**
     * Pure-Kotlin Brutal Eagle scoring (higher = stronger Warrior candidate).
     * Rewards Tier-1 countries & clean residential ISPs; harshly penalizes WARP, Tor,
     * datacenter ASNs, geo-errors, missing Angular shell, and blacklisted IPs.
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
            WARRIOR_TIER1_COUNTRIES.contains(cc) -> 450L
            GeoBypassEngine.isFlowCountrySupported(cc) -> 120L
            else -> -800_000L
        }
        score += when {
            egress.warpActive || egress.reputationTier == BrutalAntiGeoblockEngine.ExitReputationTier.WARP_OR_TOR -> -2_500L
            egress.reputationTier == BrutalAntiGeoblockEngine.ExitReputationTier.RESIDENTIAL_ISP -> 900L
            !egress.isProxyOrHosting -> 500L
            egress.reputationTier == BrutalAntiGeoblockEngine.ExitReputationTier.DATACENTER_LOW_RISK -> -220L
            else -> -600L
        }
        score += if (report.flow.reachable && !report.flow.unsupportedRegion && report.flow.appShellOk) 550L else -500_000L
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
     * Secondary zero-mercy GET verification on `flow.google.com/about/` & `labs.google/fx/tools/flow`
     * with authentic Chrome headers and body inspection so flagged datacenter/WARP exits don't pass
     * the home shell only to geo-lock inside Flow Workspace.
     */
    fun doubleConfirmFlowWorkspace(proxy: Proxy, countryCode: String? = "US", timeoutMs: Int = 3800): Boolean {
        val targets = listOf(
            "https://flow.google.com/about/",
            "https://labs.google/fx/tools/flow"
        )
        for (target in targets) {
            val ok = runCatching {
                val conn = (URL(target).openConnection(proxy) as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    BrutalAntiGeoblockEngine.applyCleanBrowserHeaders(this, countryCode = countryCode, mobile = false)
                }
                try {
                    val code = conn.responseCode
                    val loc = conn.getHeaderField("Location").orEmpty()
                    if (code in listOf(403, 429, 451)) return@runCatching false
                    if (loc.contains("/sorry/", ignoreCase = true)) return@runCatching false
                    val body = if (code in 200..299) {
                        runCatching {
                            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                                val buf = CharArray(16384)
                                val n = reader.read(buf)
                                if (n > 0) String(buf, 0, n) else ""
                            }
                        }.getOrDefault("")
                    } else {
                        ""
                    }
                    !GeoBypassEngine.isFlowUnsupportedResponse(code, loc.ifBlank { target }, body) && code in 200..399
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
                stepHeadline = "$WARRIOR_BADGE_PREFIX · Engaging Heavy Brutal Eagle bypass for Google Flow…"
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
        val initialHighRisk = initialProbe.egress?.isProxyOrHosting == true ||
            initialProbe.egress?.warpActive == true
        val initialDoubleConfirmed = if (initialProbe.readyForFlow && initialHighRisk) {
            doubleConfirmFlowWorkspace(freshRoute.javaNetProxy, initialProbe.egress?.countryCode, 3800)
        } else {
            !initialHighRisk
        }

        if (!excludeCurrentRoute && isWarriorConfirmed(
                initialProbe,
                blacklistedIps = combinedExcludedIps,
                requireNonHostingOrDoubleConfirmed = true,
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
            emit("⚔ [$WARRIOR_BADGE_PREFIX] Flow Conquered (${eg.reputationTier.label})! ${eg.countryCode} (${eg.ip}) · ${initialProbe.flow.latencyMs}ms")
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
                isResidentialClean = !eg.isProxyOrHosting && !eg.warpActive,
                latencyMs = initialProbe.flow.latencyMs,
                attemptsCount = 1,
                chainSpec = freshRoute.upstreamChainSpec,
                headline = "$WARRIOR_BADGE_PREFIX Conquered Google Flow (${eg.countryCode} · ${initialProbe.flow.latencyMs}ms)",
                recommendation = initialProbe.recommendation,
                probeReport = initialProbe,
                logLines = log
            )
        }

        // Current route failed Flow's harsh gate or failed high-risk double-confirm — quarantine in Flow Warrior memory ONLY
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
            val reasonTag = if (initialProbe.egress.warpActive) "WARP" else initialProbe.egress.reputationTier.label
            emit("  ✗ Rejected Flow Exit [$badIp] (${initialProbe.egress.countryCode} · $reasonTag) — quarantined & laundering through residential cascade.")
        }

        // Stage 2: Heavy 5-Round 96-Candidate Entry-Laundering + Residential Cascade
        emit("⚔ [$WARRIOR_BADGE_PREFIX] Escalating 5-Round Heavy Brutal Eagle Cascade (up to 96 multi-hop candidates)…")
        val chainResult = GeoBypassEngine.discoverAndChainBestRoute(
            context = context,
            targetService = GeoBypassEngine.TargetService.GOOGLE_FLOW,
            allowHarvest = true,
            maxCandidates = 96,
            excludedEgressIps = combinedExcludedIps,
            excludeCurrentRoute = true,
            excludedChainSpecs = excludedChainSpecs,
            maxRounds = 5
        ) { step -> emit("  [Warrior] $step") }

        val finalProbe = chainResult.report
        val winnerEgress = finalProbe.egress
        val isClean = winnerEgress != null && !winnerEgress.isProxyOrHosting && !winnerEgress.warpActive
        var doubleConfirmed = isClean
        if (chainResult.success && winnerEgress != null && !isClean) {
            val resolvedAfter = GeoBypassEngine.resolveCurrentRoute(
                context = context,
                targetService = GeoBypassEngine.TargetService.GOOGLE_FLOW
            )
            doubleConfirmed = doubleConfirmFlowWorkspace(resolvedAfter.javaNetProxy, winnerEgress.countryCode, 3800)
            emit("  • Zero-Mercy Workspace GET gate (${winnerEgress.reputationTier.label}): ${if (doubleConfirmed) "PASSED ✓" else "BLOCKED ✗"}")
        }

        val conquered = chainResult.success && isWarriorConfirmed(
            finalProbe,
            blacklistedIps = emptySet(),
            requireNonHostingOrDoubleConfirmed = false,
            doubleConfirmedClean = doubleConfirmed
        )

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
            emit("⚔ [$WARRIOR_BADGE_PREFIX VICTORY] Flow Conquered via ${chainResult.bestChainLabel} (${winnerEgress.countryCode} · ${winnerEgress.ip} · ${winnerEgress.reputationTier.label})")
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
            "$WARRIOR_BADGE_PREFIX Conquered Google Flow (${winnerEgress?.countryCode ?: "?"} · ${if (isClean) "Clean Residential" else "Workspace Verified"})"
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
