package dev.zig.notificationfilter.service

internal enum class NotificationDeliveryDecision { FORWARD, BLOCK_IMAGE, DEFER_UNTIL_UNLOCK }

/** Final gate for a notification already accepted by a contact, keyword, or classifier. */
internal object NotificationDeliveryPolicy {
    fun decide(
        hasImage: Boolean,
        imagesEnabled: Boolean,
        contactBypass: Boolean,
        privateWhileLocked: Boolean,
        sensitiveNotificationsEnabled: Boolean,
    ): NotificationDeliveryDecision = when {
        // Keep the existing sensitive-off deferral. With images disabled, private content
        // from an unmatched sender is also deferred because Android may redact its media.
        privateWhileLocked && (!sensitiveNotificationsEnabled ||
            (!imagesEnabled && !contactBypass)) -> NotificationDeliveryDecision.DEFER_UNTIL_UNLOCK
        hasImage && !imagesEnabled && !contactBypass -> NotificationDeliveryDecision.BLOCK_IMAGE
        else -> NotificationDeliveryDecision.FORWARD
    }
}
