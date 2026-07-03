package dev.zig.notificationfilter.domain.summary

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.zig.notificationfilter.data.local.db.NotificationReviewDao
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import dev.zig.notificationfilter.domain.NotificationPublisher
import java.time.LocalDate
import java.time.ZoneId

@HiltWorker
class DailySummaryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val reviewDao: NotificationReviewDao,
    private val publisher: NotificationPublisher,
    private val preferences: ZigUserPreferences,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!preferences.dailySummaryEnabled) return Result.success()

        val startOfDayMs = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val count = reviewDao.countReviewableToday(startOfDayMs)
        publisher.postDailySummary(count)
        return Result.success()
    }
}
