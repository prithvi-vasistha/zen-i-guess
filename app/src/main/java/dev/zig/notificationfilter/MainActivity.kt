package dev.zig.notificationfilter

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import dev.zig.notificationfilter.ui.common.SafeLaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.zig.notificationfilter.data.local.ContactsSyncManager
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import dev.zig.notificationfilter.domain.NotificationPublisher
import dev.zig.notificationfilter.domain.NotificationHealth
import dev.zig.notificationfilter.domain.summary.DailySummaryScheduler
import dev.zig.notificationfilter.service.ZigNotificationListenerService
import dev.zig.notificationfilter.ui.MainScreen
import dev.zig.notificationfilter.ui.navigation.ZigScreen
import dev.zig.notificationfilter.ui.onboarding.OnboardingScreen
import dev.zig.notificationfilter.ui.onboarding.TermsAndConditionsScreen
import dev.zig.notificationfilter.ui.theme.ZigTheme
import javax.inject.Inject
import dev.zig.notificationfilter.core.errors.AppErrors
import dev.zig.notificationfilter.ui.common.AppErrorDialog

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Index of the tab to open on launch from a notification deep-link (-1 = default).
    private var startTab by mutableStateOf(-1)

    // Bumped each time the daily-summary notification asks to open the review deck. Forwarded to
    // MainScreen, which owns the overlay; a nonce (not a flag) so a repeat tap re-triggers it.
    private var dailyReviewNonce by mutableStateOf(0)

    // First-run gate states — read from prefs before setContent so the very first
    // composition sees the correct values without a recomposition flash.
    private var termsAccepted by mutableStateOf(false)
    private var onboardingCompleted by mutableStateOf(false)

    @Inject lateinit var contactsSyncManager: ContactsSyncManager
    @Inject lateinit var preferences: ZigUserPreferences
    @Inject lateinit var notificationHealth: NotificationHealth
    @Inject lateinit var dailySummaryScheduler: DailySummaryScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        termsAccepted = preferences.termsAccepted
        onboardingCompleted = preferences.onboardingCompleted
        handleNotificationIntent(intent)
        AppErrors.run("Preparing system bars") { enableEdgeToEdge() }
        setContent {
            val appearance by preferences.appearance.collectAsStateWithLifecycle()
            val listenerState by notificationHealth.snapshot.collectAsStateWithLifecycle()
            val pendingErrors by AppErrors.pending.collectAsStateWithLifecycle()
            var accessReminderDismissed by rememberSaveable { mutableStateOf(false) }
            SafeLaunchedEffect(listenerState.notificationAccess) {
                if (listenerState.notificationAccess) accessReminderDismissed = false
            }
            val darkTheme = appearance.mode.isDark(isSystemInDarkTheme())
            SideEffect {
                // System bar icons follow ZiG's override as well as the system theme.
                val bars = if (darkTheme) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                AppErrors.run("Updating system bars") { enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars) }
            }
            ZigTheme(appearance = appearance, darkTheme = darkTheme) {
                when {
                    !termsAccepted -> TermsAndConditionsScreen(
                        onAccepted = {
                            AppErrors.run("Saving terms acceptance") {
                                preferences.termsAccepted = true
                                termsAccepted = true
                            }
                        },
                    )
                    !onboardingCompleted -> OnboardingScreen(
                        onCompleted = {
                            AppErrors.run("Completing setup") {
                                preferences.onboardingCompleted = true
                                startTab = ZigScreen.all.indexOf(ZigScreen.Apps)
                                onboardingCompleted = true
                            }
                        },
                    )
                    else -> MainScreen(
                        startTab = startTab,
                        dailyReviewNonce = dailyReviewNonce,
                    )
                }
                if (pendingErrors.isEmpty() && termsAccepted && onboardingCompleted && !listenerState.notificationAccess && !accessReminderDismissed) {
                    AlertDialog(
                        onDismissRequest = { accessReminderDismissed = true },
                        title = { Text("Enable notification access") },
                        text = { Text("ZiG needs notification access to filter your notifications. Enable it in Android settings to continue filtering.") },
                        confirmButton = {
                            TextButton(onClick = {
                                if (notificationHealth.openNotificationAccess()) accessReminderDismissed = true
                            }) { Text("Open settings") }
                        },
                        dismissButton = {
                            TextButton(onClick = { accessReminderDismissed = true }) { Text("Not now") }
                        },
                    )
                }
                AppErrorDialog()
            }
        }
    }

    // Handles the case where MainActivity is already running — Android calls onNewIntent
    // instead of onCreate when FLAG_ACTIVITY_CLEAR_TOP brings an existing instance to front.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    // ContactsSyncManager must not be initialized before onboarding is complete —
    // the checklist owns the gated path for READ_CONTACTS acquisition.
    // Both calls are idempotent and permission-guarded, so calling on every resume
    // after onboarding is safe.
    override fun onResume() {
        super.onResume()
        AppErrors.run("Restoring daily summary schedule") { dailySummaryScheduler.resolvePermissionRequest() }
        notificationHealth.ensureListenerConnected()
        ZigNotificationListenerService.instance?.reconcileActiveNotifications()
        if (!onboardingCompleted) return
        if (preferences.contactsBypassEnabled) {
            AppErrors.run("Updating contacts") {
                contactsSyncManager.register()
                contactsSyncManager.requestSyncIfNeeded()
            }
        }
    }

    private fun handleNotificationIntent(intent: Intent?) {
        AppErrors.run("Opening notification") {
            startTab = resolveStartTab(intent)
            if (resolveDailyReview(intent)) dailyReviewNonce++
            dismissNotificationIfRequested(intent)
        }
    }

    private fun resolveStartTab(intent: Intent?): Int {
        val target = intent?.getStringExtra(NotificationPublisher.EXTRA_NAVIGATE_TO) ?: return -1
        return when (target) {
            NotificationPublisher.NAV_TARGET_REVIEW -> ZigScreen.all.indexOf(ZigScreen.Review)
            else -> -1
        }
    }

    private fun resolveDailyReview(intent: Intent?): Boolean =
        intent?.getStringExtra(NotificationPublisher.EXTRA_NAVIGATE_TO) ==
            NotificationPublisher.NAV_TARGET_DAILY_REVIEW

    private fun dismissNotificationIfRequested(intent: Intent?) {
        val notifId = intent?.getIntExtra(
            NotificationPublisher.EXTRA_ZIG_NOTIF_TO_DISMISS, Int.MIN_VALUE,
        ) ?: return
        if (notifId != Int.MIN_VALUE) {
            AppErrors.run("Dismissing notification") {
                NotificationManagerCompat.from(this).cancel(
                    intent.getStringExtra(NotificationPublisher.EXTRA_ZIG_NOTIF_TAG), notifId,
                )
            }
        }
    }
}
