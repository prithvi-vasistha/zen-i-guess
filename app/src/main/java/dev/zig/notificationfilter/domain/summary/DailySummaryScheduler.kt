package dev.zig.notificationfilter.domain.summary

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * Scheduling uses [ExistingPeriodicWorkPolicy.KEEP] so calling [schedule] a second time
 * (e.g., app update, re-opt-in) is a no-op if the work is already enqueued. [cancel] is
 * called when the user opts out; [schedule] is called again if they opt back in.
 */
@Singleton
class DailySummaryScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        internal const val WORK_NAME = "zig_daily_summary"

        // Default fire time: 20:00 local time.
        private val TARGET_TIME = LocalTime.of(20, 0)
    }

    fun schedule() {
        val request = PeriodicWorkRequestBuilder<DailySummaryWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delayUntilNextTarget(), TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    // Returns milliseconds until the next occurrence of TARGET_TIME in the device's local zone.
    // If it's already past TARGET_TIME today, the first run is scheduled for tomorrow.
    private fun delayUntilNextTarget(): Long {
        val now = LocalDateTime.now(ZoneId.systemDefault())
        val todayTarget = now.toLocalDate().atTime(TARGET_TIME)
        val nextTarget = if (now.isBefore(todayTarget)) todayTarget else todayTarget.plusDays(1)
        return Duration.between(now, nextTarget).toMillis().coerceAtLeast(0L)
    }
}
