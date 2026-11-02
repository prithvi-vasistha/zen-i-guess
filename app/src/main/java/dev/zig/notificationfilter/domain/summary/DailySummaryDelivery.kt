package dev.zig.notificationfilter.domain.summary

import dev.zig.notificationfilter.data.local.db.NotificationLogDao
import dev.zig.notificationfilter.data.local.db.NotificationLogEntity
import dev.zig.notificationfilter.data.local.db.NotificationReviewDao
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import dev.zig.notificationfilter.domain.NotificationPublisher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import dev.zig.notificationfilter.core.errors.AppErrors

@Singleton
class DailySummaryDelivery @Inject constructor(
    private val scheduler: DailySummaryScheduler,
    private val reviewDao: NotificationReviewDao,
    private val logDao: NotificationLogDao,
    private val publisher: NotificationPublisher,
    private val preferences: ZigUserPreferences,
) {
    private val mutex = Mutex()

    internal suspend fun deliver(id: String, now: Instant = Instant.now()) = mutex.withLock {
        val occurrence = scheduler.claim(id, now) ?: return@withLock
        val result = try {
            val count = reviewDao.countReviewableBetween(occurrence.startOfDay, occurrence.scheduledAt)
            if (!preferences.dailySummaryEnabled) {
                scheduler.recordResult("Daily Summary turned off", "The pending summary was cancelled.")
                return@withLock
            }
            val result = publisher.postDailySummary(count, occurrence.date, occurrence.id)
            scheduler.recordResult(result.status.label, result.detail)
            result
        } catch (e: Exception) {
            AppErrors.report("Delivering daily summary", e)
            AppErrors.run("Saving daily summary failure") {
                scheduler.recordResult("Daily summary failed", e.message ?: e.javaClass.simpleName)
            }
            android.util.Log.e("ZiG", "Daily summary failed", e)
            return@withLock
        }
        try {
            logDao.insert(NotificationLogEntity(jobId = "summary-${occurrence.id}",
                packageName = "dev.zig.notificationfilter", title = "Daily Summary", content = "",
                status = if (result.posted) "SUMMARY_POSTED" else result.status.name,
                filterReason = "${occurrence.date}: ${result.status.label}", timestamp = System.currentTimeMillis()))
        } catch (e: Exception) {
            AppErrors.report("Saving daily summary log", e)
        }
    }
}
