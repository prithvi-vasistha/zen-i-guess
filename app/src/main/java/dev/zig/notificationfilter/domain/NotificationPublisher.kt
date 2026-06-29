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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.zig.notificationfilter.R
import dev.zig.notificationfilter.service.NotificationActionReceiver
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        private const val CHANNEL_ID = "resurrected_alerts"
        const val EXTRA_ZIG_NOTIF_TO_DISMISS = "zig_notif_to_dismiss"
    }

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Important Alerts",
            NotificationManager.IMPORTANCE_HIGH,
        )
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    // POST_NOTIFICATIONS is a runtime permission on API 33+. Granting it is the
    // responsibility of the onboarding Activity. If it hasn't been granted yet
    // we drop the notification silently rather than crash.
    @SuppressLint("MissingPermission")
    fun publish(
        packageName: String,
        title: String,
        content: String,
        contentIntent: PendingIntent?,
        originalKey: String,
        originalNotifId: Int,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val appName = resolveAppName(packageName)
        val largeIcon = BitmapFactory.decodeResource(context.resources, R.drawable.zig_logo)

        // ID is stable per (app, original notification ID) pair — keeps separate conversations
        // as separate ZiG notifications while replacing the same conversation on update.
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
                putExtra(NotificationActionReceiver.EXTRA_ORIGINAL_KEY, originalKey)
                putExtra(NotificationActionReceiver.EXTRA_ZIG_NOTIF_ID, zigNotifId)
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
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_ZIG_NOTIF_TO_DISMISS, zigNotifId)
            },
            piFlags,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("ZiG-$appName")
            .setContentText(content)
            .setSubText(title)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(largeIcon)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Tapping the notification body opens the original app and auto-dismisses.
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            // Action buttons visible in the expanded notification panel.
            .addAction(0, "Dismiss", dismissPendingIntent)
            .addAction(0, "Open ZiG", openZigPendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(zigNotifId, notification)
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
