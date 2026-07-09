package dev.zig.notificationfilter.data.local.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface KeywordRuleDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: KeywordRuleEntity)

    @Delete
    suspend fun delete(entity: KeywordRuleEntity)

    @Update
    suspend fun update(entity: KeywordRuleEntity)

    @Query("SELECT * FROM keyword_rule ORDER BY id ASC")
    fun getAll(): Flow<List<KeywordRuleEntity>>

    @Query("SELECT * FROM keyword_rule ORDER BY id ASC")
    suspend fun getAllSnapshot(): List<KeywordRuleEntity>

    @Query("SELECT * FROM keyword_rule WHERE ruleType = :type ORDER BY id ASC")
    fun getByType(type: String): Flow<List<KeywordRuleEntity>>

    @Query("SELECT * FROM keyword_rule WHERE ruleType = :type ORDER BY id ASC")
    suspend fun getSnapshotByType(type: String): List<KeywordRuleEntity>
}
