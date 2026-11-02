package dev.zig.notificationfilter.service

import android.app.KeyguardManager
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
// import android.content.pm.PackageManager  // Phase 1: unused — restored if needed in future lanes
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.zig.notificationfilter.core.di.ApplicationScope
import dev.zig.notificationfilter.data.local.NativeBridge
import dev.zig.notificationfilter.data.local.FilterState
import dev.zig.notificationfilter.data.local.db.NotificationProcessingDao
import dev.zig.notificationfilter.data.local.db.NotificationProcessingEntity
import dev.zig.notificationfilter.domain.NotificationHealth
import dev.zig.notificationfilter.domain.NotificationDeliveryStatus
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import dev.zig.notificationfilter.core.errors.launchSafely
import dev.zig.notificationfilter.core.errors.AppErrors
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class ZigNotificationListenerService : NotificationListenerService() {

    companion object {
        // Nullable reference used by NotificationActionReceiver to call cancelNotification().
        // Volatile because the receiver reads it from a different thread.
        @Volatile var instance: ZigNotificationListenerService? = null
        private val processingMutex = Mutex()
        private var lastCleanupAt = 0L
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

    @Inject lateinit var processingDao: NotificationProcessingDao
    @Inject lateinit var filterState: FilterState
    @Inject lateinit var health: NotificationHealth
    private var receiverRegistered = false

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                appScope.launchSafely("Recovering notifications after unlock") {
                    repeat(20) {
                        if (!keyguardManager.isKeyguardLocked) {
                            reconcileActiveNotifications()
                            return@launchSafely
                        }
                        delay(250L)
                    }
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        health.listenerConnected(true)
        if (!receiverRegistered) {
            // USER_PRESENT is a protected system action. System UI may send it
            // from its own UID; NOT_EXPORTED would reject that legitimate unlock.
            AppErrors.run("Listening for phone unlock") {
                ContextCompat.registerReceiver(this, unlockReceiver,
                    IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED)
                receiverRegistered = true
            }
        }
        reconcileActiveNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) instance = null
        if (instance == null) health.listenerConnected(false)
        unregisterUnlockReceiver()
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            health.listenerConnected(false)
        }
        health.ensureListenerConnected()
        unregisterUnlockReceiver()
        super.onDestroy()
    }

    private fun unregisterUnlockReceiver() {
        if (receiverRegistered) {
            AppErrors.run("Stopping phone unlock observation") {
                try { unregisterReceiver(unlockReceiver) } catch (_: IllegalArgumentException) { }
            }
            receiverRegistered = false
        }
    }

    private fun trackable(sbn: StatusBarNotification): Boolean =
        sbn.packageName != packageName && !sbn.isOngoing &&
            sbn.notification.category != Notification.CATEGORY_SERVICE &&
            sbn.notification.category != Notification.CATEGORY_SYSTEM

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (AppErrors.run("Reading incoming notification") { trackable(sbn) } != true) return
        health.notificationReceived()
        appScope.launchSafely("Processing notification") { processNotification(sbn, recovering = false) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null || AppErrors.run("Reading removed notification") { trackable(sbn) } != true) return
        appScope.launchSafely("Recording notification removal") {
            processingMutex.withLock {
                try {
                    val record = processingDao.get(sbn.key) ?: return@withLock
                    if (record.state != "COMPLETE") finishUnavailable(record,
                        "The source app removed this notification before pending work could finish.")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    AppErrors.report("Recording notification removal", e)
                }
            }
        }
    }

    private fun fingerprint(sbn: StatusBarNotification): String {
        val latestMessage = resolveLatestMessage(sbn.notification)
        val codeText = NotificationCodeText.latest(sbn.notification)
        val parts = listOf(sbn.key, sbn.postTime.toString(), resolveTitle(sbn),
            resolveContent(sbn.notification), latestMessage,
            sbn.notification.visibility.toString(), NotificationImageDetector.hasImage(sbn.notification).toString()) +
            // Preserve existing fingerprints for ordinary messages. Include independent
            // copy text when big text changes or a newest empty entry replaces a code.
            if (codeText != latestMessage) listOf("copy-text", codeText) else emptyList()
        val bytes = parts.joinToString("\u0000").toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private suspend fun processNotification(sbn: StatusBarNotification, recovering: Boolean) =
        processingMutex.withLock {
            var jobId = UUID.randomUUID().toString()
            try {
                val now = System.currentTimeMillis()
                if (now - lastCleanupAt >= 60L * 60L * 1_000L) {
                    processingDao.deleteOlderThan(now - NotificationRecoveryPolicy.RETENTION_MS)
                    lastCleanupAt = now
                }
                val previous = processingDao.get(sbn.key)
                val signature = fingerprint(sbn)
                if (!NotificationRecoveryPolicy.shouldProcess(previous?.fingerprint, previous?.state,
                        signature, previous?.postTime, sbn.postTime, now, recovering)) return@withLock
                if (previous?.fingerprint == signature) jobId = previous.jobId
                if (previous != null && previous.fingerprint != signature && previous.state != "COMPLETE") {
                    reviewDao.updateDelivery(previous.jobId, NotificationDeliveryStatus.SUPERSEDED.name,
                        "A newer notification from this source replaced the pending check.")
                }
                // A crash between the Android post and the DB acknowledgement is ambiguous.
                // Confirm an existing alert when possible; never repeat an uncertain attempt.
                if (previous?.fingerprint == signature && previous.state == "POSTING") {
                    val status = if (notificationPublisher.hasPosted(sbn.key, signature))
                        NotificationDeliveryStatus.POSTED else NotificationDeliveryStatus.DELIVERY_UNCONFIRMED
                    reviewDao.updateDelivery(jobId, status.name, status.label)
                    processingDao.setState(sbn.key, "COMPLETE", now)
                    return@withLock
                }
                processingDao.save(NotificationProcessingEntity(sbn.key, signature, sbn.postTime,
                    now, jobId, "PROCESSING"))
                filterState.awaitReady()
                evaluateNotification(sbn, jobId, signature)
                val state = processingDao.get(sbn.key)?.state
                if (state == "PROCESSING" || state == "POSTING") {
                    processingDao.setState(sbn.key, "COMPLETE", System.currentTimeMillis())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppErrors.report("Processing notification", e)
                Log.e("ZiG", "Notification processing failed", e)
                try {
                    processingDao.setState(sbn.key, "ERROR", System.currentTimeMillis())
                    log(jobId, sbn.packageName, resolveTitle(sbn), "", "PIPELINE_ERROR",
                        "Processing interrupted; retry is possible while the original remains available")
                    review(jobId, sbn.packageName, resolveTitle(sbn), "", sbn.postTime, "PIPELINE_ERROR",
                        deliveryStatus = NotificationDeliveryStatus.NOT_POSTED_ERROR.name,
                        deliveryDetail = "Processing was interrupted before delivery.")
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    AppErrors.report("Saving notification failure details", failure)
                }
            }
        }

    private suspend fun finishUnavailable(record: NotificationProcessingEntity, detail: String,
        expired: Boolean = false) {
        val status = if (expired) NotificationDeliveryStatus.RECOVERY_EXPIRED
            else if (record.state == "POSTING") NotificationDeliveryStatus.DELIVERY_UNCONFIRMED
            else NotificationDeliveryStatus.SOURCE_UNAVAILABLE
        reviewDao.updateDelivery(record.jobId, status.name, detail)
        processingDao.setState(record.sourceKey, "COMPLETE", System.currentTimeMillis())
    }

    /** Replay only current, recent work; completed fingerprints are persisted across restarts. */
    internal fun reconcileActiveNotifications() {
        appScope.launchSafely("Recovering active notifications") {
            try {
                if (instance !== this@ZigNotificationListenerService) return@launchSafely
                val active = getActiveNotifications() ?: return@launchSafely
                val keys = active.map { it.key }.toSet()
                processingMutex.withLock {
                    val now = System.currentTimeMillis()
                    processingDao.pending().forEach { record ->
                        val maxAge = if (record.state == "DEFERRED") NotificationRecoveryPolicy.RETENTION_MS
                            else NotificationRecoveryPolicy.RECENT_WINDOW_MS
                        if (now - record.postTime > maxAge) {
                            finishUnavailable(record, "The recovery window expired; old alerts are not replayed.", expired = true)
                        } else if (record.sourceKey !in keys) {
                            finishUnavailable(record, "Pending content is no longer available for recovery.")
                        }
                    }
                    processingDao.deleteOlderThan(now - NotificationRecoveryPolicy.RETENTION_MS)
                }
                active.filter(::trackable).sortedBy { it.postTime }.forEach {
                    processNotification(it, recovering = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppErrors.report("Recovering active notifications", e)
            }
        }
    }

    private suspend fun evaluateNotification(sbn: StatusBarNotification, jobId: String, signature: String) {
        val packageName = sbn.packageName

        // ── Pre-flight structural filters ──────────────────────────────────────
        // Recheck structural eligibility before writing review or log rows.
        // The processing journal already tracks this source for recovery.

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
        val hiddenContent = NotificationRedactionDetector.isRedacted(this, sbn.notification)
        if (title.isBlank() && content.isBlank() && !hasImage &&
            !hiddenContent && !(privateWhileLocked && !preferences.imageNotificationsEnabled)) {
            reviewDao.updateDelivery(jobId, NotificationDeliveryStatus.SOURCE_UNAVAILABLE.name,
                "No readable content or image metadata was available when checking again.")
            return
        }
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
                    processingDao.setState(originalKey, "DEFERRED", System.currentTimeMillis())
                    log(jobId, packageName, title, content, "LOCKED_DEFERRED",
                        "Private notification deferred until unlock to check content and image settings")
                    review(jobId, packageName, title, displayContent, sbn.postTime, "LOCKED_DEFERRED",
                        messageText = cacheKey, deliveryStatus = NotificationDeliveryStatus.WAITING_FOR_UNLOCK.name,
                        deliveryDetail = "Waiting to check content and image settings after unlock.")
                }
                NotificationDeliveryDecision.BLOCK_IMAGE -> {
                    log(jobId, packageName, title, content, "IMAGE_BLOCKED",
                        "Image notifications disabled and no contact bypass matched — suppressed")
                    review(jobId, packageName, title, displayContent, sbn.postTime,
                        "IMAGE_BLOCKED", messageText = cacheKey,
                        deliveryStatus = NotificationDeliveryStatus.NOT_POSTED_FILTERED.name,
                        deliveryDetail = "Images disabled; no matched contact bypass.")
                }
                NotificationDeliveryDecision.FORWARD -> {
                    processingDao.setState(originalKey, "POSTING", System.currentTimeMillis())
                    review(jobId, packageName, title, displayContent, sbn.postTime,
                        systemDecision, modelConfidence, inferredCategory, cacheKey,
                        NotificationDeliveryStatus.POSTING.name)
                    val delivery = notificationPublisher.publish(
                        packageName, title,
                        content.ifBlank { if (hasImage) "Image attachment" else "" },
                        contentIntent, originalKey, sbn.id, sbn.notification.category,
                        sourceFingerprint = signature, originalVisibility = sbn.notification.visibility,
                        codeText = NotificationCodeText.latest(sbn.notification),
                    )
                    log(jobId, packageName, title, content,
                        if (delivery.posted) "PUBLISHED" else delivery.status.name,
                        if (delivery.posted) reason else delivery.status.label)
                    review(jobId, packageName, title, displayContent, sbn.postTime,
                        systemDecision, modelConfidence, inferredCategory, cacheKey,
                        delivery.status.name, delivery.detail)
                }
            }
        }

        // Android 15 OTP sanitization can persist even when unlocked. The placeholder
        // is neither an OTP keyword nor usable training content. Alert using only
        // a generic message; source apps may have been silenced during setup.
        if (hiddenContent) {
            log(jobId, packageName, title, "", "CONTENT_HIDDEN", "Android hid the notification text")
            if (!preferences.hiddenContentAlertsEnabled) {
                review(jobId, packageName, title, "Content hidden by Android", sbn.postTime, "CONTENT_HIDDEN",
                    deliveryStatus = NotificationDeliveryStatus.CONTENT_HIDDEN.name,
                    deliveryDetail = "Hidden content alerts are off. Open the original app to read this message.")
                return
            }
            // Neither image rules nor lock-screen deferral can decide whether
            // unavailable content is safe to discard. Preserve the source alert.
            processingDao.setState(originalKey, "POSTING", System.currentTimeMillis())
            review(jobId, packageName, title, "Content hidden by Android", sbn.postTime, "CONTENT_HIDDEN",
                deliveryStatus = NotificationDeliveryStatus.POSTING.name)
            val delivery = notificationPublisher.publishHiddenContent(packageName, contentIntent,
                originalKey, sbn.id, signature, sbn.notification.visibility)
            val detail = if (delivery.posted)
                "Content hidden — alert posted. Open the original app to read it. ${delivery.detail}"
            else "Content hidden — alert could not be posted. ${delivery.status.label}. ${delivery.detail}"
            log(jobId, packageName, title, "", if (delivery.posted) "PUBLISHED" else delivery.status.name, detail)
            review(jobId, packageName, title, "Content hidden by Android", sbn.postTime, "CONTENT_HIDDEN",
                deliveryStatus = delivery.status.name, deliveryDetail = detail)
            return
        }

        // Locked screen + sender-marked-sensitive (VISIBILITY_PRIVATE). Behaviour is governed
        // by the user's "Sensitive notifications" setting:
        //
        //  • ON (default) — bypass text filtering, subject to the final image setting.
        //    If images are disabled and the sender isn't a matched contact, defer too:
        //    redacted metadata cannot prove that a private notification has no image.
        //
        //  • OFF — defer by user preference and re-fetch content on unlock.
        //    VISIBILITY_PRIVATE alone does not prove that Android hid the text;
        //    Android 15 OTP sanitization is detected separately above.
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
        val keywordMatch = NativeBridge.matchKeywords(text)
        if (keywordMatch == NativeBridge.KeywordMatch.BLOCK) {
            log(jobId, packageName, title, content, "KEYWORD_BLOCKED",
                "Body matched a blocked keyword rule — silenced")
            review(jobId, packageName, title, displayContent, sbn.postTime, "KEYWORD_BLOCKED", messageText = cacheKey,
                deliveryStatus = NotificationDeliveryStatus.NOT_POSTED_FILTERED.name)
            return
        }
        log(jobId, packageName, title, content, "BLOCKLIST_MISS",
            "No blocked keyword matched")

        // Gate 4: keyword allowlist (Tier 2b — deterministic, pass immediately)
        if (keywordMatch == NativeBridge.KeywordMatch.ALLOW) {
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
        } catch (e: Throwable) {
            AppErrors.report("Classifying notification", e)
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
        deliveryStatus: String = "NOT_POSTED_FILTERED",
        deliveryDetail: String = "",
    ) {
        reviewDao.upsertPipeline(
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
                deliveryStatus = deliveryStatus,
                deliveryDetail = deliveryDetail,
            ),
        )
    }
}
