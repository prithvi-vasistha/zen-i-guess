package dev.zig.notificationfilter.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zig.notificationfilter.data.local.NativeBridge
import dev.zig.notificationfilter.data.local.db.KeywordRuleType
import dev.zig.notificationfilter.data.local.db.NotificationReviewEntity
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exercises the real Hilt listener, Rust gates, Room queries, and notification publisher. */
@RunWith(AndroidJUnit4::class)
class ImageNotificationPipelineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val token = UUID.randomUUID().toString()
    private val sourcePackage = "dev.zig.image.tests.$token"
    private val listenerComponent = "${context.packageName}/.service.ZigNotificationListenerService"
    private lateinit var listener: ZigNotificationListenerService
    private lateinit var preferences: ZigUserPreferences
    private var previousImagesEnabled = true
    private var previousContactsEnabled = true
    private var previousSensitiveEnabled = true
    private val picture = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    private var forwardedId: Int? = null

    @Before
    fun setUp() {
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("cmd notification allow_listener $listenerComponent")
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (ZigNotificationListenerService.instance == null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50L)
        }
        listener = checkNotNull(ZigNotificationListenerService.instance) { "Listener did not connect" }
        runBlocking { withTimeout(10_000L) { listener.filterState.awaitReady() } }
        preferences = listener.preferences
        previousImagesEnabled = preferences.imageNotificationsEnabled
        previousContactsEnabled = preferences.contactsBypassEnabled
        previousSensitiveEnabled = preferences.sensitiveNotificationsEnabled
        preferences.imageNotificationsEnabled = false
        preferences.contactsBypassEnabled = true
        preferences.sensitiveNotificationsEnabled = true
        NativeBridge.addAppToManaged(sourcePackage)
    }

    @After
    fun tearDown() = runBlocking {
        if (!::listener.isInitialized) return@runBlocking
        preferences.imageNotificationsEnabled = previousImagesEnabled
        preferences.contactsBypassEnabled = previousContactsEnabled
        preferences.sensitiveNotificationsEnabled = previousSensitiveEnabled
        NativeBridge.removeAppFromManaged(sourcePackage)
        // Restore native rule snapshots after adding test-only rules. No saved rules are edited.
        NativeBridge.clearKeywordWhitelist()
        NativeBridge.clearKeywordBlocklist()
        listener.application.let { it as dev.zig.notificationfilter.ZigApp }
            .keywordRuleDao.getAllSnapshot().forEach { rule ->
                val joined = rule.conditions.joinToString("||")
                if (rule.ruleType == KeywordRuleType.BLOCK.name) {
                    NativeBridge.addKeywordRuleToBlocklist(joined)
                } else {
                    NativeBridge.addKeywordRuleToWhitelist(joined)
                }
            }
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { it.id == forwardedId }.forEach {
                context.getSystemService(NotificationManager::class.java).cancel(it.tag, it.id)
            }
        shell("cmd notification disallow_listener $listenerComponent")
    }

    @Test
    fun matchedContactImageIsForwardedEvenWhenImagesAndItsKeywordAreBlocked() = runBlocking {
        val title = "Contact $token"
        NativeBridge.addContactToWhitelist(title.lowercase())
        NativeBridge.addKeywordRuleToBlocklist(token)
        val row = evaluate(image(title, token))
        assertEquals("CONTACT_PASS", row.systemDecision)
        val stages = stages(row)
        assertTrue("PUBLISHED" in stages)
        assertFalse("MODEL_INVOKED" in stages)
        assertFalse("IMAGE_BLOCKED" in stages)
        assertForwarded()
    }

    @Test
    fun staleContactEntryDoesNotExemptImagesWhenContactBypassIsDisabled() = runBlocking {
        val title = "Contact $token"
        NativeBridge.addContactToWhitelist(title.lowercase())
        preferences.contactsBypassEnabled = false
        NativeBridge.addKeywordRuleToWhitelist(token)
        val row = evaluate(image(title, token))
        assertEquals("IMAGE_BLOCKED", row.systemDecision)
        assertFalse("CONTACT_PASS" in stages(row))
        assertNotForwarded()
    }

    @Test
    fun keywordAllowedImageIsStoppedAtTheFinalGateAndExcludedFromTraining() = runBlocking {
        NativeBridge.addKeywordRuleToWhitelist(token)
        val row = evaluate(image("Promotion", token))
        assertEquals("IMAGE_BLOCKED", row.systemDecision)
        val stages = stages(row)
        assertTrue("KEYWORD_PASS" in stages)
        assertEquals("IMAGE_BLOCKED", stages.last())
        assertNotForwarded()
        assertFalse(listener.reviewDao.searchArchiveFlow(0L, sourcePackage).first()
            .any { it.id == row.id })
        assertFalse(listener.reviewDao.getTodayAiEvaluatedUndecided(0L)
            .any { it.id == row.id })
    }

    @Test
    fun exactMatchAllowedImageIsStoppedAtTheFinalGate() = runBlocking {
        seedAllowOverride("Promotion $token")
        val row = evaluate(image("Promotion", token))
        assertEquals("IMAGE_BLOCKED", row.systemDecision)
        val stages = stages(row)
        assertTrue("EXACT_MATCH_ALLOW" in stages)
        assertTrue("MODEL_ALLOWED" in stages)
        assertFalse("PUBLISHED" in stages)
        assertNotForwarded()
    }

    @Test
    fun genuinelyImageOnlyNotificationReachesTheFinalGate() = runBlocking {
        seedAllowOverride("")
        val row = evaluate(image("", ""))
        assertEquals("IMAGE_BLOCKED", row.systemDecision)
        assertEquals("Image attachment", row.content)
        assertNotForwarded()
    }

    @Test
    fun enabledImageToggleKeepsTheExistingKeywordAllowPath() = runBlocking {
        preferences.imageNotificationsEnabled = true
        NativeBridge.addKeywordRuleToWhitelist(token)
        val row = evaluate(image("Promotion", token))
        assertEquals("KEYWORD_PASS", row.systemDecision)
        assertForwarded()
    }

    @Test
    fun textWithAnAppIconStillPassesWithImagesDisabled() = runBlocking {
        NativeBridge.addKeywordRuleToWhitelist(token)
        val notification = builder().setContentTitle("Text").setContentText(token)
            .setLargeIcon(picture).build()
        val row = evaluate(notification)
        assertEquals("KEYWORD_PASS", row.systemDecision)
        assertForwarded()
    }

    private fun builder() = NotificationCompat.Builder(context, "image-tests")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

    private fun image(title: String, body: String) = builder()
        .setContentTitle(title).setContentText(body)
        .setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture)).build()

    private suspend fun evaluate(notification: Notification): NotificationReviewEntity {
        val postedAt = System.currentTimeMillis()
        val id = token.hashCode()
        forwardedId = (sourcePackage + id.toString()).hashCode()
        @Suppress("DEPRECATION")
        val sbn = StatusBarNotification(
            sourcePackage, sourcePackage, id, null, Process.myUid(), 0, 0,
            notification, UserHandle.getUserHandleForUid(Process.myUid()), postedAt,
        )
        listener.onNotificationPosted(sbn)
        return withTimeout(15_000L) {
            listener.reviewDao.searchActiveFlow(0L, sourcePackage)
                .first { rows -> rows.any { it.timestamp == postedAt && it.deliveryStatus != "POSTING" } }
                .first { it.timestamp == postedAt && it.deliveryStatus != "POSTING" }
        }
    }

    private suspend fun seedAllowOverride(messageText: String) {
        listener.reviewDao.insert(NotificationReviewEntity(
            jobId = "image-test-$token", packageName = sourcePackage,
            title = "", content = "", timestamp = System.currentTimeMillis() - 1,
            systemDecision = "RESTORED", messageText = messageText,
            userOverrideStatus = "MANUALLY_ALLOWED",
        ))
    }

    private suspend fun stages(row: NotificationReviewEntity) = listener.dao.getAll().first()
        .filter { it.jobId == row.jobId }.sortedBy { it.id }.map { it.status }

    private fun assertForwarded() {
        assertTrue(context.getSystemService(NotificationManager::class.java)
            .activeNotifications.any { it.id == forwardedId })
    }

    private fun assertNotForwarded() {
        assertFalse(context.getSystemService(NotificationManager::class.java)
            .activeNotifications.any { it.id == forwardedId })
    }

    private fun shell(command: String) {
        val result = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(result).use { it.readBytes() }
    }
}
