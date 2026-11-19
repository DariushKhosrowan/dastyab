package com.dastyab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermetic JVM unit tests for [ThemeManager] theme registry, normalization,
 * system-mode resolution, and cycling.
 */
class ThemeManagerTest {

    @Test
    fun registryContainsExpectedLightAndDarkThemeCounts() {
        val all = ThemeManager.AppTheme.values()
        assertEquals(7, all.size)
        assertEquals(4, ThemeManager.lightThemes().size)
        assertEquals(2, ThemeManager.darkThemes().size)
        assertTrue(ThemeManager.lightThemes().all { it.isLight })
        assertTrue(ThemeManager.darkThemes().all { !it.isLight })
    }

    @Test
    fun normalizeAndFromIdResolveValidAndFallbackGracefully() {
        assertEquals("light_daylight_ivory", ThemeManager.normalizeThemeId("LIGHT_DAYLIGHT_IVORY"))
        assertEquals("light_crisp_porcelain", ThemeManager.normalizeThemeId("  light_crisp_porcelain "))
        assertEquals("light_sage_meadow", ThemeManager.normalizeThemeId("light_sage_meadow"))
        assertEquals("light_warm_sand", ThemeManager.normalizeThemeId("light_warm_sand"))
        assertEquals("dark_midnight_indigo", ThemeManager.normalizeThemeId("dark_midnight_indigo"))
        assertEquals("system", ThemeManager.normalizeThemeId("system"))
        assertEquals("dark_royal_gold", ThemeManager.normalizeThemeId("unknown_theme"))
        assertEquals("dark_royal_gold", ThemeManager.normalizeThemeId(null))

        assertEquals(ThemeManager.AppTheme.LIGHT_SAGE_MEADOW, ThemeManager.fromId("light_sage_meadow"))
        assertEquals(ThemeManager.AppTheme.DARK_ROYAL_GOLD, ThemeManager.fromId("invalid"))
    }

    @Test
    fun resolveEffectiveThemeRespectsFollowSystemAndExplicitSelections() {
        assertEquals(
            ThemeManager.AppTheme.DARK_ROYAL_GOLD,
            ThemeManager.resolveEffectiveTheme(ThemeManager.AppTheme.FOLLOW_SYSTEM, systemNightMode = true)
        )
        assertEquals(
            ThemeManager.AppTheme.LIGHT_DAYLIGHT_IVORY,
            ThemeManager.resolveEffectiveTheme(ThemeManager.AppTheme.FOLLOW_SYSTEM, systemNightMode = false)
        )

        // Explicit light or dark selection ignores systemNightMode
        assertEquals(
            ThemeManager.AppTheme.LIGHT_WARM_SAND,
            ThemeManager.resolveEffectiveTheme(ThemeManager.AppTheme.LIGHT_WARM_SAND, systemNightMode = true)
        )
        assertFalse(
            ThemeManager.resolveEffectiveTheme(ThemeManager.AppTheme.DARK_MIDNIGHT_INDIGO, systemNightMode = false).isLight
        )
    }

    @Test
    fun nextThemeCyclesCleanlyThroughAllThemesAndWraps() {
        val all = ThemeManager.AppTheme.values()
        var current = all.first()
        val visited = mutableListOf<ThemeManager.AppTheme>()
        repeat(all.size) {
            visited.add(current)
            current = ThemeManager.nextTheme(current)
        }
        assertEquals(all.toList(), visited)
        assertEquals(all.first(), current)
    }
}
