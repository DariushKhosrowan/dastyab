package com.dastyab.app

import java.util.concurrent.atomic.AtomicReference

/**
 * FailoverWatchdog — 5-minute Smart Auto-Failover & Background Harvester state machine.
 *
 * Pure-Kotlin evaluation & DoH failover selection so all state transitions are
 * unit-testable on the plain JVM without Android runtime dependencies.
 */
object FailoverWatchdog {

    const val CHECK_INTERVAL_SECONDS = 300L // 5 minutes
    const val HARVEST_EVERY_N_TICKS = 6     // Quiet background harvest every 30 minutes

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
        val lastSummary: String = "Watchdog idle"
    )

    enum class Action {
        KEEP_CURRENT,
        SWAP_DOH_OR_LAYER
    }

    private val current = AtomicReference(Snapshot())

    fun getSnapshot(): Snapshot = current.get()

    fun update(snapshot: Snapshot) {
        current.set(snapshot)
    }

    fun reset(active: Boolean = false, dohUrl: String = TunnelConfig.DEFAULT_DOH_URL) {
        current.set(
            Snapshot(
                active = active,
                healthy = true,
                activeDohUrl = dohUrl,
                lastSummary = if (active) "5-min Smart Auto-Failover active" else "Watchdog idle"
            )
        )
    }

    /**
     * Decides whether the current tunnel is healthy or requires auto-failover.
     * Healthy if general web connectivity works AND at least one Google AI target (AI Studio or Flow) responds.
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
