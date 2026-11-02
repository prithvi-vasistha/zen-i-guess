package dev.zig.notificationfilter.domain

enum class NotificationDeliveryStatus(val label: String) {
    LEGACY("Delivery not recorded"),
    POSTING("Posting to Android"),
    POSTED("Posted to Android"),
    ALREADY_POSTED("Already posted to Android"),
    NOT_POSTED_PERMISSION("Not posted — notification permission disabled"),
    NOT_POSTED_APP_BLOCKED("Not posted — app notifications disabled"),
    NOT_POSTED_CHANNEL_BLOCKED("Not posted — notification channel disabled"),
    NOTHING_TO_SUMMARIZE("Nothing to review"),
    NOT_POSTED_ERROR("Not posted — publishing failed"),
    NOT_POSTED_FILTERED("Not posted — blocked by filtering"),
    WAITING_FOR_UNLOCK("Waiting for unlock"),
    CONTENT_HIDDEN("Content hidden by Android"),
    SOURCE_UNAVAILABLE("Original notification no longer available"),
    RECOVERY_EXPIRED("Recovery window expired"),
    SUPERSEDED("Replaced by a newer notification"),
    DELIVERY_UNCONFIRMED("Delivery could not be confirmed after restart"),
}

data class NotificationDeliveryResult(
    val status: NotificationDeliveryStatus,
    val detail: String = "",
) {
    val posted: Boolean get() = status == NotificationDeliveryStatus.POSTED ||
        status == NotificationDeliveryStatus.ALREADY_POSTED
}

data class NotificationPostingSettings(
    val permissionGranted: Boolean,
    val appEnabled: Boolean,
    val channelEnabled: Boolean,
    val channelImportance: Int,
    val channelHasSound: Boolean,
    val channelHasVibration: Boolean,
) {
    fun blockingStatus(): NotificationDeliveryStatus? = when {
        !permissionGranted -> NotificationDeliveryStatus.NOT_POSTED_PERMISSION
        !appEnabled -> NotificationDeliveryStatus.NOT_POSTED_APP_BLOCKED
        !channelEnabled -> NotificationDeliveryStatus.NOT_POSTED_CHANNEL_BLOCKED
        else -> null
    }
}
