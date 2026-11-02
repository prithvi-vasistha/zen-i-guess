package dev.zig.notificationfilter.domain.summary

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import dev.zig.notificationfilter.core.errors.AppErrors

data class DailySummaryState(val enabled: Boolean, val timingAccess: Boolean, val nextAt: Long?,
    val lastStatus: String, val lastDetail: String)

/** One persisted wall-clock alarm, independent of the listener and WorkManager. */
@Singleton
class DailySummaryScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: ZigUserPreferences,
) {
    companion object {
        internal const val WORK_NAME = "zig_daily_summary" // Retire the previous periodic job.
        internal const val ACTION_DELIVER = "dev.zig.notificationfilter.action.DAILY_SUMMARY"
        internal const val EXTRA_OCCURRENCE = "summary_occurrence"
        private const val REQUEST_ID = 0x7A690003
    }

    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val store = context.getSharedPreferences("daily_summary_schedule", Context.MODE_PRIVATE)
    private var legacyWorkCancelled = false
    private val _state = MutableStateFlow(readState())
    val state = _state.asStateFlow()

    fun canSchedulePrecisely(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    /** The toggle stays off until Android has granted timing access. */
    @Synchronized
    fun setEnabled(enabled: Boolean): Boolean {
        preferences.dailySummaryPermissionPending = false
        preferences.dailySummaryEnabled = enabled && canSchedulePrecisely()
        schedule()
        return preferences.dailySummaryEnabled == enabled
    }

    @Synchronized
    fun requestEnable(): Intent? {
        if (canSchedulePrecisely()) { setEnabled(true); return null }
        preferences.dailySummaryEnabled = false
        preferences.dailySummaryPermissionPending = true
        schedule()
        return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
    }

    @Synchronized
    fun resolvePermissionRequest() {
        if (preferences.dailySummaryPermissionPending) {
            preferences.dailySummaryEnabled = canSchedulePrecisely()
            preferences.dailySummaryPermissionPending = false
        }
        schedule()
    }

    @Synchronized
    fun permissionGranted() {
        // A system broadcast may arrive before the settings Activity returns.
        if (canSchedulePrecisely() && preferences.dailySummaryPermissionPending) resolvePermissionRequest()
        else schedule()
    }

    @Synchronized
    fun schedule() {
        retireLegacyWork()
        if (!preferences.dailySummaryEnabled || !canSchedulePrecisely()) {
            preferences.dailySummaryEnabled = false
            clearAlarm()
            refreshState()
            return
        }
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val time = LocalTime.of(preferences.dailySummaryHour, preferences.dailySummaryMinute)
        val existing = pendingOccurrence()
        val occurrence = if (existing != null && existing.zoneId == zone.id &&
            Instant.ofEpochMilli(existing.scheduledAt).atZone(zone).toLocalTime() == time &&
            now.toEpochMilli() - existing.scheduledAt <= DailySummaryPolicy.MAX_LATENESS_MS &&
            existing.date.toString() != store.getString("attempted_date", null)) existing
        else DailySummaryPolicy.next(now, zone, time, store.getString("attempted_date", null))
        scheduleOccurrence(occurrence)
    }

    @Synchronized
    fun reschedule() { clearAlarm(); schedule() }

    @Synchronized
    fun cancel() {
        preferences.dailySummaryEnabled = false
        preferences.dailySummaryPermissionPending = false
        retireLegacyWork()
        clearAlarm()
        refreshState()
    }

    @Synchronized
    internal fun pendingOccurrence(): DailySummaryOccurrence? {
        val id = store.getString("id", null) ?: return null
        val zone = store.getString("zone", null) ?: return null
        return DailySummaryOccurrence(id, store.getLong("at", 0L), zone)
    }

    @Synchronized
    internal fun scheduleOccurrence(occurrence: DailySummaryOccurrence) {
        if (!preferences.dailySummaryEnabled || !canSchedulePrecisely()) { schedule(); return }
        check(store.edit().putString("id", occurrence.id).putLong("at", occurrence.scheduledAt)
            .putString("zone", occurrence.zoneId).commit()) { "Could not save summary alarm" }
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, occurrence.scheduledAt, alarmIntent(occurrence.id))
        } catch (failure: SecurityException) {
            AppErrors.report("Scheduling daily summary alarm", failure)
            preferences.dailySummaryEnabled = false
            clearAlarm()
        }
        refreshState()
    }

    /** Persist the attempt before posting, so restarting cannot repeat a dismissed recap. */
    @Synchronized
    internal fun claim(id: String, now: Instant): DailySummaryOccurrence? {
        val occurrence = pendingOccurrence() ?: return null
        if (occurrence.id != id) return null
        if (!preferences.dailySummaryEnabled || !canSchedulePrecisely()) { schedule(); return null }
        if (now.toEpochMilli() < occurrence.scheduledAt) return null
        if (!DailySummaryPolicy.isDue(occurrence, now)) {
            recordResult("Recovery window expired", "An old daily summary was skipped.")
            reschedule()
            return null
        }
        if (store.getString("attempted_date", null) == occurrence.date.toString()) { reschedule(); return null }
        check(store.edit().putString("attempted_date", occurrence.date.toString())
            .putString("last_status", "Delivery could not be confirmed")
            .putString("last_detail", "The summary attempt started; Android posting has not been confirmed.").commit())
        clearAlarm()
        schedule() // Recalculate the next local date; never add a fixed 24 hours.
        return occurrence
    }

    @Synchronized
    internal fun recordResult(status: String, detail: String) {
        check(store.edit().putString("last_status", status).putString("last_detail", detail).commit())
        refreshState()
    }

    private fun alarmIntent(id: String) = PendingIntent.getBroadcast(
        context, REQUEST_ID,
        Intent(context, DailySummaryReceiver::class.java).setAction(ACTION_DELIVER).putExtra(EXTRA_OCCURRENCE, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun clearAlarm() {
        alarms.cancel(alarmIntent("cancelled"))
        check(store.edit().remove("id").remove("at").remove("zone").commit())
    }

    private fun retireLegacyWork() {
        if (!legacyWorkCancelled) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            legacyWorkCancelled = true
        }
    }

    private fun readState() = AppErrors.run("Reading daily summary schedule") { DailySummaryState(preferences.dailySummaryEnabled, canSchedulePrecisely(),
        if (store.contains("at")) store.getLong("at", 0L) else null,
        store.getString("last_status", "").orEmpty(), store.getString("last_detail", "").orEmpty())
    } ?: DailySummaryState(false, false, null, "Schedule unavailable", "Could not read the daily summary schedule.")

    private fun refreshState() { _state.value = readState() }
}
