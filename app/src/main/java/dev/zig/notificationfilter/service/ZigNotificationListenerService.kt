package dev.zig.notificationfilter.service

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import dev.zig.notificationfilter.core.di.ApplicationScope
import dev.zig.notificationfilter.data.local.NativeBridge
import dev.zig.notificationfilter.data.local.db.NotificationLogDao
import dev.zig.notificationfilter.data.local.db.NotificationLogEntity
import dev.zig.notificationfilter.domain.NotificationPublisher
import dev.zig.notificationfilter.domain.llm.LlmEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ZigNotificationListenerService : NotificationListenerService() {

    @Inject lateinit var dao: NotificationLogDao

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    @Inject lateinit var llmEngine: LlmEngine
    @Inject lateinit var notificationPublisher: NotificationPublisher

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        evaluateNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // TODO: Forward to removal pipeline
    }

    private fun evaluateNotification(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val title = resolveTitle(sbn)
        val content = sbn.notification?.extras
            ?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        // Fast Check 1: opt-in gate — notifications from unmanaged apps are ignored entirely.
        if (!NativeBridge.isAppManaged(packageName)) return

        // Fast Check 2: whitelisted contacts skip LLM inference and are published immediately.
        if (NativeBridge.isContactWhitelisted(title)) {
            notificationPublisher.publish(packageName, title, content)
            logAsync(sbn, packageName, title, status = "ALLOWED_CONTACT", filterReason = "CONTACT_WHITELIST")
            return
        }

        // AI Lane: all remaining managed-app notifications are evaluated by the on-device LLM.
        appScope.launch {
            val appName = try {
                val info = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                packageManager.getApplicationLabel(info).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                packageName
            }

            val category = sbn.notification?.category ?: "None"
            val channelId = sbn.notification?.channelId ?: "None"

            val notificationMetadataBlock = """
                Package Name: $packageName
                App Name: $appName
                Category: $category
                Title: $title
                Body: $content
                Channel ID: $channelId
                Timestamp: ${sbn.postTime}
            """.trimIndent()

            val allowed = llmEngine.evaluate(notificationMetadataBlock)

            if (allowed) {
                notificationPublisher.publish(packageName, title, content)
                dao.insert(
                    NotificationLogEntity(
                        packageName = packageName,
                        title = title,
                        content = content,
                        filterReason = "LLM_INFERENCE",
                        status = "ALLOWED_LLM",
                        timestamp = sbn.postTime,
                    )
                )
            } else {
                dao.insert(
                    NotificationLogEntity(
                        packageName = packageName,
                        title = title,
                        content = content,
                        filterReason = "LLM_INFERENCE",
                        status = "BLOCKED_LLM",
                        timestamp = sbn.postTime,
                    )
                )
            }
        }
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
