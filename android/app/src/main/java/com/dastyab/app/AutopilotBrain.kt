package com.dastyab.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * AutopilotBrain — Self-learning, anti-flapping algorithmic core for DastYab Autopilot.
 *
 * Implements modern networking & ML control primitives in pure Kotlin (unit-testable on JVM):
 *  1. **Contextual Network Fingerprinting** (`WIFI:<hash>`, `CELL:<mcc-mnc>`, `ETH`, `DEFAULT`)
 *     so Irancell, MCI, Rightel, and home Wi-Fi DPI patterns stay cleanly isolated.
 *  2. **Discounted UCB1 + EWMA Latency/Jitter Multi-Armed Bandit** scoring arms by smoothed
 *     RTT, jitter stability, time-decayed survival rate, intent geo-clearance, and controlled exploration.
 *  3. **3-State Circuit Breaker + Hysteresis Deadband** quarantining dead endpoints with
 *     exponential backoff (`30s → 30m`) and preventing route ping-pong unless a challenger
 *     outperforms by `>= 25%` across `>= 2` consecutive checks.
 *  4. **Happy Eyeballs v2 Protocol-Diverse Staggered Racer** launching top arm first (`0ms`),
 *     then hedging across distinct protocol families (`+250ms`, `+550ms`, `+900ms`) and
 *     cancelling remaining probes the moment a route clears the active Intent Gate.
 *  5. **Hot-Standby Pool & /24 Clean-IP Seed Memory** for `<500ms` failover and targeted scanning.
 */
object AutopilotBrain {

    const val EWMA_ALPHA = 0.30
    const val DISCOUNT_GAMMA = 0.90
    const val UCB_EXPLORATION_C = 0.55
    const val CIRCUIT_TRIP_FAILURES = 3
    const val CIRCUIT_BASE_COOLDOWN_MS = 30_000L
    const val CIRCUIT_MAX_COOLDOWN_MS = 1_800_000L
    const val HYSTERESIS_PREEMPT_RATIO = 1.25

    const val GEO_CLEAR_WEB = 1 shl 0
    const val GEO_CLEAR_AISTUDIO = 1 shl 1
    const val GEO_CLEAR_FLOW = 1 shl 2

    val DEFAULT_STAGGER_MS = listOf(0L, 250L, 550L, 900L)

    private const val PREFS_NAME = "dastyab_autopilot_brain"
    private const val KEY_ARMS_TSV = "arms_tsv_v1"
    private const val KEY_SEEDS_TSV = "seeds_tsv_v1"
    private const val KEY_INTENT = "active_intent_v1"
    private const val KEY_STANDBY_URIS = "standby_uris_v1"
    private const val KEY_AUTO_CONSENT_REMEMBERED = "auto_consent_remembered_v1"
    private const val MAX_ARMS_STORED = 240
    private const val MAX_SEEDS_PER_NET = 24

    enum class AutopilotIntent(val key: String) {
        BALANCED_WEB("balanced"),
        AI_STUDIO("aistudio"),
        GOOGLE_FLOW("flow"),
        BLACKOUT_SURVIVAL("blackout");

