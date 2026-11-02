package dev.zig.notificationfilter.ui.settings

import androidx.compose.material3.ColorScheme
import android.os.Build
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zig.notificationfilter.data.preferences.AccentColor
import dev.zig.notificationfilter.data.preferences.AppearancePreferences
import dev.zig.notificationfilter.data.preferences.ThemeMode
import dev.zig.notificationfilter.data.preferences.ThemeStyle
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import dev.zig.notificationfilter.ui.theme.ZigTheme
import dev.zig.notificationfilter.ui.theme.zigColorScheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearanceSettingsTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var preferences: ZigUserPreferences
    private lateinit var original: AppearancePreferences
    private lateinit var rendered: ColorScheme
    private val systemDark = mutableStateOf(false)

    @Before
    fun setUp() {
        // The preceding notification tests intentionally put the emulator to sleep.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (command in listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard")) {
            automation.executeShellCommand(command).use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
            }
        }
        preferences = ZigUserPreferences(ApplicationProvider.getApplicationContext())
        original = preferences.appearance.value
        preferences.setAppearance(AppearancePreferences())
        compose.setContent {
            val appearance by preferences.appearance.collectAsState()
            ZigTheme(appearance, appearance.mode.isDark(systemDark.value)) {
                val colors = MaterialTheme.colorScheme
                SideEffect { rendered = colors }
                AppearanceScreen(
                    appearance = appearance,
                    onModeChange = { preferences.setAppearance(preferences.appearance.value.copy(mode = it)) },
                    onStyleChange = { preferences.setAppearance(preferences.appearance.value.copy(style = it)) },
                    onAccentChange = { preferences.setAppearance(preferences.appearance.value.copy(accent = it)) },
                    onReset = { preferences.setAppearance(AppearancePreferences()) }, onBack = {},
                )
            }
        }
        // Compose can finish rendering before Android focuses the full-screen dialog.
        // Wait for its native window before injecting the first theme-option tap.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) compose.waitUntil(10_000) {
            var focused = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                focused = WindowInspector.getGlobalWindowViews().any { root ->
                    root.isShown && root.hasWindowFocus() &&
                        root.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) is DialogWindowProvider
                }
            }
            focused
        }
    }

    @After fun restoreAppearance() { preferences.setAppearance(original) }

    @Test
    fun themeAndAccentSelectionsUpdateTheRootAndPersistTogether() {
        compose.onNodeWithText("Dark").performClick()
        compose.onNodeWithText("AMOLED").performScrollTo().performClick()
        compose.onNodeWithText("Purple").performScrollTo().performClick()
        val expected = AppearancePreferences(ThemeMode.DARK, ThemeStyle.AMOLED, AccentColor.PURPLE)
        compose.runOnIdle {
            assertEquals(expected, preferences.appearance.value)
            assertEquals(expected, ZigUserPreferences(ApplicationProvider.getApplicationContext()).appearance.value)
            assertEquals(zigColorScheme(true, expected.style, expected.accent).background, rendered.background)
            assertEquals(zigColorScheme(true, expected.style, expected.accent).primary, rendered.primary)
        }
    }

    @Test
    fun systemModeTracksThePhoneAndResetRestoresDefaultsImmediately() {
        compose.runOnIdle { systemDark.value = true }
        compose.runOnIdle { assertEquals(zigColorScheme(true, ThemeStyle.ZIG, AccentColor.GREEN).background, rendered.background) }
        compose.onNodeWithText("Light").performClick()
        compose.runOnIdle { assertEquals(zigColorScheme(false, ThemeStyle.ZIG, AccentColor.GREEN).background, rendered.background) }
        compose.onNodeWithText("Paper").performScrollTo().performClick()
        compose.onNodeWithText("Blue").performScrollTo().performClick()
        compose.onNodeWithText("Reset").performClick()
        compose.runOnIdle {
            assertEquals(AppearancePreferences(), preferences.appearance.value)
            assertEquals(zigColorScheme(true, ThemeStyle.ZIG, AccentColor.GREEN).primary, rendered.primary)
            assertEquals(zigColorScheme(true, ThemeStyle.ZIG, AccentColor.GREEN).background, rendered.background)
        }
    }
}
