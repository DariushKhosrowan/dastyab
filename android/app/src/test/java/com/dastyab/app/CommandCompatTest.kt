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
            listOf("zones")
        )
        for (cmd in commands) {
            assertTrue("Expected RunNative for $cmd", CommandCompat.check(cmd) is CommandCompat.Verdict.RunNative)
        }
    }
}
