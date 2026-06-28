package dev.zig.notificationfilter.data.local.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface KeywordRuleDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: KeywordRuleEntity)

    @Delete
    suspend fun delete(entity: KeywordRuleEntity)

    @Query("SELECT * FROM keyword_rule ORDER BY id ASC")
    fun getAll(): Flow<List<KeywordRuleEntity>>

    @Query("SELECT * FROM keyword_rule ORDER BY id ASC")
    suspend fun getAllSnapshot(): List<KeywordRuleEntity>
}
