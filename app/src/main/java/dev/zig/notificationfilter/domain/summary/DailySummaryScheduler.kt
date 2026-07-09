package dev.zig.notificationfilter.domain.summary

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the daily summary WorkManager task.
 *
 * [schedule] uses [ExistingPeriodicWorkPolicy.KEEP] so calling it repeatedly (e.g., app
 * update, re-opt-in) is a no-op when work is already enqueued. [reschedule] uses
 * [ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE] to replace the running job with a new
 * initial delay — used when the user changes their preferred summary time or after a backup
 * restore. [cancel] is called when the user opts out.
 */
@Singleton
class DailySummaryScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: ZigUserPreferences,
) {
    companion object {
        internal const val WORK_NAME = "zig_daily_summary"
    }

    /** Enqueues the job if not already running. Safe to call multiple times. */
    fun schedule() {
        enqueue(ExistingPeriodicWorkPolicy.KEEP)
    }

    /**
     * Cancels the running job and re-enqueues it with a fresh initial delay calculated from
     * the current stored time. Call this after the user changes the summary time or after a
     * backup restore so the new time takes effect immediately.
     */
    fun reschedule() {
        enqueue(ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE)
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private fun enqueue(policy: ExistingPeriodicWorkPolicy) {
        val targetTime = LocalTime.of(preferences.dailySummaryHour, preferences.dailySummaryMinute)
        val request = PeriodicWorkRequestBuilder<DailySummaryWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delayUntilNextTarget(targetTime), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, policy, request)
    }

    // Returns milliseconds until the next occurrence of targetTime in the device's local zone.
    // If targetTime has already passed today (e.g., user sets 8 PM at 9 PM), the first run is
    // scheduled for tomorrow — the returned value is always strictly positive.
    private fun delayUntilNextTarget(targetTime: LocalTime): Long {
        val now = LocalDateTime.now(ZoneId.systemDefault())
        val todayTarget = now.toLocalDate().atTime(targetTime)
        val nextTarget = if (now.isBefore(todayTarget)) todayTarget else todayTarget.plusDays(1)
        return Duration.between(now, nextTarget).toMillis()
    }
}
