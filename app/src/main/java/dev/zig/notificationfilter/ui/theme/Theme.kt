package dev.zig.notificationfilter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = ZigGreen,
    onPrimary = ZigOnGreen,
    primaryContainer = ZigGreenContainer,
    background = ZigLightBackground,
    surface = ZigLightBackground,
)

private val DarkColorScheme = darkColorScheme(
    primary = ZigGreen,
    onPrimary = ZigOnGreen,
    primaryContainer = ZigDarkGreenContainer,
    background = ZigDarkBackground,
    surface = ZigDarkBackground,
)

@Composable
fun ZigTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // dynamicColor is intentionally absent — ZiG's green accent (#4CAF50) is always enforced
    // regardless of Android 12+ wallpaper extraction.
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ZigTypography,
        content = content,
    )
}
