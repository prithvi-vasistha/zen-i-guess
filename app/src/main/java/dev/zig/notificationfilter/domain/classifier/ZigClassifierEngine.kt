package dev.zig.notificationfilter.domain.classifier

interface ZigClassifierEngine {
    suspend fun evaluate(
        category: NotificationCategory,
        packageName: String,
        text: String,
    ): ClassifierResult
}
