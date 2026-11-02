package dev.zig.notificationfilter.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import dev.zig.notificationfilter.data.preferences.AccentColor
import dev.zig.notificationfilter.data.preferences.ThemeStyle

private data class AccentPalette(
    val light: Color, val dark: Color, val lightContainer: Color, val darkContainer: Color,
)

private fun AccentColor.palette(): AccentPalette = when (this) {
    AccentColor.GREEN -> AccentPalette(Color(0xFF2E7D32), Color(0xFF81C784), Color(0xFFC8E6C9), Color(0xFF1B3A1C))
    AccentColor.BLUE -> AccentPalette(Color(0xFF1565C0), Color(0xFF90CAF9), Color(0xFFD9E8FF), Color(0xFF17365B))
    AccentColor.PURPLE -> AccentPalette(Color(0xFF6A3AB2), Color(0xFFD0BCFF), Color(0xFFEBDDFF), Color(0xFF3E285B))
    AccentColor.ROSE -> AccentPalette(Color(0xFFAD1457), Color(0xFFF7B0C8), Color(0xFFFFD9E7), Color(0xFF542438))
    AccentColor.ORANGE -> AccentPalette(Color(0xFF9C4400), Color(0xFFFFB780), Color(0xFFFFE1CA), Color(0xFF4C2D16))
    AccentColor.TEAL -> AccentPalette(Color(0xFF006B60), Color(0xFF80CBC4), Color(0xFFC1EFE8), Color(0xFF163D39))
    AccentColor.RED -> AccentPalette(Color(0xFFBA1A1A), Color(0xFFFFB4AB), Color(0xFFFFDAD6), Color(0xFF54221F))
    AccentColor.AMBER -> AccentPalette(Color(0xFF805600), Color(0xFFFFD46A), Color(0xFFFFE8AD), Color(0xFF443515))
}

/** A readable swatch in the same tone used by the current light/dark palette. */
fun AccentColor.swatch(dark: Boolean): Color = palette().let { if (dark) it.dark else it.light }

/** All accent and surface roles are supplied so controls never inherit default purple. */
fun zigColorScheme(dark: Boolean, style: ThemeStyle, accent: AccentColor): ColorScheme {
    val palette = accent.palette()
    val primary = if (dark) palette.dark else palette.light
    val container = if (dark) palette.darkContainer else palette.lightContainer
    val neutral = if (dark) Color(0xFF1A1C1E) else Color(0xFFFDFDFD)
    val background = when (style) {
        ThemeStyle.ZIG -> neutral
        ThemeStyle.SOFT -> lerp(neutral, container, 0.40f)
        ThemeStyle.PAPER -> if (dark) Color(0xFF27231E) else Color(0xFFFAF3E5)
        ThemeStyle.AMOLED -> if (dark) Color.Black else neutral
    }
    val onSurface = if (style == ThemeStyle.PAPER) {
        if (dark) Color(0xFFEEE4D1) else Color(0xFF302C25)
    } else if (dark) Color(0xFFE4E4E7) else Color(0xFF1B1C1E)
    val onVariant = lerp(onSurface, background, 0.20f)
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    fun raised(amount: Float) = lerp(background, onSurface, amount)
    return scheme.copy(
        primary = primary,
        onPrimary = if (dark) Color(0xFF101C18) else Color.White,
        primaryContainer = container,
        onPrimaryContainer = if (dark) palette.lightContainer else Color(0xFF161C18),
        inversePrimary = if (dark) palette.light else palette.dark,
        secondary = primary,
        onSecondary = if (dark) Color(0xFF101C18) else Color.White,
        secondaryContainer = lerp(container, background, 0.35f),
        onSecondaryContainer = if (dark) palette.lightContainer else Color(0xFF161C18),
        tertiary = primary,
        onTertiary = if (dark) Color(0xFF101C18) else Color.White,
        tertiaryContainer = container,
        onTertiaryContainer = if (dark) palette.lightContainer else Color(0xFF161C18),
        background = background,
        onBackground = onSurface,
        surface = background,
        onSurface = onSurface,
        surfaceVariant = raised(if (dark) 0.12f else 0.08f),
        onSurfaceVariant = onVariant,
        surfaceTint = primary,
        inverseSurface = if (dark) Color(0xFFE4E4E7) else Color(0xFF2F3033),
        inverseOnSurface = if (dark) Color(0xFF1B1C1E) else Color(0xFFF5F5F5),
        outline = lerp(onSurface, background, 0.45f),
        outlineVariant = raised(0.20f),
        scrim = Color.Black,
        surfaceBright = raised(if (dark) 0.12f else 0f),
        surfaceDim = if (dark) background else raised(0.08f),
        surfaceContainerLowest = background,
        surfaceContainerLow = raised(if (dark) 0.025f else 0.015f),
        surfaceContainer = raised(if (dark) 0.04f else 0.025f),
        surfaceContainerHigh = raised(if (dark) 0.07f else 0.04f),
        surfaceContainerHighest = raised(if (dark) 0.10f else 0.06f),
    )
}
