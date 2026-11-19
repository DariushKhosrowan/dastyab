package com.dastyab.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hermetic JVM unit tests for [UiModePrefs.Mode] parsing and defaults.
 */
class UiModePrefsTest {

    @Test
    fun modeFromName_defaultsToSimpleAndParsesCaseInsensitively() {
        assertEquals(UiModePrefs.Mode.SIMPLE, UiModePrefs.Mode.fromName(null))
        assertEquals(UiModePrefs.Mode.SIMPLE, UiModePrefs.Mode.fromName(""))
        assertEquals(UiModePrefs.Mode.SIMPLE, UiModePrefs.Mode.fromName("unknown"))
        assertEquals(UiModePrefs.Mode.SIMPLE, UiModePrefs.Mode.fromName("simple"))
        assertEquals(UiModePrefs.Mode.ADVANCED, UiModePrefs.Mode.fromName("advanced"))
        assertEquals(UiModePrefs.Mode.ADVANCED, UiModePrefs.Mode.fromName("ADVANCED"))
        assertEquals(UiModePrefs.Mode.TERMINAL, UiModePrefs.Mode.fromName("terminal"))
        assertEquals(UiModePrefs.Mode.TERMINAL, UiModePrefs.Mode.fromName("TERMINAL"))
    }
}
