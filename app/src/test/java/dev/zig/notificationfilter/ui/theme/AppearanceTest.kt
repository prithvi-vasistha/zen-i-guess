package dev.zig.notificationfilter.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.zig.notificationfilter.data.preferences.AccentColor
import dev.zig.notificationfilter.data.preferences.AppearancePreferences
import dev.zig.notificationfilter.data.preferences.ThemeMode
import dev.zig.notificationfilter.data.preferences.ThemeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {
    @Test
    fun `missing and unknown settings keep system mode and the green ZiG theme`() {
        assertEquals(AppearancePreferences(), AppearancePreferences.fromIds(null, null, null))
        assertEquals(AppearancePreferences(), AppearancePreferences.fromIds("future", "future", "future"))
        assertEquals(AppearancePreferences(mode = ThemeMode.DARK, accent = AccentColor.TEAL),
            AppearancePreferences.fromIds("dark", "future", "teal"))
    }

    @Test
    fun `explicit mode overrides the system in either direction`() {
        assertTrue(ThemeMode.DARK.isDark(false))
        assertFalse(ThemeMode.LIGHT.isDark(true))
        assertTrue(ThemeMode.SYSTEM.isDark(true))
        assertFalse(ThemeMode.SYSTEM.isDark(false))
    }

    @Test
    fun `text and accent contrast remains readable for every palette combination`() {
        for (dark in listOf(false, true)) for (style in ThemeStyle.entries) for (accent in AccentColor.entries) {
            val c = zigColorScheme(dark, style, accent)
            val pairs = listOf(
                c.primary to c.onPrimary, c.primaryContainer to c.onPrimaryContainer,
                c.secondaryContainer to c.onSecondaryContainer,
                c.background to c.onBackground, c.surfaceVariant to c.onSurfaceVariant,
                c.surfaceContainerHighest to c.onSurface, c.background to c.primary,
                c.background to c.onSurfaceVariant, c.inverseSurface to c.inverseOnSurface,
            )
            for ((background, foreground) in pairs) {
                val contrast = (maxOf(background.luminance(), foreground.luminance()) + 0.05f) /
                    (minOf(background.luminance(), foreground.luminance()) + 0.05f)
                assertTrue("$dark $style $accent: contrast $contrast for $foreground on $background", contrast >= 4.5f)
            }
        }
    }

    @Test
    fun `AMOLED is pure black only in dark mode`() {
        for (accent in AccentColor.entries) {
            assertEquals(Color.Black, zigColorScheme(true, ThemeStyle.AMOLED, accent).background)
            assertEquals(Color.Black, zigColorScheme(true, ThemeStyle.AMOLED, accent).surface)
            assertTrue(zigColorScheme(false, ThemeStyle.AMOLED, accent).background.luminance() > 0.9f)
        }
    }

    @Test
    fun `each accent and dark theme style has a distinct palette`() {
        assertTrue(AccentColor.entries.size >= 5)
        for (dark in listOf(false, true)) {
            assertEquals(AccentColor.entries.size, AccentColor.entries.map { it.swatch(dark) }.toSet().size)
        }
        assertEquals(ThemeStyle.entries.size,
            ThemeStyle.entries.map { zigColorScheme(true, it, AccentColor.GREEN).background }.toSet().size)
    }
}
