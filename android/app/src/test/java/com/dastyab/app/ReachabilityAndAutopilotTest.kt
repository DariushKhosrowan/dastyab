package com.dastyab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM unit tests for [ReachabilityVerifier] HTTP status classification
 * and [NewbieAutopilot] outcome decision engine.
 */
class ReachabilityAndAutopilotTest {

    @Test
    fun classifyHttpCode_distinguishesReachableFromRestricted() {
        assertEquals(ReachabilityVerifier.Status.REACHABLE, ReachabilityVerifier.classifyHttpCode(200))
        assertEquals(ReachabilityVerifier.Status.REACHABLE, ReachabilityVerifier.classifyHttpCode(204))
        assertEquals(ReachabilityVerifier.Status.REACHABLE, ReachabilityVerifier.classifyHttpCode(302))
        assertEquals(ReachabilityVerifier.Status.HTTP_RESTRICTED, ReachabilityVerifier.classifyHttpCode(403))
        assertEquals(ReachabilityVerifier.Status.HTTP_RESTRICTED, ReachabilityVerifier.classifyHttpCode(451))
        assertEquals(ReachabilityVerifier.Status.HTTP_RESTRICTED, ReachabilityVerifier.classifyHttpCode(503))
    }

    @Test
    fun decideOutcome_handlesAllNewbieAutopilotScenarios() {
        val okCheck = ReachabilityVerifier.EndpointCheck(
            "OK", "https://example.com", ReachabilityVerifier.Status.REACHABLE, 200, 42, "142.250.1.1", "ok"
        )
        val blockedCheck = ReachabilityVerifier.EndpointCheck(
            "Blocked", "https://example.com", ReachabilityVerifier.Status.TLS_OR_IP_BLOCKED, -1, -1, "142.250.1.1", "blocked"
        )

        val allOk = ReachabilityVerifier.FullVerification(okCheck, okCheck, okCheck)
        assertTrue(allOk.allReady)
        assertEquals(
            NewbieAutopilot.Outcome.READY_WITH_VPN,
            NewbieAutopilot.decideOutcome(allOk, vpnConnected = true, vpnConsentNeeded = false)
        )
        assertEquals(
            NewbieAutopilot.Outcome.READY_DIRECT,
            NewbieAutopilot.decideOutcome(allOk, vpnConnected = false, vpnConsentNeeded = false)
        )

        val googleBlockedWebOk = ReachabilityVerifier.FullVerification(okCheck, blockedCheck, blockedCheck)
        assertFalse(googleBlockedWebOk.anyGoogleReady)
        assertEquals(
            NewbieAutopilot.Outcome.NEEDS_VPN_CONSENT,
            NewbieAutopilot.decideOutcome(googleBlockedWebOk, vpnConnected = false, vpnConsentNeeded = true)
        )
        assertEquals(
            NewbieAutopilot.Outcome.NEEDS_FULL_PROXY_APP,
            NewbieAutopilot.decideOutcome(googleBlockedWebOk, vpnConnected = true, vpnConsentNeeded = false)
        )

        val allOffline = ReachabilityVerifier.FullVerification(blockedCheck, blockedCheck, blockedCheck)
        assertEquals(
            NewbieAutopilot.Outcome.OFFLINE_OR_BLACKOUT,
            NewbieAutopilot.decideOutcome(allOffline, vpnConnected = false, vpnConsentNeeded = false)
        )
    }

    @Test
    fun decideExternalAutopilotPlan_handlesAllExternalVpnScenarios() {
        assertEquals(
            NewbieAutopilot.ExternalRoutePlan.USE_EXTERNAL_VPN,
            NewbieAutopilot.decideExternalAutopilotPlan(
                externalVpnActive = true,
                listeningProxyPort = 10808,
                dastyabVpnActive = false,
                targetReachable = true
            )
        )
        assertEquals(
            NewbieAutopilot.ExternalRoutePlan.USE_DETECTED_LOCAL_PROXY,
            NewbieAutopilot.decideExternalAutopilotPlan(
                externalVpnActive = false,
                listeningProxyPort = 7890,
                dastyabVpnActive = false,
                targetReachable = true
            )
        )
        assertEquals(
            NewbieAutopilot.ExternalRoutePlan.USE_DASTYAB_VPN,
            NewbieAutopilot.decideExternalAutopilotPlan(
                externalVpnActive = false,
                listeningProxyPort = null,
                dastyabVpnActive = true,
                targetReachable = true
            )
        )
        assertEquals(
            NewbieAutopilot.ExternalRoutePlan.USE_EXTERNAL_VPN,
            NewbieAutopilot.decideExternalAutopilotPlan(
                externalVpnActive = false,
                listeningProxyPort = null,
                dastyabVpnActive = false,
                targetReachable = true
            )
        )
        assertEquals(
            NewbieAutopilot.ExternalRoutePlan.NEEDS_EXTERNAL_VPN_CONNECT,
            NewbieAutopilot.decideExternalAutopilotPlan(
                externalVpnActive = false,
                listeningProxyPort = null,
                dastyabVpnActive = false,
                targetReachable = false
            )
        )
    }
}
