package dev.zig.notificationfilter.domain

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.zig.notificationfilter.R
import dev.zig.notificationfilter.service.NotificationActionReceiver
import dev.zig.notificationfilter.service.NotificationCodeCopier
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import dev.zig.notificationfilter.core.errors.AppErrors

@Singleton
class NotificationPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        // v2: rotated from "resurrected_alerts" — Android silently ignores importance
        // upgrades to an already-registered channel ID, so a new ID forces a clean slate.
        const val CHANNEL_ID = "zig_alerts_v2"
        const val EXTRA_ZIG_NOTIF_TAG = "extra_zig_notif_tag"
        private const val EXTRA_SOURCE_FINGERPRINT = "zig_source_fingerprint"
        private const val TEST_ALERT_ID = 0x7A690002
        private const val CHANNEL_ID_LEGACY = "resurrected_alerts"

        // Separate channel for the daily summary so users can mute it independently
        // of the high-importance forwarded alert channel.
        internal const val CHANNEL_ID_SUMMARY = "zig_daily_summary"

        // Stable ID for the daily summary notification — only ever one at a time.
        internal const val NOTIF_ID_SUMMARY = 0x7A690001

        const val EXTRA_ZIG_NOTIF_TO_DISMISS = "zig_notif_to_dismiss"
        const val EXTRA_NAVIGATE_TO = "zig_navigate_to"
        const val NAV_TARGET_REVIEW = "review"
        // Tapping the daily summary opens the gamified Daily Review swipe deck (Phase 3).
        const val NAV_TARGET_DAILY_REVIEW = "daily_review"
    }

    fun postingSettings(channelId: String = CHANNEL_ID): NotificationPostingSettings {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(channelId)
        val groupBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            channel?.group?.let { manager.getNotificationChannelGroup(it)?.isBlocked } == true
        return NotificationPostingSettings(
            permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
            appEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && manager.areNotificationsPaused()),
            channelEnabled = channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE && !groupBlocked,
            channelImportance = channel?.importance ?: NotificationManager.IMPORTANCE_NONE,
            channelHasSound = channel?.sound != null,
            channelHasVibration = channel?.shouldVibrate() == true,
        )
    }

    fun hasPosted(originalKey: String, fingerprint: String): Boolean =
        context.getSystemService(NotificationManager::class.java).activeNotifications.any {
            it.tag == originalKey && it.notification.extras.getString(EXTRA_SOURCE_FINGERPRINT) == fingerprint
        }

    fun sendTestAlert(): NotificationDeliveryResult = publish(
        context.packageName, "Notification test", "ZiG can post alerts. Sound and pop-ups depend on your Android settings.",
        null, "zig-test-alert", TEST_ALERT_ID, NotificationCompat.CATEGORY_MESSAGE,
    )

    init {
        AppErrors.run("Preparing notification channels") {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.deleteNotificationChannel(CHANNEL_ID_LEGACY)
        val channel = NotificationChannel(CHANNEL_ID, "Important Alerts", NotificationManager.IMPORTANCE_HIGH)
        manager.createNotificationChannel(channel)
        val summaryChannel = NotificationChannel(
            CHANNEL_ID_SUMMARY,
            "Daily Summary",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "End-of-day recap of notifications ZiG reviewed today" }
        manager.createNotificationChannel(summaryChannel)
        }
    }

    // POST_NOTIFICATIONS is a runtime permission on API 33+. Granting it is the
    // responsibility of the Activity. Every forwarding attempt returns a delivery
    // result so the listener cannot record a denied post as a successful alert.
    fun publishHiddenContent(
        packageName: String,
        contentIntent: PendingIntent?,
        originalKey: String,
        originalNotifId: Int,
        sourceFingerprint: String,
        originalVisibility: Int,
    ): NotificationDeliveryResult = publish(
        packageName, "", "", contentIntent, originalKey, originalNotifId,
        NotificationCompat.CATEGORY_MESSAGE, sourceFingerprint, originalVisibility,
        codeText = "", hiddenContent = true,
    )

    @SuppressLint("MissingPermission")
    fun publish(
        packageName: String,
        title: String,
        content: String,
        contentIntent: PendingIntent?,
        originalKey: String,
        originalNotifId: Int,
        originalCategory: String?,
        sourceFingerprint: String? = null,
        originalVisibility: Int = NotificationCompat.VISIBILITY_PRIVATE,
        codeText: String = content,
        hiddenContent: Boolean = false,
    ): NotificationDeliveryResult {
        return try {
            val settings = postingSettings()
            val blocked = settings.blockingStatus()
            if (blocked != null) return NotificationDeliveryResult(blocked)
            if (sourceFingerprint != null && hasPosted(originalKey, sourceFingerprint)) {
                return NotificationDeliveryResult(NotificationDeliveryStatus.ALREADY_POSTED)
            }

            val appName = resolveAppName(packageName)
            val alertContent = if (hiddenContent)
                "Sensitive message received. Open $appName to view it." else content
            val largeIcon = BitmapFactory.decodeResource(context.resources, R.drawable.zig_logo)

            // Preserve the legacy numeric ID; the full source key tag separates
            // notifications that reuse an ID and replaces the same source on update.
            val zigNotifId = (packageName + originalNotifId.toString()).hashCode()

            // FLAG_IMMUTABLE: prevents external apps from altering the pending intent.
            // FLAG_UPDATE_CURRENT: if a prior PI exists for the same (action, requestCode),
            // replace its extras so "Dismiss" always targets the newest notification.
            val piFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

            val dismissPendingIntent = PendingIntent.getBroadcast(
                context,
                zigNotifId,
                Intent(context, NotificationActionReceiver::class.java).apply {
                    action = NotificationActionReceiver.ACTION_DISMISS
                    data = Uri.parse("zig://notification/dismiss/${Uri.encode(originalKey)}")
                    putExtra(NotificationActionReceiver.EXTRA_ORIGINAL_KEY, originalKey)
                    putExtra(NotificationActionReceiver.EXTRA_ZIG_NOTIF_ID, zigNotifId)
                    putExtra(EXTRA_ZIG_NOTIF_TAG, originalKey)
                },
                piFlags,
            )

            // getActivity() is required here — not getBroadcast(). Android 10+ blocks startActivity()
            // from a BroadcastReceiver.onReceive() background context. Using getActivity() directly
            // preserves the user-gesture foreground-launch privilege granted when tapping the action.
            // ComponentName string avoids a domain→UI class import that can confuse Hilt's codegen.
            val openZigPendingIntent = PendingIntent.getActivity(
                context,
                zigNotifId + 1,
                Intent().apply {
                    component = ComponentName(context.packageName, "${context.packageName}.MainActivity")
                    data = Uri.parse("zig://notification/open/${Uri.encode(originalKey)}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EXTRA_ZIG_NOTIF_TO_DISMISS, zigNotifId)
                    putExtra(EXTRA_ZIG_NOTIF_TAG, originalKey)
                },
                piFlags,
            )

            // A redacted payload can still carry the original conversation's
            // PendingIntent. Prefer it; otherwise offer the source app's launcher.
            val sourcePendingIntent = contentIntent ?: if (hiddenContent) {
                AppErrors.run("Preparing original app shortcut") {
                    context.packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        // Keep the app's launch URI intact; a foreign URI can be
                        // mistaken for a deep link. Identifier isolates modern PIs.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) launch.identifier = originalKey
                        PendingIntent.getActivity(context, originalKey.hashCode(), launch, piFlags)
                    }
                }
            } else null

            val visibility = when (originalVisibility) {
                NotificationCompat.VISIBILITY_PUBLIC -> NotificationCompat.VISIBILITY_PUBLIC
                NotificationCompat.VISIBILITY_SECRET -> NotificationCompat.VISIBILITY_SECRET
                else -> NotificationCompat.VISIBILITY_PRIVATE
            }
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("ZiG-$appName")
                .setContentText(alertContent)
                .setStyle(NotificationCompat.BigTextStyle().bigText(alertContent))
                .setSubText(if (hiddenContent) "Sensitive message received" else title)
                .setSmallIcon(R.drawable.ic_notification)
                .setLargeIcon(largeIcon)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(visibility)
                // Preserve the category as one input to Android's interruption rules.
                // The user's channel and Do Not Disturb settings still control alerts.
                .setCategory(originalCategory ?: NotificationCompat.CATEGORY_MESSAGE)
                // Request the defaults without overriding the user's channel settings.
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                // Tapping the notification body opens the original app and auto-dismisses.
                .setContentIntent(sourcePendingIntent ?: openZigPendingIntent)
                .addExtras(android.os.Bundle().apply { putString(EXTRA_SOURCE_FINGERPRINT, sourceFingerprint) })
                .setAutoCancel(true)

            if (hiddenContent) builder.setAllowSystemGeneratedContextualActions(false)

            (if (hiddenContent) null else VerificationCodeExtractor.extract(title, codeText))?.let { code ->
                val token = UUID.randomUUID().toString()
                val copyIntent = PendingIntent.getBroadcast(context, zigNotifId,
                    Intent(context, NotificationActionReceiver::class.java).apply {
                        action = NotificationActionReceiver.ACTION_COPY_CODE
                        data = Uri.parse("zig://notification/copy/${Uri.encode(originalKey)}")
                        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        putExtra(NotificationActionReceiver.EXTRA_ZIG_NOTIF_ID, zigNotifId)
                        putExtra(EXTRA_ZIG_NOTIF_TAG, originalKey)
                        putExtra(NotificationCodeCopier.EXTRA_CODE, code)
                        putExtra(NotificationCodeCopier.EXTRA_TOKEN, token)
                    }, piFlags)
                builder.addExtras(android.os.Bundle().apply {
                    putString(NotificationCodeCopier.EXTRA_CODE, code)
                    putString(NotificationCodeCopier.EXTRA_TOKEN, token)
                })
                // First action for the standard heads-up and expanded templates. Android
                // controls their presentation; a broadcast copies without opening ZiG.
                builder.addAction(NotificationCompat.Action.Builder(R.drawable.ic_copy, "Copy code", copyIntent)
                    .setShowsUserInterface(false)
                    .setAuthenticationRequired(true)
                    .build())
                builder.setAllowSystemGeneratedContextualActions(false)
            }
            if (hiddenContent && sourcePendingIntent != null) {
                builder.addAction(NotificationCompat.Action.Builder(0, "Open $appName", sourcePendingIntent)
                    .setAuthenticationRequired(true).build())
                builder.addAction(0, "Dismiss", dismissPendingIntent)
            } else {
                builder.addAction(0, "Dismiss", dismissPendingIntent)
                    .addAction(0, "Open ZiG", openZigPendingIntent)
            }

            if (visibility == NotificationCompat.VISIBILITY_PRIVATE) {
                // This alternate notification is only a lock-screen representation. Never
                // copy the sender, message, source extras or actions into the public version.
                builder.setPublicVersion(NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("ZiG-$appName")
                    .setContentText(if (hiddenContent) "Sensitive message received" else "New notification")
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setContentIntent(if (hiddenContent) sourcePendingIntent ?: openZigPendingIntent else openZigPendingIntent)
                    .setAutoCancel(true)
                    .build())
            }
            val notification = builder.build()

            // Include Android's complete source key: apps can reuse numeric IDs with different tags.
            NotificationManagerCompat.from(context).notify(originalKey, zigNotifId, notification)
            NotificationDeliveryResult(
                NotificationDeliveryStatus.POSTED,
                if (settings.channelImportance < NotificationManager.IMPORTANCE_HIGH || !settings.channelHasSound)
                    "The alert channel may be quiet. Android controls sound and pop-ups."
                else "Android controls sound and pop-ups, including Do Not Disturb.",
            )
        } catch (failure: SecurityException) {
            AppErrors.report("Publishing notification", failure)
            NotificationDeliveryResult(NotificationDeliveryStatus.NOT_POSTED_ERROR, "Android refused the posting request. Check notification access and permissions.")
        } catch (failure: RuntimeException) {
            AppErrors.report("Publishing notification", failure)
            NotificationDeliveryResult(NotificationDeliveryStatus.NOT_POSTED_ERROR, "Android could not accept the posting request.")
        }
    }

    @SuppressLint("MissingPermission")
    internal fun postDailySummary(count: Int, date: LocalDate = LocalDate.now(), occurrenceId: String? = null,
        channelId: String = CHANNEL_ID_SUMMARY): NotificationDeliveryResult {
        if (count <= 0) return NotificationDeliveryResult(NotificationDeliveryStatus.NOTHING_TO_SUMMARIZE,
            "There were no AI notifications to review before the scheduled time.")
        return try {
            val settings = postingSettings(channelId)
            val blocked = settings.blockingStatus()
            if (blocked != null) return NotificationDeliveryResult(blocked,
                if (blocked == NotificationDeliveryStatus.NOT_POSTED_CHANNEL_BLOCKED) "Daily Summary channel is disabled."
                else "Check ZiG's notification permissions in Android settings.")
            if (occurrenceId != null && context.getSystemService(NotificationManager::class.java).activeNotifications.any {
                it.id == NOTIF_ID_SUMMARY && it.notification.extras.getString(EXTRA_SOURCE_FINGERPRINT) == occurrenceId
            }) return NotificationDeliveryResult(NotificationDeliveryStatus.ALREADY_POSTED)

            val tapIntent = PendingIntent.getActivity(
                context,
                NOTIF_ID_SUMMARY,
                Intent().apply {
                    component = ComponentName(context.packageName, "${context.packageName}.MainActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EXTRA_NAVIGATE_TO, if (date == LocalDate.now()) NAV_TARGET_DAILY_REVIEW else NAV_TARGET_REVIEW)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val dayLabel = if (date == LocalDate.now()) "today" else "on ${date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}"
            val body = "ZiG reviewed $count notification${if (count == 1) "" else "s"} $dayLabel. Tap to review."
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("ZiG Daily Summary")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(tapIntent)
                .setAutoCancel(true)
                .addExtras(android.os.Bundle().apply { putString(EXTRA_SOURCE_FINGERPRINT, occurrenceId) })
                .build()

            NotificationManagerCompat.from(context).notify(NOTIF_ID_SUMMARY, notification)
            NotificationDeliveryResult(NotificationDeliveryStatus.POSTED,
                "Android controls sound and lock-screen visibility through the Daily Summary channel.")
        } catch (failure: RuntimeException) {
            AppErrors.report("Publishing daily summary", failure)
            NotificationDeliveryResult(NotificationDeliveryStatus.NOT_POSTED_ERROR, "Android could not accept the daily summary.")
        }
    }

    private fun resolveAppName(packageName: String): String {
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}
