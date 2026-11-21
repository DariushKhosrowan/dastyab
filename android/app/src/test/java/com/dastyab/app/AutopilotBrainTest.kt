package com.dastyab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hermetic JVM unit tests for [AutopilotBrain] (Bandit, Circuit Breaker,
 * Hysteresis Guard, Happy Eyeballs v2 Staggered Racing, and TSV persistence).
 */
class AutopilotBrainTest {

    @Before
    fun setUp() {
        AutopilotBrain.resetInMemoryForTest()
    }

    @Test
    fun contextualIsolation_separatesCellAndWifiNetworks() {
        AutopilotBrain.recordOutcome("cell:43211", "vless-a", "vless", success = true, rttMs = 110.0)
        AutopilotBrain.recordOutcome("cell:43211", "vless-a", "vless", success = true, rttMs = 120.0)
        AutopilotBrain.recordOutcome("wifi:999", "vless-a", "vless", success = false, rttMs = null)
        AutopilotBrain.recordOutcome("wifi:999", "vless-a", "vless", success = false, rttMs = null)
        AutopilotBrain.recordOutcome("wifi:999", "vless-a", "vless", success = false, rttMs = null)

        val cand = AutopilotBrain.CandidateArm("vless-a", "vless")
        val scoreCell = AutopilotBrain.scoreArm("cell:43211", cand, AutopilotBrain.AutopilotIntent.AI_STUDIO)
        val scoreWifi = AutopilotBrain.scoreArm("wifi:999", cand, AutopilotBrain.AutopilotIntent.AI_STUDIO)

        assertTrue("Healthy cell route should stay positive", scoreCell > 0.5)
        assertTrue("Tripped Wi-Fi route should be quarantined (< 0)", scoreWifi < 0.0)
    }

    @Test
    fun circuitBreaker_quarantinesHalfOpensAndRecovers() {
        val now = 1_000_000L
        AutopilotBrain.recordOutcome("cell:432", "trojan-1", "trojan", false, null, nowMs = now)
        AutopilotBrain.recordOutcome("cell:432", "trojan-1", "trojan", false, null, nowMs = now + 10)
        val tripped = AutopilotBrain.recordOutcome("cell:432", "trojan-1", "trojan", false, null, nowMs = now + 20)

        assertEquals(AutopilotBrain.CircuitState.OPEN, tripped.effectiveCircuit(now + 25))
        assertEquals(AutopilotBrain.CircuitState.OPEN, tripped.effectiveCircuit(now + 10_000))
        assertEquals(AutopilotBrain.CircuitState.HALF_OPEN, tripped.effectiveCircuit(now + 35_000))

        val recovered = AutopilotBrain.recordOutcome(
            "cell:432",
            "trojan-1",
            "trojan",
            success = true,
            rttMs = 140.0,
            nowMs = now + 36_000
        )
        assertEquals(AutopilotBrain.CircuitState.CLOSED, recovered.circuitState)
        assertEquals(0, recovered.consecutiveFailures)
    }

    @Test
    fun hysteresisGuard_preventsFlappingBetweenSimilarRoutes() {
        assertFalse(
            AutopilotBrain.shouldPreemptActive(
                activeHealthy = true,
                activeScore = 0.80,
                challengerScore = 0.92,
                challengerLeadStreak = 3
            )
        )
        assertFalse(
            AutopilotBrain.shouldPreemptActive(
                activeHealthy = true,
                activeScore = 0.80,
                challengerScore = 1.15,
                challengerLeadStreak = 1
            )
        )
        assertTrue(
            AutopilotBrain.shouldPreemptActive(
                activeHealthy = true,
                activeScore = 0.80,
                challengerScore = 1.10,
                challengerLeadStreak = 2
            )
        )
        assertTrue(
            AutopilotBrain.shouldPreemptActive(
                activeHealthy = false,
                activeScore = 0.90,
                challengerScore = 0.50,
                challengerLeadStreak = 0
            )
        )
    }

    @Test
    fun selectDiverseSlate_prioritizesDistinctProtocolFamilies() {
        val candidates = listOf(
            AutopilotBrain.CandidateArm("v1", "vless", basePriority = 0.9),
            AutopilotBrain.CandidateArm("v2", "vless", basePriority = 0.88),
            AutopilotBrain.CandidateArm("wg1", "wireguard", basePriority = 0.70),
            AutopilotBrain.CandidateArm("h1", "hy2", basePriority = 0.65),
            AutopilotBrain.CandidateArm("ov1", "openvpn", basePriority = 0.60)
        )
        val slate = AutopilotBrain.selectDiverseSlate(
            "wifi:home",
            candidates,
            AutopilotBrain.AutopilotIntent.BLACKOUT_SURVIVAL,
            maxSlots = 4
        )
        assertEquals(4, slate.size)
        val families = slate.map { it.candidate.protocolFamily }.toSet()
        assertTrue("Expected >= 3 distinct protocol families in Happy Eyeballs slate", families.size >= 3)
    }

