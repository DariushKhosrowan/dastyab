package com.dastyab.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM unit tests for [FlowWarriorEngine] (independent Brutal Eagle Google Flow algorithm).
 */
class FlowWarriorEngineTest {

    private fun report(
        cc: String,
        ip: String = "198.51.100.10",
        isHosting: Boolean = false,
        reachable: Boolean = true,
        unsupported: Boolean = false,
        appShellOk: Boolean = true,
        accountsOk: Boolean = true,
        latencyMs: Long = 180L
    ): GeoBypassEngine.AccessProbeReport {
        val egress = GeoBypassEngine.EgressInfo(ip, cc, cc, isHosting, "test")
        val flowProbe = GeoBypassEngine.ServiceProbeResult(
            reachable = reachable,
            httpCode = if (reachable) 200 else 403,
            latencyMs = latencyMs,
            unsupportedRegion = unsupported,
            finalLocation = "https://flow.google.com/",
            detail = "ok",
            appShellOk = appShellOk
        )
        val okProbe = flowProbe.copy(appShellOk = true, unsupportedRegion = false, reachable = true)
        return GeoBypassEngine.AccessProbeReport(
            routeLabel = "WarriorRoute($cc)",
            egress = egress,
            flowCountrySupported = GeoBypassEngine.isFlowCountrySupported(cc),
            aiStudio = okProbe,
            flow = flowProbe,
            headline = "ok",
            recommendation = "ok",
            accountsReachable = accountsOk,
            aiStudioCountrySupported = GeoBypassEngine.isAiStudioCountrySupported(cc)
        )
    }

    @Test
    fun isWarriorConfirmed_rejectsUnsupportedMissingShellOrBlacklistedIp() {
        val good = report(cc = "US", ip = "203.0.113.5", isHosting = false)
        assertTrue(FlowWarriorEngine.isWarriorConfirmed(good))
        assertFalse(FlowWarriorEngine.isWarriorConfirmed(good, blacklistedIps = setOf("203.0.113.5")))

        val noShell = report(cc = "US", appShellOk = false)
        assertFalse(FlowWarriorEngine.isWarriorConfirmed(noShell))

        val unsupportedCountry = report(cc = "IR")
        assertFalse(FlowWarriorEngine.isWarriorConfirmed(unsupportedCountry))

        val blockedAccounts = report(cc = "GB", accountsOk = false)
        assertFalse(FlowWarriorEngine.isWarriorConfirmed(blockedAccounts))
    }

    @Test
    fun scoreWarriorCandidate_prefersTier1ResidentialOverDatacenter() {
        val usClean = report(cc = "US", ip = "198.51.100.1", isHosting = false, latencyMs = 200L)
        val usDatacenter = report(cc = "US", ip = "198.51.100.2", isHosting = true, latencyMs = 120L)
        val tier2Clean = report(cc = "RO", ip = "198.51.100.3", isHosting = false, latencyMs = 180L)

        val sUsClean = FlowWarriorEngine.scoreWarriorCandidate(usClean)
        val sUsDc = FlowWarriorEngine.scoreWarriorCandidate(usDatacenter)
        val sRoClean = FlowWarriorEngine.scoreWarriorCandidate(tier2Clean)

        assertTrue("Clean US residential must outscore datacenter US", sUsClean > sUsDc)
        assertTrue("Tier-1 US residential must outscore Tier-2 RO", sUsClean > sRoClean)
        assertTrue("Blacklisted IP must receive extreme penalty", FlowWarriorEngine.scoreWarriorCandidate(usClean, setOf("198.51.100.1")) < -500_000L)
    }

    @Test
    fun warriorNamespace_isCompletelyIsolatedFromDefaultNetworkContext() {
        val wKey = FlowWarriorEngine.warriorContextKey("cell:43211")
        assertTrue(wKey.startsWith("flow_warrior:"))
        assertFalse(wKey == "cell:43211")
    }
}
