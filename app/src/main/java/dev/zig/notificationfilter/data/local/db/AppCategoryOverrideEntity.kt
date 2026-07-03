package dev.zig.notificationfilter.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_category_override")
data class AppCategoryOverrideEntity(
    @PrimaryKey
    val packageName: String,
    // Category name from NotificationCategory enum (e.g. "FINANCE", "SOCIAL").
    // Stored as String so new categories can be added without a schema migration.
    val defaultCategory: String,
)
