package com.dastyab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM unit tests for [TunnelConfig] VLESS parsing, DNS/MTU validation,
 * split-tunnel package normalization, and routing mode lookup.
 */
class TunnelConfigTest {

    @Test
    fun parseVlessUri_fullLinkWithEncodedParamsAndLabel() {
        val raw =
            "vless://11111111-2222-3333-4444-555555555555@edge.example.com:8443" +
                "?security=tls&type=ws&sni=cdn.example.com&path=%2Fdastyab%2Fws#DastYab%20Edge"

        val parsed = TunnelConfig.parseVlessUri(raw)
        assertNotNull(parsed)
        assertEquals("11111111-2222-3333-4444-555555555555", parsed!!.uuid)
        assertEquals("edge.example.com", parsed.host)
        assertEquals(8443, parsed.port)
        assertEquals("cdn.example.com", parsed.sni)
        assertEquals("/dastyab/ws", parsed.path)
        assertEquals("tls", parsed.security)
        assertEquals("ws", parsed.transport)
        assertEquals("DastYab Edge", parsed.label)
        assertEquals("DastYab Edge · edge.example.com:8443 (ws/tls)", parsed.summary())
    }

    @Test
    fun parseVlessUri_defaultsPortSniPathTransportSecurity() {
        val raw = "vless://abcd-1234@worker.example.dev"
        val parsed = TunnelConfig.parseVlessUri(raw)
        assertNotNull(parsed)
        assertEquals(443, parsed!!.port)
        assertEquals("worker.example.dev", parsed.sni)
        assertEquals("/", parsed.path)
        assertEquals("tls", parsed.security)
        assertEquals("ws", parsed.transport)
        assertEquals("", parsed.label)
        assertEquals("worker.example.dev:443 (ws/tls)", parsed.summary())
    }

    @Test
    fun parseVlessUri_rejectsInvalidOrWrongScheme() {
        assertNull(TunnelConfig.parseVlessUri(null))
        assertNull(TunnelConfig.parseVlessUri(""))
        assertNull(TunnelConfig.parseVlessUri("   "))
        assertNull(TunnelConfig.parseVlessUri("https://example.com"))
        assertNull(TunnelConfig.parseVlessUri("vless://missing-uuid.example.com:443"))
        assertNull(TunnelConfig.parseVlessUri("vless://uuid@:443"))
    }

    @Test
    fun isValidIpv4_andSanitizeDns() {
        assertTrue(TunnelConfig.isValidIpv4("1.1.1.1"))
        assertTrue(TunnelConfig.isValidIpv4("8.8.4.4"))
        assertTrue(TunnelConfig.isValidIpv4("255.255.255.0"))
        assertFalse(TunnelConfig.isValidIpv4("256.1.1.1"))
        assertFalse(TunnelConfig.isValidIpv4("1.1.1"))
        assertFalse(TunnelConfig.isValidIpv4("dns.google"))
        assertFalse(TunnelConfig.isValidIpv4(""))

        assertEquals("9.9.9.9", TunnelConfig.sanitizeDns(" 9.9.9.9 ", TunnelConfig.DEFAULT_DNS_PRIMARY))
        assertEquals(
            TunnelConfig.DEFAULT_DNS_PRIMARY,
            TunnelConfig.sanitizeDns("invalid", TunnelConfig.DEFAULT_DNS_PRIMARY)
        )
        assertEquals(
            TunnelConfig.DEFAULT_DNS_SECONDARY,
            TunnelConfig.sanitizeDns(null, TunnelConfig.DEFAULT_DNS_SECONDARY)
        )
    }

    @Test
    fun clampMtu_enforcesSafeBounds() {
        assertEquals(TunnelConfig.MIN_MTU, TunnelConfig.clampMtu(500))
        assertEquals(1400, TunnelConfig.clampMtu(1400))
        assertEquals(TunnelConfig.MAX_MTU, TunnelConfig.clampMtu(9000))
    }

    @Test
    fun normalizePackages_trimsDedupesAndExcludesSelf() {
        val result = TunnelConfig.normalizePackages(
            listOf(
                " com.android.chrome ",
                "com.dastyab.app",
                "",
                "   ",
                "com.android.chrome",
                "org.mozilla.firefox"
            )
        )
        assertEquals(setOf("com.android.chrome", "org.mozilla.firefox"), result)
    }

    @Test
    fun modeFromName_handlesCaseAndFallback() {
        assertEquals(TunnelConfig.Mode.SMART, TunnelConfig.Mode.fromName(null))
        assertEquals(TunnelConfig.Mode.SMART, TunnelConfig.Mode.fromName("unknown"))
        assertEquals(TunnelConfig.Mode.GLOBAL, TunnelConfig.Mode.fromName("global"))
        assertEquals(TunnelConfig.Mode.SPLIT_INCLUDE, TunnelConfig.Mode.fromName("SPLIT_INCLUDE"))
        assertEquals(TunnelConfig.Mode.SPLIT_EXCLUDE, TunnelConfig.Mode.fromName("split_exclude"))
    }