    @Test
    fun raceStaggered_shortCircuitsFastPrimaryWithoutLaunchingBackups() {
        val slate = listOf(
            AutopilotBrain.CandidateArm("primary", "vless"),
            AutopilotBrain.CandidateArm("backup1", "trojan"),
            AutopilotBrain.CandidateArm("backup2", "wireguard")
        )
        val attempts = AtomicInteger(0)
        val result = AutopilotBrain.raceStaggered(
            slate = slate,
            staggerScheduleMs = listOf(0L, 450L, 900L),
            probeTimeoutMs = 1500L
        ) { arm ->
            attempts.incrementAndGet()
            if (arm.armId == "primary") "ok-primary" else null
        }
        assertNotNull(result)
        assertEquals(0, result!!.winnerIndex)
        assertEquals("ok-primary", result.payload)
        assertEquals(1, attempts.get())
        assertEquals(2, result.standbyArms.size)
    }

    @Test
    fun raceStaggered_hedgesToBackupWhenPrimaryStalls() {
        val slate = listOf(
            AutopilotBrain.CandidateArm("primary", "vless"),
            AutopilotBrain.CandidateArm("backup1", "hy2")
        )
        val result = AutopilotBrain.raceStaggered(
            slate = slate,
            staggerScheduleMs = listOf(0L, 80L),
            probeTimeoutMs = 1500L
        ) { arm ->
            if (arm.armId == "primary") {
                try { Thread.sleep(800L) } catch (_: InterruptedException) {}
                null
            } else {
                "ok-backup"
            }
        }
        assertNotNull(result)
        assertEquals(1, result!!.winnerIndex)
        assertEquals("ok-backup", result.payload)
    }

    @Test
    fun tsvSerialization_roundtripsArmsAndSeedsCleanly() {
        val stats = listOf(
            AutopilotBrain.ArmStats(
                contextKey = "cell:43211",
                armId = "vless:104.16.1.2:443",
                protocolFamily = "vless",
                ewmaMs = 145.5,
                jitterMs = 18.2,
                discountedWins = 4.2,
                discountedTrials = 5.0,
                consecutiveFailures = 0,
                consecutiveWins = 3,
                circuitState = AutopilotBrain.CircuitState.CLOSED,
                quarantineUntilMs = 0L,
                tripCount = 1,
                geoMask = AutopilotBrain.GEO_CLEAR_WEB or AutopilotBrain.GEO_CLEAR_AISTUDIO,
                lastUpdatedMs = 123456789L
            )
        )
        val raw = AutopilotBrain.serializeArms(stats)
        val decoded = AutopilotBrain.deserializeArms(raw)
        assertEquals(1, decoded.size)
        assertEquals(stats[0], decoded[0])

        val seeds = mapOf("cell:43211" to listOf("104.16.1.2", "172.64.2.3"))
        val rawSeeds = AutopilotBrain.serializeSeeds(seeds)
        assertEquals(seeds, AutopilotBrain.deserializeSeeds(rawSeeds))
    }

    @Test
    fun scorePredictivePath_stronglyPrefersSub300msOverSlowPath() {
        AutopilotBrain.recordOutcome("cell:test", "fast-arm", "vless", success = true, rttMs = 140.0)
        AutopilotBrain.recordOutcome("cell:test", "slow-arm", "trojan", success = true, rttMs = 680.0)

        val fast = AutopilotBrain.CandidateArm("fast-arm", "vless")
        val slow = AutopilotBrain.CandidateArm("slow-arm", "trojan")
        val fastScore = AutopilotBrain.scorePredictivePath("cell:test", fast, AutopilotBrain.AutopilotIntent.BALANCED_WEB)
        val slowScore = AutopilotBrain.scorePredictivePath("cell:test", slow, AutopilotBrain.AutopilotIntent.BALANCED_WEB)

        assertTrue("Sub-300ms path ($fastScore) must outscore slow path ($slowScore)", fastScore > slowScore + 0.15)
        val slate = AutopilotBrain.selectPredictiveSlate(
            "cell:test", listOf(slow, fast), AutopilotBrain.AutopilotIntent.BALANCED_WEB, maxSlots = 2
        )
        assertEquals("fast-arm", slate.first().candidate.armId)
    }

    @Test
    fun raceMultiPathPredictive_allowsFastSub300msArmToOvertakeSlowFirstResponder() {
        val slate = listOf(
            AutopilotBrain.CandidateArm("slow-first", "trojan"),
            AutopilotBrain.CandidateArm("fast-sub300", "vless")
        )
        val result = AutopilotBrain.raceMultiPathPredictive(
            slate = slate,
            latencyThresholdMs = 300L,
            graceWindowMs = 200L,
            probeTimeoutMs = 1500L,
            measureLatencyMs = { rtt -> rtt }
        ) { arm ->
            if (arm.armId == "slow-first") {
                try { Thread.sleep(15L) } catch (_: InterruptedException) {}
                480L
            } else {
                try { Thread.sleep(60L) } catch (_: InterruptedException) {}
                95L
            }
        }
        assertNotNull(result)
        assertEquals("fast-sub300", result!!.winnerArm.armId)
        assertEquals(95L, result.payload)
    }
}
