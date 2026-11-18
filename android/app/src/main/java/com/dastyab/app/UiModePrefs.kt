package com.dastyab.app

import android.content.Context

/**
 * UiModePrefs — persists whether the user prefers Simple Mode (1-tap automatic UI)
 * or Advanced Mode (full pro VPN layers, harvester editor, toolbox, and CLI).
 */
object UiModePrefs {

    private const val PREFS_NAME = "dastyab_ui_mode"
    private const val KEY_MODE = "ui_mode"
    private const val KEY_AUTO_HEAL = "auto_heal_google"

    enum class Mode {
        SIMPLE,
        ADVANCED;

        companion object {
            fun fromName(name: String?): Mode {
                if (name.isNullOrBlank()) return SIMPLE
                return values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SIMPLE
            }
        }
    }

    fun getMode(context: Context): Mode {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODE, Mode.SIMPLE.name)
        return Mode.fromName(raw)
    }

    fun setMode(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .apply()
    }

    fun isAutoHealEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_HEAL, true)
    }

    fun setAutoHealEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_HEAL, enabled)
            .apply()
    }
}
