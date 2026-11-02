package dev.zig.notificationfilter.service

import android.app.Notification
import android.os.Bundle

internal object NotificationCodeText {
    fun latest(notification: Notification): String {
        val extras = notification.extras
        @Suppress("DEPRECATION")
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.filterIsInstance<Bundle>()
        if (!messages.isNullOrEmpty()) {
            // An image/empty latest message must not resurrect an older code in the thread.
            return messages.last().getCharSequence("text")?.toString().orEmpty()
        }
        return extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
    }
}
