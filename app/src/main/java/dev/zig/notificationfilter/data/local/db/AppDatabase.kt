package dev.zig.notificationfilter.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [NotificationLogEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notificationLogDao(): NotificationLogDao
}
