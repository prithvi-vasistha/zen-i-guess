package dev.zig.notificationfilter.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zig.notificationfilter.MainActivity
import dev.zig.notificationfilter.core.errors.AppErrors
import dev.zig.notificationfilter.domain.NotificationPublisher
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class NotificationCodeCopyTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val token = "copy-test-${UUID.randomUUID()}"
    private val publisher = NotificationPublisher(context)
    private var originalClip: ClipData? = null
    private var originalHeadsUp = "null"
    private var originalAccessibilityFlags = 0
    private var configuredPin = false
    private var originalReports = emptySet<String>()

    @Before fun setup(): Unit = runBlocking {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        shell("cmd statusbar collapse")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        originalHeadsUp = shell("settings get global heads_up_notifications_enabled").trim()
        shell("settings put global heads_up_notifications_enabled 1")
        originalAccessibilityFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        focusApp()
        originalClip = clipboard.primaryClip
        clipboard.setPrimaryClip(ClipData.newPlainText("Test clipboard", "Existing clipboard"))
        dismissClipboardOverlay()
        originalReports = AppErrors.pending.value.map { it.id }.toSet()
    }

    @After fun cleanup(): Unit = runBlocking {
        if (configuredPin) shell("locksettings clear --old 2468")
        shell("input keyevent KEYCODE_WAKEUP")
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        await("Test cleanup could not dismiss the lock screen") {
            shell("wm dismiss-keyguard")
            !keyguard.isKeyguardLocked
        }
        shell("cmd statusbar collapse")
        manager.activeNotifications.filter { it.tag?.startsWith(token) == true }.forEach { manager.cancel(it.tag, it.id) }
        if (originalHeadsUp == "null") shell("settings delete global heads_up_notifications_enabled")
        else shell("settings put global heads_up_notifications_enabled $originalHeadsUp")
        if (originalClip != null) clipboard.setPrimaryClip(originalClip!!)
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clipboard.clearPrimaryClip()
        else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        dismissClipboardOverlay()
        automation.serviceInfo = automation.serviceInfo.apply { flags = originalAccessibilityFlags }
        AppErrors.pending.value.filterNot { it.id in originalReports }.forEach { AppErrors.dismiss(it.id) }
    }

    @Test fun copyIsTheFirstActionAndCopiesOnlyASensitiveCodeWithoutDismissing(): Unit = runBlocking {
        val alert = post("Your verification code is 001234")
        val action = alert.actions.first()
        assertEquals(listOf("Copy code", "Dismiss", "Open ZiG"), alert.actions.map { it.title.toString() })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(action.isAuthenticationRequired)
            assertTrue(action.actionIntent.isImmutable)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) assertFalse(alert.allowSystemGeneratedContextualActions)
        assertEquals("Existing clipboard", clipboardText()) // Arrival never overwrites the clipboard.
        action.actionIntent.send()
        awaitClipboard("001234")
        assertTrue(clipboard.primaryClip!!.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        assertEquals("001234", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(1, manager.activeNotifications.count { it.tag == token })
    }

    @Test fun ordinaryAmbiguousAndPromotionalMessagesKeepOnlyTheExistingActions(): Unit = runBlocking {
        for ((index, text) in listOf("Order 123456 is on the way", "Your OTPs are 123456 and 654321", "Your promo code is FREE100").withIndex()) {
            val alert = post(text, key = "$token-$index")
            assertEquals(listOf("Dismiss", "Open ZiG"), alert.actions.map { it.title.toString() })
            assertFalse(alert.extras.containsKey(NotificationCodeCopier.EXTRA_CODE))
        }
        assertEquals("Existing clipboard", clipboardText())
    }

    @Test fun updatingANotificationCopiesItsNewestCode(): Unit = runBlocking {
        val original = post("Your OTP is 111111").actions.first().actionIntent
        post("Your OTP is 222222")
        original.send() // FLAG_UPDATE_CURRENT refreshes even an already-held PendingIntent.
        awaitClipboard("222222")
        assertEquals(1, manager.activeNotifications.count { it.tag == token })
    }

    @Test fun identicalNumericIdsInDifferentSourceNotificationsDoNotShareCodes(): Unit = runBlocking {
        val first = post("Access code: 1234", key = "$token-first")
        val second = post("Access code: 9876", key = "$token-second")
        assertEquals(2, manager.activeNotifications.count { it.tag?.startsWith(token) == true })
        first.actions.first().actionIntent.send()
        awaitClipboard("1234")
        second.actions.first().actionIntent.send()
        awaitClipboard("9876")
    }

    @Test fun removedAndNonCodeUpdatesCannotCopyAStaleCode(): Unit = runBlocking {
        val stale = post("OTP 123456").actions.first().actionIntent
        post("Your parcel is on the way")
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            NotificationActionReceiver().onReceive(context, copyIntent("123456", "old-token"))
        }
        stale.send()
        delay(300)
        assertEquals("Existing clipboard", clipboardText())
        val current = post("OTP 654321")
        val removed = current.actions.first().actionIntent
        manager.cancel(token, notificationId)
        await { manager.activeNotifications.none { it.tag == token } }
        removed.send()
        delay(300)
        assertEquals("Existing clipboard", clipboardText())
    }

    @Test fun immutableActionIgnoresInjectedCodeExtras(): Unit = runBlocking {
        val alert = post("OTP 123456")
        alert.actions.first().actionIntent.send(context, 0, Intent().putExtra(NotificationCodeCopier.EXTRA_CODE, "999999"))
        awaitClipboard("123456")
    }

    @Test fun privateAndSecretCodesAreNotAddedToThePublicLockScreenPreview(): Unit = runBlocking {
        val alert = post("OTP 123456", visibility = Notification.VISIBILITY_PRIVATE)
        assertNull(alert.publicVersion.actions)
        assertFalse(alert.publicVersion.extras.containsKey(NotificationCodeCopier.EXTRA_CODE))
        assertFalse(alert.publicVersion.extras.containsKey(NotificationCodeCopier.EXTRA_TOKEN))
        assertFalse(alert.publicVersion.extras.toString().contains("123456"))
        val secret = post("OTP 654321", key = "$token-secret", visibility = Notification.VISIBILITY_SECRET)
        assertNull(secret.publicVersion)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) assertTrue(secret.actions.first().isAuthenticationRequired)
    }

    @Test fun lockedPhoneCannotCopyEvenIfTheReceiverIsCalledDirectly(): Unit = runBlocking {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        assertFalse("Test fixture requires a phone without an existing PIN", keyguard.isDeviceSecure)
        shell("locksettings set-pin 2468")
        configuredPin = true
        val alert = post("OTP 123456")
        val intent = copyIntent(alert.extras.getString(NotificationCodeCopier.EXTRA_CODE)!!,
            alert.extras.getString(NotificationCodeCopier.EXTRA_TOKEN)!!)
        shell("input keyevent KEYCODE_SLEEP")
        await("Phone did not lock") { keyguard.isDeviceLocked }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            NotificationActionReceiver().onReceive(context, intent)
        }
        shell("locksettings clear --old 2468")
        configuredPin = false
        shell("input keyevent KEYCODE_WAKEUP")
        await("Phone did not unlock after clearing the test PIN") {
            shell("wm dismiss-keyguard")
            !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked
        }
        shell("am start -n ${context.packageName}/.MainActivity -f 0x10020000")
        focusApp()
        assertEquals("Existing clipboard", clipboardText())
    }

    @Test fun clipboardFailureIsReportedAndDoesNotCrashOrChangeTheClipboard(): Unit = runBlocking {
        val alert = post("OTP 123456")
        val denied = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.CLIPBOARD_SERVICE) throw SecurityException("Forced clipboard access failure")
                else super.getSystemService(name)
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            NotificationActionReceiver().onReceive(denied, copyIntent(alert.extras.getString(NotificationCodeCopier.EXTRA_CODE)!!,
                alert.extras.getString(NotificationCodeCopier.EXTRA_TOKEN)!!))
        }
        assertTrue(AppErrors.pending.value.any { it.operation == "Copying verification code" &&
            it.detail.contains("Forced clipboard access failure") })
        assertEquals("Existing clipboard", clipboardText())
    }

    @Test fun newestMessagingEntryIsUsedAndEmptyOrOrdinaryUpdatesDoNotReuseAnOlderCode(): Unit = runBlocking {
        fun source(latest: String): Notification = NotificationCompat.Builder(context, NotificationPublisher.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info).addExtras(Bundle().apply {
                putParcelableArray(Notification.EXTRA_MESSAGES, arrayOf(
                    Bundle().apply { putCharSequence("text", "OTP 111111") },
                    Bundle().apply { putCharSequence("text", latest) }))
            }).build()
        val latest = source("Your OTP is 222222")
        val alert = post("OTP 111111\nYour OTP is 222222", codeText = NotificationCodeText.latest(latest))
        alert.actions.first().actionIntent.send()
        awaitClipboard("222222")
        for (newText in listOf("Your delivery is arriving", "")) {
            val updated = post("OTP 111111\n$newText", codeText = NotificationCodeText.latest(source(newText)))
            assertEquals(2, updated.actions.size)
        }
    }

    @Test fun fullBigTextCanSupplyACodeThatIsMissingFromTheCollapsedMessage(): Unit = runBlocking {
        val source = NotificationCompat.Builder(context, NotificationPublisher.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentText("Your verification code…")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Your verification code is 654321")).build()
        val alert = post("Your verification code…", codeText = NotificationCodeText.latest(source))
        alert.actions.first().actionIntent.send()
        awaitClipboard("654321")
    }

    @Test fun headsUpPopupCopyButtonCopiesTheCode(): Unit = runBlocking {
        // The notification arrives while ZiG is in the background, as in normal use.
        shell("input keyevent KEYCODE_HOME")
        post("Your verification code is 654321")
        clickSystemCopyButton(5_000)
        shell("cmd statusbar collapse")
        shell("am start -n ${context.packageName}/.MainActivity -f 0x10020000")
        focusApp()
        awaitClipboard("654321")
        assertEquals(1, manager.activeNotifications.count { it.tag == token })
    }

    @Test fun expandedNotificationPanelCopyButtonCopiesTheCode(): Unit = runBlocking {
        post("Your verification code is 009988")
        shell("cmd statusbar expand-notifications")
        clickSystemCopyButton(5_000)
        shell("cmd statusbar collapse")
        focusApp()
        awaitClipboard("009988")
        assertEquals(1, manager.activeNotifications.count { it.tag == token })
    }

    @Test fun originalDismissActionStillRemovesTheNotification(): Unit = runBlocking {
        val alert = post("OTP 123456")
        alert.actions.first { it.title == "Dismiss" }.actionIntent.send()
        await { manager.activeNotifications.none { it.tag == token } }
        assertEquals("Existing clipboard", clipboardText())
    }

    private val notificationId get() = ("copy.test.source" + "42").hashCode()

    private suspend fun post(text: String, key: String = token, codeText: String = text,
        visibility: Int = Notification.VISIBILITY_PUBLIC): Notification {
        val result = publisher.publish("copy.test.source", "Code copy test", text, null, key, 42,
            Notification.CATEGORY_MESSAGE, originalVisibility = visibility, codeText = codeText)
        assertTrue(result.toString(), result.posted)
        await { manager.activeNotifications.any { it.tag == key && it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == text } }
        return manager.activeNotifications.first { it.tag == key }.notification
    }

    private fun copyIntent(code: String, copyToken: String) = Intent(context, NotificationActionReceiver::class.java)
        .setAction(NotificationActionReceiver.ACTION_COPY_CODE)
        .putExtra(NotificationActionReceiver.EXTRA_ZIG_NOTIF_ID, notificationId)
        .putExtra(NotificationPublisher.EXTRA_ZIG_NOTIF_TAG, token)
        .putExtra(NotificationCodeCopier.EXTRA_CODE, code)
        .putExtra(NotificationCodeCopier.EXTRA_TOKEN, copyToken)

    private fun clipboardText() = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    private suspend fun awaitClipboard(value: String) = await { clipboardText() == value }

    private suspend fun focusApp() {
        var focused = false
        await("ZiG did not regain window focus") {
            activity.scenario.onActivity {
                focused = it.window.decorView.hasWindowFocus() ||
                    // An existing error dialog can own the app's focused window.
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        WindowInspector.getGlobalWindowViews().any { root -> root.isShown && root.hasWindowFocus() })
            }
            focused
        }
    }

    private suspend fun dismissClipboardOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        withTimeoutOrNull(2_000) {
            while (true) {
                val dismiss = automation.windows.mapNotNull { it.root }.flatMap(::descendants)
                    .firstOrNull { it.isVisibleToUser && it.contentDescription?.toString() == "Dismiss copied text" }
                if (dismiss?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return@withTimeoutOrNull
                delay(30)
            }
        }
    }

    private suspend fun clickSystemCopyButton(timeout: Long) {
        try {
            withTimeout(timeout) {
                while (true) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) automation.clearCache()
                    // RemoteViews descendants can carry the posting app's package name.
                    // Select the System UI window, then interact with its actual visible views.
                    val nodes = automation.windows.mapNotNull { it.root }
                        .filter { it.packageName?.toString() == "com.android.systemui" }.flatMap(::descendants)
                    for (node in nodes.filter { it.isVisibleToUser && it.text?.toString().equals("Copy code", true) }) {
                        var target: AccessibilityNodeInfo? = node
                        repeat(4) {
                            if (target?.isClickable == true && target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return@withTimeout
                            target = target?.parent
                        }
                    }
                    // Expand the notification itself, including a collapsed heads-up popup.
                    nodes.firstOrNull { it.isVisibleToUser && it.viewIdResourceName?.endsWith("/expand_button") == true &&
                        it.contentDescription?.toString().equals("Expand", true) }
                        ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    delay(50)
                }
            }
        } catch (failure: Exception) {
            automation.windows.mapNotNull { it.root }.flatMap(::descendants).filter { it.isVisibleToUser }.forEach {
                Log.e("CopyUi", "${it.packageName} ${it.viewIdResourceName} text=${it.text} description=${it.contentDescription}")
            }
            throw failure
        }
    }

    private fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::descendants).orEmpty() }

    private suspend fun await(message: String = "Condition was not met", check: () -> Boolean) {
        try {
            withTimeout(10_000) { while (!check()) delay(30) }
        } catch (failure: TimeoutCancellationException) {
            throw AssertionError(message, failure)
        }
    }

    private fun shell(command: String): String = automation.executeShellCommand(command).use { fd ->
        ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
    }
}
