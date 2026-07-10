package dev.zig.notificationfilter

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.zig.notificationfilter.data.local.ContactsSyncManager
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import dev.zig.notificationfilter.domain.NotificationPublisher
import dev.zig.notificationfilter.ui.MainScreen
import dev.zig.notificationfilter.ui.navigation.ZigScreen
import dev.zig.notificationfilter.ui.onboarding.OnboardingScreen
import dev.zig.notificationfilter.ui.onboarding.TermsAndConditionsScreen
import dev.zig.notificationfilter.ui.theme.ZigTheme
import javax.inject.Inject

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        termsAccepted = preferences.termsAccepted
        onboardingCompleted = preferences.onboardingCompleted
        startTab = resolveStartTab(intent)
        if (resolveDailyReview(intent)) dailyReviewNonce++
        dismissNotificationIfRequested(intent)
        enableEdgeToEdge()
        setContent {
            ZigTheme {
                when {
                    !termsAccepted -> TermsAndConditionsScreen(
                        onAccepted = {
                            preferences.termsAccepted = true
                            termsAccepted = true
                        },
                    )
                    !onboardingCompleted -> OnboardingScreen(
                        onCompleted = {
                            preferences.onboardingCompleted = true
                            startTab = ZigScreen.all.indexOf(ZigScreen.Apps)
                            onboardingCompleted = true
                        },
                    )
                    else -> MainScreen(
                        startTab = startTab,
                        dailyReviewNonce = dailyReviewNonce,
                    )
                }
            }
        }
    }

    // Handles the case where MainActivity is already running — Android calls onNewIntent
    // instead of onCreate when FLAG_ACTIVITY_CLEAR_TOP brings an existing instance to front.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startTab = resolveStartTab(intent)
        if (resolveDailyReview(intent)) dailyReviewNonce++
        dismissNotificationIfRequested(intent)
    }

    // ContactsSyncManager must not be initialized before onboarding is complete —
    // the checklist owns the gated path for READ_CONTACTS acquisition.
    // Both calls are idempotent and permission-guarded, so calling on every resume
    // after onboarding is safe.
    override fun onResume() {
        super.onResume()
        if (!onboardingCompleted) return
        if (preferences.contactsBypassEnabled) {
            contactsSyncManager.register()
            contactsSyncManager.requestSyncIfNeeded()
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
            NotificationManagerCompat.from(this).cancel(notifId)
        }
    }
}
