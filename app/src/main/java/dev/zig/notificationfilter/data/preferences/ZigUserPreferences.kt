package dev.zig.notificationfilter.data.preferences

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import dev.zig.notificationfilter.core.errors.AppErrors

/**
 * SharedPreferences for user-facing settings. Appearance is observed across the app,
 * including changes from backup restore; scheduled reminder settings are saved durably.
 */
@Singleton
class ZigUserPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("zig_user_prefs", Context.MODE_PRIVATE)

    private val _appearance = MutableStateFlow(readAppearance())
    val appearance = _appearance.asStateFlow()
    // SharedPreferences keeps a weak listener reference, so retain it for this singleton.
    private val appearanceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in APPEARANCE_KEYS) _appearance.value = readAppearance()
    }

    init { prefs.registerOnSharedPreferenceChangeListener(appearanceListener) }

    fun setAppearance(value: AppearancePreferences) {
        prefs.edit().putString(KEY_THEME_MODE, value.mode.id)
            .putString(KEY_THEME_STYLE, value.style.id)
            .putString(KEY_ACCENT_COLOR, value.accent.id).also { check(it.commit()) { "Could not save appearance settings" } }
        _appearance.value = readAppearance()
    }

    private fun readAppearance() = AppErrors.run("Reading appearance settings") { AppearancePreferences.fromIds(
        prefs.getString(KEY_THEME_MODE, null), prefs.getString(KEY_THEME_STYLE, null),
        prefs.getString(KEY_ACCENT_COLOR, null),
    ) } ?: AppearancePreferences()

    var dailySummaryEnabled: Boolean
        get() = AppErrors.run("Reading app settings") { prefs.getBoolean(KEY_DAILY_SUMMARY, false) } ?: false
        set(value) { check(prefs.edit().putBoolean(KEY_DAILY_SUMMARY, value).commit()) { "Could not save app settings" } }

    // Keep the user's opt-in across the trip to Android's special-access settings.
    var dailySummaryPermissionPending: Boolean
        get() = AppErrors.run("Reading app settings") { prefs.getBoolean("daily_summary_permission_pending", false) } ?: false
        set(value) { check(prefs.edit().putBoolean("daily_summary_permission_pending", value).commit()) { "Could not save app settings" } }

    var dailySummaryHour: Int
        get() = AppErrors.run("Reading app settings") { prefs.getInt(KEY_DAILY_SUMMARY_HOUR, 20) } ?: 20
        set(value) { check(prefs.edit().putInt(KEY_DAILY_SUMMARY_HOUR, value).commit()) { "Could not save app settings" } }

    var dailySummaryMinute: Int
        get() = AppErrors.run("Reading app settings") { prefs.getInt(KEY_DAILY_SUMMARY_MINUTE, 0) } ?: 0
        set(value) { check(prefs.edit().putInt(KEY_DAILY_SUMMARY_MINUTE, value).commit()) { "Could not save app settings" } }

    var termsAccepted: Boolean
        get() = AppErrors.run("Reading app settings") { prefs.getBoolean(KEY_TERMS_ACCEPTED, false) } ?: false
        set(value) { check(prefs.edit().putBoolean(KEY_TERMS_ACCEPTED, value).commit()) { "Could not save app settings" } }

    var onboardingCompleted: Boolean
        get() = AppErrors.run("Reading app settings") { prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false) } ?: false
        set(value) { check(prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, value).commit()) { "Could not save app settings" } }

    // One-time guard: the two sample notifications used by the tour are inserted only
    // on the first launch. Once seeded, they behave like any real notification.
    var demoSeeded: Boolean
        get() = AppErrors.run("Reading app settings") { prefs.getBoolean(KEY_DEMO_SEEDED, false) } ?: false
        set(value) { check(prefs.edit().putBoolean(KEY_DEMO_SEEDED, value).commit()) { "Could not save app settings" } }

    var defaultRulesSeeded: Boolean
        get() = AppErrors.run("Reading app settings") { prefs.getBoolean(KEY_DEFAULT_RULES_SEEDED, false) } ?: false
        set(value) { check(prefs.edit().putBoolean(KEY_DEFAULT_RULES_SEEDED, value).commit()) { "Could not save app settings" } }

    // The "Set app to Silent" setup card on the Managed Apps screen is dismissable.
    // Once dismissed it stays dismissed across launches.
    var setupBannerDismissed: Boolean
        get() = AppErrors.run("Reading app settings") { prefs.getBoolean(KEY_SETUP_BANNER_DISMISSED, false) } ?: false
        set(value) { check(prefs.edit().putBoolean(KEY_SETUP_BANNER_DISMISSED, value).commit()) { "Could not save app settings" } }

    // When true (default), a notification the sender marked sensitive (VISIBILITY_PRIVATE)
    // that arrives while the device is locked bypasses text filtering. The image setting
    // still applies: private unmatched senders are deferred when images are disabled.
    // When false, it is not shown while locked — it is deferred and classified on unlock.
    var sensitiveNotificationsEnabled: Boolean
        get() = AppErrors.run("Reading app settings") { prefs.getBoolean(KEY_SENSITIVE_NOTIFICATIONS, true) } ?: true
        set(value) { check(prefs.edit().putBoolean(KEY_SENSITIVE_NOTIFICATIONS, value).commit()) { "Could not save app settings" } }

    // Android OTP redaction may persist after unlock. Alert without trying to
    // classify unavailable content, independently of lock-screen and image rules.
    var hiddenContentAlertsEnabled: Boolean
        get() = AppErrors.run("Reading app settings") { prefs.getBoolean(KEY_HIDDEN_CONTENT_ALERTS, true) } ?: true
        set(value) { check(prefs.edit().putBoolean(KEY_HIDDEN_CONTENT_ALERTS, value).commit()) { "Could not save app settings" } }

    // When true (default), ZiG whitelists notifications from senders whose names appear in
    // the device contacts. Defaults to true so existing users who completed onboarding before
    // this preference existed are unaffected. New users going through onboarding have this
    // set explicitly in OnboardingViewModel.onContactsBypassResolved().
    var contactsBypassEnabled: Boolean
        get() = AppErrors.run("Reading app settings") { prefs.getBoolean(KEY_CONTACTS_BYPASS_ENABLED, true) } ?: true
        set(value) { check(prefs.edit().putBoolean(KEY_CONTACTS_BYPASS_ENABLED, value).commit()) { "Could not save app settings" } }

    // Images are eligible for normal filtering by default. When disabled, the final
    // delivery gate blocks image notifications unless Smart Contact Bypass matched.
    var imageNotificationsEnabled: Boolean
        get() = AppErrors.run("Reading app settings") { prefs.getBoolean(KEY_IMAGE_NOTIFICATIONS, true) } ?: true
        set(value) { check(prefs.edit().putBoolean(KEY_IMAGE_NOTIFICATIONS, value).commit()) { "Could not save app settings" } }

    // One-time coach-mark tours, shown the first time the user visits each screen after
    // onboarding. Set to true once the user finishes or skips the tour.
    var notificationsTourSeen: Boolean
        get() = AppErrors.run("Reading app settings") { prefs.getBoolean(KEY_NOTIFICATIONS_TOUR_SEEN, false) } ?: false
        set(value) { check(prefs.edit().putBoolean(KEY_NOTIFICATIONS_TOUR_SEEN, value).commit()) { "Could not save app settings" } }

    var rulesTourSeen: Boolean
        get() = AppErrors.run("Reading app settings") { prefs.getBoolean(KEY_RULES_TOUR_SEEN, false) } ?: false
        set(value) { check(prefs.edit().putBoolean(KEY_RULES_TOUR_SEEN, value).commit()) { "Could not save app settings" } }

    private companion object {
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_THEME_STYLE = "theme_style"
        const val KEY_ACCENT_COLOR = "accent_color"
        val APPEARANCE_KEYS = setOf(KEY_THEME_MODE, KEY_THEME_STYLE, KEY_ACCENT_COLOR)
        const val KEY_DAILY_SUMMARY = "daily_summary_enabled"
        const val KEY_DAILY_SUMMARY_HOUR = "daily_summary_hour"
        const val KEY_DAILY_SUMMARY_MINUTE = "daily_summary_minute"
        const val KEY_TERMS_ACCEPTED = "terms_accepted"
        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        const val KEY_DEMO_SEEDED = "demo_seeded"
        const val KEY_DEFAULT_RULES_SEEDED = "default_rules_seeded"
        const val KEY_SETUP_BANNER_DISMISSED = "setup_banner_dismissed"
        const val KEY_SENSITIVE_NOTIFICATIONS = "sensitive_notifications_enabled"
        const val KEY_HIDDEN_CONTENT_ALERTS = "hidden_content_alerts_enabled"
        const val KEY_CONTACTS_BYPASS_ENABLED = "contacts_bypass_enabled"
        const val KEY_IMAGE_NOTIFICATIONS = "image_notifications_enabled"
        const val KEY_NOTIFICATIONS_TOUR_SEEN = "notifications_tour_seen"
        const val KEY_RULES_TOUR_SEEN = "rules_tour_seen"
    }
}
