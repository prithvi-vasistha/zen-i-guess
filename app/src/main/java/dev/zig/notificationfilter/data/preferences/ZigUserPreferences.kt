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

    private companion object {
        const val KEY_DAILY_SUMMARY = "daily_summary_enabled"
    }
}
