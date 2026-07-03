package dev.zig.notificationfilter.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationLogDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: NotificationLogEntity)

    @Query("SELECT * FROM notification_log ORDER BY timestamp DESC")
    fun getAll(): Flow<List<NotificationLogEntity>>

    // Live view: Room re-emits whenever a row is inserted or deleted.
    @Query("SELECT * FROM notification_log ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int): Flow<List<NotificationLogEntity>>

    // One-shot snapshot used by the search phase-1 memory filter.
    @Query("SELECT * FROM notification_log ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentSnapshot(limit: Int): List<NotificationLogEntity>

    // Returns PAGE_SIZE distinct jobIds for the given page (newest traces first).
    // The subquery groups by jobId and orders by the most-recent timestamp in each group.
    @Query("""
        SELECT jobId FROM (
            SELECT jobId, MAX(timestamp) AS ts
            FROM notification_log
            GROUP BY jobId
            ORDER BY ts DESC
            LIMIT :pageSize OFFSET :offset
        )
    """)
    suspend fun getJobIdPage(pageSize: Int, offset: Int): List<String>

    // Fetches all log rows for a set of jobIds. Returns rows ordered by timestamp ASC
    // so each trace can be reconstructed with steps in pipeline order.
    @Query("SELECT * FROM notification_log WHERE jobId IN (:jobIds) ORDER BY timestamp ASC")
    suspend fun getLogsForJobs(jobIds: List<String>): List<NotificationLogEntity>

    // Returns distinct jobIds where any row for that job matches the search term.
    // The ViewModel fetches complete log rows for these jobs so the Kotlin filter
    // can apply prefix-aware logic (app:, status:, msg:) to the full trace.
    @Query("""
        SELECT DISTINCT jobId FROM notification_log
        WHERE packageName  LIKE '%' || :query || '%'
           OR title        LIKE '%' || :query || '%'
           OR content      LIKE '%' || :query || '%'
           OR status       LIKE '%' || :query || '%'
           OR filterReason LIKE '%' || :query || '%'
    """)
    suspend fun searchMatchingJobIds(query: String): List<String>

    @Query("DELETE FROM notification_log WHERE timestamp <= :thresholdTime")
    suspend fun deleteLogsOlderThan(thresholdTime: Long)

    @Query("DELETE FROM notification_log")
    suspend fun deleteAll()
}
