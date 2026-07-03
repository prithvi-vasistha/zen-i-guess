package dev.zig.notificationfilter.domain.classifier

data class ClassifierResult(
    val allowed: Boolean,
    // Raw sigmoid output P(BLOCK) from the TFLite model — stored for active learning telemetry.
    val confidence: Float,
    val category: NotificationCategory,
)
