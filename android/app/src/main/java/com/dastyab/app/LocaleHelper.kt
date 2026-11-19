package com.dastyab.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * LocaleHelper — Multilingual UI persistence and context wrapper.
 *
 * Supports 6 languages with full RTL handling for Persian (`fa`) & Arabic (`ar`):
 *   • English (`en`, LTR)
 *   • فارسی / Persian (`fa`, RTL)
 *   • العربية / Arabic (`ar`, RTL)
 *   • Türkçe / Turkish (`tr`, LTR)
 *   • Русский / Russian (`ru`, LTR)
 *   • 中文 / Chinese (`zh`, LTR)
 *
 * Pure-Kotlin language normalization & cycling are unit-testable on the plain JVM.
 */
object LocaleHelper {

    const val PREFS_NAME = "dastyab_locale"
    private const val KEY_LANG = "app_language"

    const val LANG_EN = "en"
    const val LANG_FA = "fa"
    const val LANG_AR = "ar"
    const val LANG_TR = "tr"
    const val LANG_RU = "ru"
    const val LANG_ZH = "zh"

    data class AppLanguage(
        val code: String,
        val nativeName: String,
        val badge: String,
        val isRtl: Boolean
    )

    val SUPPORTED_LANGUAGES: List<AppLanguage> = listOf(
        AppLanguage(LANG_EN, "English", "EN", isRtl = false),
        AppLanguage(LANG_FA, "فارسی (Persian)", "فا", isRtl = true),
        AppLanguage(LANG_AR, "العربية (Arabic)", "ع", isRtl = true),
        AppLanguage(LANG_TR, "Türkçe (Turkish)", "TR", isRtl = false),
        AppLanguage(LANG_RU, "Русский (Russian)", "RU", isRtl = false),
        AppLanguage(LANG_ZH, "中文 (Chinese)", "中", isRtl = false)
    )

    fun normalizeLanguage(code: String?): String {
        val c = code?.trim()?.lowercase().orEmpty()
        return when {
            c == LANG_FA || c.startsWith("fa-") || c == "persian" || c == "farsi" -> LANG_FA
            c == LANG_AR || c.startsWith("ar-") || c == "arabic" -> LANG_AR
            c == LANG_TR || c.startsWith("tr-") || c == "turkish" -> LANG_TR
            c == LANG_RU || c.startsWith("ru-") || c == "russian" -> LANG_RU
            c == LANG_ZH || c.startsWith("zh-") || c == "chinese" -> LANG_ZH
            else -> LANG_EN
        }
    }

    fun nextLanguage(current: String?): String {
        return if (normalizeLanguage(current) == LANG_FA) LANG_EN else LANG_FA
    }

    fun isRtlLanguage(code: String?): Boolean {
        val norm = normalizeLanguage(code)
        return norm == LANG_FA || norm == LANG_AR
    }

    fun toggleButtonLabel(current: String?): String {
        // Shows the alternate quick-toggle badge for EN <-> FA compatibility
        return if (normalizeLanguage(current) == LANG_FA) "EN" else "فا"
    }

    fun badgeLabel(code: String?): String {
        val norm = normalizeLanguage(code)
        return SUPPORTED_LANGUAGES.firstOrNull { it.code == norm }?.badge ?: "EN"
    }

    fun displayName(code: String?): String {
        val norm = normalizeLanguage(code)
        return SUPPORTED_LANGUAGES.firstOrNull { it.code == norm }?.nativeName ?: "English"
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

    fun showLanguagePickerDialog(activity: Activity, onSelected: (String) -> Unit = {}) {
        if (!UiSafety.isActivityAlive(activity)) return
        val current = getLanguage(activity)
        val items = SUPPORTED_LANGUAGES.map { lang ->
            val mark = if (lang.code == current) "● " else ""
            "$mark${lang.nativeName}"
        }.toTypedArray()

        UiSafety.safeShowDialog(
            activity,
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.lang_picker_title))
                .setItems(items) { _, which ->
                    val chosen = SUPPORTED_LANGUAGES[which].code
                    setLanguage(activity, chosen)
                    onSelected(chosen)
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    /** Wraps [base] context with the persisted language configuration and RTL/LTR direction. */
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
