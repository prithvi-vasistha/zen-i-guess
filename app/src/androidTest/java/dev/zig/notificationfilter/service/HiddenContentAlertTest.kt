package dev.zig.notificationfilter.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zig.notificationfilter.MainActivity
import dev.zig.notificationfilter.domain.NotificationDeliveryStatus
import dev.zig.notificationfilter.domain.NotificationPublisher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HiddenContentAlertTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val publisher = NotificationPublisher(context)
    private val key = "hidden-test-${UUID.randomUUID()}"
    private var previousFlags = 0
    private var openedSettings = false

    @Before fun prepare() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        shell("cmd statusbar collapse")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
    }

    @After fun cleanup() {
        if (openedSettings) shell("input keyevent KEYCODE_BACK")
        shell("cmd statusbar collapse")
        manager.activeNotifications.filter { it.tag?.startsWith(key) == true }.forEach { manager.cancel(it.tag, it.id) }
        automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
    }

    @Test fun originalDestinationIsPreservedAndHiddenAlertsNeverOfferCodeCopying() = runBlocking {
        val destination = originalDestination()
        val alert = post(destination)
        assertEquals(destination, alert.contentIntent)
        assertEquals(listOf("Open Settings", "Dismiss"), alert.actions.map { it.title.toString() })
        assertEquals(destination, alert.actions.first().actionIntent)
        assertTrue(alert.actions.first().isAuthenticationRequired)
        assertFalse(alert.extras.containsKey(NotificationCodeCopier.EXTRA_CODE))
        assertFalse(alert.extras.containsKey(NotificationCodeCopier.EXTRA_TOKEN))
        assertEquals("Sensitive message received. Open Settings to view it.",
            alert.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test fun privateLockScreenPreviewContainsOnlyTheGenericAlert() = runBlocking {
        val destination = originalDestination()
        val alert = post(destination)
        assertEquals(Notification.VISIBILITY_PRIVATE, alert.visibility)
        assertEquals("Sensitive message received", alert.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertNull(alert.publicVersion.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        assertNull(alert.publicVersion.actions)
        assertFalse(alert.publicVersion.extras.containsKey(NotificationCodeCopier.EXTRA_CODE))
        assertEquals(destination, alert.publicVersion.contentIntent)
    }

    @Test fun sourceAppLauncherIsUsedWhenTheOriginalDestinationIsMissing() = runBlocking {
        val alert = post(null)
        assertEquals("Open Settings", alert.actions.first().title.toString())
        assertEquals(alert.contentIntent, alert.actions.first().actionIntent)
        clickOpenActionAndVerifySettings()
    }

    @Test fun missingSourceAndLauncherStillPostsWithAnOpenZigFallback() = runBlocking {
        val result = publisher.publishHiddenContent("missing.source.app", null, key, 42, "hidden",
            Notification.VISIBILITY_PRIVATE)
        assertTrue(result.toString(), result.posted)
        val alert = alert()
        assertEquals(listOf("Dismiss", "Open ZiG"), alert.actions.map { it.title.toString() })
        assertEquals(alert.contentIntent, alert.actions.last().actionIntent)
    }

    @Test fun identicalPostingIsIdempotentAndSeparateSourceTagsRemainSeparate() = runBlocking {
        post(null)
        assertEquals(NotificationDeliveryStatus.ALREADY_POSTED,
            publisher.publishHiddenContent("com.android.settings", null, key, 42, "hidden", Notification.VISIBILITY_PRIVATE).status)
        publisher.publishHiddenContent("com.android.settings", null, "$key-other", 42, "other", Notification.VISIBILITY_PRIVATE)
        withTimeout(10_000L) {
            while (manager.activeNotifications.count { it.tag?.startsWith(key) == true } != 2) delay(30L)
        }
        assertEquals(2, manager.activeNotifications.count { it.tag?.startsWith(key) == true })
    }

    @Test fun actualExpandedOpenActionLaunchesTheOriginalApp() = runBlocking {
        post(originalDestination())
        clickOpenActionAndVerifySettings()
    }

    private suspend fun clickOpenActionAndVerifySettings() {
        shell("cmd statusbar expand-notifications")
        withTimeout(10_000L) {
            while (true) {
                if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
                val nodes = automation.windows.mapNotNull { it.root }.flatMap(::descendants)
                val open = nodes.firstOrNull { it.isVisibleToUser && it.text?.toString() == "Open Settings" }
                if (open?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) break
                nodes.firstOrNull { it.isVisibleToUser && it.viewIdResourceName == "android:id/expand_button" }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                delay(50L)
            }
        }
        openedSettings = true
        withTimeout(10_000L) {
            while (automation.rootInActiveWindow?.packageName?.toString() != "com.android.settings") delay(50L)
        }
    }

    private fun originalDestination(): PendingIntent = PendingIntent.getActivity(context, key.hashCode(),
        Intent(Settings.ACTION_WIFI_SETTINGS).setPackage("com.android.settings"), PendingIntent.FLAG_IMMUTABLE)

    private suspend fun post(destination: PendingIntent?): Notification {
        val result = publisher.publishHiddenContent("com.android.settings", destination, key, 42, "hidden", Notification.VISIBILITY_PRIVATE)
        assertTrue(result.toString(), result.posted)
        return alert()
    }

    private suspend fun alert(): Notification {
        withTimeout(10_000L) { while (manager.activeNotifications.none { it.tag == key }) delay(30L) }
        return manager.activeNotifications.first { it.tag == key }.notification
    }

    private fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::descendants).orEmpty() }

    private fun shell(command: String): String = automation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }
}
