package dev.zig.notificationfilter.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import dev.zig.notificationfilter.core.di.ApplicationScope
import dev.zig.notificationfilter.data.local.NativeBridge
import dev.zig.notificationfilter.data.local.db.NotificationLogDao
import dev.zig.notificationfilter.data.local.db.NotificationLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ZigNotificationListenerService : NotificationListenerService() {

    @Inject lateinit var dao: NotificationLogDao

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        evaluateNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // TODO: Forward to removal pipeline
    }

    private fun evaluateNotification(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val title = resolveTitle(sbn)

        if (NativeBridge.isAppWhitelisted(packageName)) {
            logAsync(sbn, packageName, title, status = "ALLOWED", filterReason = "APP_WHITELIST")
            return
        }

        if (NativeBridge.isContactWhitelisted(title)) {
            logAsync(sbn, packageName, title, status = "ALLOWED", filterReason = "CONTACT_WHITELIST")
            return
        }

        logAsync(sbn, packageName, title, status = "PENDING", filterReason = "REQUIRES_LLM")
    }

    private fun resolveTitle(sbn: StatusBarNotification): String {
        val extras = sbn.notification?.extras ?: return ""
        return extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: ""
    }

    private fun logAsync(
        sbn: StatusBarNotification,
        packageName: String,
        title: String,
        status: String,
        filterReason: String,
    ) {
        val content = sbn.notification?.extras
            ?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        appScope.launch {
            dao.insert(
                NotificationLogEntity(
                    packageName = packageName,
                    title = title,
                    content = content,
                    filterReason = filterReason,
                    status = status,
                    timestamp = sbn.postTime,
                )
            )
        }
    }
}
