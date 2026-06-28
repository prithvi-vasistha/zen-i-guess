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

    @Query("DELETE FROM notification_log WHERE timestamp <= :thresholdTime")
    suspend fun deleteLogsOlderThan(thresholdTime: Long)
}
