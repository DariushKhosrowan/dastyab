package com.dastyab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocaleHelperTest {

    @Test
    fun normalizeLanguageMapsPersianAndEnglishCorrectly() {
        assertEquals(LocaleHelper.LANG_FA, LocaleHelper.normalizeLanguage("fa"))
        assertEquals(LocaleHelper.LANG_FA, LocaleHelper.normalizeLanguage("fa-IR"))
        assertEquals(LocaleHelper.LANG_FA, LocaleHelper.normalizeLanguage("Persian"))
        assertEquals(LocaleHelper.LANG_EN, LocaleHelper.normalizeLanguage("en"))
        assertEquals(LocaleHelper.LANG_EN, LocaleHelper.normalizeLanguage(null))
        assertEquals(LocaleHelper.LANG_EN, LocaleHelper.normalizeLanguage("de"))
    }

    @Test
    fun nextLanguageAndRtlFlagsToggleCleanly() {
        assertEquals(LocaleHelper.LANG_FA, LocaleHelper.nextLanguage("en"))
        assertEquals(LocaleHelper.LANG_EN, LocaleHelper.nextLanguage("fa"))
        assertTrue(LocaleHelper.isRtlLanguage("fa"))
        assertFalse(LocaleHelper.isRtlLanguage("en"))
        assertEquals("EN", LocaleHelper.toggleButtonLabel("fa"))
        assertEquals("فا", LocaleHelper.toggleButtonLabel("en"))
    }
}
