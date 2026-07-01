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
    // Terminal pipeline stage that produced this row: LLM_BLOCKED, MANAGED_FAIL,
    // LLM_ALLOWED, CONTACT_PASS, KEYWORD_PASS. Drives which rows appear in the review inbox.
    val systemDecision: String,
    // Kotlin defaults mirror the SQL DEFAULTs in MIGRATION_4_5 so both new inserts
    // and upgraded rows start in the correct state.
    val reviewState: String = ReviewState.PENDING.name,
    val syncStatus: String = SyncStatus.UNPROCESSED.name,
)
