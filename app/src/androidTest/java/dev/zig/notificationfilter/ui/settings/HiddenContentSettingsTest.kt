package dev.zig.notificationfilter.ui.settings

import android.app.KeyguardManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zig.notificationfilter.MainActivity
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import dev.zig.notificationfilter.ui.theme.ZigTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HiddenContentSettingsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = ZigUserPreferences(context)
    private var original = true
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var viewModel: SettingsViewModel

    @Before fun prepare() {
        original = prefs.hiddenContentAlertsEnabled
        prefs.hiddenContentAlertsEnabled = true
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (command in listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard")) {
            instrumentation.uiAutomation.executeShellCommand(command).use {
                ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
            }
        }
        compose.waitUntil(10_000) { !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity {
            viewModel = ViewModelProvider(it)[SettingsViewModel::class.java]
            it.setContent { ZigTheme { SettingsScreen() } }
        }
        compose.waitUntil(10_000) {
            var focused = false
            instrumentation.runOnMainSync {
                focused = WindowInspector.getGlobalWindowViews().any { it.isShown && it.hasWindowFocus() }
            }
            focused
        }
    }

    @After fun cleanup() {
        if (::scenario.isInitialized) scenario.close()
        prefs.hiddenContentAlertsEnabled = original
    }

    @Test fun notificationsTogglePersistsBothDirectionsAndMatchesTheViewModel() {
        compose.onNodeWithText("Alert for hidden content").performScrollTo().performClick()
        compose.runOnIdle {
            assertFalse(ZigUserPreferences(context).hiddenContentAlertsEnabled)
            assertFalse(viewModel.hiddenContentAlertsEnabled.value)
        }
        compose.onNodeWithText("Alert for hidden content").performClick()
        compose.runOnIdle {
            assertTrue(ZigUserPreferences(context).hiddenContentAlertsEnabled)
            assertTrue(viewModel.hiddenContentAlertsEnabled.value)
        }
    }
}
