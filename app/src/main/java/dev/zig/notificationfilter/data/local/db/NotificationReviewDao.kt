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

    // Active review inbox: LLM-suppressed notifications within the rolling 24-hour window.
    // MANAGED_FAIL rows are excluded — those notifications never passed the managed-apps gate
    // so they are not meaningful candidates for user review.
    // cutoffTimestamp = System.currentTimeMillis() - 24h, recomputed hourly by the ViewModel.
    @Query("""
        SELECT * FROM notification_review
        WHERE systemDecision = 'LLM_BLOCKED'
        AND timestamp >= :cutoffTimestamp
        ORDER BY timestamp DESC
    """)
    fun getFilteredNotificationsFlow(cutoffTimestamp: Long): Flow<List<NotificationReviewEntity>>

    // Unreviewed suppressed notifications only — useful for badge counts and "needs action" views.
    @Query("""
        SELECT * FROM notification_review
        WHERE reviewState = 'PENDING'
        AND systemDecision = 'LLM_BLOCKED'
        ORDER BY timestamp DESC
    """)
    fun getPendingReviewFlow(): Flow<List<NotificationReviewEntity>>

    // Updating reviewState also resets syncStatus to UNPROCESSED so a user changing
    // their mind (ALLOWED → BLOCKED) is re-exported to the training dataset.
    @Query("UPDATE notification_review SET reviewState = :state, syncStatus = 'UNPROCESSED' WHERE id = :id")
    suspend fun updateReviewState(id: Long, state: String)

    // Called by the retraining WorkManager to fetch only rows that need to be
    // written to the master training CSV — avoids scanning the full table nightly.
    @Query("SELECT * FROM notification_review WHERE syncStatus = 'UNPROCESSED'")
    suspend fun getUnprocessedReviews(): List<NotificationReviewEntity>

    // Called after the WorkManager successfully appends a batch to the training CSV.
    @Query("UPDATE notification_review SET syncStatus = 'EXPORTED' WHERE id IN (:ids)")
    suspend fun markAsExported(ids: List<Long>)
}
