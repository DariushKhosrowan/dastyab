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

    @Test
    fun endpointCheckFromAccessProbe_neverReportsReachableWhenGeoRestricted() {
        val okAi = GeoBypassEngine.ServiceProbeResult(true, 200, 80, false, "https://aistudio.google.com/", "ok", appShellOk = true)
        val okFlow = GeoBypassEngine.ServiceProbeResult(true, 200, 95, false, "https://flow.google.com/", "ok", appShellOk = true)
        val deEgress = GeoBypassEngine.EgressInfo("185.10.10.10", "DE", "Germany", false, "ipwho.is")
        val irEgress = GeoBypassEngine.EgressInfo("5.5.5.5", "IR", "Iran", false, "ipwho.is")

        // 1. Unknown/null egress with HTTP 200 -> GEO_RESTRICTED
        val nullEgressReport = GeoBypassEngine.AccessProbeReport(
            routeLabel = "Direct",
            egress = null,
            flowCountrySupported = false,
            aiStudio = okAi,
            flow = okFlow,
            headline = "Unknown",
            recommendation = "",
            accountsReachable = true
        )
        assertEquals(
            ReachabilityVerifier.Status.GEO_RESTRICTED,
            ReachabilityVerifier.endpointCheckFromAccessProbe("AI Studio", ReachabilityVerifier.URL_AISTUDIO, nullEgressReport, GeoBypassEngine.TargetService.AI_STUDIO).status
        )
        assertEquals(
            ReachabilityVerifier.Status.GEO_RESTRICTED,
            ReachabilityVerifier.endpointCheckFromAccessProbe("Google Flow", ReachabilityVerifier.URL_FLOW, nullEgressReport, GeoBypassEngine.TargetService.GOOGLE_FLOW).status
        )

        // 2. Blocked country (IR) even when HTTP 200 -> GEO_RESTRICTED
        val irReport = nullEgressReport.copy(
            egress = irEgress,
            aiStudioCountrySupported = false,
            flowCountrySupported = false
        )
        val irAiCheck = ReachabilityVerifier.endpointCheckFromAccessProbe(
            "AI Studio", ReachabilityVerifier.URL_AISTUDIO, irReport, GeoBypassEngine.TargetService.AI_STUDIO
        )
        assertEquals(ReachabilityVerifier.Status.GEO_RESTRICTED, irAiCheck.status)
        assertFalse(irAiCheck.isOk)

        // 3. Flow missing Angular shell -> GEO_RESTRICTED
        val noShellReport = GeoBypassEngine.AccessProbeReport(
            routeLabel = "Proxy",
            egress = deEgress,
            flowCountrySupported = true,
            aiStudio = okAi,
            flow = okFlow.copy(appShellOk = false),
            headline = "Missing shell",
            recommendation = "",
            accountsReachable = true
        )
        assertEquals(
            ReachabilityVerifier.Status.GEO_RESTRICTED,
            ReachabilityVerifier.endpointCheckFromAccessProbe("Google Flow", ReachabilityVerifier.URL_FLOW, noShellReport, GeoBypassEngine.TargetService.GOOGLE_FLOW).status
        )

        // 4. accounts.google.com blocked -> HTTP_RESTRICTED
        val noAccounts = noShellReport.copy(flow = okFlow, accountsReachable = false)
        assertEquals(
            ReachabilityVerifier.Status.HTTP_RESTRICTED,
            ReachabilityVerifier.endpointCheckFromAccessProbe("AI Studio", ReachabilityVerifier.URL_AISTUDIO, noAccounts, GeoBypassEngine.TargetService.AI_STUDIO).status
        )

        // 5. Full clean clearance -> REACHABLE
        val clean = noAccounts.copy(accountsReachable = true)
        val aiOkCheck = ReachabilityVerifier.endpointCheckFromAccessProbe("AI Studio", ReachabilityVerifier.URL_AISTUDIO, clean, GeoBypassEngine.TargetService.AI_STUDIO)
        val flowOkCheck = ReachabilityVerifier.endpointCheckFromAccessProbe("Google Flow", ReachabilityVerifier.URL_FLOW, clean, GeoBypassEngine.TargetService.GOOGLE_FLOW)
        assertEquals(ReachabilityVerifier.Status.REACHABLE, aiOkCheck.status)
        assertEquals(ReachabilityVerifier.Status.REACHABLE, flowOkCheck.status)
        assertTrue(aiOkCheck.isOk && flowOkCheck.isOk)
    }
}
