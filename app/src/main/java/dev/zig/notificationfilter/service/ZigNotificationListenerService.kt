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

        // 1. ZiG's own notifications must not re-enter the pipeline.
        if (packageName == this.packageName) return

        // 2. Ongoing notifications are background tasks, syncs, and media players.
        if (sbn.isOngoing) return

        // 3. OS service and system-category notifications are infrastructure noise.
        val notifCategory = sbn.notification?.category
        if (notifCategory == Notification.CATEGORY_SERVICE ||
            notifCategory == Notification.CATEGORY_SYSTEM) return

        // 4. Keep image-only notifications so the final image gate can inspect them.
        // A private notification may be redacted to nothing while locked; when image
        // filtering is enabled, retain it for re-evaluation after unlock too.
        val title = resolveTitle(sbn)
        val content = resolveContent(sbn.notification)
        val hasImage = NotificationImageDetector.hasImage(sbn.notification)
        val privateWhileLocked = keyguardManager.isKeyguardLocked &&
            sbn.notification.visibility == Notification.VISIBILITY_PRIVATE
        if (title.isBlank() && content.isBlank() && !hasImage &&
            !(privateWhileLocked && !preferences.imageNotificationsEnabled)) return
        // Pre-format the classifier input once here so it is available to every gate,
        // to the review() helper, and to the exact-match cache check in the ensemble.
        val text = listOf(title, content).filter { it.isNotBlank() }.joinToString(" ")
        // Exact-match cache key. For a MessagingStyle notification `content` is the WHOLE
        // conversation (all of EXTRA_MESSAGES joined), so it grows with every new message and
        // would never match a previously-stored key. The key must instead be the single newest
        // message — the one that actually triggered this notification — so a repeat of the same
        // message reproduces the same key. For non-messaging notifications this equals `text`.
        val latestMessage = resolveLatestMessage(sbn.notification)
        val cacheKey = listOf(title, latestMessage).filter { it.isNotBlank() }.joinToString(" ")
        // Content stored on the review row (and shown on the Notifications/Archive tab): the
        // single newest message, so each row reads as a standalone message rather than the
        // accumulating MessagingStyle thread. Classification (`text`) and the re-published panel
        // notification keep the full conversation, so notification-panel chaining is unaffected.
        // For non-messaging notifications latestMessage == content, so this is a no-op there.
        val displayContent = latestMessage.ifBlank { content }
            .ifBlank { if (hasImage) "Image attachment" else "" }
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
            review(jobId, packageName, title, displayContent, sbn.postTime, "MANAGED_FAIL")
            return
        }
        log(jobId, packageName, title, content, "MANAGED_PASS", "App is managed")

        // Honour the preference explicitly as well as the Rust set: a contact sync already
        // in flight when the user disables bypass must not create an image exemption.
        val contactMatched = preferences.contactsBypassEnabled &&
            NativeBridge.isContactWhitelisted(title.trim().lowercase())

        // Every allow path uses this final gate, including the locked-screen shortcut.
        // Contact matches remain exempt; keyword/model/cache allows cannot override the toggle.
        suspend fun publishAllowed(
            systemDecision: String,
            reason: String,
            modelConfidence: Float = 0f,
            inferredCategory: String = "UNKNOWN",
        ) {
            when (NotificationDeliveryPolicy.decide(
                hasImage = hasImage,
                imagesEnabled = preferences.imageNotificationsEnabled,
                contactBypass = contactMatched,
                privateWhileLocked = privateWhileLocked,
                sensitiveNotificationsEnabled = preferences.sensitiveNotificationsEnabled,
            )) {
                NotificationDeliveryDecision.DEFER_UNTIL_UNLOCK -> {
                    synchronized(deferredKeys) {
                        deferredKeys.add(originalKey)
                        if (deferredKeys.size > maxDeferred) {
                            deferredKeys.remove(deferredKeys.first())
                        }
                    }
                    log(jobId, packageName, title, content, "LOCKED_DEFERRED",
                        "Private notification deferred until unlock to check content and image settings")
                }
                NotificationDeliveryDecision.BLOCK_IMAGE -> {
                    log(jobId, packageName, title, content, "IMAGE_BLOCKED",
                        "Image notifications disabled and no contact bypass matched — suppressed")
                    review(jobId, packageName, title, displayContent, sbn.postTime,
                        "IMAGE_BLOCKED", messageText = cacheKey)
                }
                NotificationDeliveryDecision.FORWARD -> {
                    notificationPublisher.publish(
                        packageName, title,
                        content.ifBlank { if (hasImage) "Image attachment" else "" },
                        contentIntent, originalKey, sbn.id, sbn.notification.category,
                    )
                    log(jobId, packageName, title, content, "PUBLISHED", reason)
                    review(jobId, packageName, title, displayContent, sbn.postTime,
                        systemDecision, modelConfidence, inferredCategory, cacheKey)
                }
            }
        }

        // Locked screen + sender-marked-sensitive (VISIBILITY_PRIVATE). Behaviour is governed
        // by the user's "Sensitive notifications" setting:
        //
        //  • ON (default) — bypass text filtering, subject to the final image setting.
        //    If images are disabled and the sender isn't a matched contact, defer too:
        //    redacted metadata cannot prove that a private notification has no image.
        //
        //  • OFF — defer: Android delivers redacted text ("sensitive content" placeholder) to
        //    listeners while locked, so classifying now is meaningless. Record the key, publish
        //    nothing (no disturbance), and re-fetch the full content to classify on unlock
        //    (see processDeferredOnUnlock).
        if (privateWhileLocked) {
            if (preferences.sensitiveNotificationsEnabled && contactMatched) {
                log(jobId, packageName, title, content, "CONTACT_PASS",
                    "Title \"$title\" matched contact whitelist")
                publishAllowed("CONTACT_PASS", "Forwarded to user via contact whitelist")
            } else {
                if (preferences.sensitiveNotificationsEnabled && preferences.imageNotificationsEnabled) {
                    log(jobId, packageName, title, content, "LOCKED_PASS",
                        "Device locked + VISIBILITY_PRIVATE — sensitive notifications enabled, bypassing text filter")
                }
                publishAllowed("LOCKED_PASS", "Forwarded to user via locked-screen bypass")
            }
            return
        }

        // Gate 2: contact whitelist (Tier 1 — highest priority, bypasses classifier)
        // Lookup is lowercased to match the normalised names stored by ContactsSyncManager.
        if (contactMatched) {
            log(jobId, packageName, title, content, "CONTACT_PASS",
                "Title \"$title\" matched contact whitelist")
            publishAllowed("CONTACT_PASS", "Forwarded to user via contact whitelist")
            return
        }
        log(jobId, packageName, title, content, "CONTACT_MISS",
            "Title \"$title\" not in contact whitelist")

        // Gate 3: keyword blocklist (Tier 2a — deterministic, mute immediately)
        // Checked before the allowlist so an explicit block always wins.
        if (NativeBridge.containsBlocklistedKeyword(text)) {
            log(jobId, packageName, title, content, "KEYWORD_BLOCKED",
                "Body matched a blocked keyword rule — silenced")
            review(jobId, packageName, title, displayContent, sbn.postTime, "KEYWORD_BLOCKED", messageText = cacheKey)
            return
        }
        log(jobId, packageName, title, content, "BLOCKLIST_MISS",
            "No blocked keyword matched")

        // Gate 4: keyword allowlist (Tier 2b — deterministic, pass immediately)
        if (NativeBridge.containsWhitelistedKeyword(text)) {
            log(jobId, packageName, title, content, "KEYWORD_PASS",
                "Body matched an allowed keyword rule")
            publishAllowed("KEYWORD_PASS", "Forwarded to user via keyword match")
            return
        }
        log(jobId, packageName, title, content, "KEYWORD_MISS",
            "No allowed keyword matched — escalating to on-device classifier")

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
            ensembleClassifier.evaluate(category, packageName, text, cacheKey)
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
                "Allowed by $decidedBy (base score ${result.baseConfidence}) — checking final delivery settings")
            publishAllowed("PUBLISHED", "Forwarded to user via $decidedBy",
                result.baseConfidence, inferredCategory)
        } else {
            log(jobId, packageName, title, content, "MODEL_BLOCKED",
                "Blocked by $decidedBy (base score ${result.baseConfidence}) — suppressed")
            review(jobId, packageName, title, displayContent, sbn.postTime, "MODEL_BLOCKED",
                result.baseConfidence, inferredCategory, cacheKey)
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

    // The single newest message — the one that triggered this notification. For a MessagingStyle
    // notification that is the last entry of EXTRA_MESSAGES (chronological order); otherwise it is
    // EXTRA_TEXT, identical to resolveContent. Used only as the exact-match cache key, which must
    // stay stable as a conversation grows (resolveContent, by contrast, returns the whole thread
    // for display and model context).
    private fun resolveLatestMessage(notification: android.app.Notification?): String {
        val extras = notification?.extras ?: return ""
        @Suppress("DEPRECATION")
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            ?.filterIsInstance<Bundle>()
        if (!messages.isNullOrEmpty()) {
            val latest = messages
                .mapNotNull { it.getCharSequence("text")?.toString()?.takeIf { msg -> msg.isNotBlank() } }
                .lastOrNull()
            if (latest != null) return latest
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
