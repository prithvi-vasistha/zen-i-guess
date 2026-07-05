package dev.zig.notificationfilter.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notification_review",
    indices = [
        Index(value = ["jobId"]),
        Index(value = ["packageName"]),
        Index(value = ["timestamp"]),
        Index(value = ["messageText"]),
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
    // ── Active learning telemetry (added in MIGRATION_5_6) ────────────────────
    // Raw P(BLOCK) sigmoid output from the TFLite model. 0.0 for non-ML code paths.
    val modelConfidence: Float = 0f,
    // Category resolved by NotificationCategory.resolve() at inference time.
    // Format: "CATEGORY_FINANCE", "CATEGORY_UNKNOWN", etc. "UNKNOWN" for non-ML paths.
    val inferredCategory: String = "UNKNOWN",
    // Nullable: non-null only when the user overrides the inferred category from the UI.
    val userAssignedCategory: String? = null,
    // User action on the model's decision. One of: "NONE", "MANUALLY_ALLOWED", "MANUALLY_BLOCKED".
    // "NONE" = model's verdict stands. Stored as String so future states require no migration.
    val userOverrideStatus: String = "NONE",
    // ── Personal Memory (RAC / KNN, added in MIGRATION_6_7) ───────────────────
    // Dense text embedding of this notification, populated only when the user
    // overrides the decision (see PersonalMemoryRepository). NULL for every other row.
    // Stored as a BLOB via FloatArrayConverter. Serves as the corpus for on-device
    // vector similarity search in the ensemble classifier.
    //
    // NOTE: FloatArray gives this data class referential equals/hashCode. That is
    // intentional and safe — the review UI maps entities to a separate UI model that
    // never carries the embedding, so structural equality is never relied upon here.
    val embedding: FloatArray? = null,
    // ── Exact-match cache (added in MIGRATION_7_8) ────────────────────────────
    // Pre-formatted classifier input: listOf(title, content).filter { it.isNotBlank() }
    // .joinToString(" "). Stored with NOCASE collation so the B-Tree index is used by
    // the plain-equality query in NotificationReviewDao.getExactMatchOverride().
    // Empty string for every row inserted before v8 — they never match.
    @ColumnInfo(collate = ColumnInfo.NOCASE)
    val messageText: String = "",
)
