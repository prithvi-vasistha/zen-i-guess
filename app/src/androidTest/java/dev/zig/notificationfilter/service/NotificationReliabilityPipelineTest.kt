package dev.zig.notificationfilter.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zig.notificationfilter.data.local.NativeBridge
import dev.zig.notificationfilter.R
import dev.zig.notificationfilter.data.local.db.ManagedAppEntity
import dev.zig.notificationfilter.data.local.db.NotificationReviewEntity
import dev.zig.notificationfilter.domain.NotificationDeliveryStatus
import dev.zig.notificationfilter.domain.NotificationPublisher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NotificationReliabilityPipelineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val token = "zigtest" + UUID.randomUUID().toString().filter { it.isLetter() }
    private val sourcePackage = "dev.zig.reliability.tests.$token"
    private val component = "${context.packageName}/.service.ZigNotificationListenerService"
    private lateinit var listener: ZigNotificationListenerService
    private var previousImages = true
    private var previousSensitive = true
    private var previousContacts = true
    private var previousHidden = true
    private var shellWasManaged = false
    private var configuredTestPin = false

    @Before fun setup() = runBlocking {
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("cmd notification allow_listener $component")
        unlock()
        waitUntil { ZigNotificationListenerService.instance?.health?.snapshot?.value?.listenerConnected == true }
        listener = checkNotNull(ZigNotificationListenerService.instance)
        withTimeout(10_000L) { listener.filterState.awaitReady() }
        previousImages = listener.preferences.imageNotificationsEnabled
        previousSensitive = listener.preferences.sensitiveNotificationsEnabled
        previousContacts = listener.preferences.contactsBypassEnabled
        previousHidden = listener.preferences.hiddenContentAlertsEnabled
        listener.preferences.imageNotificationsEnabled = true
        listener.preferences.sensitiveNotificationsEnabled = true
        listener.preferences.contactsBypassEnabled = false
        listener.preferences.hiddenContentAlertsEnabled = true
        NativeBridge.addAppToManaged(sourcePackage)
        NativeBridge.addKeywordRuleToWhitelist(token)
        shellWasManaged = "com.android.shell" in (listener.application as dev.zig.notificationfilter.ZigApp)
            .managedAppDao.getAllPackageNames()
    }

    @After fun cleanup() = runBlocking {
        if (!::listener.isInitialized) return@runBlocking
        shell("dumpsys deviceidle unforce")
        shell("dumpsys battery reset")
        if (configuredTestPin) {
            shell("locksettings clear --old 2468")
            configuredTestPin = false
        }
        unlock()
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        waitUntil {
            unlock()
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        }
        listener.preferences.imageNotificationsEnabled = previousImages
        listener.preferences.sensitiveNotificationsEnabled = previousSensitive
        listener.preferences.contactsBypassEnabled = previousContacts
        listener.preferences.hiddenContentAlertsEnabled = previousHidden
        val current = ZigNotificationListenerService.instance
        current?.activeNotifications?.filter {
            it.packageName == "com.android.shell" && it.tag == "zig-$token"
        }?.forEach { current.cancelNotification(it.key) }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter {
            it.tag?.contains(token) == true
        }.forEach { manager.cancel(it.tag, it.id) }
        NativeBridge.removeAppFromManaged(sourcePackage)
        if (!shellWasManaged) (listener.application as dev.zig.notificationfilter.ZigApp)
            .managedAppDao.delete(ManagedAppEntity("com.android.shell"))
        listener.filterState.refresh()
        shell("cmd notification disallow_listener $component")
    }

    @Test fun keywordPassWithDeniedPermissionIsNeverRecordedAsPublished() = runBlocking {
        // Actual runtime revocation kills the instrumentation process. Deny the
        // context permission check while exercising the real publisher and listener.
        val original = listener.notificationPublisher
        listener.notificationPublisher = NotificationPublisher(object : ContextWrapper(context) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (permission == android.Manifest.permission.POST_NOTIFICATIONS) PackageManager.PERMISSION_DENIED
                else super.checkPermission(permission, pid, uid)
        })
        try {
            val sbn = notification()
            val row = evaluate(sbn)
            assertEquals("KEYWORD_PASS", row.systemDecision)
            assertEquals("NOT_POSTED_PERMISSION", row.deliveryStatus)
            assertFalse(stages(row).contains("PUBLISHED"))
            assertTrue(stages(row).contains("NOT_POSTED_PERMISSION"))
            assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag == sbn.key })
        } finally { listener.notificationPublisher = original }
    }

    @Test fun duplicateCallbacksProduceOneAlertAndOneReviewCard() = runBlocking {
        val sbn = notification()
        val row = evaluate(sbn)
        listener.onNotificationPosted(sbn)
        delay(500L)
        assertEquals(1, listener.reviewDao.searchActiveFlow(0L, sourcePackage).first().size)
        assertEquals(1, stages(row).count { it == "PUBLISHED" })
        assertEquals("COMPLETE", listener.processingDao.get(sbn.key)?.state)
    }

    @Test fun privateKeywordAlertHasAnIconPreviewWithoutSenderOrOtpContent() = runBlocking {
        val sbn = notification("OTP 2468 $token", Notification.VISIBILITY_PRIVATE, "Private sender")
        val row = evaluate(sbn)
        assertEquals("KEYWORD_PASS", row.systemDecision)
        val posted = postedAlert(sbn)
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
        assertEquals(NotificationPublisher.CHANNEL_ID, posted.channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH,
            context.getSystemService(NotificationManager::class.java).getNotificationChannel(posted.channelId).importance)
        assertEquals(R.drawable.ic_notification, posted.smallIcon.resId)
        val preview = checkNotNull(posted.publicVersion)
        assertEquals(Notification.VISIBILITY_PUBLIC, preview.visibility)
        assertEquals(R.drawable.ic_notification, preview.smallIcon.resId)
        assertEquals("New notification", preview.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertNull(preview.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        assertNull(preview.actions)
        assertFalse(preview.extras.toString().contains("2468"))
        assertFalse(preview.extras.toString().contains("Private sender"))
        assertEquals("OTP 2468 $token", posted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        listener.onNotificationPosted(sbn)
        delay(250L)
        assertEquals(1, stages(row).count { it == "PUBLISHED" })
    }

    @Test fun publicKeywordAlertRetainsItsPublicVisibility() = runBlocking {
        val sbn = notification(visibility = Notification.VISIBILITY_PUBLIC)
        evaluate(sbn)
        val posted = postedAlert(sbn)
        assertEquals(Notification.VISIBILITY_PUBLIC, posted.visibility)
        assertNull(posted.publicVersion)
        assertEquals(token, posted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }

    @Test fun copyActionUsesTheLatestMessageInsteadOfAnOlderConversationOtp(): Unit = runBlocking {
        val source = notification("$token newest message", Notification.VISIBILITY_PUBLIC)
        source.notification.extras.putParcelableArray(Notification.EXTRA_MESSAGES, arrayOf(
            android.os.Bundle().apply { putCharSequence("text", "OTP 111111 $token") },
            android.os.Bundle().apply { putCharSequence("text", "OTP 222222 $token") }))
        val row = evaluate(source)
        assertEquals("KEYWORD_PASS", row.systemDecision)
        val posted = postedAlert(source)
        assertEquals("Copy code", posted.actions.first().title.toString())
        assertEquals("222222", posted.extras.getString(NotificationCodeCopier.EXTRA_CODE))

        source.notification.extras.putParcelableArray(Notification.EXTRA_MESSAGES, arrayOf(
            android.os.Bundle().apply { putCharSequence("text", "OTP 111111 $token") },
            android.os.Bundle().apply { putCharSequence("text", "$token ordinary latest message") }))
        listener.onNotificationPosted(source)
        waitUntil { context.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.tag == source.key }?.notification?.actions?.none { it.title == "Copy code" } == true }
        assertNull(postedAlert(source).extras.getString(NotificationCodeCopier.EXTRA_CODE))
    }

    @Test fun copyActionReadsTheCompleteBigTextOfAnAllowedNotification(): Unit = runBlocking {
        val source = notification("$token verification code…", Notification.VISIBILITY_PUBLIC)
        source.notification.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "Your verification code is 009988")
        val row = evaluate(source)
        assertEquals("KEYWORD_PASS", row.systemDecision)
        assertEquals("009988", postedAlert(source).extras.getString(NotificationCodeCopier.EXTRA_CODE))

        // Same source key, post time and collapsed text: only the expanded code changes.
        source.notification.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "Your verification code is 001122")
        listener.onNotificationPosted(source)
        waitUntil { postedAlert(source).extras.getString(NotificationCodeCopier.EXTRA_CODE) == "001122" }
    }

    @Test fun anEmptyNewestConversationEntryRemovesTheCopyAction(): Unit = runBlocking {
        val source = notification("$token code message", Notification.VISIBILITY_PUBLIC)
        val older = android.os.Bundle().apply { putCharSequence("text", "OTP 111111 $token") }
        source.notification.extras.putParcelableArray(Notification.EXTRA_MESSAGES, arrayOf(older))
        evaluate(source)
        assertEquals("111111", postedAlert(source).extras.getString(NotificationCodeCopier.EXTRA_CODE))

        // The old display/cache helpers omit empty messages, so their text is unchanged.
        source.notification.extras.putParcelableArray(Notification.EXTRA_MESSAGES,
            arrayOf(older, android.os.Bundle().apply { putCharSequence("text", "") }))
        listener.onNotificationPosted(source)
        waitUntil { postedAlert(source).actions.none { it.title == "Copy code" } }
        assertNull(postedAlert(source).extras.getString(NotificationCodeCopier.EXTRA_CODE))
    }

    @Test fun secretKeywordAlertHasNoPublicLockScreenPreview() = runBlocking {
        val sbn = notification(visibility = Notification.VISIBILITY_SECRET)
        evaluate(sbn)
        val posted = postedAlert(sbn)
        assertEquals(Notification.VISIBILITY_SECRET, posted.visibility)
        assertNull(posted.publicVersion)
    }

    @Test fun unknownSourceVisibilityFallsBackToAPrivatePreview() = runBlocking {
        val sbn = notification(visibility = 42)
        evaluate(sbn)
        val posted = postedAlert(sbn)
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
        assertEquals("New notification", posted.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }

    @Test fun crashAfterPostingIsConfirmedWithoutRepeatingTheAlert() = runBlocking {
        val sbn = notification()
        val row = evaluate(sbn)
        val record = checkNotNull(listener.processingDao.get(sbn.key))
        listener.processingDao.save(record.copy(state = "POSTING"))
        listener.onNotificationPosted(sbn)
        waitUntil { listener.processingDao.get(sbn.key)?.state == "COMPLETE" }
        assertEquals("POSTED", listener.reviewDao.getByJobId(row.jobId)?.deliveryStatus)
        assertEquals(1, stages(row).count { it == "PUBLISHED" })
    }

    @Test fun uncertainPostingAttemptDoesNotResurrectADismissedAlert() = runBlocking {
        val sbn = notification()
        val row = evaluate(sbn)
        val record = checkNotNull(listener.processingDao.get(sbn.key))
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag == sbn.key }.forEach { manager.cancel(it.tag, it.id) }
        waitUntil { manager.activeNotifications.none { it.tag == sbn.key } }
        listener.processingDao.save(record.copy(state = "POSTING"))
        listener.onNotificationPosted(sbn)
        waitUntil { listener.processingDao.get(sbn.key)?.state == "COMPLETE" }
        assertEquals("DELIVERY_UNCONFIRMED", listener.reviewDao.getByJobId(row.jobId)?.deliveryStatus)
        assertFalse(manager.activeNotifications.any { it.tag == sbn.key })
    }

    @Test fun androidHiddenContentNeverReachesKeywordsOrModelTraining() = runBlocking {
        val sbn = notification("Sensitive notification content hidden")
        assertTrue(NotificationRedactionDetector.isRedacted(context, sbn.notification))
        val row = evaluate(sbn)
        assertEquals("CONTENT_HIDDEN", row.systemDecision)
        assertEquals("POSTED", row.deliveryStatus)
        assertTrue(row.deliveryDetail.contains("Content hidden — alert posted"))
        assertEquals("", row.messageText)
        assertFalse(postedAlert(sbn).actions.any { it.title == "Copy code" })
        assertFalse(stages(row).contains("MODEL_INVOKED"))
        assertFalse(stages(row).contains("KEYWORD_PASS"))
        assertTrue(listener.reviewDao.searchArchiveFlow(0L, sourcePackage).first().isEmpty())
        assertFalse(listener.reviewDao.getTodayAiEvaluatedUndecided(0L).any { it.id == row.id })
    }

    @Test fun hiddenContentAlertsIgnoreImageAndKeywordRules() = runBlocking {
        listener.preferences.imageNotificationsEnabled = false
        listener.preferences.sensitiveNotificationsEnabled = false
        NativeBridge.addKeywordRuleToBlocklist(token)
        val row = evaluate(notification("Sensitive notification content hidden"))
        assertEquals("POSTED", row.deliveryStatus)
        assertEquals("CONTENT_HIDDEN", row.systemDecision)
        assertFalse(stages(row).any { it in listOf("KEYWORD_BLOCKED", "IMAGE_BLOCKED", "MODEL_INVOKED", "LOCKED_DEFERRED") })
    }

    @Test fun hiddenContentOptOutPersistsAndDoesNotPost() = runBlocking {
        listener.preferences.hiddenContentAlertsEnabled = false
        assertFalse(dev.zig.notificationfilter.data.preferences.ZigUserPreferences(context).hiddenContentAlertsEnabled)
        val sbn = notification("Sensitive notification content hidden")
        val row = evaluate(sbn)
        assertEquals("CONTENT_HIDDEN", row.deliveryStatus)
        assertTrue(row.deliveryDetail.contains("alerts are off"))
        assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag == sbn.key })
    }

    @Test fun hiddenContentWithDeniedPermissionNeverClaimsAnAlertWasPosted() = runBlocking {
        val original = listener.notificationPublisher
        listener.notificationPublisher = NotificationPublisher(object : ContextWrapper(context) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (permission == android.Manifest.permission.POST_NOTIFICATIONS) PackageManager.PERMISSION_DENIED
                else super.checkPermission(permission, pid, uid)
        })
        try {
            val row = evaluate(notification("Sensitive notification content hidden"))
            assertEquals("CONTENT_HIDDEN", row.systemDecision)
            assertEquals("NOT_POSTED_PERMISSION", row.deliveryStatus)
            assertFalse(row.deliveryDetail.contains("alert posted"))
            assertFalse(stages(row).contains("PUBLISHED"))
        } finally { listener.notificationPublisher = original }
    }

    @Test fun bigTextOnlyRedactionWithNoTitleStillPosts() = runBlocking {
        val sbn = notification("", title = "")
        sbn.notification.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "Sensitive notification content hidden")
        val row = evaluate(sbn)
        assertEquals("POSTED", row.deliveryStatus)
        assertEquals("CONTENT_HIDDEN", row.systemDecision)
    }

    @Test fun redactionReplacesAnOldCodeAndReadableContentRestoresCopying() = runBlocking {
        val sbn = notification("OTP 123456 $token")
        evaluate(sbn)
        assertEquals("123456", postedAlert(sbn).extras.getString(NotificationCodeCopier.EXTRA_CODE))
        sbn.notification.extras.putCharSequence(Notification.EXTRA_TEXT, "Sensitive notification content hidden")
        listener.onNotificationPosted(sbn)
        waitUntil { postedAlert(sbn).extras.getCharSequence(Notification.EXTRA_TEXT).toString().startsWith("Sensitive message received.") }
        assertFalse(postedAlert(sbn).actions.any { it.title == "Copy code" })
        assertNull(postedAlert(sbn).extras.getString(NotificationCodeCopier.EXTRA_CODE))
        sbn.notification.extras.putCharSequence(Notification.EXTRA_TEXT, "OTP 009988 $token")
        listener.onNotificationPosted(sbn)
        waitUntil { postedAlert(sbn).extras.getString(NotificationCodeCopier.EXTRA_CODE) == "009988" }
        assertEquals(1, context.getSystemService(NotificationManager::class.java).activeNotifications.count { it.tag == sbn.key })
    }

    @Test fun repeatedHiddenCallbackUsesOnePersistedPostingAttempt() = runBlocking {
        val sbn = notification("Sensitive notification content hidden")
        val row = evaluate(sbn)
        repeat(3) { listener.onNotificationPosted(sbn) }
        delay(350L)
        assertEquals("POSTED", listener.reviewDao.getByJobId(row.jobId)?.deliveryStatus)
        assertEquals(1, stages(row).count { it == "PUBLISHED" })
        assertEquals(1, context.getSystemService(NotificationManager::class.java).activeNotifications.count { it.tag == sbn.key })
    }

    @Test @SdkSuppress(minSdkVersion = 31)
    fun hiddenAlertPreservesOriginalAndDoesNotRepeatAfterRebind() = runBlocking {
        manageShell()
        val output = postHiddenShellNotification()
        assertTrue("Shell post failed: $output", output.contains("posting:"))
        val row = shellReviewRow()
        assertEquals("POSTED", row.deliveryStatus)
        assertEquals("CONTENT_HIDDEN", row.systemDecision)
        assertTrue(listener.activeNotifications.any { it.packageName == "com.android.shell" && it.tag == "zig-$token" })
        InstrumentationRegistry.getInstrumentation().runOnMainSync { listener.requestUnbind() }
        waitUntil { ZigNotificationListenerService.instance == null }
        waitUntil { ZigNotificationListenerService.instance?.health?.snapshot?.value?.listenerConnected == true }
        listener = checkNotNull(ZigNotificationListenerService.instance)
        delay(350L)
        assertEquals(1, stages(row).count { it == "PUBLISHED" })
        assertEquals("POSTED", listener.reviewDao.getByJobId(row.jobId)?.deliveryStatus)
    }

    @Test @SdkSuppress(minSdkVersion = 31)
    fun hiddenContentPostsWhileSecurelyLockedInDozeWithImagesOff() = runBlocking {
        manageShell()
        listener.preferences.imageNotificationsEnabled = false
        listener.preferences.sensitiveNotificationsEnabled = false
        assertFalse(context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        shell("locksettings set-pin 2468")
        configuredTestPin = true
        waitUntil { context.getSystemService(KeyguardManager::class.java).isDeviceSecure }
        shell("input keyevent KEYCODE_SLEEP")
        waitUntil { context.getSystemService(KeyguardManager::class.java).isKeyguardLocked }
        shell("dumpsys battery unplug")
        shell("dumpsys deviceidle force-idle")
        val output = postHiddenShellNotification()
        assertTrue(output.contains("posting:"))
        val row = shellReviewRow()
        assertEquals("POSTED", row.deliveryStatus)
        assertEquals("CONTENT_HIDDEN", row.systemDecision)
        val source = listener.activeNotifications.first { it.tag == "zig-$token" }
        val alert = postedAlert(source)
        assertEquals(Notification.VISIBILITY_PRIVATE, alert.visibility)
        assertEquals("Sensitive message received", alert.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertFalse(alert.actions.any { it.title == "Copy code" })
        unlock()
        waitUntil { !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked }
        listener.reconcileActiveNotifications()
        delay(350L)
        assertEquals(1, stages(row).count { it == "PUBLISHED" })
    }

    @Test fun hiddenContentFromAnUnmanagedAppDoesNotPost() = runBlocking {
        NativeBridge.removeAppFromManaged(sourcePackage)
        val sbn = notification("Sensitive notification content hidden")
        listener.onNotificationPosted(sbn)
        waitUntil { listener.processingDao.get(sbn.key)?.state == "COMPLETE" }
        val record = checkNotNull(listener.processingDao.get(sbn.key))
        assertEquals("MANAGED_FAIL", listener.reviewDao.getByJobId(record.jobId)?.systemDecision)
        assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag == sbn.key })
    }

    @Test fun privateVisibilityAndEmptyTextAreNotMistakenForOtpRedaction() {
        val builder = NotificationCompat.Builder(context, "test").setSmallIcon(android.R.drawable.ic_dialog_info)
        assertFalse(NotificationRedactionDetector.isRedacted(context, builder.setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentText("otp $token").build()))
        assertFalse(NotificationRedactionDetector.isRedacted(context, builder.setContentText("").build()))
    }

    @Test fun disabledAndQuietChannelsAreReadWithoutChangingImportantAlerts() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val publisher = NotificationPublisher(context)
        val blockedId = "blocked-$token"
        val quietId = "quiet-$token"
        try {
            manager.createNotificationChannel(NotificationChannel(blockedId, "Blocked test", NotificationManager.IMPORTANCE_NONE))
            manager.createNotificationChannel(NotificationChannel(quietId, "Quiet test", NotificationManager.IMPORTANCE_LOW))
            assertEquals(NotificationDeliveryStatus.NOT_POSTED_CHANNEL_BLOCKED, publisher.postingSettings(blockedId).blockingStatus())
            assertNull(publisher.postingSettings(quietId).blockingStatus())
            assertTrue(publisher.postingSettings().channelEnabled)
        } finally {
            manager.deleteNotificationChannel(blockedId)
            manager.deleteNotificationChannel(quietId)
        }
    }

    @Test fun listenerAutomaticallyRebindsWhileAccessRemainsGranted() = runBlocking {
        assertTrue(listener.health.hasNotificationAccess())
        InstrumentationRegistry.getInstrumentation().runOnMainSync { listener.requestUnbind() }
        waitUntil { ZigNotificationListenerService.instance == null }
        waitUntil { ZigNotificationListenerService.instance?.health?.snapshot?.value?.listenerConnected == true }
        listener = checkNotNull(ZigNotificationListenerService.instance)
        assertTrue(listener.health.snapshot.value.listenerConnected)
    }

    @Test fun revokedAccessStaysRevokedAndUserRestorationResumesKeywordDelivery() = runBlocking {
        val health = listener.health
        shell("cmd notification disallow_listener $component")
        waitUntil { ZigNotificationListenerService.instance == null && !health.snapshot.value.notificationAccess }
        health.ensureListenerConnected()
        assertFalse(health.requestReconnect())
        delay(1_500L)
        assertFalse(health.hasNotificationAccess())
        assertNull(ZigNotificationListenerService.instance)

        // Android settings grants access; ZiG itself never changes this permission.
        shell("cmd notification allow_listener $component")
        waitUntil { ZigNotificationListenerService.instance?.health?.snapshot?.value?.listenerConnected == true }
        listener = checkNotNull(ZigNotificationListenerService.instance)
        assertTrue(health.snapshot.value.notificationAccess)
        val row = evaluate(notification())
        assertEquals("KEYWORD_PASS", row.systemDecision)
        assertEquals("POSTED", row.deliveryStatus)
        assertEquals(1, stages(row).count { it == "PUBLISHED" })
    }

    @Test fun deferredOriginalIsRecoveredAfterListenerRebindAndUnlock() = runBlocking {
        var phase = "locking"
        try {
            manageShell()
            listener.preferences.sensitiveNotificationsEnabled = false
            assertFalse("The test emulator must start without a device credential",
                context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
            shell("locksettings set-pin 2468")
            configuredTestPin = true
            waitUntil { context.getSystemService(KeyguardManager::class.java).isDeviceSecure }
            shell("input keyevent KEYCODE_SLEEP")
            waitUntil { context.getSystemService(KeyguardManager::class.java).isKeyguardLocked }
            phase = "receiving deferred notification"
            val output = shell("cmd notification post -t Zig zig-$token The_cab_is_arriving")
            assertTrue("Shell post failed: $output", output.contains("posting:"))
            val waiting = shellReviewRow()
            assertEquals("WAITING_FOR_UNLOCK", waiting.deliveryStatus)
            phase = "reconnecting"
            InstrumentationRegistry.getInstrumentation().runOnMainSync { listener.requestUnbind() }
            waitUntil { ZigNotificationListenerService.instance == null }
            waitUntil { ZigNotificationListenerService.instance?.health?.snapshot?.value?.listenerConnected == true }
            listener = checkNotNull(ZigNotificationListenerService.instance)
            phase = "unlocking and delivering"
            unlock()
            waitUntil { listener.reviewDao.getByJobId(waiting.jobId)?.deliveryStatus == "POSTED" }
            val delivered = checkNotNull(listener.reviewDao.getByJobId(waiting.jobId))
            assertEquals(waiting.id, delivered.id)
            assertEquals("KEYWORD_PASS", delivered.systemDecision)
            assertEquals(1, stages(delivered).count { it == "PUBLISHED" })
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            throw AssertionError("Timed out $phase; locked=${keyguard.isKeyguardLocked}, secure=${keyguard.isDeviceSecure}", e)
        }
    }

    @Test fun boundListenerCanPostAnAlertDuringForcedDoze() = runBlocking {
        manageShell()
        shell("input keyevent KEYCODE_SLEEP")
        shell("dumpsys battery unplug")
        shell("dumpsys deviceidle force-idle")
        val output = shell("cmd notification post -t Zig zig-$token The_cab_is_arriving")
        assertTrue("Shell post failed: $output", output.contains("posting:"))
        val row = shellReviewRow()
        assertEquals("POSTED", row.deliveryStatus)
        assertTrue(stages(row).contains("PUBLISHED"))
    }

    private suspend fun manageShell() {
        (listener.application as dev.zig.notificationfilter.ZigApp).managedAppDao.insert(ManagedAppEntity("com.android.shell"))
        listener.filterState.refresh()
        NativeBridge.addKeywordRuleToWhitelist(token)
        NativeBridge.addKeywordRuleToWhitelist("cab||arriving")
    }

    @android.annotation.TargetApi(31)
    private fun postHiddenShellNotification(): String {
        // UiAutomation tokenizes commands without shell quoting. Feed a shell's
        // stdin so the complete, spaced OS placeholder is one notification body.
        val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use {
            it.write("cmd notification post -t Zig zig-$token 'Sensitive notification content hidden'\n".toByteArray())
        }
        return ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private suspend fun shellReviewRow(): NotificationReviewEntity {
        waitUntil { listener.activeNotifications.any { it.tag == "zig-$token" } }
        val original = listener.activeNotifications.first { it.tag == "zig-$token" }
        waitUntil {
            val record = listener.processingDao.get(original.key)
            record != null && listener.reviewDao.getByJobId(record.jobId)?.deliveryStatus?.let { it != "POSTING" } == true
        }
        val record = checkNotNull(listener.processingDao.get(original.key))
        return checkNotNull(listener.reviewDao.getByJobId(record.jobId))
    }

    private fun postedAlert(sbn: StatusBarNotification): Notification =
        context.getSystemService(NotificationManager::class.java).activeNotifications.first { it.tag == sbn.key }.notification

    private fun notification(body: String = token, visibility: Int = Notification.VISIBILITY_PUBLIC,
        title: String = token): StatusBarNotification {
        val notification = NotificationCompat.Builder(context, "test").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(body).setVisibility(visibility).build()
        @Suppress("DEPRECATION")
        return StatusBarNotification(sourcePackage, sourcePackage, token.hashCode(), token, Process.myUid(), 0, 0,
            notification, UserHandle.getUserHandleForUid(Process.myUid()), System.currentTimeMillis())
    }

    private suspend fun evaluate(sbn: StatusBarNotification): NotificationReviewEntity {
        listener.onNotificationPosted(sbn)
        val row = withTimeout(15_000L) { listener.reviewDao.searchActiveFlow(0L, sourcePackage)
            .first { rows -> rows.any { it.deliveryStatus != "POSTING" } }.first { it.deliveryStatus != "POSTING" } }
        waitUntil { listener.processingDao.get(sbn.key)?.state == "COMPLETE" }
        return row
    }

    private suspend fun stages(row: NotificationReviewEntity) = listener.dao.getAll().first()
        .filter { it.jobId == row.jobId }.sortedBy { it.id }.map { it.status }

    private suspend fun waitUntil(condition: suspend () -> Boolean) = withTimeout(15_000L) {
        while (!condition()) delay(50L)
    }

    private fun unlock() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        if (configuredTestPin) {
            SystemClock.sleep(700L) // Let the secure keyguard's PIN view finish appearing.
            shell("input keyevent KEYCODE_2 KEYCODE_4 KEYCODE_6 KEYCODE_8 KEYCODE_ENTER")
        }
    }
    private fun shell(command: String): String {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
