package dev.zig.notificationfilter.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.zig.notificationfilter.ui.theme.ZigFullscreenDialog
import dev.zig.notificationfilter.data.preferences.AccentColor
import dev.zig.notificationfilter.data.preferences.AppearancePreferences
import dev.zig.notificationfilter.data.preferences.ThemeMode
import dev.zig.notificationfilter.data.preferences.ThemeStyle
import dev.zig.notificationfilter.ui.theme.swatch
import dev.zig.notificationfilter.ui.theme.zigColorScheme

private val OptionShape = RoundedCornerShape(16.dp)
private val OptionSpacing = 12.dp

/** Selections take effect immediately; Back keeps the user's choices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    appearance: AppearancePreferences,
    onModeChange: (ThemeMode) -> Unit,
    onStyleChange: (ThemeStyle) -> Unit,
    onAccentChange: (AccentColor) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    val dark = appearance.mode.isDark(isSystemInDarkTheme())
    ZigFullscreenDialog(onDismissRequest = onBack) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Appearance") },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = { TextButton(onClick = onReset) { Text("Reset") } },
                    )
                },
            ) { padding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppearanceSection("Theme mode", "System follows your phone's light and dark settings.")
                            BoxWithConstraints {
                                // Keep labels intact when the screen or enlarged text leaves too little room.
                                val stacked = maxWidth / LocalDensity.current.fontScale < 240.dp
                                if (stacked) {
                                    Column(Modifier.fillMaxWidth().selectableGroup(),
                                        verticalArrangement = Arrangement.spacedBy(OptionSpacing)) {
                                        ThemeMode.entries.forEach { mode ->
                                            ThemeModeChoice(mode, appearance.mode == mode, true, Modifier.fillMaxWidth(),
                                                onClick = { onModeChange(mode) })
                                        }
                                    }
                                } else {
                                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max).selectableGroup(),
                                        horizontalArrangement = Arrangement.spacedBy(OptionSpacing)) {
                                        ThemeMode.entries.forEach { mode ->
                                            ThemeModeChoice(mode, appearance.mode == mode, false, Modifier.weight(1f).fillMaxHeight(),
                                                onClick = { onModeChange(mode) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppearanceSection("Theme style", "Choose the surfaces around your notifications.")
                            BoxWithConstraints {
                                val columns = if (maxWidth < 340.dp || LocalDensity.current.fontScale > 1.3f) 1 else 2
                                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(OptionSpacing)) {
                                    ThemeStyle.entries.chunked(columns).forEach { styles ->
                                        Row(Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(OptionSpacing)) {
                                            styles.forEach { style ->
                                                ThemeStyleCard(style, appearance, dark, Modifier.weight(1f),
                                                    onClick = { onStyleChange(style) })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppearanceSection("Accent colour", "Used for buttons, switches and highlights.")
                            BoxWithConstraints {
                                val columns = if (maxWidth < 340.dp || LocalDensity.current.fontScale > 1.3f) 3 else 4
                                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(OptionSpacing)) {
                                    AccentColor.entries.chunked(columns).forEach { accents ->
                                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                                            horizontalArrangement = Arrangement.spacedBy(OptionSpacing)) {
                                            accents.forEach { accent ->
                                                AccentChoice(accent, appearance.accent == accent, dark, Modifier.weight(1f).fillMaxHeight(),
                                                    onClick = { onAccentChange(accent) })
                                            }
                                            repeat(columns - accents.size) { Spacer(Modifier.weight(1f)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppearanceSection("Preview", "Your choices are applied and saved immediately.")
                            Surface(shape = OptionShape, color = MaterialTheme.colorScheme.surfaceContainer) {
                                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                                        Icon(Icons.Default.Notifications, contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(12.dp).size(24.dp))
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text("Your ZiG", style = MaterialTheme.typography.titleMedium)
                                        Text("${appearance.style.label} · ${appearance.accent.label}",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceSection(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ThemeModeChoice(mode: ThemeMode, selected: Boolean, stacked: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.selectable(selected, role = Role.RadioButton, onClick = onClick),
        shape = OptionShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        if (stacked) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OptionSpacing)) {
                Text(mode.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (selected) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                else Spacer(Modifier.size(16.dp))
            }
        } else {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(mode.label, style = MaterialTheme.typography.labelLarge)
                if (selected) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                else Spacer(Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun ThemeStyleCard(
    style: ThemeStyle, appearance: AppearancePreferences, dark: Boolean, modifier: Modifier, onClick: () -> Unit,
) {
    val selected = appearance.style == style
    val preview = zigColorScheme(dark, style, appearance.accent)
    Surface(
        modifier = modifier.selectable(selected, role = Role.RadioButton, onClick = onClick),
        shape = OptionShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // A miniature notification, rendered in this style's actual palette.
            Surface(shape = RoundedCornerShape(12.dp), color = preview.background, border = BorderStroke(1.dp, preview.outlineVariant)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(20.dp).background(preview.primary, CircleShape))
                    Box(Modifier.fillMaxWidth(0.75f).height(5.dp).background(preview.onSurface.copy(alpha = 0.7f), CircleShape))
                    Box(Modifier.fillMaxWidth(0.5f).height(5.dp).background(preview.onSurfaceVariant.copy(alpha = 0.4f), CircleShape))
                    Box(Modifier.fillMaxWidth().height(12.dp).background(preview.primaryContainer, CircleShape))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(style.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp))
                else Spacer(Modifier.size(18.dp))
            }
            // Reserve the same description space, while allowing longer text to grow.
            Text(style.description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 2)
        }
    }
}

@Composable
private fun AccentChoice(accent: AccentColor, selected: Boolean, dark: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.selectable(selected, role = Role.RadioButton, onClick = onClick),
        shape = OptionShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(36.dp).background(accent.swatch(dark), CircleShape), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Default.Check, contentDescription = null,
                    tint = if (dark) androidx.compose.ui.graphics.Color(0xFF101C18) else androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(20.dp))
            }
            Text(accent.label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
