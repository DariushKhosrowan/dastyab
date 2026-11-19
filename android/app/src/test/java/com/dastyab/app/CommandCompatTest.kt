package com.dastyab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM unit tests for [CommandCompat] routing verdicts.
 */
class CommandCompatTest {

    @Test
    fun aistudioCommandRoutesToNativeVpnBrowser() {
        val verdict = CommandCompat.check(listOf("aistudio"))
        assertTrue(verdict is CommandCompat.Verdict.OpenAistudio)
        assertEquals(CommandCompat.AISTUDIO_URL, (verdict as CommandCompat.Verdict.OpenAistudio).url)
    }

    @Test
    fun aistudioCommandIsCaseInsensitive() {
        val verdict = CommandCompat.check(listOf("AIStudio", "--open"))
        assertTrue(verdict is CommandCompat.Verdict.OpenAistudio)
    }

    @Test
    fun flowCommandRoutesToNativeVpnBrowser() {
        val verdict = CommandCompat.check(listOf("flow"))
        assertTrue(verdict is CommandCompat.Verdict.OpenFlow)
        assertEquals(CommandCompat.FLOW_URL, (verdict as CommandCompat.Verdict.OpenFlow).url)
    }

    @Test
    fun standardCliCommandsRunNatively() {
        val commands = listOf(
            emptyList(),
            listOf("verify"),
            listOf("deploy"),
            listOf("autopilot"),
            listOf("scan"),
            listOf("configs"),
            listOf("security"),
            listOf("doctor"),
            listOf("backup"),
            listOf("zones"),
            listOf("find", "3"),
            listOf("cleanip", "list"),
            listOf("export", "v2rayn"),
            listOf("routes", "list"),
            listOf("dns", "list", "example.com"),
            listOf("multi-worker", "deploy", "--count", "2"),
            listOf("daemon", "status"),
            listOf("dns-tunnel", "worker"),
            listOf("dns-tunnel", "resolvers"),
            listOf("--profile", "work", "deploy", "--preset", "gaming")
        )
        for (cmd in commands) {
            assertTrue("Expected RunNative for $cmd", CommandCompat.check(cmd) is CommandCompat.Verdict.RunNative)
        }
    }

    @Test
    fun linuxOnlySubcommandsAreBlockedWithAndroidGuidance() {
        val blockedCmds = listOf(
            listOf("platform"),
            listOf("--profile", "work", "platform", "up"),
            listOf("subscribe"),
            listOf("update-apply"),
            listOf("daemon", "install-systemd"),
            listOf("dns-tunnel", "deploy"),
            listOf("dns-tunnel", "provision")
        )
        for (cmd in blockedCmds) {
            val verdict = CommandCompat.check(cmd)
            assertTrue("Expected Blocked for $cmd", verdict is CommandCompat.Verdict.Blocked)
            val blocked = verdict as CommandCompat.Verdict.Blocked
            assertTrue("Expected helpful hint for $cmd", !blocked.hint.isNullOrBlank())
        }
    }

    @Test
    fun globalFlagsBeforeAistudioStillRouteToNativeBrowser() {
        val verdict = CommandCompat.check(listOf("--profile", "work", "--lang", "fa", "aistudio"))
        assertTrue(verdict is CommandCompat.Verdict.OpenAistudio)
    }
}
