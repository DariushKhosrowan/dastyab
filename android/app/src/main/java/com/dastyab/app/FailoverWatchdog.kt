package com.dastyab.app

import java.util.concurrent.atomic.AtomicReference

/**
 * FailoverWatchdog — Adaptive Self-Healing Watchdog + Half-Open Passive Liveness Sentinel.
 *
 * Combines:
 *  • Passive data-path observation ([PassiveLivenessSentinel], matching Rust `LivenessSentinel`):
 *    detects silent firewall blackholing (outbound TX active, 0 inbound RX for >= 4.5s) without
 *    wasting battery on constant probes.
 *  • Adaptive tick cadence: tightens immediately to 15s when suspect/healing or after OS
 *    network transitions, and relaxes to 240s–300s during sustained healthy streaks.
 *  • Pure-Kotlin state transitions so all invariants are JVM unit-testable.
 */
object FailoverWatchdog {

    const val CHECK_INTERVAL_SECONDS = 300L // Default calm baseline (5 minutes)
    const val SUSPECT_INTERVAL_SECONDS = 15L // Rapid healing interval when stall/failure detected
    const val WARMUP_INTERVAL_SECONDS = 60L // Initial post-connect cadence
    const val HEALTHY_INTERVAL_SECONDS = 240L // Battery-friendly cadence after >= 3 healthy ticks
    const val HARVEST_EVERY_N_TICKS = 6 // Quiet background harvest cadence
    const val STALL_WINDOW_MS = 4_500L

    val FALLBACK_DOH_CHAIN: List<String> = listOf(
        "https://1.1.1.1/dns-query",
        "https://8.8.8.8/dns-query",
        "https://9.9.9.9/dns-query",
        "https://dns.google/dns-query",
        "https://cloudflare-dns.com/dns-query"
    )

    data class Snapshot(
        val active: Boolean = false,
        val healthy: Boolean = true,
        val lastCheckMs: Long = 0L,
        val lastLatencyMs: Long = -1L,
        val checkCount: Int = 0,
        val swapCount: Int = 0,
        val backgroundHarvestCount: Int = 0,
        val activeDohUrl: String = TunnelConfig.DEFAULT_DOH_URL,
        val lastSummary: String = "Watchdog idle",
        val nextIntervalSeconds: Long = WARMUP_INTERVAL_SECONDS,
        val healthyStreak: Int = 0,
        val suspectState: Boolean = false
    )

    enum class Action {
        KEEP_CURRENT,
        SWAP_DOH_OR_LAYER
    }

    enum class SentinelVerdict {
        IDLE,
        HEALTHY,
        STALLED
    }

    /**
     * Pure-Kotlin half-open blackhole detector (Android counterpart to Rust `LivenessSentinel`).
     * Observes outbound vs inbound traffic timestamps on the TUN or local proxy bridge.
     */
    class PassiveLivenessSentinel(
        private val stallWindowMs: Long = STALL_WINDOW_MS,
        private val minConsecutiveStalls: Int = 2
    ) {
        private var txBytes: Long = 0L
        private var rxBytes: Long = 0L
        private var lastTxMs: Long = -1L
        private var lastRxMs: Long = -1L
        private var everSeenRx: Boolean = false
        private var stallStreak: Int = 0

        @Synchronized
        fun reset() {
            txBytes = 0L
            rxBytes = 0L
            lastTxMs = -1L
            lastRxMs = -1L
            everSeenRx = false
            stallStreak = 0
        }

        @Synchronized
        fun recordOutbound(bytes: Long, nowMs: Long = System.currentTimeMillis()) {
            if (bytes <= 0L) return
            txBytes += bytes
            lastTxMs = nowMs
        }

        @Synchronized
        fun recordInbound(bytes: Long, nowMs: Long = System.currentTimeMillis()) {
            if (bytes <= 0L) return
            rxBytes += bytes
            lastRxMs = nowMs
            everSeenRx = true
            stallStreak = 0
        }

        @Synchronized
        fun evaluate(nowMs: Long = System.currentTimeMillis()): SentinelVerdict {
            if (lastTxMs < 0L || (nowMs - lastTxMs) > stallWindowMs * 3) {
                stallStreak = 0
                return SentinelVerdict.IDLE
            }
            val silentInbound = if (everSeenRx && lastRxMs >= 0L) {
                lastTxMs > lastRxMs && (nowMs - lastRxMs) >= stallWindowMs
            } else {
                txBytes >= 1024L && (nowMs - lastTxMs) >= stallWindowMs
            }
            return if (silentInbound) {
                stallStreak++
                if (stallStreak >= minConsecutiveStalls) SentinelVerdict.STALLED else SentinelVerdict.HEALTHY
            } else {
                stallStreak = 0
                SentinelVerdict.HEALTHY
            }
        }
    }

    val passiveSentinel = PassiveLivenessSentinel()
    private val current = AtomicReference(Snapshot())

    fun getSnapshot(): Snapshot = current.get()

    fun update(snapshot: Snapshot) {
        current.set(snapshot)
    }

    fun reset(active: Boolean = false, dohUrl: String = TunnelConfig.DEFAULT_DOH_URL) {
        passiveSentinel.reset()
        current.set(
            Snapshot(
                active = active,
                healthy = true,
                activeDohUrl = dohUrl,
                lastSummary = if (active) "Adaptive Auto-Heal active" else "Watchdog idle",
                nextIntervalSeconds = if (active) WARMUP_INTERVAL_SECONDS else CHECK_INTERVAL_SECONDS,
                healthyStreak = 0,
                suspectState = false
            )
        )
    }

    /** Computes the next adaptive probe delay in seconds based on health & suspect status. */
    fun recommendedDelaySeconds(healthyStreak: Int, suspect: Boolean): Long = when {
        suspect -> SUSPECT_INTERVAL_SECONDS
        healthyStreak >= 3 -> HEALTHY_INTERVAL_SECONDS
        else -> WARMUP_INTERVAL_SECONDS
    }

    /**
     * Decides whether the current tunnel is healthy or requires auto-failover.
     * Healthy if general web connectivity works AND at least one Google AI target responds.
     */
    fun evaluateAction(
        internetOk: Boolean,
        aiStudioOk: Boolean,
        flowOk: Boolean
    ): Action {
        return if (internetOk && (aiStudioOk || flowOk)) {
            Action.KEEP_CURRENT
        } else {
            Action.SWAP_DOH_OR_LAYER
        }
    }

    /** Picks the next DoH endpoint in the failover ring different from [currentDoh]. */
    fun nextFallbackDoh(currentDoh: String, candidates: List<String> = FALLBACK_DOH_CHAIN): String {
        if (candidates.isEmpty()) return TunnelConfig.DEFAULT_DOH_URL
        val idx = candidates.indexOfFirst { it.equals(currentDoh.trim(), ignoreCase = true) }
        return if (idx < 0) candidates.first() else candidates[(idx + 1) % candidates.size]
    }

    /** Returns true if this tick should also run a quiet background config harvest. */
    fun shouldRunBackgroundHarvest(tickNumber: Int, currentPoolSize: Int): Boolean {
        if (tickNumber <= 0) return false
        return currentPoolSize == 0 || (tickNumber % HARVEST_EVERY_N_TICKS == 0)
    }
}
