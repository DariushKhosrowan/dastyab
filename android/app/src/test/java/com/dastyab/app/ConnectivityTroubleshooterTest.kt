package com.dastyab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM unit tests for [ConnectivityTroubleshooter] censorship classification
 * and tailored rescue-plan generation.
 */
class ConnectivityTroubleshooterTest {

    @Test
    fun diagnose_telegramAndGoogleBlockedButGithubReachable() {
        val report = ConnectivityTroubleshooter.diagnose(
            ConnectivityTroubleshooter.ProbeSnapshot(
                hasNetwork = true,
                vpnActive = false,
                cleanDnsMs = 35,
                dohMs = 60,
                githubDirectMs = 120,
                githubMirrorMs = 80,
                telegramMs = -1,
                aiStudioMs = -1,
                flowMs = -1,
                installedFallbackCount = 0
            )
        )
        assertEquals(ConnectivityTroubleshooter.Diagnosis.TELEGRAM_BLOCKED_GITHUB_OK, report.diagnosis)
        assertEquals(
            ConnectivityTroubleshooter.RescueActionId.HARVEST_CONFIGS,
            report.rescueSteps.first().actionId
        )
    }

    @Test
    fun diagnose_githubAndTelegramBlockedButCleanDnsWorks() {
        val report = ConnectivityTroubleshooter.diagnose(
            ConnectivityTroubleshooter.ProbeSnapshot(
                hasNetwork = true,
                vpnActive = false,
                cleanDnsMs = 45,
                dohMs = -1,
                githubDirectMs = -1,
                githubMirrorMs = -1,
                telegramMs = -1,
                aiStudioMs = -1,
                flowMs = -1,
                installedFallbackCount = 2
            )
        )
        assertEquals(ConnectivityTroubleshooter.Diagnosis.GITHUB_AND_TELEGRAM_BLOCKED, report.diagnosis)
        assertTrue(report.rescueSteps.any { it.actionId == ConnectivityTroubleshooter.RescueActionId.ENABLE_DNS_BLACKOUT_VPN })
        assertTrue(report.rescueSteps.any { it.actionId == ConnectivityTroubleshooter.RescueActionId.LAUNCH_FALLBACK_VPN })
        assertTrue(report.rescueSteps.any { it.actionId == ConnectivityTroubleshooter.RescueActionId.SET_BOOTSTRAP_CONFIG })
    }

    @Test
    fun diagnose_severeBlackoutRecommendsDnsBlackoutAndBootstrap() {
        val report = ConnectivityTroubleshooter.diagnose(
            ConnectivityTroubleshooter.ProbeSnapshot(
                hasNetwork = true,
                vpnActive = false,
                cleanDnsMs = -1,
                dohMs = -1,
                githubDirectMs = -1,
                githubMirrorMs = -1,
                telegramMs = -1,
                aiStudioMs = -1,
                flowMs = -1,
                installedFallbackCount = 0
            )
        )
        assertEquals(ConnectivityTroubleshooter.Diagnosis.DNS_POISONED_OR_BLACKOUT, report.diagnosis)
        assertEquals(
            ConnectivityTroubleshooter.RescueActionId.ENABLE_DNS_BLACKOUT_VPN,
            report.rescueSteps.first().actionId
        )
    }

    @Test
    fun diagnose_noNetworkReturnsNoNetwork() {
        val report = ConnectivityTroubleshooter.diagnose(
            ConnectivityTroubleshooter.ProbeSnapshot(
                hasNetwork = false,
                vpnActive = false,
                cleanDnsMs = -1,
                dohMs = -1,
                githubDirectMs = -1,
                githubMirrorMs = -1,
                telegramMs = -1,
                aiStudioMs = -1,
                flowMs = -1,
                installedFallbackCount = 0
            )
        )
        assertEquals(ConnectivityTroubleshooter.Diagnosis.NO_NETWORK, report.diagnosis)
    }
}
