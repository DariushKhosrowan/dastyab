package com.dastyab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverWatchdogTest {

    @Test
    fun evaluateActionKeepsCurrentOnlyWhenWebAndGoogleAreHealthy() {
        assertEquals(
            FailoverWatchdog.Action.KEEP_CURRENT,
            FailoverWatchdog.evaluateAction(internetOk = true, aiStudioOk = true, flowOk = false)
        )
        assertEquals(
            FailoverWatchdog.Action.KEEP_CURRENT,
            FailoverWatchdog.evaluateAction(internetOk = true, aiStudioOk = false, flowOk = true)
        )
        assertEquals(
            FailoverWatchdog.Action.SWAP_DOH_OR_LAYER,
            FailoverWatchdog.evaluateAction(internetOk = true, aiStudioOk = false, flowOk = false)
        )
        assertEquals(
            FailoverWatchdog.Action.SWAP_DOH_OR_LAYER,
            FailoverWatchdog.evaluateAction(internetOk = false, aiStudioOk = true, flowOk = true)
        )
    }

    @Test
    fun nextFallbackDohRotatesThroughChain() {
        val first = FailoverWatchdog.FALLBACK_DOH_CHAIN[0]
        val second = FailoverWatchdog.nextFallbackDoh(first)
        assertNotEquals(first, second)
        assertEquals(FailoverWatchdog.FALLBACK_DOH_CHAIN[1], second)

        val last = FailoverWatchdog.FALLBACK_DOH_CHAIN.last()
        assertEquals(first, FailoverWatchdog.nextFallbackDoh(last))
    }

    @Test
    fun backgroundHarvestTriggersOnEmptyPoolOrEverySixthTick() {
        assertTrue(FailoverWatchdog.shouldRunBackgroundHarvest(tickNumber = 1, currentPoolSize = 0))
        assertFalse(FailoverWatchdog.shouldRunBackgroundHarvest(tickNumber = 1, currentPoolSize = 10))
        assertTrue(FailoverWatchdog.shouldRunBackgroundHarvest(tickNumber = 6, currentPoolSize = 10))
        assertFalse(FailoverWatchdog.shouldRunBackgroundHarvest(tickNumber = 0, currentPoolSize = 0))
    }

    @Test
    fun resetAndUpdateSnapshotWorksCleanly() {
        FailoverWatchdog.reset(active = true, dohUrl = "https://8.8.8.8/dns-query")
        val snap = FailoverWatchdog.getSnapshot()
        assertTrue(snap.active)
        assertEquals("https://8.8.8.8/dns-query", snap.activeDohUrl)
    }

    @Test
    fun adaptiveCadenceTightensOnSuspectAndRelaxesWhenHealthy() {
        assertEquals(
            FailoverWatchdog.SUSPECT_INTERVAL_SECONDS,
            FailoverWatchdog.recommendedDelaySeconds(healthyStreak = 5, suspect = true)
        )
        assertEquals(
            FailoverWatchdog.WARMUP_INTERVAL_SECONDS,
            FailoverWatchdog.recommendedDelaySeconds(healthyStreak = 1, suspect = false)
        )
        assertEquals(
            FailoverWatchdog.HEALTHY_INTERVAL_SECONDS,
            FailoverWatchdog.recommendedDelaySeconds(healthyStreak = 3, suspect = false)
        )
    }

    @Test
    fun passiveLivenessSentinelDetectsHalfOpenBlackholeOnlyAfterSustainedStall() {
        val sentinel = FailoverWatchdog.PassiveLivenessSentinel(stallWindowMs = 4500L, minConsecutiveStalls = 2)
        sentinel.recordInbound(512L, nowMs = 1_000L)
        sentinel.recordOutbound(2048L, nowMs = 2_000L)

        // Within window -> healthy
        assertEquals(FailoverWatchdog.SentinelVerdict.HEALTHY, sentinel.evaluate(nowMs = 4_000L))

        // First expired check -> still 1 consecutive, waits for 2nd confirmation
        sentinel.recordOutbound(512L, nowMs = 6_000L)
        assertEquals(FailoverWatchdog.SentinelVerdict.HEALTHY, sentinel.evaluate(nowMs = 6_500L))

        // Second expired check -> triggers STALLED
        assertEquals(FailoverWatchdog.SentinelVerdict.STALLED, sentinel.evaluate(nowMs = 7_500L))

        // Any inbound byte resets stall counter cleanly
        sentinel.recordInbound(128L, nowMs = 7_600L)
        assertEquals(FailoverWatchdog.SentinelVerdict.HEALTHY, sentinel.evaluate(nowMs = 7_700L))
    }

    @Test
    fun latencyTrackerAndEvaluator_autoSwitchOnlyAfterSustainedOver300msOrSevereSpike() {
        val tracker = FailoverWatchdog.LatencyHealthTracker(thresholdMs = 300L, requiredConsecutiveHigh = 2, severeSpikeMs = 900L)
        val ok1 = tracker.record(180L)
        assertFalse(ok1.shouldSwitch)
        assertEquals(0, ok1.highLatencyStreak)

        // Single mild blip above 300ms (EWMA still < 300ms) -> hysteresis avoids flapping
        val blip = tracker.record(330L)
        assertFalse(blip.shouldSwitch)
        assertEquals(1, blip.highLatencyStreak)

        // Second consecutive reading above 300ms -> triggers 300ms SLA switch
        val sustained = tracker.record(360L)
        assertTrue(sustained.shouldSwitch)
        assertEquals(2, sustained.highLatencyStreak)

        val eval = FailoverWatchdog.evaluateHealthAndLatency(
            internetOk = true,
            aiStudioOk = true,
            flowOk = false,
            latencyMs = 360L,
            highLatencyStreak = sustained.highLatencyStreak,
            ewmaLatencyMs = sustained.ewmaMs
        )
        assertEquals(FailoverWatchdog.Action.SWAP_DOH_OR_LAYER, eval.action)
        assertEquals(FailoverWatchdog.HealReason.LATENCY_EXCEEDED_300MS, eval.reason)
        assertFalse(eval.shouldEscalateBlackout)
    }

    @Test
    fun severeBlackoutDetection_escalatesToDnsBlackoutVpnAfterSustainedOutage() {
        val evalSmart = FailoverWatchdog.evaluateHealthAndLatency(
            internetOk = false,
            aiStudioOk = false,
            flowOk = false,
            latencyMs = -1L,
            consecutiveBlackoutTicks = 2,
            currentMode = TunnelConfig.Mode.SMART
        )
        assertEquals(FailoverWatchdog.Action.SWAP_DOH_OR_LAYER, evalSmart.action)
        assertEquals(FailoverWatchdog.HealReason.SEVERE_BLACKOUT, evalSmart.reason)
        assertTrue(evalSmart.shouldEscalateBlackout)

        // Already in DNS_BLACKOUT -> does not re-flag escalation unnecessarily
        assertFalse(
            FailoverWatchdog.shouldEscalateToDnsBlackout(
                internetOk = false,
                consecutiveBlackoutTicks = 3,
                currentMode = TunnelConfig.Mode.DNS_BLACKOUT
            )
        )
        assertEquals(
            FailoverWatchdog.CONTINUOUS_PROBE_INTERVAL_SECONDS,
            FailoverWatchdog.recommendedDelaySeconds(healthyStreak = 4, suspect = false, observedLatencyMs = 350L)
        )
    }
}
