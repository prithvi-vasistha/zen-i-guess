package dev.zig.notificationfilter.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable lifecycle metadata only; current content and PendingIntents come from Android. */
@Entity(tableName = "notification_processing", indices = [Index("updatedAt"), Index("state")])
data class NotificationProcessingEntity(
    @PrimaryKey val sourceKey: String,
    val fingerprint: String,
    val postTime: Long,
    val updatedAt: Long,
    val jobId: String,
    val state: String,
)
