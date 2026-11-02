package dev.zig.notificationfilter.domain.summary

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import dev.zig.notificationfilter.core.errors.AppErrors

@AndroidEntryPoint
class DailySummaryRescheduleReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: DailySummaryScheduler

    override fun onReceive(context: Context, intent: Intent) {
        AppErrors.run("Restoring daily summary schedule") {
        when (intent.action) {
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> scheduler.permissionGranted()
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> scheduler.schedule()
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> scheduler.reschedule()
        }
        }
    }
}
