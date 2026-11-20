package com.dastyab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM unit tests for [TerminalCommandRouter] covering prompt formatting,
 * command classification, node/route JSON serialization, and IPC event parsing.
 */
class TerminalCommandRouterTest {

    @Test
    fun normalizeAndClassify_handlesPromptsAndBuiltins() {
        assertEquals("", TerminalCommandRouter.normalizeInput("   "))
        assertEquals("dastyab flow best", TerminalCommandRouter.normalizeInput("  $ dastyab flow best "))
        assertEquals("python3 --version", TerminalCommandRouter.normalizeInput("# python3 --version"))

        assertEquals(TerminalCommandRouter.Action.EMPTY, TerminalCommandRouter.classify("  "))
        assertEquals(TerminalCommandRouter.Action.CLEAR, TerminalCommandRouter.classify("clear"))
        assertEquals(TerminalCommandRouter.Action.CLEAR, TerminalCommandRouter.classify("$ cls"))
        assertEquals(TerminalCommandRouter.Action.PYTHON_SHELL, TerminalCommandRouter.classify("dastyab flow countries"))
        assertEquals(TerminalCommandRouter.Action.PYTHON_SHELL, TerminalCommandRouter.classify("python3 -c 'print(42)'"))
    }

    @Test
    fun formatPrompt_defaultsToHomeAndDisplaysSubdirs() {
        assertEquals("dastyab@android:~$ ", TerminalCommandRouter.formatPrompt(null))
        assertEquals("dastyab@android:~$ ", TerminalCommandRouter.formatPrompt("~"))
        assertEquals("dastyab@android:~/.dastyab$ ", TerminalCommandRouter.formatPrompt("~/.dastyab"))
    }

    @Test
    fun buildVlessSyncJson_deduplicatesAndFiltersNonVless() {
        val u1 = "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:443?type=ws#A"
        val u2 = "vless://22222222-2222-2222-2222-222222222222@5.6.7.8:443?type=tcp#B"
        val json = TerminalCommandRouter.buildVlessSyncJson(
            activeUri = u1,
            layerUri = u1,
            harvested = listOf("http://invalid", u2, u1)
        )
        assertTrue(json.contains("1.2.3.4:443"))
        assertTrue(json.contains("5.6.7.8:443"))
        assertFalse(json.contains("http://invalid"))
        assertTrue(json.startsWith("[") && json.endsWith("]"))
    }

    @Test
    fun buildRouteSyncJson_serializesWithoutAndroidJsonStubs() {
        val json = TerminalCommandRouter.buildRouteSyncJson(
            externalVpnActive = false,
            dastyabVpnActive = true,
            bridgePort = 10850,
            upstreamScheme = TunnelConfig.ProxyScheme.SOCKS5,
            upstreamHost = "127.0.0.1",
            upstreamPort = 10808,
            upstreamChain = "http://127.0.0.1:10809",
            aiStudioChain = "socks5://127.0.0.1:10808",
            flowChain = "socks5://127.0.0.1:10808 -> http://198.51.100.8:8080",
            harvestedProxyHops = listOf(
                TunnelConfig.ProxyHop(TunnelConfig.ProxyScheme.SOCKS5, "203.0.113.5", 1080, "u", "p")
            )
        )
        assertTrue(json.contains("\"external_vpn_active\":false"))
        assertTrue(json.contains("\"dastyab_vpn_active\":true"))
        assertTrue(json.contains("\"bridge_port\":10850"))
        assertTrue(json.contains("\"upstream_scheme\":\"socks5\""))
        assertTrue(json.contains("\"upstream_host\":\"127.0.0.1\""))
        assertTrue(json.contains("\"upstream_port\":10808"))
        assertTrue(json.contains("\"upstream_chain\":[{\"scheme\":\"http\",\"host\":\"127.0.0.1\",\"port\":10809"))
        assertTrue(json.contains("\"aistudio_chain\":[{\"scheme\":\"socks5\",\"host\":\"127.0.0.1\",\"port\":10808"))
        assertTrue(json.contains("\"flow_chain\":[{\"scheme\":\"socks5\",\"host\":\"127.0.0.1\",\"port\":10808"))
        assertTrue(json.contains("\"host\":\"198.51.100.8\",\"port\":8080"))
        assertTrue(json.contains("\"proxy_hops\":[{\"scheme\":\"socks5\",\"host\":\"203.0.113.5\",\"port\":1080,\"username\":\"u\",\"password\":\"p\"}]"))
    }

    @Test
    fun parseCliArgsJson_parsesEscapedArgsOnPlainJvm() {
        val parsed = TerminalCommandRouter.parseCliArgsJson("""["scan","--preset","standard","\"quoted\""]""")
        assertEquals(listOf("scan", "--preset", "standard", "\"quoted\""), parsed)
        assertEquals(emptyList<String>(), TerminalCommandRouter.parseCliArgsJson("[]"))
    }

    @Test
    fun parseEventLine_extractsStructuredAndroidEvents() {
        assertNull(TerminalCommandRouter.parseEventLine("[flow] normal output"))
        val ev = TerminalCommandRouter.parseEventLine(
            """[DASTYAB_EVENT] OPEN_BROWSER {"url":"https://flow.google.com/","proxy_port":10860}"""
        )
        assertNotNull(ev)
        assertEquals("OPEN_BROWSER", ev!!.kind)
        assertTrue(ev.payloadJson.contains("flow.google.com"))
    }

    @Test
    fun buildPipInstallCommand_sanitizesAndFormatsPackages() {
        assertNull(TerminalCommandRouter.buildPipInstallCommand("   "))
        assertNull(TerminalCommandRouter.buildPipInstallCommand("pkg; rm -rf /"))
        assertEquals(
            "pip install requests>=2.28 pyyaml PySocks",
            TerminalCommandRouter.buildPipInstallCommand("requests>=2.28, pyyaml  PySocks")
        )
        assertTrue(
            TerminalCommandRouter.LINUX_PYTHON_PRESETS.any { it.command == "pip install --recommended" }
        )
    }
}
