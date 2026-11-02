package dev.zig.notificationfilter.service

import android.app.Notification
import android.content.Context
import java.util.Locale

/** Recognizes the OS placeholder, not every private notification or missing message. */
internal object NotificationRedactionDetector {
    fun isRedacted(context: Context, notification: Notification): Boolean {
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val placeholders = mutableSetOf("sensitive notification content hidden")
        // AOSP uses this localized resource when sanitizing untrusted listener payloads.
        // It is not a public API: OEMs may omit it; recognition is deliberately best effort.
        @Suppress("DiscouragedApi")
        val id = context.resources.getIdentifier("redacted_notification_message", "string", "android")
        if (id != 0) placeholders.add(context.getString(id).trim().lowercase(Locale.ROOT))
        return listOf(text, bigText).any { it.trim().lowercase(Locale.ROOT) in placeholders }
    }
}
