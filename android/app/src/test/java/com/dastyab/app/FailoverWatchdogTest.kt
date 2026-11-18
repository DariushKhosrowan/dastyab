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
}
