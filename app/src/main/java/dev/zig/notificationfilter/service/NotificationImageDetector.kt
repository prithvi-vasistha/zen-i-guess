package dev.zig.notificationfilter.service

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.app.NotificationCompat

/** Detects image payloads without reading media URIs or treating icons/avatars as attachments. */
internal object NotificationImageDetector {
    @Suppress("DEPRECATION")
    fun hasImage(notification: Notification): Boolean {
        val extras = notification.extras
        if (extras?.get(Notification.EXTRA_PICTURE) is Bitmap) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            extras?.get(Notification.EXTRA_PICTURE_ICON) is Icon) return true

        val messages = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(notification)?.messages.orEmpty()
        val latestTimestamp = messages.maxOfOrNull { it.timestamp } ?: return false
        // An image and its caption can be separate Message entries with the same timestamp.
        // Older attachments belong to the conversation history, not the incoming message.
        return messages.any {
            it.timestamp == latestTimestamp &&
                it.dataMimeType?.startsWith("image/", ignoreCase = true) == true &&
                it.dataUri != null
        }
    }
}
