package dev.zig.notificationfilter.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "managed_app")
data class ManagedAppEntity(
    @PrimaryKey val packageName: String,
)
