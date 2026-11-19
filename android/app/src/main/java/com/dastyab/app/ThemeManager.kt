package com.dastyab.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.WindowInsetsController

/**
 * ThemeManager — Multi-theme engine for DastYab Android.
 *
 * Supports Follow System plus 2 Dark themes (Royal Gold, Midnight Indigo)
 * and 4 Light themes (Daylight Ivory, Crisp Porcelain, Sage Meadow, Warm Sand).
 * Pure-Kotlin selection/resolution logic is JVM unit-testable.
 */
object ThemeManager {

    const val PREFS_NAME = "dastyab_theme"
    private const val KEY_THEME = "app_theme_id"

    enum class AppTheme(
        val id: String,
        val styleRes: Int,
        val isLight: Boolean,
        val nameResId: Int
    ) {
        FOLLOW_SYSTEM("system", R.style.DastyabTheme, false, R.string.theme_follow_system),
        DARK_ROYAL_GOLD("dark_royal_gold", R.style.DastyabTheme, false, R.string.theme_dark_royal_gold),
        DARK_MIDNIGHT_INDIGO("dark_midnight_indigo", R.style.DastyabTheme_MidnightIndigo, false, R.string.theme_dark_midnight_indigo),
        LIGHT_DAYLIGHT_IVORY("light_daylight_ivory", R.style.DastyabTheme_DaylightIvory, true, R.string.theme_light_daylight_ivory),
        LIGHT_CRISP_PORCELAIN("light_crisp_porcelain", R.style.DastyabTheme_CrispPorcelain, true, R.string.theme_light_crisp_porcelain),
        LIGHT_SAGE_MEADOW("light_sage_meadow", R.style.DastyabTheme_SageMeadow, true, R.string.theme_light_sage_meadow),
        LIGHT_WARM_SAND("light_warm_sand", R.style.DastyabTheme_WarmSand, true, R.string.theme_light_warm_sand)
    }

    fun normalizeThemeId(id: String?): String {
        val norm = id?.trim()?.lowercase().orEmpty()
        return AppTheme.values().firstOrNull { it.id == norm }?.id ?: AppTheme.DARK_ROYAL_GOLD.id
    }

    fun fromId(id: String?): AppTheme {
        val norm = normalizeThemeId(id)
        return AppTheme.values().firstOrNull { it.id == norm } ?: AppTheme.DARK_ROYAL_GOLD
    }

    fun resolveEffectiveTheme(selected: AppTheme, systemNightMode: Boolean): AppTheme {
        return if (selected == AppTheme.FOLLOW_SYSTEM) {
            if (systemNightMode) AppTheme.DARK_ROYAL_GOLD else AppTheme.LIGHT_DAYLIGHT_IVORY
        } else {
            selected
        }
    }

    fun nextTheme(current: AppTheme): AppTheme {
        val all = AppTheme.values()
        val idx = all.indexOf(current)
        return all[(idx + 1) % all.size]
    }

    fun lightThemes(): List<AppTheme> = AppTheme.values().filter { it.isLight }

    fun darkThemes(): List<AppTheme> = AppTheme.values().filter { it != AppTheme.FOLLOW_SYSTEM && !it.isLight }

    fun getSelectedTheme(context: Context): AppTheme {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return fromId(prefs.getString(KEY_THEME, AppTheme.DARK_ROYAL_GOLD.id))
    }

    fun setSelectedTheme(context: Context, theme: AppTheme) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, theme.id)
            .apply()
    }

    fun isSystemDarkMode(context: Context): Boolean {
        val mask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
    }

    fun resolveEffectiveTheme(context: Context): AppTheme {
        return resolveEffectiveTheme(getSelectedTheme(context), isSystemDarkMode(context))
    }

    fun resolveThemeStyle(context: Context): Int = resolveEffectiveTheme(context).styleRes

    fun isCurrentlyLightTheme(context: Context): Boolean = resolveEffectiveTheme(context).isLight

    fun applyTheme(activity: Activity) {
        val effective = resolveEffectiveTheme(activity)
        activity.setTheme(effective.styleRes)

        val bg = resolveColor(activity, R.attr.dastyabBackground, R.color.colorBackground)
        val statusBarColor = resolveColor(activity, R.attr.dastyabPrimaryDark, R.color.colorPrimaryDark)
        val navBarColor = resolveColor(activity, R.attr.dastyabBackground, R.color.colorBackground)

        val window = activity.window ?: return
        window.setBackgroundDrawable(ColorDrawable(bg))
        window.statusBarColor = statusBarColor
        window.navigationBarColor = navBarColor

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController
            if (controller != null) {
                val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                controller.setSystemBarsAppearance(if (effective.isLight) flags else 0, flags)
            }
        } else {
            @Suppress("DEPRECATION")
            val decor = window.decorView
            @Suppress("DEPRECATION")
            var vis = decor.systemUiVisibility
            @Suppress("DEPRECATION")
            vis = if (effective.isLight) {
                var next = vis or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    next = next or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
                next
            } else {
                var next = vis and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    next = next and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                }
                next
            }
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = vis
        }
    }

    fun resolveColor(context: Context, attrId: Int, fallbackColorRes: Int): Int {
        val tv = TypedValue()
        if (context.theme.resolveAttribute(attrId, tv, true)) {
            if (tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data
            }
            if (tv.resourceId != 0) {
                return context.getColor(tv.resourceId)
            }
        }
        return context.getColor(fallbackColorRes)
    }

    fun localizedName(context: Context, theme: AppTheme): String {
        return context.getString(theme.nameResId)
    }

    fun badgeLabel(context: Context): String {
        val selected = getSelectedTheme(context)
        return when {
            selected == AppTheme.FOLLOW_SYSTEM -> context.getString(R.string.theme_badge_auto)
            selected.isLight -> context.getString(R.string.theme_badge_light)
            else -> context.getString(R.string.theme_badge_dark)
        }
    }

    fun showThemePickerDialog(activity: Activity, onSelected: (AppTheme) -> Unit = {}) {
        if (!UiSafety.isActivityAlive(activity)) return
        val selected = getSelectedTheme(activity)
        val themes = AppTheme.values()
        val labels = themes.map { theme ->
            val mark = if (theme == selected) "● " else ""
            val tag = when {
                theme == AppTheme.FOLLOW_SYSTEM -> ""
                theme.isLight -> " [${activity.getString(R.string.theme_tag_light)}]"
                else -> " [${activity.getString(R.string.theme_tag_dark)}]"
            }
            "$mark${localizedName(activity, theme)}$tag"
        }.toTypedArray()

        UiSafety.safeShowDialog(
            activity,
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.theme_picker_title))
                .setItems(labels) { _, which ->
                    val chosen = themes[which]
                    setSelectedTheme(activity, chosen)
                    onSelected(chosen)
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }
}