    @Test
    fun googleAccessMode_andProxySanitizers() {
        assertEquals(TunnelConfig.GoogleAccessMode.AUTO_SMART, TunnelConfig.GoogleAccessMode.fromName(null))
        assertEquals(TunnelConfig.GoogleAccessMode.IN_APP_PROXY, TunnelConfig.GoogleAccessMode.fromName("in_app_proxy"))
        assertEquals(TunnelConfig.ProxyScheme.SOCKS5, TunnelConfig.ProxyScheme.fromName("socks5"))
        assertEquals(TunnelConfig.ProxyScheme.DIRECT, TunnelConfig.ProxyScheme.fromName("invalid"))

        assertEquals("127.0.0.1", TunnelConfig.sanitizeProxyHost("  "))
        assertEquals("127.0.0.1", TunnelConfig.sanitizeProxyHost("127.0.0.1:1080"))
        assertEquals("192.168.1.50", TunnelConfig.sanitizeProxyHost(" 192.168.1.50 "))

        assertEquals(10850, TunnelConfig.clampPort(80, 10850, min = TunnelConfig.MIN_LOCAL_PORT))
        assertEquals(10850, TunnelConfig.clampPort(70000, 10850, min = TunnelConfig.MIN_LOCAL_PORT))
        assertEquals(10809, TunnelConfig.clampPort(10809, 10850, min = TunnelConfig.MIN_LOCAL_PORT))
    }

    @Test
    fun parseProxyHop_andParseProxyChain_supportAuthTelegramAndDropSelfLoops() {
        val socks = TunnelConfig.parseProxyHop("socks5://user:p%40ss@198.51.100.10:1080")
        assertNotNull(socks)
        assertEquals(TunnelConfig.ProxyScheme.SOCKS5, socks!!.scheme)
        assertEquals("198.51.100.10", socks.host)
        assertEquals(1080, socks.port)
        assertEquals("user", socks.username)
        assertEquals("p@ss", socks.password)

        val tgSocks = TunnelConfig.parseProxyHop("tg://socks?server=9.9.9.9&port=443&user=u1&pass=p1")
        assertNotNull(tgSocks)
        assertEquals(TunnelConfig.ProxyScheme.SOCKS5, tgSocks!!.scheme)
        assertEquals("9.9.9.9", tgSocks.host)
        assertEquals(443, tgSocks.port)
        assertEquals("u1", tgSocks.username)
        assertEquals("p1", tgSocks.password)

        val chain = TunnelConfig.parseProxyChain(
            "http://127.0.0.1:10850 -> socks5://127.0.0.1:10808, http://198.51.100.2:8080 -> localhost:10850",
            selfBridgePort = 10850
        )
        assertEquals(2, chain.size)
        assertEquals(TunnelConfig.ProxyScheme.SOCKS5, chain[0].scheme)
        assertEquals(10808, chain[0].port)
        assertEquals(TunnelConfig.ProxyScheme.HTTP, chain[1].scheme)
        assertEquals("198.51.100.2", chain[1].host)

        val upstream = TunnelConfig.toLocalProxyUpstream(chain, selfBridgePort = 10850)
        assertTrue(upstream is LocalProxyServer.Upstream.Chain)
        assertEquals(2, (upstream as LocalProxyServer.Upstream.Chain).flattenedHops().size)
    }

    @Test
    fun effectiveUpstreamHops_separatesAiStudioAndFlowWithGlobalFallback() {
        val settings = TunnelConfig.Settings(
            upstreamProxyScheme = TunnelConfig.ProxyScheme.HTTP,
            upstreamProxyHost = "127.0.0.1",
            upstreamProxyPort = 10809,
            upstreamProxyChain = "http://127.0.0.1:10809",
            aiStudioProxyChain = "socks5://127.0.0.1:10808",
            flowProxyChain = "socks5://127.0.0.1:10808 -> http://198.51.100.5:3128"
        )
        val aiHops = TunnelConfig.effectiveUpstreamHops(settings, "aistudio", 10850)
        val flowHops = TunnelConfig.effectiveUpstreamHops(settings, "flow", 10850)
        val fallbackHops = TunnelConfig.effectiveUpstreamHops(settings.copy(aiStudioProxyChain = ""), "aistudio", 10850)

        assertEquals(1, aiHops.size)
        assertEquals(TunnelConfig.ProxyScheme.SOCKS5, aiHops[0].scheme)
        assertEquals(2, flowHops.size)
        assertEquals("198.51.100.5", flowHops[1].host)
        assertEquals(1, fallbackHops.size)
        assertEquals(10809, fallbackHops[0].port)
    }
}
