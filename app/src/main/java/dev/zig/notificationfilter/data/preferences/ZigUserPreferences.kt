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

    var dailySummaryHour: Int
        get() = prefs.getInt(KEY_DAILY_SUMMARY_HOUR, 20)
        set(value) = prefs.edit().putInt(KEY_DAILY_SUMMARY_HOUR, value).apply()

    var dailySummaryMinute: Int
        get() = prefs.getInt(KEY_DAILY_SUMMARY_MINUTE, 0)
        set(value) = prefs.edit().putInt(KEY_DAILY_SUMMARY_MINUTE, value).apply()

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

    var defaultRulesSeeded: Boolean
        get() = prefs.getBoolean(KEY_DEFAULT_RULES_SEEDED, false)
        set(value) = prefs.edit().putBoolean(KEY_DEFAULT_RULES_SEEDED, value).apply()

    // The "Set app to Silent" setup card on the Managed Apps screen is dismissable.
    // Once dismissed it stays dismissed across launches.
    var setupBannerDismissed: Boolean
        get() = prefs.getBoolean(KEY_SETUP_BANNER_DISMISSED, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_BANNER_DISMISSED, value).apply()

    // When true (default), a notification the sender marked sensitive (VISIBILITY_PRIVATE)
    // that arrives while the device is locked is shown immediately, bypassing the filter.
    // When false, it is not shown while locked — it is deferred and classified on unlock.
    var sensitiveNotificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SENSITIVE_NOTIFICATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_SENSITIVE_NOTIFICATIONS, value).apply()

    // When true (default), ZiG whitelists notifications from senders whose names appear in
    // the device contacts. Defaults to true so existing users who completed onboarding before
    // this preference existed are unaffected. New users going through onboarding have this
    // set explicitly in OnboardingViewModel.onContactsBypassResolved().
    var contactsBypassEnabled: Boolean
        get() = prefs.getBoolean(KEY_CONTACTS_BYPASS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_CONTACTS_BYPASS_ENABLED, value).apply()

    // One-time coach-mark tours, shown the first time the user visits each screen after
    // onboarding. Set to true once the user finishes or skips the tour.
    var notificationsTourSeen: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS_TOUR_SEEN, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS_TOUR_SEEN, value).apply()

    var rulesTourSeen: Boolean
        get() = prefs.getBoolean(KEY_RULES_TOUR_SEEN, false)
        set(value) = prefs.edit().putBoolean(KEY_RULES_TOUR_SEEN, value).apply()

    private companion object {
        const val KEY_DAILY_SUMMARY = "daily_summary_enabled"
        const val KEY_DAILY_SUMMARY_HOUR = "daily_summary_hour"
        const val KEY_DAILY_SUMMARY_MINUTE = "daily_summary_minute"
        const val KEY_TERMS_ACCEPTED = "terms_accepted"
        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        const val KEY_DEMO_SEEDED = "demo_seeded"
        const val KEY_DEFAULT_RULES_SEEDED = "default_rules_seeded"
        const val KEY_SETUP_BANNER_DISMISSED = "setup_banner_dismissed"
        const val KEY_SENSITIVE_NOTIFICATIONS = "sensitive_notifications_enabled"
        const val KEY_CONTACTS_BYPASS_ENABLED = "contacts_bypass_enabled"
        const val KEY_NOTIFICATIONS_TOUR_SEEN = "notifications_tour_seen"
        const val KEY_RULES_TOUR_SEEN = "rules_tour_seen"
    }
}
