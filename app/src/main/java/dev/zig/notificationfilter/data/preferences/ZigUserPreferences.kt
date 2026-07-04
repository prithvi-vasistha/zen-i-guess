package dev.zig.notificationfilter.data.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around SharedPreferences for user-facing toggles.
 *
 * Intentionally simple: one boolean preference, no reactive flow. The ViewModel
 * reads it once and the Worker checks it before posting. No DataStore dependency
 * needed for a single flag.
 */
@Singleton
class ZigUserPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("zig_user_prefs", Context.MODE_PRIVATE)

    var dailySummaryEnabled: Boolean
        get() = prefs.getBoolean(KEY_DAILY_SUMMARY, true)
        set(value) = prefs.edit().putBoolean(KEY_DAILY_SUMMARY, value).apply()

    var termsAccepted: Boolean
        get() = prefs.getBoolean(KEY_TERMS_ACCEPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_TERMS_ACCEPTED, value).apply()

    var onboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, value).apply()

    // One-time guard: the two sample notifications used by the tour are inserted only
    // on the first launch. Once seeded, they behave like any real notification.
    var demoSeeded: Boolean
        get() = prefs.getBoolean(KEY_DEMO_SEEDED, false)
        set(value) = prefs.edit().putBoolean(KEY_DEMO_SEEDED, value).apply()

    // When true (default), a notification the sender marked sensitive (VISIBILITY_PRIVATE)
    // that arrives while the device is locked is shown immediately, bypassing the filter.
    // When false, it is not shown while locked — it is deferred and classified on unlock.
    var sensitiveNotificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SENSITIVE_NOTIFICATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_SENSITIVE_NOTIFICATIONS, value).apply()

    private companion object {
        const val KEY_DAILY_SUMMARY = "daily_summary_enabled"
        const val KEY_TERMS_ACCEPTED = "terms_accepted"
        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        const val KEY_DEMO_SEEDED = "demo_seeded"
        const val KEY_SENSITIVE_NOTIFICATIONS = "sensitive_notifications_enabled"
    }
}
