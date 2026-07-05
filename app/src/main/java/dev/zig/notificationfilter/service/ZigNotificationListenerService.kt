package dev.zig.notificationfilter.service

import android.app.KeyguardManager
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
// import android.content.pm.PackageManager  // Phase 1: unused — restored if needed in future lanes
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.zig.notificationfilter.core.di.ApplicationScope
import dev.zig.notificationfilter.data.local.NativeBridge
import dev.zig.notificationfilter.data.local.db.NotificationLogDao
import dev.zig.notificationfilter.data.local.db.NotificationLogEntity
import dev.zig.notificationfilter.data.local.db.NotificationReviewDao
import dev.zig.notificationfilter.data.local.db.NotificationReviewEntity
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import dev.zig.notificationfilter.domain.NotificationPublisher
import dev.zig.notificationfilter.domain.classifier.DecisionSource
import dev.zig.notificationfilter.domain.classifier.EnsembleClassifier
import dev.zig.notificationfilter.domain.classifier.EnsembleResult
import dev.zig.notificationfilter.domain.classifier.NotificationCategory
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

    // Initialized lazily so getSystemService() is called after onCreate().
    private val keyguardManager: KeyguardManager by lazy {
        getSystemService(KeyguardManager::class.java)
    }

    @Inject lateinit var dao: NotificationLogDao
    @Inject lateinit var reviewDao: NotificationReviewDao
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope
    @Inject lateinit var ensembleClassifier: EnsembleClassifier
    @Inject lateinit var notificationPublisher: NotificationPublisher
    @Inject lateinit var preferences: ZigUserPreferences

    // Keys of notifications marked sensitive (VISIBILITY_PRIVATE) that arrived while the device
    // was locked. Android delivers redacted text to listeners on the lock screen, so we cannot
    // classify them then; we record their keys and re-fetch the full content once the user
    // unlocks (see [unlockReceiver] / [processDeferredOnUnlock]). Guarded by synchronized(this).
    private val deferredKeys = LinkedHashSet<String>()

    // Cap so a device left locked for a long time can't accumulate an unbounded backlog.
    private val maxDeferred = 100

    // Fires on device unlock (ACTION_USER_PRESENT); triggers re-classification of deferred keys.
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                processDeferredOnUnlock()
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        // ACTION_USER_PRESENT is a protected system broadcast and cannot be received via the
        // manifest on API 26+, so it is registered at runtime for the listener's lifetime.
        ContextCompat.registerReceiver(
            this,
            unlockReceiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        try {
            unregisterReceiver(unlockReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was never registered (connect failed) — nothing to unregister.
        }
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

    // Re-classifies notifications that were deferred while the device was locked. Invoked on
    // unlock via [unlockReceiver]. Drains the recorded keys, re-fetches their current (now
    // un-redacted) content via getActiveNotifications, and runs each back through the pipeline;
    // since the device is unlocked, the deferral branch is skipped and they flow through
    // contacts → keywords → the model, which publishes the genuine ones and suppresses spam.
    private fun processDeferredOnUnlock() {
        val keys = synchronized(deferredKeys) {
            if (deferredKeys.isEmpty()) return
            deferredKeys.toTypedArray().also { deferredKeys.clear() }
        }
        appScope.launch {
            val active = try {
                getActiveNotifications(keys)
            } catch (_: Exception) {
                // Listener not connected or a transient failure — nothing to process.
                null
            } ?: return@launch
            active.forEach { evaluateNotification(it) }
        }
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
        val content = resolveContent(sbn.notification)
        if (title.isBlank() && content.isBlank()) return
        // Pre-format the classifier input once here so it is available to every gate,
        // to the review() helper, and to the exact-match cache check in the ensemble.
        val text = listOf(title, content).filter { it.isNotBlank() }.joinToString(" ")
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

        // Locked screen + sender-marked-sensitive (VISIBILITY_PRIVATE). Behaviour is governed
        // by the user's "Sensitive notifications" setting:
        //
        //  • ON (default) — VIP hall-pass: show it immediately without filtering. (Some users
        //    prefer never to miss a sensitive alert on the lock screen.)
        //
        //  • OFF — defer: Android delivers redacted text ("sensitive content" placeholder) to
        //    listeners while locked, so classifying now is meaningless. Record the key, publish
        //    nothing (no disturbance), and re-fetch the full content to classify on unlock
        //    (see processDeferredOnUnlock).
        if (keyguardManager.isKeyguardLocked &&
            sbn.notification?.visibility == Notification.VISIBILITY_PRIVATE) {
            if (preferences.sensitiveNotificationsEnabled) {
                log(jobId, packageName, title, content, "LOCKED_PASS",
                    "Device locked + VISIBILITY_PRIVATE — sensitive notifications enabled, bypassing filter")
                notificationPublisher.publish(packageName, title, content, contentIntent, originalKey, sbn.id, sbn.notification?.category)
                log(jobId, packageName, title, content, "PUBLISHED",
                    "Forwarded to user via locked-screen bypass")
                review(jobId, packageName, title, content, sbn.postTime, "LOCKED_PASS", messageText = text)
            } else {
                synchronized(deferredKeys) {
                    deferredKeys.add(originalKey)
                    // Bound the backlog: drop the oldest once we exceed the cap.
                    if (deferredKeys.size > maxDeferred) {
                        deferredKeys.remove(deferredKeys.first())
                    }
                }
                log(jobId, packageName, title, content, "LOCKED_DEFERRED",
                    "Device locked + VISIBILITY_PRIVATE — deferred until unlock for classification")
            }
            return
        }

        // Gate 2: contact whitelist (Tier 1 — highest priority, bypasses classifier)
        // Lookup is lowercased to match the normalised names stored by ContactsSyncManager.
        if (NativeBridge.isContactWhitelisted(title.trim().lowercase())) {
            log(jobId, packageName, title, content, "CONTACT_PASS",
                "Title \"$title\" matched contact whitelist")
            notificationPublisher.publish(packageName, title, content, contentIntent, originalKey, sbn.id, sbn.notification?.category)
            log(jobId, packageName, title, content, "PUBLISHED",
                "Forwarded to user via contact whitelist")
            review(jobId, packageName, title, content, sbn.postTime, "CONTACT_PASS", messageText = text)
            return
        }
        log(jobId, packageName, title, content, "CONTACT_MISS",
            "Title \"$title\" not in contact whitelist")

        // Gate 3: keyword rules (Tier 2 — deterministic, bypasses classifier)
        if (NativeBridge.containsWhitelistedKeyword(content)) {
            log(jobId, packageName, title, content, "KEYWORD_PASS",
                "Body matched a keyword rule")
            notificationPublisher.publish(packageName, title, content, contentIntent, originalKey, sbn.id, sbn.notification?.category)
            log(jobId, packageName, title, content, "PUBLISHED",
                "Forwarded to user via keyword match")
            review(jobId, packageName, title, content, sbn.postTime, "KEYWORD_PASS", messageText = text)
            return
        }
        log(jobId, packageName, title, content, "KEYWORD_MISS",
            "No keyword rule matched — escalating to on-device classifier")

        // ── Phase 1 LLM lane (archived) ────────────────────────────────────────
        // val appName = try {
        //     val info = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        //     packageManager.getApplicationLabel(info).toString()
        // } catch (_: PackageManager.NameNotFoundException) { packageName }
        // val metadataBlock = "Package Name: $packageName\nApp Name: $appName\n..."
        // log(jobId, ..., "LLM_INVOKED", ...)
        // val allowed = llmEngine.evaluate(metadataBlock)
        // ──────────────────────────────────────────────────────────────────────

        // Classifier lane: the RAC ensemble evaluates notifications that passed all
        // deterministic gates — exact-match cache → base TFLite model (Base Instinct) →
        // KNN over Personal Memory. Fails open (allows) if the model is not yet installed,
        // logging MODEL_ERROR so it is visible in the Logs tab.
        val category = NotificationCategory.resolve(packageName, text)

        log(jobId, packageName, title, content, "MODEL_INVOKED",
            "Sending to on-device ensemble [$category]")

        val result = try {
            ensembleClassifier.evaluate(category, packageName, text)
        } catch (e: Exception) {
            log(jobId, packageName, title, content, "MODEL_ERROR",
                "Inference failed: ${e.message} — failing open")
            // Fail open: allow the notification and record zero confidence so the
            // active learning loop can identify model errors in the training export.
            EnsembleResult(
                allowed = true,
                source = DecisionSource.BASE_MODEL,
                baseConfidence = 0f,
                category = category,
                topSimilarity = 0f,
                consensusShare = 0f,
                neighborCount = 0,
            )
        }

        val inferredCategory = "CATEGORY_${result.category.name}"

        // Provenance logs: surface the decision source explicitly for the Logs tab.
        when (result.source) {
            DecisionSource.EXACT_MATCH_OVERRIDE -> {
                val verdict = if (result.allowed) "ALLOW" else "BLOCK"
                log(jobId, packageName, title, content, "EXACT_MATCH_$verdict",
                    "Exact-match cache hit — replaying past manual decision → $verdict (no ML invoked)")
            }
            DecisionSource.PERSONAL_MEMORY -> {
                val verdict = if (result.allowed) "ALLOW" else "BLOCK"
                log(jobId, packageName, title, content, "MEMORY_OVERRIDE_$verdict",
                    "Personal Memory vetoed base model → $verdict " +
                        "(topSim=${result.topSimilarity}, consensus=${result.consensusShare}, " +
                        "neighbors=${result.neighborCount})")
            }
            DecisionSource.BASE_MODEL -> Unit
        }

        val decidedBy = when (result.source) {
            DecisionSource.EXACT_MATCH_OVERRIDE -> "exact-match cache"
            DecisionSource.PERSONAL_MEMORY -> "Personal Memory"
            DecisionSource.BASE_MODEL -> "base classifier"
        }

        if (result.allowed) {
            log(jobId, packageName, title, content, "MODEL_ALLOWED",
                "Allowed by $decidedBy (base score ${result.baseConfidence}) — forwarding")
            notificationPublisher.publish(packageName, title, content, contentIntent, originalKey, sbn.id, sbn.notification?.category)
            log(jobId, packageName, title, content, "PUBLISHED",
                "Forwarded to user via $decidedBy")
            review(jobId, packageName, title, content, sbn.postTime, "PUBLISHED",
                result.baseConfidence, inferredCategory, text)
        } else {
            log(jobId, packageName, title, content, "MODEL_BLOCKED",
                "Blocked by $decidedBy (base score ${result.baseConfidence}) — suppressed")
            review(jobId, packageName, title, content, sbn.postTime, "MODEL_BLOCKED",
                result.baseConfidence, inferredCategory, text)
        }
    }

    // Extracts the best available text from a notification.
    // For MessagingStyle notifications (WhatsApp, Messages, etc.), EXTRA_MESSAGES contains
    // all messages in the conversation in chronological order. Joining them with newlines
    // gives the classifier and the review screen full conversation context instead of
    // just the latest message from EXTRA_TEXT.
    private fun resolveContent(notification: android.app.Notification?): String {
        val extras = notification?.extras ?: return ""
        @Suppress("DEPRECATION")
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            ?.filterIsInstance<Bundle>()
        if (!messages.isNullOrEmpty()) {
            return messages
                .mapNotNull { it.getCharSequence("text")?.toString()?.takeIf { msg -> msg.isNotBlank() } }
                .joinToString("\n")
        }
        return extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
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
        modelConfidence: Float = 0f,
        inferredCategory: String = "UNKNOWN",
        messageText: String = "",
    ) {
        reviewDao.insert(
            NotificationReviewEntity(
                jobId = jobId,
                packageName = packageName,
                title = title,
                content = content,
                timestamp = timestamp,
                systemDecision = systemDecision,
                modelConfidence = modelConfidence,
                inferredCategory = inferredCategory,
                messageText = messageText,
            ),
        )
    }
}
