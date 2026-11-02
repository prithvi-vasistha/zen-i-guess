package dev.zig.notificationfilter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import dev.zig.notificationfilter.data.preferences.AppearancePreferences

internal val LocalZigDarkTheme = staticCompositionLocalOf { false }

@Composable
fun ZigTheme(
    appearance: AppearancePreferences = AppearancePreferences(),
    darkTheme: Boolean = appearance.mode.isDark(isSystemInDarkTheme()),
    content: @Composable () -> Unit,
) {
    val colorScheme = remember(darkTheme, appearance.style, appearance.accent) {
        zigColorScheme(darkTheme, appearance.style, appearance.accent)
    }
    CompositionLocalProvider(LocalZigDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = colorScheme, typography = ZigTypography, content = content)
    }
}
