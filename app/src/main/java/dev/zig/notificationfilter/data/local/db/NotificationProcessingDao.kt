package dev.zig.notificationfilter.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NotificationProcessingDao {
    @Query("SELECT * FROM notification_processing WHERE sourceKey = :key")
    suspend fun get(key: String): NotificationProcessingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(record: NotificationProcessingEntity)

    @Query("SELECT * FROM notification_processing WHERE state != 'COMPLETE'")
    suspend fun pending(): List<NotificationProcessingEntity>

    @Query("UPDATE notification_processing SET state = :state, updatedAt = :now WHERE sourceKey = :key")
    suspend fun setState(key: String, state: String, now: Long)

    @Query("DELETE FROM notification_processing WHERE updatedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
