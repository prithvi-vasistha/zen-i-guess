package dev.zig.notificationfilter.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notification_review",
    indices = [
        Index(value = ["jobId"]),
        Index(value = ["packageName"]),
        Index(value = ["timestamp"]),
    ],
)
data class NotificationReviewEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val jobId: String,
    val packageName: String,
    val title: String,
    val content: String,
    val timestamp: Long,
    // Terminal pipeline stage that produced this row: LLM_BLOCKED, MANAGED_FAIL, PUBLISHED.
    // Drives which rows appear in the review inbox — stored as raw String so new stage
    // names can be added without a DB migration.
    val systemDecision: String,
    // Enum types — Room uses ReviewEnumConverters to store these as TEXT in SQLite.
    // Kotlin defaults mirror the SQL DEFAULTs in MIGRATION_4_5 so upgraded rows start correctly.
    val reviewState: ReviewState = ReviewState.PENDING,
    val syncStatus: SyncStatus = SyncStatus.UNPROCESSED,
)
