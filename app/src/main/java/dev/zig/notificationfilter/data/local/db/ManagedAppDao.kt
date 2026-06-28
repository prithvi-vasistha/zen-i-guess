package dev.zig.notificationfilter.data.local.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ManagedAppDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ManagedAppEntity)

    @Delete
    suspend fun delete(entity: ManagedAppEntity)

    @Query("SELECT * FROM managed_app")
    fun getAll(): Flow<List<ManagedAppEntity>>

    @Query("SELECT packageName FROM managed_app")
    suspend fun getAllPackageNames(): List<String>
}
