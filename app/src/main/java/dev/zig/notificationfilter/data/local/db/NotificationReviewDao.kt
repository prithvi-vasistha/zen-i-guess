package dev.zig.notificationfilter.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationReviewDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: NotificationReviewEntity)

    // Active review inbox: classifier-suppressed notifications within the rolling 24-hour window.
    // MANAGED_FAIL rows are excluded — those notifications never passed the managed-apps gate
    // so they are not meaningful candidates for user review.
    // cutoffTimestamp = System.currentTimeMillis() - 24h, recomputed hourly by the ViewModel.
    // IN clause covers legacy LLM_BLOCKED rows (Phase 1) and new MODEL_BLOCKED rows (Phase 2+).
    @Query("""
        SELECT * FROM notification_review
        WHERE systemDecision IN ('LLM_BLOCKED', 'MODEL_BLOCKED')
        AND timestamp >= :cutoffTimestamp
        ORDER BY timestamp DESC
    """)
    fun getFilteredNotificationsFlow(cutoffTimestamp: Long): Flow<List<NotificationReviewEntity>>

    // Unreviewed suppressed notifications only — useful for badge counts and "needs action" views.
    @Query("""
        SELECT * FROM notification_review
        WHERE reviewState = 'PENDING'
        AND systemDecision IN ('LLM_BLOCKED', 'MODEL_BLOCKED')
        ORDER BY timestamp DESC
    """)
    fun getPendingReviewFlow(): Flow<List<NotificationReviewEntity>>

    // Updating reviewState also resets syncStatus to UNPROCESSED so a user changing
    // their mind (ALLOWED → BLOCKED) is re-exported to the training dataset.
    @Query("UPDATE notification_review SET reviewState = :state, syncStatus = 'UNPROCESSED' WHERE id = :id")
    suspend fun updateReviewState(id: Long, state: String)

    @Query("UPDATE notification_review SET userOverrideStatus = :status WHERE id = :id")
    suspend fun updateOverrideStatus(id: Long, status: String)

    @Query("UPDATE notification_review SET userAssignedCategory = :category WHERE id = :id")
    suspend fun updateUserAssignedCategory(id: Long, category: String?)

    // ── Personal Memory (RAC / KNN) ───────────────────────────────────────────

    // Single-row fetch used by PersonalMemoryRepository to read a notification's
    // text before embedding it at override time.
    @Query("SELECT * FROM notification_review WHERE id = :id")
    suspend fun getById(id: Long): NotificationReviewEntity?

    // Persists (or clears, when null) the embedding vector for one review row.
    @Query("UPDATE notification_review SET embedding = :embedding WHERE id = :id")
    suspend fun updateEmbedding(id: Long, embedding: FloatArray?)

    // The vector-search corpus: every row the user has explicitly overridden AND
    // for which an embedding has been computed. Bounded by the number of manual
    // overrides, so an in-memory cosine scan over this set is cheap.
    @Query("""
        SELECT * FROM notification_review
        WHERE userOverrideStatus IN ('MANUALLY_ALLOWED', 'MANUALLY_BLOCKED')
        AND embedding IS NOT NULL
    """)
    suspend fun getPersonalMemory(): List<NotificationReviewEntity>

    // Classifier-blocked notifications older than the 24-hour active window — shown on the Archive screen.
    @Query("""
        SELECT * FROM notification_review
        WHERE systemDecision IN ('LLM_BLOCKED', 'MODEL_BLOCKED')
        AND timestamp < :cutoffTimestamp
        ORDER BY timestamp DESC
    """)
    fun getArchivedNotificationsFlow(cutoffTimestamp: Long): Flow<List<NotificationReviewEntity>>

    // Search across packageName, title, and content with a LIKE '%query%' match.
    // Applied to the active window (timestamp >= cutoffTimestamp) for the inbox, and
    // to the archive window (timestamp >= archiveCutoffTimestamp) for the archive screen.
    // Sorting is applied in Kotlin on the emitted list — see ReviewFilter / SortBy.
    // Phase D: IN clause expanded to include PUBLISHED so the review screen shows
    // both model-blocked and model-allowed notifications with context-aware actions.
    @Query("""
        SELECT * FROM notification_review
        WHERE systemDecision IN ('LLM_BLOCKED', 'MODEL_BLOCKED', 'PUBLISHED')
        AND timestamp >= :cutoffTimestamp
        AND (packageName LIKE '%' || :query || '%'
          OR title      LIKE '%' || :query || '%'
          OR content    LIKE '%' || :query || '%')
        ORDER BY timestamp DESC
    """)
    fun searchActiveFlow(cutoffTimestamp: Long, query: String): Flow<List<NotificationReviewEntity>>

    // Archive = last 30 days (>= archiveCutoffTimestamp), overlapping with the active inbox.
    // Today's notifications appear in both tabs.
    @Query("""
        SELECT * FROM notification_review
        WHERE systemDecision IN ('LLM_BLOCKED', 'MODEL_BLOCKED', 'PUBLISHED')
        AND timestamp >= :archiveCutoffTimestamp
        AND (packageName LIKE '%' || :query || '%'
          OR title      LIKE '%' || :query || '%'
          OR content    LIKE '%' || :query || '%')
        ORDER BY timestamp DESC
    """)
    fun searchArchiveFlow(archiveCutoffTimestamp: Long, query: String): Flow<List<NotificationReviewEntity>>

    // Called by the retraining WorkManager to fetch only rows that need to be
    // written to the master training CSV — avoids scanning the full table nightly.
    @Query("SELECT * FROM notification_review WHERE syncStatus = 'UNPROCESSED'")
    suspend fun getUnprocessedReviews(): List<NotificationReviewEntity>

    // Called after the WorkManager successfully appends a batch to the training CSV.
    @Query("UPDATE notification_review SET syncStatus = 'EXPORTED' WHERE id IN (:ids)")
    suspend fun markAsExported(ids: List<Long>)
}
