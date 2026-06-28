package dev.zig.notificationfilter.domain

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.zig.notificationfilter.R
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        private const val CHANNEL_ID = "resurrected_alerts"
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
    fun publish(packageName: String, title: String, content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val appName = resolveAppName(packageName)

        val largeIcon = BitmapFactory.decodeResource(context.resources, R.drawable.zig_logo)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("ZiG-$appName")
            .setContentText(content)
            .setSubText(title)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(largeIcon)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        // hashCode() on a package name is stable across runs on the same device
        // and keeps one live notification per source app (new arrival replaces old).
        NotificationManagerCompat.from(context).notify(packageName.hashCode(), notification)
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
