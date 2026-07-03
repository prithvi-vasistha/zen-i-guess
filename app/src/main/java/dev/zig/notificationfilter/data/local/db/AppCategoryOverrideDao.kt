package dev.zig.notificationfilter.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppCategoryOverrideDao {

    // REPLACE strategy: re-inserting the same packageName updates the category.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AppCategoryOverrideEntity)

    @Query("SELECT * FROM app_category_override WHERE packageName = :packageName LIMIT 1")
    suspend fun getForPackage(packageName: String): AppCategoryOverrideEntity?

    // Observed by the UI so the header dropdown reflects the current override in real-time.
    @Query("SELECT * FROM app_category_override")
    fun getAllFlow(): Flow<List<AppCategoryOverrideEntity>>

    @Query("DELETE FROM app_category_override WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}
