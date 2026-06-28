package dev.zig.notificationfilter.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notification_log",
    indices = [Index(value = ["timestamp"])]
)
data class NotificationLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val title: String,
    val content: String,
    val filterReason: String,
    val status: String,
    val timestamp: Long
)
