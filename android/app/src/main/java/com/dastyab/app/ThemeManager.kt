package com.dastyab.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView

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

    private data class ThemePreviewPalette(val bgHex: Int, val surfaceHex: Int, val accentHex: Int)

    private fun previewPalette(theme: AppTheme, systemDark: Boolean): ThemePreviewPalette {
        val resolved = resolveEffectiveTheme(theme, systemDark)
        return when (resolved) {
            AppTheme.DARK_MIDNIGHT_INDIGO -> ThemePreviewPalette(0xFF070B14.toInt(), 0xFF172554.toInt(), 0xFF38BDF8.toInt())
            AppTheme.LIGHT_DAYLIGHT_IVORY -> ThemePreviewPalette(0xFFF7F4ED.toInt(), 0xFFFFFFFF.toInt(), 0xFFB3830C.toInt())
            AppTheme.LIGHT_CRISP_PORCELAIN -> ThemePreviewPalette(0xFFEFF4FB.toInt(), 0xFFFFFFFF.toInt(), 0xFF1D4ED8.toInt())
            AppTheme.LIGHT_SAGE_MEADOW -> ThemePreviewPalette(0xFFEFF6F1.toInt(), 0xFFFFFFFF.toInt(), 0xFF047857.toInt())
            AppTheme.LIGHT_WARM_SAND -> ThemePreviewPalette(0xFFFAF2EB.toInt(), 0xFFFFFFFF.toInt(), 0xFFC2410C.toInt())
            else -> ThemePreviewPalette(0xFF090705.toInt(), 0xFF201910.toInt(), 0xFFE3BE4E.toInt())
        }
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

    /**
     * Applies only the selected style resource BEFORE [Activity.setContentView].
     * Never touches [android.view.Window.getInsetsController] or decor views here,
     * preventing NullPointerException inside PhoneWindow during Activity.recreate().
     */
    fun applyTheme(activity: Activity) {
        val effective = resolveEffectiveTheme(activity)
        activity.setTheme(effective.styleRes)
    }

    /**
     * Configures window background, status/navigation bar colors, and light-bar icon contrast
     * ONLY once the activity's DecorView is already installed.
     */
    fun applySystemBars(activity: Activity) {
        if (!UiSafety.isActivityAlive(activity)) return
        val window = activity.window ?: return
        val decor = window.peekDecorView() ?: return
        val effective = resolveEffectiveTheme(activity)

        val bg = resolveColor(activity, R.attr.dastyabBackground, R.color.colorBackground)
        val statusBarColor = resolveColor(activity, R.attr.dastyabPrimaryDark, R.color.colorPrimaryDark)
        val navBarColor = resolveColor(activity, R.attr.dastyabBackground, R.color.colorBackground)

        window.setBackgroundDrawable(ColorDrawable(bg))
        window.statusBarColor = statusBarColor
        window.navigationBarColor = navBarColor

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = decor.windowInsetsController ?: return
            val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            controller.setSystemBarsAppearance(if (effective.isLight) flags else 0, flags)
        } else {
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

    /**
     * Defers system-bar styling to the attached DecorView message queue (safe across recreate()).
     */
    fun scheduleSystemBars(activity: Activity) {
        if (!UiSafety.isActivityAlive(activity)) return
        val decor = activity.window?.peekDecorView() ?: return
        decor.post {
            if (UiSafety.isActivityAlive(activity)) {
                applySystemBars(activity)
            }
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
        val systemDark = isSystemDarkMode(activity)
        val density = activity.resources.displayMetrics.density
        val textPrimary = resolveColor(activity, R.attr.dastyabTextPrimary, R.color.colorTextPrimary)
        val textSecondary = resolveColor(activity, R.attr.dastyabTextSecondary, R.color.colorTextSecondary)
        val accent = resolveColor(activity, R.attr.dastyabAccent, R.color.colorAccent)
        val cardBorder = resolveColor(activity, R.attr.dastyabCardBorder, R.color.colorCardBorder)
        val cardBg = resolveColor(activity, R.attr.dastyabSurfaceElevated, R.color.colorSurfaceElevated)
        val accentSoft = resolveColor(activity, R.attr.dastyabAccentSoft, R.color.colorChipBg)

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = themes.size
            override fun getItem(position: Int): Any = themes[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val theme = themes[position]
                val isSelected = theme == selected
                val palette = previewPalette(theme, systemDark)

                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val padH = (16 * density).toInt()
                    val padV = (10 * density).toInt()
                    setPadding(padH, padV, padH, padV)

                    val pill = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        val innerH = (14 * density).toInt()
                        val innerV = (12 * density).toInt()
                        setPadding(innerH, innerV, innerH, innerV)
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = 14f * density
                            setColor(if (isSelected) accentSoft else cardBg)
                            setStroke(
                                ((if (isSelected) 1.6f else 1f) * density).toInt().coerceAtLeast(1),
                                if (isSelected) accent else cardBorder
                            )
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )

                        // Dual-tone color swatch preview (BG circle with inner Accent orb)
                        val swatchSize = (34 * density).toInt()
                        val swatchContainer = LinearLayout(activity).apply {
                            gravity = Gravity.CENTER
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(palette.bgHex)
                                setStroke((1.5f * density).toInt().coerceAtLeast(1), palette.accentHex)
                            }
                            layoutParams = LinearLayout.LayoutParams(swatchSize, swatchSize).apply {
                                marginEnd = (12 * density).toInt()
                            }
                            val innerSize = (15 * density).toInt()
                            addView(View(activity).apply {
                                background = GradientDrawable().apply {
                                    shape = GradientDrawable.OVAL
                                    setColor(palette.accentHex)
                                }
                                layoutParams = LinearLayout.LayoutParams(innerSize, innerSize)
                            })
                        }
                        addView(swatchContainer)

                        val textCol = LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            addView(TextView(activity).apply {
                                text = localizedName(activity, theme)
                                textSize = 14.5f
                                setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                                setTextColor(if (isSelected) accent else textPrimary)
                            })
                            val subTag = when {
                                theme == AppTheme.FOLLOW_SYSTEM -> activity.getString(R.string.theme_badge_auto)
                                theme.isLight -> activity.getString(R.string.theme_tag_light)
                                else -> activity.getString(R.string.theme_tag_dark)
                            }
                            addView(TextView(activity).apply {
                                text = subTag
                                textSize = 11.5f
                                setTextColor(textSecondary)
                            })
                        }
                        addView(textCol)

                        if (isSelected) {
                            addView(TextView(activity).apply {
                                text = "✓"
                                textSize = 16f
                                setTypeface(null, Typeface.BOLD)
                                setTextColor(accent)
                                setPadding((8 * density).toInt(), 0, 0, 0)
                            })
                        }
                    }
                    addView(pill)
                }
                return row
            }
        }

        UiSafety.safeShowDialog(
            activity,
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.theme_picker_title))
                .setAdapter(adapter) { _, which ->
                    val chosen = themes[which]
                    setSelectedTheme(activity, chosen)
                    onSelected(chosen)
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }
}
