package com.dastyab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM unit tests for [ConfigHarvester] URI extraction, HTML unescaping,
 * jsDelivr mirror conversion, and protocol prioritization.
 */
class ConfigHarvesterTest {

    @Test
    fun extractProxyConfigs_handlesTelegramHtmlAndPrioritizesVless() {
        val html = """
            <div class="tgme_widget_message_text">
                trojan://secretpass@tr.example.com:443?sni=tr.example.com#TrojanNode<br/>
                vless://11111111-2222-3333-4444-555555555555@edge.example.com:443?security=tls&amp;type=ws&amp;sni=cdn.example.com#VlessNode
            </div>
        """.trimIndent()

        val configs = ConfigHarvester.extractProxyConfigs(html)
        assertEquals(2, configs.size)
        assertTrue("VLESS should be prioritized first", configs[0].startsWith("vless://"))
        assertTrue("HTML &amp; should be unescaped", configs[0].contains("&type=ws&sni=cdn.example.com"))
        assertTrue(configs[1].startsWith("trojan://"))
    }

    @Test
    fun toJsDelivrMirror_convertsRawGithubUrls() {
        val raw = "https://raw.githubusercontent.com/WhiteDNS/WhiteVPN/main/configs/vless.txt"
        assertEquals(
            "https://cdn.jsdelivr.net/gh/WhiteDNS/WhiteVPN@main/configs/vless.txt",
            ConfigHarvester.toJsDelivrMirror(raw)
        )
        assertNull(ConfigHarvester.toJsDelivrMirror("https://example.com/list.txt"))
    }

    @Test
    fun knownFallbackTools_includesRequestedAntiCensorshipProjects() {
        val urls = ConfigHarvester.KNOWN_FALLBACK_TOOLS.map { it.githubUrl }
        assertTrue(urls.contains("https://github.com/WhiteDNS/WhiteDNS-Android"))
        assertTrue(urls.contains("https://github.com/WhiteDNS/WhiteVPN"))
        assertTrue(urls.contains("https://github.com/WhiteDNS/IP-Range-Scout-Android"))
        assertTrue(urls.contains("https://github.com/anonvector/SlipNet"))
        assertTrue(urls.contains("https://github.com/shirokhorshid"))
        assertTrue(urls.contains("https://github.com/getlantern"))
        assertTrue(urls.contains("https://github.com/thetorproject"))
    }
}
