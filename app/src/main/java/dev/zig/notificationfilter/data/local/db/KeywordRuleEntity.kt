package dev.zig.notificationfilter.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "keyword_rule")
data class KeywordRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val conditions: List<String>,
)
