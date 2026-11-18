package com.dastyab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainDnsRulesTest {

    @Test
    fun normalizeDomainStripsSchemePathPortAndWildcard() {
        assertEquals("digikala.com", DomainDnsRules.normalizeDomain("https://Digikala.COM:443/shop"))
        assertEquals("shaparak.ir", DomainDnsRules.normalizeDomain("*.shaparak.ir."))
        assertNull(DomainDnsRules.normalizeDomain("   "))
        assertNull(DomainDnsRules.normalizeDomain("localhost"))
    }

    @Test
    fun iranBypassMatchesIrTldAndCuratedLocalServices() {
        val rules = DomainDnsRules.Rules(iranBypassEnabled = true)
        assertEquals(DomainDnsRules.Decision.IRAN_BYPASS, DomainDnsRules.evaluate("bankmellat.ir", rules))
        assertEquals(DomainDnsRules.Decision.IRAN_BYPASS, DomainDnsRules.evaluate("api.shaparak.ir", rules))
        assertEquals(DomainDnsRules.Decision.IRAN_BYPASS, DomainDnsRules.evaluate("shop.digikala.com", rules))
        assertTrue(DomainDnsRules.shouldBypassToLocalDns("snapp.cab", rules))

        // International Google AI targets stay on encrypted DoH
        assertEquals(DomainDnsRules.Decision.DEFAULT_DOH, DomainDnsRules.evaluate("aistudio.google.com", rules))
        assertFalse(DomainDnsRules.shouldBypassToLocalDns("flow.google.com", rules))
    }

    @Test
    fun forceDohOverridesIranBypassAndCustomBypass() {
        val rules = DomainDnsRules.Rules(
            iranBypassEnabled = true,
            customBypassDomains = setOf("example.org"),
            customForceDohDomains = setOf("shaparak.ir", "example.org")
        )
        assertEquals(DomainDnsRules.Decision.FORCE_DOH, DomainDnsRules.evaluate("pay.shaparak.ir", rules))
        assertFalse(DomainDnsRules.shouldBypassToLocalDns("sub.example.org", rules))
    }

    @Test
    fun disablingIranBypassKeepsCustomBypassWorking() {
        val rules = DomainDnsRules.Rules(
            iranBypassEnabled = false,
            customBypassDomains = setOf("mybank.local")
        )
        assertEquals(DomainDnsRules.Decision.DEFAULT_DOH, DomainDnsRules.evaluate("digikala.com", rules))
        assertEquals(DomainDnsRules.Decision.CUSTOM_BYPASS, DomainDnsRules.evaluate("app.mybank.local", rules))
        assertTrue(DomainDnsRules.shouldBypassToLocalDns("mybank.local", rules))
    }
}
