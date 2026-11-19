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

    @Test
    fun allSixSupportedLanguagesNormalizeAndExposeBadgesAndRtlProperly() {
        assertEquals(6, LocaleHelper.SUPPORTED_LANGUAGES.size)

        assertEquals(LocaleHelper.LANG_AR, LocaleHelper.normalizeLanguage("ar-SA"))
        assertEquals(LocaleHelper.LANG_AR, LocaleHelper.normalizeLanguage("Arabic"))
        assertEquals(LocaleHelper.LANG_TR, LocaleHelper.normalizeLanguage("tr-TR"))
        assertEquals(LocaleHelper.LANG_TR, LocaleHelper.normalizeLanguage("turkish"))
        assertEquals(LocaleHelper.LANG_RU, LocaleHelper.normalizeLanguage("ru-RU"))
        assertEquals(LocaleHelper.LANG_RU, LocaleHelper.normalizeLanguage("Russian"))
        assertEquals(LocaleHelper.LANG_ZH, LocaleHelper.normalizeLanguage("zh-CN"))
        assertEquals(LocaleHelper.LANG_ZH, LocaleHelper.normalizeLanguage("Chinese"))

        assertTrue(LocaleHelper.isRtlLanguage("fa"))
        assertTrue(LocaleHelper.isRtlLanguage("ar-EG"))
        assertFalse(LocaleHelper.isRtlLanguage("en"))
        assertFalse(LocaleHelper.isRtlLanguage("tr"))
        assertFalse(LocaleHelper.isRtlLanguage("ru"))
        assertFalse(LocaleHelper.isRtlLanguage("zh"))

        assertEquals("EN", LocaleHelper.badgeLabel("en"))
        assertEquals("فا", LocaleHelper.badgeLabel("fa"))
        assertEquals("ع", LocaleHelper.badgeLabel("ar"))
        assertEquals("TR", LocaleHelper.badgeLabel("tr"))
        assertEquals("RU", LocaleHelper.badgeLabel("ru"))
        assertEquals("中", LocaleHelper.badgeLabel("zh"))

        assertTrue(LocaleHelper.displayName("ar").contains("العربية"))
        assertTrue(LocaleHelper.displayName("tr").contains("Türkçe"))
        assertTrue(LocaleHelper.displayName("ru").contains("Русский"))
        assertTrue(LocaleHelper.displayName("zh").contains("中文"))
    }
}
