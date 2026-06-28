package dev.zig.notificationfilter.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ZigNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        evaluateNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // TODO: Forward to removal pipeline
    }

    private fun evaluateNotification(sbn: StatusBarNotification) {
        // TODO: Rules engine → ML pipeline
    }
}
