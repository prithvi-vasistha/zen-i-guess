package dev.zig.notificationfilter.domain

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.zig.notificationfilter.core.di.ApplicationScope
import dev.zig.notificationfilter.data.local.FilterReadiness
import dev.zig.notificationfilter.data.local.FilterState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import dev.zig.notificationfilter.core.errors.launchSafely
import dev.zig.notificationfilter.core.errors.AppErrors
import javax.inject.Inject
import javax.inject.Singleton

data class NotificationHealthSnapshot(
    val notificationAccess: Boolean,
    val listenerConnected: Boolean,
    val ruleReadiness: FilterReadiness,
    val posting: NotificationPostingSettings,
    val batteryExempt: Boolean,
    val backgroundRestricted: Boolean,
    val lastNotificationAt: Long,
)

@Singleton
class NotificationHealth @Inject constructor(
    @ApplicationContext private val context: Context,
    private val publisher: NotificationPublisher,
    private val filterState: FilterState,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("notification_health", Context.MODE_PRIVATE)
    @Volatile private var connected = false
    private var reconnectJob: Job? = null
    private val _snapshot = MutableStateFlow(readSnapshot())
    val snapshot = _snapshot.asStateFlow()

    init {
        appScope.launchSafely("Observing notification readiness") { filterState.readiness.collect { refresh() } }
    }

    fun hasNotificationAccess(): Boolean = AppErrors.run("Checking notification access") {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component())
        } else context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    } ?: false

    private fun component() = ComponentName(context.packageName,
        "${context.packageName}.service.ZigNotificationListenerService")

    fun listenerConnected(value: Boolean) {
        connected = value
        ensureListenerConnected()
    }

    /** One retry sequence per process; no service instance or screen needs to stay alive. */
    fun ensureListenerConnected() {
        appScope.launchSafely("Restoring notification listener", Dispatchers.Main.immediate) {
            refresh()
            if (connected || !hasNotificationAccess()) {
                reconnectJob?.cancel()
                reconnectJob = null
                return@launchSafely
            }
            if (reconnectJob?.isActive == true) return@launchSafely
            reconnectJob = appScope.launchSafely("Reconnecting notification listener", Dispatchers.Main) {
                // Give Android time to tear down an old binding before requesting another.
                for (waitMs in listOf(1_000L, 3_000L, 10_000L, 30_000L)) {
                    delay(waitMs)
                    if (connected || !hasNotificationAccess()) {
                        refresh()
                        return@launchSafely
                    }
                    requestReconnect()
                }
            }
        }
    }

    fun notificationReceived() {
        AppErrors.run("Recording last notification time") {
        val now = System.currentTimeMillis()
        prefs.edit().putLong("last_notification_at", now).apply()
        _snapshot.value = _snapshot.value.copy(lastNotificationAt = now)
        }
    }

    fun refresh() { _snapshot.value = readSnapshot() }

    private fun readSnapshot() = AppErrors.run("Reading notification state") { NotificationHealthSnapshot(
        notificationAccess = hasNotificationAccess(),
        listenerConnected = connected,
        ruleReadiness = filterState.readiness.value,
        posting = publisher.postingSettings(),
        batteryExempt = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName),
        backgroundRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
        lastNotificationAt = prefs.getLong("last_notification_at", 0L),
    ) } ?: NotificationHealthSnapshot(false, connected, filterState.readiness.value,
        NotificationPostingSettings(false, false, false, NotificationManager.IMPORTANCE_NONE, false, false),
        false, false, 0L)

    fun requestReconnect(): Boolean {
        if (!hasNotificationAccess() || connected) return false
        return try {
            NotificationListenerService.requestRebind(component())
            Log.d("ZiG", "Requested notification listener reconnection")
            true
        } catch (failure: RuntimeException) {
            AppErrors.report("Reconnecting notification listener", failure, 60_000L)
            Log.w("ZiG", "Android rejected notification listener reconnection", failure)
            false
        }
    }

    fun openNotificationAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component().flattenToString()), reportFailure = false)) return true
        return open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
    fun openAlertChannel(channelId: String = NotificationPublisher.CHANNEL_ID) = open(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, channelId))
    fun openAppNotifications(packageName: String = context.packageName) =
        open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    fun openPhoneSettings() = open(Intent(Settings.ACTION_SETTINGS))

    private fun open(intent: Intent, reportFailure: Boolean = true): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (failure: RuntimeException) {
        if (reportFailure) AppErrors.report("Opening Android settings", failure)
        false
    }
}
