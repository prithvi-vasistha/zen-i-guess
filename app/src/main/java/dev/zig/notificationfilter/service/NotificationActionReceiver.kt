package dev.zig.notificationfilter.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DISMISS = "dev.zig.notificationfilter.action.DISMISS"
        const val EXTRA_ORIGINAL_KEY = "extra_original_key"
        const val EXTRA_ZIG_NOTIF_ID = "extra_zig_notif_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISMISS) return
        val zigNotifId = intent.getIntExtra(EXTRA_ZIG_NOTIF_ID, Int.MIN_VALUE)
        val originalKey = intent.getStringExtra(EXTRA_ORIGINAL_KEY)
        // Cancel the ZiG-forwarded notification from the status bar.
        if (zigNotifId != Int.MIN_VALUE) {
            NotificationManagerCompat.from(context).cancel(zigNotifId)
        }
        // Cancel the original notification from the source app via the NLS.
        if (originalKey != null) {
            ZigNotificationListenerService.instance?.cancelNotification(originalKey)
        }
    }
}