        companion object {
            fun fromKey(raw: String?): AutopilotIntent =
                values().firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) } ?: BALANCED_WEB
        }
    }

    enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

    enum class SessionPhase {
        IDLE, SENSING, RACING, VERIFYING, CONNECTED, HEALING, NEEDS_ACTION
    }

    data class CandidateArm(
        val armId: String,
        val protocolFamily: String,
        val basePriority: Double = 0.5,
        val payload: String = armId
    )

    data class ArmStats(
        val contextKey: String,
        val armId: String,
        val protocolFamily: String,
        val ewmaMs: Double = 0.0,
        val jitterMs: Double = 0.0,
        val discountedWins: Double = 1.0,
        val discountedTrials: Double = 2.0,
        val consecutiveFailures: Int = 0,
        val consecutiveWins: Int = 0,
        val circuitState: CircuitState = CircuitState.CLOSED,
        val quarantineUntilMs: Long = 0L,
        val tripCount: Int = 0,
        val geoMask: Int = 0,
        val lastUpdatedMs: Long = 0L
    ) {
        fun survivalRate(): Double =
            if (discountedTrials <= 0.0) 0.5 else (discountedWins / discountedTrials).coerceIn(0.0, 1.0)

        fun effectiveCircuit(nowMs: Long): CircuitState = when (circuitState) {
            CircuitState.OPEN -> if (nowMs >= quarantineUntilMs) CircuitState.HALF_OPEN else CircuitState.OPEN
            else -> circuitState
        }
    }

    data class ScoredArm(
        val candidate: CandidateArm,
        val score: Double,
        val stats: ArmStats?
    )

    data class StaggeredRaceResult<T>(
        val winnerIndex: Int,
        val winnerArm: CandidateArm,
        val payload: T,
        val elapsedMs: Long,
        val standbyArms: List<CandidateArm>,
        val attemptsLaunched: Int
    )

    data class BrainSessionSnapshot(
        val phase: SessionPhase = SessionPhase.IDLE,
        val intent: AutopilotIntent = AutopilotIntent.BALANCED_WEB,
        val networkContextKey: String = "default",
        val stepIndex: Int = 0, // 0=idle, 1=sensing, 2=racing, 3=verifying/done
        val stepHeadline: String = "",
        val activeArmLabel: String = "",
        val standbyCount: Int = 0,
        val quarantinedCount: Int = 0,
        val topScoredLines: List<String> = emptyList()
    )

    private val lock = Any()
    private val armsMap = LinkedHashMap<String, ArmStats>() // key = "$contextKey||$armId"
    private val cleanSeedsMap = LinkedHashMap<String, MutableList<String>>()
    private val sessionRef = AtomicReference(BrainSessionSnapshot())
    private var loadedFromPrefs = false

    fun getSessionSnapshot(): BrainSessionSnapshot = sessionRef.get()

    fun updateSession(transform: (BrainSessionSnapshot) -> BrainSessionSnapshot) {
        while (true) {
            val prev = sessionRef.get()
            val next = transform(prev)
            if (sessionRef.compareAndSet(prev, next)) return
        }
    }

    /** Clears in-memory bandit state (primarily for deterministic JVM unit tests). */
    fun resetInMemoryForTest() {
        synchronized(lock) {
            armsMap.clear()
            cleanSeedsMap.clear()
            loadedFromPrefs = true
        }
        sessionRef.set(BrainSessionSnapshot())
    }

    /** Ensures persisted state is loaded on Android runtime (no-op if already loaded). */
    fun ensureLoaded(context: Context) {
        synchronized(lock) {
            if (loadedFromPrefs) return
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val rawArms = prefs.getString(KEY_ARMS_TSV, "").orEmpty()
            deserializeArms(rawArms).forEach { st ->
                armsMap["${st.contextKey}||${st.armId}"] = st
            }
            val rawSeeds = prefs.getString(KEY_SEEDS_TSV, "").orEmpty()
            deserializeSeeds(rawSeeds).forEach { (net, ips) ->
                cleanSeedsMap[net] = ips.toMutableList()
            }
            val savedIntent = AutopilotIntent.fromKey(prefs.getString(KEY_INTENT, null))
            updateSession { it.copy(intent = savedIntent) }
            loadedFromPrefs = true
        }
    }

    fun getSavedIntent(context: Context): AutopilotIntent {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AutopilotIntent.fromKey(prefs.getString(KEY_INTENT, null))
    }

    fun saveIntent(context: Context, intent: AutopilotIntent) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_INTENT, intent.key).apply()
        updateSession { it.copy(intent = intent) }
    }

    fun saveStandbyUris(context: Context, uris: List<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_STANDBY_URIS, uris.distinct().take(4).joinToString("\n")).apply()
    }

    fun loadStandbyUris(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STANDBY_URIS, "").orEmpty()
        return raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
    }

    fun isConsentRemembered(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CONSENT_REMEMBERED, false)

    fun markConsentRemembered(context: Context, remembered: Boolean = true) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_CONSENT_REMEMBERED, remembered).apply()
    }

    /** Privacy-preserving local network fingerprint distinguishing Cellular carriers & Wi-Fi networks. */
    fun detectNetworkContextKey(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "default"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val active = cm.activeNetwork ?: return "offline"
                val caps = cm.getNetworkCapabilities(active) ?: return "default"
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                        val lp = cm.getLinkProperties(active)
                        val dnsHint = lp?.dnsServers?.firstOrNull()?.hostAddress.orEmpty()
                        val hash = (lp?.interfaceName.orEmpty() + ":" + dnsHint).hashCode().toUInt()
                        "wifi:$hash"
                    }
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                        val op = tm?.networkOperator?.takeIf { it.length >= 5 } ?: "unknown"
                        "cell:$op"
                    }
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn_ext"
                    else -> "default"
                }
            } else {
                "legacy"
            }
        } catch (_: Exception) {
            "default"
        }
    }

    /** Pure-Kotlin Discounted UCB1 + EWMA RTT/Jitter + Intent alignment score. */
    fun scoreArm(
        contextKey: String,
        candidate: CandidateArm,
        intent: AutopilotIntent,
        nowMs: Long = System.currentTimeMillis()
    ): Double {
        val st: ArmStats?
        val totalTrials: Double
        synchronized(lock) {
            val netArms = armsMap.values.filter { it.contextKey == contextKey }
            totalTrials = netArms.sumOf { it.discountedTrials }.coerceAtLeast(2.0)
            st = armsMap["$contextKey||${candidate.armId}"]
        }
        if (st == null) {
            val ucbPrior = UCB_EXPLORATION_C * sqrt(max(1.0, ln(totalTrials)))
            return 0.60 + min(0.65, ucbPrior) + candidate.basePriority * 0.15
        }
        val eff = st.effectiveCircuit(nowMs)
        if (eff == CircuitState.OPEN) return -10.0

        val survival = st.survivalRate()
        val latencyPenalty = if (st.ewmaMs > 0.0) {
            ((st.ewmaMs + 0.5 * st.jitterMs) / 2500.0).coerceIn(0.0, 0.55)
        } else {
            0.12
        }
        val explore = UCB_EXPLORATION_C * sqrt(max(1.0, ln(totalTrials)) / max(1.0, st.discountedTrials))

        val intentBonus = when (intent) {
            AutopilotIntent.BALANCED_WEB -> if ((st.geoMask and GEO_CLEAR_WEB) != 0) 0.12 else 0.0
            AutopilotIntent.AI_STUDIO -> if ((st.geoMask and GEO_CLEAR_AISTUDIO) != 0) 0.30 else 0.0
            AutopilotIntent.GOOGLE_FLOW -> if ((st.geoMask and GEO_CLEAR_FLOW) != 0) 0.40 else 0.0
            AutopilotIntent.BLACKOUT_SURVIVAL -> {
                val pf = candidate.protocolFamily.lowercase()
                if (pf in setOf("doh", "quic", "hy2", "hysteria2", "tuic", "openvpn", "ovpn", "wireguard", "wg")) {
                    0.22
                } else 0.05
            }
        }
        val halfOpenPenalty = if (eff == CircuitState.HALF_OPEN) 0.18 else 0.0

        return max(
            0.0,
            survival - latencyPenalty + min(0.60, explore) + intentBonus + candidate.basePriority * 0.10 - halfOpenPenalty
        )
    }

    /** Records an observed outcome for `(contextKey, armId)`, updating EWMA, UCB1 counts, and circuit breaker. */
    fun recordOutcome(
        contextKey: String,
        armId: String,
        protocolFamily: String,
        success: Boolean,
        rttMs: Double?,
        geoMaskObserved: Int = if (success) GEO_CLEAR_WEB else 0,
        nowMs: Long = System.currentTimeMillis(),
        persistContext: Context? = null
    ): ArmStats {
        val updated: ArmStats
        synchronized(lock) {
            val mapKey = "$contextKey||$armId"
            val prev = armsMap[mapKey] ?: ArmStats(contextKey, armId, protocolFamily, lastUpdatedMs = nowMs)
            val decayedWins = prev.discountedWins * DISCOUNT_GAMMA
            val decayedTrials = max(1.0, prev.discountedTrials * DISCOUNT_GAMMA) + 1.0

            updated = if (success) {
                var newEwma = prev.ewmaMs
                var newJitter = prev.jitterMs
                if (rttMs != null && rttMs > 0.0 && rttMs.isFinite()) {
                    if (prev.ewmaMs <= 0.0) {
                        newEwma = rttMs
                        newJitter = rttMs * 0.10
                    } else {
                        val diff = abs(rttMs - prev.ewmaMs)
                        newEwma = EWMA_ALPHA * rttMs + (1.0 - EWMA_ALPHA) * prev.ewmaMs
                        newJitter = EWMA_ALPHA * diff + (1.0 - EWMA_ALPHA) * prev.jitterMs
                    }
                }
                prev.copy(
                    protocolFamily = protocolFamily,
                    ewmaMs = newEwma,
                    jitterMs = newJitter,
                    discountedWins = decayedWins + 1.0,
                    discountedTrials = decayedTrials,
                    consecutiveFailures = 0,
                    consecutiveWins = prev.consecutiveWins + 1,
                    circuitState = CircuitState.CLOSED,
                    quarantineUntilMs = 0L,
                    geoMask = prev.geoMask or geoMaskObserved,
                    lastUpdatedMs = nowMs
                )
            } else {
                val nextFail = prev.consecutiveFailures + 1
                val eff = prev.effectiveCircuit(nowMs)
                var nextCircuit = prev.circuitState
                var nextQuarantine = prev.quarantineUntilMs
                var nextTrips = prev.tripCount
                if (eff == CircuitState.HALF_OPEN || nextFail >= CIRCUIT_TRIP_FAILURES) {
                    nextTrips = max(1, prev.tripCount + 1)
                    val shift = min(6, nextTrips - 1)
                    val cooldown = min(CIRCUIT_MAX_COOLDOWN_MS, CIRCUIT_BASE_COOLDOWN_MS shl shift)
                    nextCircuit = CircuitState.OPEN
                    nextQuarantine = nowMs + cooldown
                }
                prev.copy(
                    protocolFamily = protocolFamily,
                    discountedWins = decayedWins,
                    discountedTrials = decayedTrials,
                    consecutiveFailures = nextFail,
                    consecutiveWins = 0,
                    circuitState = nextCircuit,
                    quarantineUntilMs = nextQuarantine,
                    tripCount = nextTrips,
                    lastUpdatedMs = nowMs
                )
            }

            armsMap[mapKey] = updated
            if (armsMap.size > MAX_ARMS_STORED) {
                val oldestKey = armsMap.values.minByOrNull { it.lastUpdatedMs }?.let { "${it.contextKey}||${it.armId}" }
                if (oldestKey != null) armsMap.remove(oldestKey)
            }
        }
        persistContext?.let { saveArmsToPrefs(it) }
        return updated
    }

    /** Happy Eyeballs v2 protocol-diverse slate selection: picks strongest non-quarantined arms across diverse protocol families. */
    fun selectDiverseSlate(
        contextKey: String,
        candidates: List<CandidateArm>,
        intent: AutopilotIntent,
        maxSlots: Int = 4,
        nowMs: Long = System.currentTimeMillis()
    ): List<ScoredArm> {
        if (candidates.isEmpty() || maxSlots <= 0) return emptyList()
        val scored = candidates.map { cand ->
            val st = synchronized(lock) { armsMap["$contextKey||${cand.armId}"] }
            ScoredArm(cand, scoreArm(contextKey, cand, intent, nowMs), st)
        }.sortedByDescending { it.score }

        val eligible = if (scored.any { it.score >= 0.0 }) scored.filter { it.score >= 0.0 } else scored
        val selected = ArrayList<ScoredArm>(maxSlots)
        val seenIds = HashSet<String>()
        val seenFamilies = HashSet<String>()

        eligible.firstOrNull()?.let { best ->
            selected.add(best)
            seenIds.add(best.candidate.armId)
            seenFamilies.add(best.candidate.protocolFamily.lowercase())
        }

        for (item in eligible) {
            if (selected.size >= maxSlots) break
            val fam = item.candidate.protocolFamily.lowercase()
            if (item.candidate.armId !in seenIds && fam !in seenFamilies) {
                selected.add(item)
                seenIds.add(item.candidate.armId)
                seenFamilies.add(fam)
            }
        }

        for (item in eligible) {
            if (selected.size >= maxSlots) break
            if (seenIds.add(item.candidate.armId)) {
                selected.add(item)
            }
        }
        return selected
    }

    /** Hysteresis guard: stops ping-ponging between nearly equal routes while healthy. */
    fun shouldPreemptActive(
        activeHealthy: Boolean,
        activeScore: Double,
        challengerScore: Double,
        challengerLeadStreak: Int
    ): Boolean {
        if (!activeHealthy) return true
        if (activeScore <= 0.0) return challengerScore > 0.0
        return challengerLeadStreak >= 2 && challengerScore >= activeScore * HYSTERESIS_PREEMPT_RATIO
    }

    /**
     * Happy Eyeballs v2 Staggered Hedged Racer:
     * Launches Arm #0 immediately, waits up to `staggerScheduleMs[i]` before launching
     * each hedged backup Arm #i, and short-circuits as soon as any arm produces a non-null result.
     */
    fun <T> raceStaggered(
        slate: List<CandidateArm>,
        staggerScheduleMs: List<Long> = DEFAULT_STAGGER_MS,
        probeTimeoutMs: Long = 2600L,
        attempt: (CandidateArm) -> T?
    ): StaggeredRaceResult<T>? {
        if (slate.isEmpty()) return null
        val startNs = System.nanoTime()
        val pool = Executors.newCachedThreadPool { r ->
            Thread(r, "dastyab-happy-race").apply { isDaemon = true }
        }
        val futures = ArrayList<Pair<Int, Future<T?>>>(slate.size)
        try {
            var prevDelay = 0L
            for (i in slate.indices) {
                val targetDelay = staggerScheduleMs.getOrElse(i) { prevDelay + 300L }
                val deltaMs = (targetDelay - prevDelay).coerceAtLeast(0L)
                if (i > 0 && deltaMs > 0L) {
                    val waitEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deltaMs)
                    while (System.nanoTime() < waitEnd) {
                        val ready = pollFirstSuccess(futures, slate, startNs)
                        if (ready != null) {
                            cancelRemaining(futures, ready.winnerIndex)
                            return ready
                        }
                        try { Thread.sleep(20L) } catch (_: InterruptedException) { break }
                    }
                }
                prevDelay = targetDelay
                val arm = slate[i]
                val fut = pool.submit(Callable {
                    runCatching { attempt(arm) }.getOrNull()
                })
                futures.add(i to fut)
            }

            // Wait for any already-launched arm to succeed up to probeTimeoutMs
            val overallDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(probeTimeoutMs)
            while (System.nanoTime() < overallDeadline) {
                val ready = pollFirstSuccess(futures, slate, startNs)
                if (ready != null) {
                    cancelRemaining(futures, ready.winnerIndex)
                    return ready
                }
                if (futures.all { it.second.isDone }) break
                try { Thread.sleep(25L) } catch (_: InterruptedException) { break }
            }
            return pollFirstSuccess(futures, slate, startNs)?.also { cancelRemaining(futures, it.winnerIndex) }
        } finally {
            futures.forEach { it.second.cancel(true) }
            pool.shutdownNow()
        }
    }

    private fun <T> pollFirstSuccess(
        futures: List<Pair<Int, Future<T?>>>,
        slate: List<CandidateArm>,
        startNs: Long
    ): StaggeredRaceResult<T>? {
        for ((idx, fut) in futures) {
            if (fut.isDone && !fut.isCancelled) {
                val value = runCatching { fut.get() }.getOrNull()
                if (value != null) {
                    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs).coerceAtLeast(1L)
                    val standby = slate.filterIndexed { i, _ -> i != idx }
                    return StaggeredRaceResult(
                        winnerIndex = idx,
                        winnerArm = slate[idx],
                        payload = value,
                        elapsedMs = elapsedMs,
                        standbyArms = standby,
                        attemptsLaunched = futures.size
                    )
                }
            }
        }
        return null
    }

    private fun <T> cancelRemaining(futures: List<Pair<Int, Future<T?>>>, winnerIdx: Int) {
        for ((idx, fut) in futures) {
            if (idx != winnerIdx) fut.cancel(true)
        }
    }

    fun recordCleanIpSeed(contextKey: String, ip: String, persistContext: Context? = null) {
        val clean = ip.trim()
        if (clean.isEmpty()) return
        synchronized(lock) {
            val list = cleanSeedsMap.getOrPut(contextKey) { mutableListOf() }
            list.remove(clean)
            list.add(0, clean)
            if (list.size > MAX_SEEDS_PER_NET) {
                while (list.size > MAX_SEEDS_PER_NET) list.removeAt(list.lastIndex)
            }
        }
        persistContext?.let { saveSeedsToPrefs(it) }
    }

    fun getCleanIpSeeds(contextKey: String): List<String> = synchronized(lock) {
        cleanSeedsMap[contextKey]?.toList().orEmpty()
    }

    fun countQuarantined(contextKey: String, nowMs: Long = System.currentTimeMillis()): Int = synchronized(lock) {
        armsMap.values.count { it.contextKey == contextKey && it.effectiveCircuit(nowMs) == CircuitState.OPEN }
    }

    fun formatTopScoredSummaries(
        contextKey: String,
        candidates: List<CandidateArm>,
        intent: AutopilotIntent,
        limit: Int = 3,
        nowMs: Long = System.currentTimeMillis()
    ): List<String> {
        val slate = selectDiverseSlate(contextKey, candidates, intent, limit, nowMs)
        return slate.map { item ->
            val st = item.stats
            val msTag = if (st != null && st.ewmaMs > 0.0) "${st.ewmaMs.toInt()}ms" else "new"
            val rateTag = if (st != null) "${(st.survivalRate() * 100).toInt()}%" else "50%"
            val scoreStr = String.format(java.util.Locale.US, "%.2f", item.score)
            "${item.candidate.protocolFamily.uppercase()} · $scoreStr ($rateTag, $msTag)"
        }
    }

    fun clearAllLearned(context: Context) {
        synchronized(lock) {
            armsMap.clear()
            cleanSeedsMap.clear()
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        updateSession { BrainSessionSnapshot() }
    }

    private fun saveArmsToPrefs(context: Context) {
        val serialized = synchronized(lock) { serializeArms(armsMap.values.toList()) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ARMS_TSV, serialized).apply()
    }

    private fun saveSeedsToPrefs(context: Context) {
        val serialized = synchronized(lock) { serializeSeeds(cleanSeedsMap) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SEEDS_TSV, serialized).apply()
    }

    /** Pure-Kotlin TSV serializer for [ArmStats] (unit-testable on JVM). */
    fun serializeArms(arms: List<ArmStats>): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
        return arms.joinToString("\n") { a ->
            listOf(
                esc(a.contextKey),
                esc(a.armId),
                esc(a.protocolFamily),
                a.ewmaMs.toString(),
                a.jitterMs.toString(),
                a.discountedWins.toString(),
                a.discountedTrials.toString(),
                a.consecutiveFailures.toString(),
                a.consecutiveWins.toString(),
                a.circuitState.name,
                a.quarantineUntilMs.toString(),
                a.tripCount.toString(),
                a.geoMask.toString(),
                a.lastUpdatedMs.toString()
            ).joinToString("\t")
        }
    }

    /** Pure-Kotlin TSV deserializer for [ArmStats]. */
    fun deserializeArms(raw: String): List<ArmStats> {
        if (raw.isBlank()) return emptyList()
        fun unesc(s: String): String {
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                if (s[i] == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        't' -> { sb.append('\t'); i += 2; continue }
                        'n' -> { sb.append('\n'); i += 2; continue }
                        '\\' -> { sb.append('\\'); i += 2; continue }
                    }
                }
                sb.append(s[i]); i++
            }
            return sb.toString()
        }
        return raw.lineSequence().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 14) return@mapNotNull null
            ArmStats(
                contextKey = unesc(p[0]),
                armId = unesc(p[1]),
                protocolFamily = unesc(p[2]),
                ewmaMs = p[3].toDoubleOrNull() ?: 0.0,
                jitterMs = p[4].toDoubleOrNull() ?: 0.0,
                discountedWins = p[5].toDoubleOrNull() ?: 1.0,
                discountedTrials = p[6].toDoubleOrNull() ?: 2.0,
                consecutiveFailures = p[7].toIntOrNull() ?: 0,
                consecutiveWins = p[8].toIntOrNull() ?: 0,
                circuitState = runCatching { CircuitState.valueOf(p[9]) }.getOrDefault(CircuitState.CLOSED),
                quarantineUntilMs = p[10].toLongOrNull() ?: 0L,
                tripCount = p[11].toIntOrNull() ?: 0,
                geoMask = p[12].toIntOrNull() ?: 0,
                lastUpdatedMs = p[13].toLongOrNull() ?: 0L
            )
        }.toList()
    }

    fun serializeSeeds(seeds: Map<String, List<String>>): String =
        seeds.entries.joinToString("\n") { (k, ips) -> "$k\t${ips.joinToString(",")}" }

    fun deserializeSeeds(raw: String): Map<String, List<String>> {
        if (raw.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, List<String>>()
        raw.lineSequence().forEach { line ->
            val idx = line.indexOf('\t')
            if (idx > 0) {
                val net = line.substring(0, idx)
                val ips = line.substring(idx + 1).split(',').map { it.trim() }.filter { it.isNotBlank() }
                if (ips.isNotEmpty()) out[net] = ips
            }
        }
        return out
    }
}
