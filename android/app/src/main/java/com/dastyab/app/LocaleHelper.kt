package com.dastyab.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * LocaleHelper — Bilingual Persian (Farsi RTL) & English UI persistence and context wrapper.
 *
 * Pure-Kotlin language normalization so parsing/toggling is unit-testable on the plain JVM.
 */
object LocaleHelper {

    const val PREFS_NAME = "dastyab_locale"
    private const val KEY_LANG = "app_language"

    const val LANG_EN = "en"
    const val LANG_FA = "fa"

    fun normalizeLanguage(code: String?): String {
        val c = code?.trim()?.lowercase().orEmpty()
        return if (c == LANG_FA || c.startsWith("fa-") || c == "persian" || c == "farsi") {
            LANG_FA
        } else {
            LANG_EN
        }
    }

    fun nextLanguage(current: String?): String {
        return if (normalizeLanguage(current) == LANG_FA) LANG_EN else LANG_FA
    }

    fun isRtlLanguage(code: String?): Boolean {
        return normalizeLanguage(code) == LANG_FA
    }

    fun toggleButtonLabel(current: String?): String {
        // Shows the language the user can switch to (or current badge)
        return if (normalizeLanguage(current) == LANG_FA) "EN" else "فا"
    }

    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return normalizeLanguage(prefs.getString(KEY_LANG, LANG_EN))
    }

    fun setLanguage(context: Context, lang: String) {
        val normalized = normalizeLanguage(lang)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANG, normalized)
            .apply()
    }

    fun toggleLanguage(context: Context): String {
        val next = nextLanguage(getLanguage(context))
        setLanguage(context, next)
        return next
    }

    /** Wraps [base] context with the persisted Persian (RTL) or English (LTR) configuration. */
    fun wrapContext(base: Context): Context {
        val lang = getLanguage(base)
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
