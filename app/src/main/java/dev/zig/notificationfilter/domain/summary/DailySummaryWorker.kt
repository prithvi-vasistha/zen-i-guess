package dev.zig.notificationfilter.domain.summary

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Drain a job queued by an older release without posting an overdue duplicate. */
class DailySummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}
