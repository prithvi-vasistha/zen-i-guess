package dev.zig.notificationfilter.domain.summary

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.zig.notificationfilter.data.local.db.AppDatabase
import dev.zig.notificationfilter.data.local.db.NotificationReviewEntity
import dev.zig.notificationfilter.data.local.db.NotificationReviewDao
import dev.zig.notificationfilter.data.local.db.NotificationLogDao
import dev.zig.notificationfilter.data.local.db.NotificationLogEntity
import dev.zig.notificationfilter.core.errors.AppErrors
import android.database.sqlite.SQLiteException
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import dev.zig.notificationfilter.domain.NotificationDeliveryStatus
import dev.zig.notificationfilter.domain.NotificationPublisher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DailySummaryAlarmTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val deps = EntryPointAccessors.fromApplication(context, SummaryTestDependencies::class.java)
    private val scheduler get() = deps.scheduler()
    private val prefs get() = deps.preferences()
    private val database get() = deps.database()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val token = UUID.randomUUID().toString()
    private val stageOnly get() = InstrumentationRegistry.getArguments().getString("summaryStageOnly") == "true"
    private val originalEnabled = prefs.dailySummaryEnabled
    private val originalHour = prefs.dailySummaryHour
    private val originalMinute = prefs.dailySummaryMinute
    private val store = context.getSharedPreferences("daily_summary_schedule", Context.MODE_PRIVATE)
    private val originalStore = store.all.toMap()
    private val occurrences = mutableListOf<DailySummaryOccurrence>()
    private var configuredTestPin = false

    @Before fun prepare() {
        scheduler.cancel()
        store.edit().clear().commit()
        manager.cancel(NotificationPublisher.NOTIF_ID_SUMMARY)
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
    }

    @After fun cleanup() = runBlocking {
        if (stageOnly) return@runBlocking
        shell("dumpsys deviceidle unforce")
        shell("dumpsys battery reset")
        if (configuredTestPin) {
            shell("locksettings clear --old 2468")
            val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
            waitUntil { !keyguard.isDeviceSecure }
            shell("input keyevent KEYCODE_WAKEUP")
            // Clearing a PIN and waking are asynchronous; dismiss again after the
            // keyguard finishes drawing rather than leaving the next test locked.
            waitUntil { shell("wm dismiss-keyguard"); !keyguard.isKeyguardLocked }
        }
        scheduler.cancel()
        manager.cancel(NotificationPublisher.NOTIF_ID_SUMMARY)
        database.openHelper.writableDatabase.execSQL("DELETE FROM notification_review WHERE jobId LIKE ?", arrayOf("summary-test-$token%"))
        occurrences.forEach {
            database.openHelper.writableDatabase.execSQL("DELETE FROM notification_log WHERE jobId = ?", arrayOf("summary-${it.id}"))
        }
        val editor = store.edit().clear()
        originalStore.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Long -> editor.putLong(key, value)
            is Boolean -> editor.putBoolean(key, value)
        } }
        editor.commit()
        prefs.dailySummaryHour = originalHour
        prefs.dailySummaryMinute = originalMinute
        prefs.dailySummaryEnabled = originalEnabled
        scheduler.schedule()
    }

    @Test fun a_optionalAccessCanBeDeclinedAndTheToggleRetried() = runBlocking {
        assertFalse(prefs.dailySummaryEnabled)
        if (!scheduler.canSchedulePrecisely()) {
            val request = checkNotNull(scheduler.requestEnable())
            assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, request.action)
            assertEquals("package:${context.packageName}", request.data.toString())
            assertFalse(prefs.dailySummaryEnabled)
            assertTrue(prefs.dailySummaryPermissionPending)
            scheduler.resolvePermissionRequest() // Returning without allowing access.
            assertFalse(prefs.dailySummaryEnabled)
            assertFalse(prefs.dailySummaryPermissionPending)
            assertNull(scheduler.pendingOccurrence())
            assertNotNull(scheduler.requestEnable()) // A later toggle requests again.
        }
        scheduler.cancel() // Skip is an ordinary, completed optional choice.
        assertFalse(prefs.dailySummaryEnabled)
        assertFalse(prefs.dailySummaryPermissionPending)
        allowTiming()
        assertNull(scheduler.requestEnable())
        assertTrue(prefs.dailySummaryEnabled)
        assertNotNull(scheduler.pendingOccurrence())
    }

    @Test fun b_duplicateAlarmCannotResurrectADismissedSummaryAcrossSchedulerRestart() = runBlocking {
        allowTiming()
        val occurrence = installFutureOccurrence()
        seed(occurrence.startOfDay + 1_000L, "MODEL_BLOCKED")
        deps.delivery().deliver(occurrence.id, Instant.ofEpochMilli(occurrence.scheduledAt))
        assertEquals("Posted to Android", scheduler.state.value.lastStatus)
        assertTrue(manager.activeNotifications.any { it.id == NotificationPublisher.NOTIF_ID_SUMMARY })
        manager.cancel(NotificationPublisher.NOTIF_ID_SUMMARY)
        // Android processes cancellation asynchronously; wait for the dismissed state
        // before checking whether a restarted delivery can bring the notification back.
        waitUntil { manager.activeNotifications.none { it.id == NotificationPublisher.NOTIF_ID_SUMMARY } }
        val restarted = DailySummaryScheduler(context, prefs)
        val restartedDelivery = DailySummaryDelivery(restarted, database.notificationReviewDao(),
            database.notificationLogDao(), deps.publisher(), prefs)
        restartedDelivery.deliver(occurrence.id, Instant.ofEpochMilli(occurrence.scheduledAt + 1_000))
        assertFalse(manager.activeNotifications.any { it.id == NotificationPublisher.NOTIF_ID_SUMMARY })
        assertEquals(1, database.notificationLogDao().getLogsForJobs(listOf("summary-${occurrence.id}"))
            .count { it.status == "SUMMARY_POSTED" })
    }

    @Test fun c_countUsesTheScheduledDayAndExcludesRulesAndDemoCards() = runBlocking {
        val occurrence = futureOccurrence()
        seed(occurrence.startOfDay - 1, "MODEL_BLOCKED")
        seed(occurrence.startOfDay, "MODEL_BLOCKED")
        seed(occurrence.scheduledAt, "PUBLISHED")
        seed(occurrence.scheduledAt + 1, "PUBLISHED")
        seed(occurrence.startOfDay + 2_000, "KEYWORD_PASS")
        val demoId = "demo-summary-test-$token"
        database.notificationReviewDao().insert(NotificationReviewEntity(jobId = demoId, packageName = "test", title = "demo",
            content = "", timestamp = occurrence.startOfDay + 3_000, systemDecision = "PUBLISHED"))
        try {
            assertEquals(2, database.notificationReviewDao().countReviewableBetween(occurrence.startOfDay, occurrence.scheduledAt))
        } finally { database.openHelper.writableDatabase.execSQL("DELETE FROM notification_review WHERE jobId = ?", arrayOf(demoId)) }
    }

    @Test fun d_emptySummaryIsRecordedWithoutPostingAnAlert() = runBlocking {
        allowTiming()
        val occurrence = installFutureOccurrence()
        deps.delivery().deliver(occurrence.id, Instant.ofEpochMilli(occurrence.scheduledAt))
        assertEquals("Nothing to review", scheduler.state.value.lastStatus)
        assertFalse(manager.activeNotifications.any { it.id == NotificationPublisher.NOTIF_ID_SUMMARY })
    }

    @Test fun e_summaryReportsMissingNotificationPermission() = runBlocking {
        allowTiming()
        val occurrence = installFutureOccurrence()
        seed(occurrence.startOfDay + 1_000, "PUBLISHED")
        val denied = object : ContextWrapper(context) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (permission == android.Manifest.permission.POST_NOTIFICATIONS) PackageManager.PERMISSION_DENIED
                else super.checkPermission(permission, pid, uid)
        }
        DailySummaryDelivery(scheduler, database.notificationReviewDao(), database.notificationLogDao(),
            NotificationPublisher(denied), prefs).deliver(occurrence.id, Instant.ofEpochMilli(occurrence.scheduledAt))
        assertEquals(NotificationDeliveryStatus.NOT_POSTED_PERMISSION.label, scheduler.state.value.lastStatus)
        assertFalse(manager.activeNotifications.any { it.id == NotificationPublisher.NOTIF_ID_SUMMARY })
    }

    @Test fun f_disabledSummaryChannelIsAnExplicitPostingFailure() {
        val channel = "summary-disabled-$token"
        manager.createNotificationChannel(NotificationChannel(channel, "Muted recap", NotificationManager.IMPORTANCE_NONE))
        try {
            val result = deps.publisher().postDailySummary(3, channelId = channel)
            assertEquals(NotificationDeliveryStatus.NOT_POSTED_CHANNEL_BLOCKED, result.status)
            assertTrue(result.detail.contains("Daily Summary"))
            assertFalse(result.posted)
        } finally { manager.deleteNotificationChannel(channel) }
    }

    @Test fun g_disablingOrChangingTimeRejectsTheOldAlarm() = runBlocking {
        allowTiming()
        val occurrence = installFutureOccurrence()
        prefs.dailySummaryHour = 21
        prefs.dailySummaryMinute = 13
        scheduler.reschedule()
        val replacement = checkNotNull(scheduler.pendingOccurrence())
        assertNotEquals(occurrence.id, replacement.id)
        assertEquals(LocalTime.of(21, 13), Instant.ofEpochMilli(replacement.scheduledAt).atZone(ZoneId.systemDefault()).toLocalTime())
        deps.delivery().deliver(occurrence.id, Instant.ofEpochMilli(occurrence.scheduledAt))
        assertFalse(manager.activeNotifications.any { it.id == NotificationPublisher.NOTIF_ID_SUMMARY })
        scheduler.cancel()
        deps.delivery().deliver(replacement.id, Instant.ofEpochMilli(replacement.scheduledAt))
        assertNull(scheduler.pendingOccurrence())
        assertFalse(manager.activeNotifications.any { it.id == NotificationPublisher.NOTIF_ID_SUMMARY })
    }

    @Test fun h_alarmWakesLockedPhoneDuringDoze() = runBlocking {
        allowTiming()
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) {
            assertTrue("PIN fixture is for an emulator", android.os.Build.HARDWARE == "ranchu" ||
                android.os.Build.HARDWARE == "goldfish" || android.os.Build.PRODUCT.startsWith("sdk_gphone"))
            shell("locksettings set-pin 2468")
            configuredTestPin = true
        }
        shell("input keyevent KEYCODE_SLEEP")
        waitUntil { keyguard.isKeyguardLocked }
        val delayMs = InstrumentationRegistry.getArguments().getString("summaryDelayMs")?.toLong() ?: 8_000L
        val at = if (stageOnly) ((System.currentTimeMillis() + delayMs) / 60_000L + 1) * 60_000L
            else System.currentTimeMillis() + delayMs
        prefs.dailySummaryEnabled = true
        val occurrence = DailySummaryOccurrence(token, at, ZoneId.systemDefault().id)
        occurrences.add(occurrence)
        val target = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalTime()
        prefs.dailySummaryHour = target.hour
        prefs.dailySummaryMinute = target.minute
        seed(System.currentTimeMillis() - 1_000, "MODEL_BLOCKED")
        scheduler.scheduleOccurrence(occurrence)
        shell("dumpsys battery unplug")
        shell("dumpsys deviceidle force-idle")
        assertTrue(shell("dumpsys deviceidle get deep").contains("IDLE"))
        if (stageOnly) { assertEquals(occurrence, scheduler.pendingOccurrence()); return@runBlocking }
        waitUntil { scheduler.state.value.lastStatus == "Posted to Android" }
        assertTrue(manager.activeNotifications.any { it.id == NotificationPublisher.NOTIF_ID_SUMMARY })
        assertTrue("Summary must post before the phone is unlocked", keyguard.isKeyguardLocked)
        assertNotEquals(occurrence.id, scheduler.pendingOccurrence()?.id)
    }

    private suspend fun allowTiming() {
        if (!scheduler.canSchedulePrecisely()) shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        waitUntil { scheduler.canSchedulePrecisely() }
    }

    @Test fun i_sqlFailureDuringSummaryIsReportedWithoutCrashingDelivery() = runBlocking {
        allowTiming()
        val occurrence = installFutureOccurrence()
        val failingReview = object : NotificationReviewDao by database.notificationReviewDao() {
            override suspend fun countReviewableBetween(start: Long, end: Long): Int {
                throw SQLiteException("Forced summary count SQL failure")
            }
        }
        try {
            DailySummaryDelivery(scheduler, failingReview, database.notificationLogDao(), deps.publisher(), prefs)
                .deliver(occurrence.id, Instant.ofEpochMilli(occurrence.scheduledAt))
            assertEquals("Daily summary failed", scheduler.state.value.lastStatus)
            assertFalse(manager.activeNotifications.any { it.id == NotificationPublisher.NOTIF_ID_SUMMARY })
            assertTrue(AppErrors.pending.value.any { it.operation == "Delivering daily summary" &&
                it.detail.contains("Forced summary count SQL failure") })
        } finally {
            AppErrors.pending.value.filter { it.detail.contains("Forced summary count SQL failure") }
                .forEach { AppErrors.dismiss(it.id) }
        }
    }

    @Test fun j_failedSummaryLogDoesNotUndoAnAlertThatWasSuccessfullyPosted() = runBlocking {
        allowTiming()
        val occurrence = installFutureOccurrence()
        seed(occurrence.startOfDay + 1_000L, "PUBLISHED")
        val failingLog = object : NotificationLogDao by database.notificationLogDao() {
            override suspend fun insert(entity: NotificationLogEntity) {
                throw SQLiteException("Forced summary log SQL failure")
            }
        }
        try {
            DailySummaryDelivery(scheduler, database.notificationReviewDao(), failingLog, deps.publisher(), prefs)
                .deliver(occurrence.id, Instant.ofEpochMilli(occurrence.scheduledAt))
            assertEquals("Posted to Android", scheduler.state.value.lastStatus)
            assertTrue(manager.activeNotifications.any { it.id == NotificationPublisher.NOTIF_ID_SUMMARY })
            assertTrue(AppErrors.pending.value.any { it.operation == "Saving daily summary log" &&
                it.detail.contains("Forced summary log SQL failure") })
        } finally {
            AppErrors.pending.value.filter { it.detail.contains("Forced summary log SQL failure") }
                .forEach { AppErrors.dismiss(it.id) }
        }
    }

    private fun futureOccurrence() = DailySummaryOccurrence(token,
        Instant.parse("2050-06-15T20:00:00Z").toEpochMilli(), "UTC")

    private fun installFutureOccurrence(): DailySummaryOccurrence {
        prefs.dailySummaryEnabled = true
        return futureOccurrence().also { occurrences.add(it); scheduler.scheduleOccurrence(it) }
    }

    private suspend fun seed(timestamp: Long, decision: String) {
        database.notificationReviewDao().insert(NotificationReviewEntity(jobId = "summary-test-$token-${UUID.randomUUID()}",
            packageName = "test", title = "A local notification", content = "For summary verification", timestamp = timestamp,
            systemDecision = decision))
    }

    private suspend fun waitUntil(check: () -> Boolean) = withTimeout(25_000L) {
        while (!check()) delay(100L)
    }

    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand(command).use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader().use { it.readText() } }
}
