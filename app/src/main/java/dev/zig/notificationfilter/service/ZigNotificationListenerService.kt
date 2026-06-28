package dev.zig.notificationfilter.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class ZigNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // TODO: Forward to notification processing use case pipeline
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // TODO: Forward to notification processing use case pipeline
    }
}
