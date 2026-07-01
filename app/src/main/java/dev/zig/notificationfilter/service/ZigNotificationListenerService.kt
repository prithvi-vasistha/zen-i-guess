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
import dev.zig.notificationfilter.data.local.db.NotificationReviewDao
import dev.zig.notificationfilter.data.local.db.NotificationReviewEntity
import dev.zig.notificationfilter.domain.NotificationPublisher
import dev.zig.notificationfilter.domain.llm.LlmEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class ZigNotificationListenerService : NotificationListenerService() {

    companion object {
        // Nullable reference used by NotificationActionReceiver to call cancelNotification().
        // Volatile because the receiver reads it from a different thread.
        @Volatile var instance: ZigNotificationListenerService? = null
    }

    @Inject lateinit var dao: NotificationLogDao
    @Inject lateinit var reviewDao: NotificationReviewDao
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope
    @Inject lateinit var llmEngine: LlmEngine
    @Inject lateinit var notificationPublisher: NotificationPublisher

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Launch on appScope (Dispatchers.IO + SupervisorJob) so the entire pipeline
        // including all dao.insert() calls runs off the main thread. A crash in one
        // notification's coroutine does not affect others.
        appScope.launch { evaluateNotification(sbn) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // TODO: Forward to removal pipeline
    }

    private suspend fun evaluateNotification(sbn: StatusBarNotification) {
        val packageName = sbn.packageName

        // ── Pre-flight structural filters ──────────────────────────────────────
        // These drop noise before entering the tracked pipeline — no log row,
        // no UUID, no DB write. Order matters: cheapest checks first.

        // 1. ZiG's own forwarded notifications must never re-enter the pipeline.
        if (packageName == this.packageName) return

        // 2. Ongoing notifications are background tasks, syncs, and media players.
        if (sbn.isOngoing) return

        // 3. OS service and system-category notifications are infrastructure noise.
        val notifCategory = sbn.notification?.category
        if (notifCategory == Notification.CATEGORY_SERVICE ||
            notifCategory == Notification.CATEGORY_SYSTEM) return

        // 4. Notifications with no visible text are shell/update pings — nothing to show.
        val title = resolveTitle(sbn)
        val content = sbn.notification?.extras
            ?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isBlank() && content.isBlank()) return
        // ──────────────────────────────────────────────────────────────────────

        val jobId = UUID.randomUUID().toString().take(8)
        val contentIntent = sbn.notification?.contentIntent
        val originalKey = sbn.key

        log(jobId, packageName, title, content, "RECEIVED",
            "Notification arrived from $packageName")

        // Gate 1: opt-in managed apps list
        if (!NativeBridge.isAppManaged(packageName)) {
            log(jobId, packageName, title, content, "MANAGED_FAIL",
                "App not in managed list — dropped")
            review(jobId, packageName, title, content, sbn.postTime, "MANAGED_FAIL")
            return
        }
        log(jobId, packageName, title, content, "MANAGED_PASS", "App is managed")

        // Gate 2: contact whitelist (Tier 1 — highest priority, bypasses LLM)
        // Lookup is lowercased to match the normalised names stored by ContactsSyncManager.
        if (NativeBridge.isContactWhitelisted(title.trim().lowercase())) {
            log(jobId, packageName, title, content, "CONTACT_PASS",
                "Title \"$title\" matched contact whitelist")
            notificationPublisher.publish(packageName, title, content, contentIntent, originalKey, sbn.id)
            log(jobId, packageName, title, content, "PUBLISHED",
                "Forwarded to user via contact whitelist")
            review(jobId, packageName, title, content, sbn.postTime, "CONTACT_PASS")
            return
        }
        log(jobId, packageName, title, content, "CONTACT_MISS",
            "Title \"$title\" not in contact whitelist")

        // Gate 3: keyword rules (Tier 2 — deterministic, bypasses LLM)
        if (NativeBridge.containsWhitelistedKeyword(content)) {
            log(jobId, packageName, title, content, "KEYWORD_PASS",
                "Body matched a keyword rule")
            notificationPublisher.publish(packageName, title, content, contentIntent, originalKey, sbn.id)
            log(jobId, packageName, title, content, "PUBLISHED",
                "Forwarded to user via keyword match")
            review(jobId, packageName, title, content, sbn.postTime, "KEYWORD_PASS")
            return
        }
        log(jobId, packageName, title, content, "KEYWORD_MISS",
            "No keyword rule matched — escalating to LLM")

        // LLM lane: on-device model evaluates notifications that passed all Rust gates
        val appName = try {
            val info = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }

        val category = sbn.notification?.category ?: "None"
        val channelId = sbn.notification?.channelId ?: "None"

        val metadataBlock = """
            Package Name: $packageName
            App Name: $appName
            Category: $category
            Title: $title
            Body: $content
            Channel ID: $channelId
            Timestamp: ${sbn.postTime}
        """.trimIndent()

        log(jobId, packageName, title, content, "LLM_INVOKED",
            "Sending to on-device LLM for evaluation")
        val allowed = llmEngine.evaluate(metadataBlock)

        if (allowed) {
            log(jobId, packageName, title, content, "LLM_ALLOWED", "Model returned: TRUE")
            notificationPublisher.publish(packageName, title, content, contentIntent, originalKey, sbn.id)
            log(jobId, packageName, title, content, "PUBLISHED",
                "Forwarded to user via LLM decision")
            // systemDecision="PUBLISHED" groups all LLM-allowed rows under one label so the
            // review screen can show them alongside suppressed rows in a full history view.
            review(jobId, packageName, title, content, sbn.postTime, "PUBLISHED")
        } else {
            log(jobId, packageName, title, content, "LLM_BLOCKED",
                "Model returned: FALSE — notification suppressed")
            review(jobId, packageName, title, content, sbn.postTime, "LLM_BLOCKED")
        }
    }

    private fun resolveTitle(sbn: StatusBarNotification): String {
        val extras = sbn.notification?.extras ?: return ""
        return extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: ""
    }

    private suspend fun log(
        jobId: String,
        packageName: String,
        title: String,
        content: String,
        status: String,
        reason: String,
    ) {
        dao.insert(
            NotificationLogEntity(
                jobId = jobId,
                packageName = packageName,
                title = title,
                content = content,
                status = status,
                filterReason = reason,
                timestamp = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun review(
        jobId: String,
        packageName: String,
        title: String,
        content: String,
        timestamp: Long,
        systemDecision: String,
    ) {
        reviewDao.insert(
            NotificationReviewEntity(
                jobId = jobId,
                packageName = packageName,
                title = title,
                content = content,
                timestamp = timestamp,
                systemDecision = systemDecision,
            ),
        )
    }
}
