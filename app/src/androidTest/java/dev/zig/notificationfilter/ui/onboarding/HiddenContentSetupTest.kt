package dev.zig.notificationfilter.ui.onboarding

import android.content.Context
import android.content.ContextWrapper
import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zig.notificationfilter.ZigApp
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import dev.zig.notificationfilter.domain.summary.DailySummaryScheduler
import dev.zig.notificationfilter.ui.theme.ZigTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HiddenContentSetupTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<ZigApp>()
    private val prefix = "hidden-setup-${UUID.randomUUID()}-"
    private val context = object : ContextWrapper(app) {
        override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(prefix + name, mode)
    }
    private val prefs = ZigUserPreferences(context)
    private val store = ViewModelStore()
    private lateinit var viewModel: OnboardingViewModel
    private val visible = mutableStateOf(true)

    @Before fun prepare() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (command in listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard")) {
            instrumentation.uiAutomation.executeShellCommand(command).use {
                ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
            }
        }
        instrumentation.runOnMainSync {
            viewModel = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = OnboardingViewModel(
                    app.contactsSyncManager, app.managedAppDao, app.filterState, prefs,
                    DailySummaryScheduler(context, prefs), app.notificationHealth, context,
                ) as T
            })[OnboardingViewModel::class.java]
        }
        compose.setContent {
            ZigTheme {
                if (visible.value) HiddenContentAlertsDialog(
                    onEnable = { viewModel.onHiddenContentAlertsResolved(true); visible.value = false },
                    onSkip = { viewModel.onHiddenContentAlertsResolved(false); visible.value = false },
                )
            }
        }
        compose.waitUntil(10_000) {
            var focused = false
            instrumentation.runOnMainSync {
                focused = WindowInspector.getGlobalWindowViews().any { root ->
                    root.isShown && root.hasWindowFocus() &&
                        root.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) is DialogWindowProvider
                }
            }
            focused
        }
    }

    @After fun cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
        app.deleteSharedPreferences(prefix + "zig_user_prefs")
        app.deleteSharedPreferences(prefix + "daily_summary_schedule")
    }

    @Test fun acceptingExplainedChoicePersistsAndCompletesTheSetupStep() {
        prefs.hiddenContentAlertsEnabled = false
        compose.onNodeWithText("Enable alerts").performClick()
        compose.runOnIdle {
            assertTrue(ZigUserPreferences(context).hiddenContentAlertsEnabled)
            assertTrue(viewModel.steps.value.last().completed)
            assertFalse(visible.value)
        }
    }

    @Test fun decliningPersistsOptOutAndStillCompletesTheSetupStep() {
        assertTrue(prefs.hiddenContentAlertsEnabled)
        compose.onNodeWithText("Keep off").performClick()
        compose.runOnIdle {
            assertFalse(ZigUserPreferences(context).hiddenContentAlertsEnabled)
            assertTrue(viewModel.steps.value.last().completed)
            assertFalse(visible.value)
        }
    }
}
