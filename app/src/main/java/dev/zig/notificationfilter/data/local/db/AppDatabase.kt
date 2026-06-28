package dev.zig.notificationfilter.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [NotificationLogEntity::class, KeywordRuleEntity::class, ManagedAppEntity::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(StringListConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun notificationLogDao(): NotificationLogDao
    abstract fun keywordRuleDao(): KeywordRuleDao
    abstract fun managedAppDao(): ManagedAppDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `keyword_rule` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`conditions` TEXT NOT NULL)"
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `managed_app` " +
                        "(`packageName` TEXT NOT NULL, PRIMARY KEY(`packageName`))"
                )
            }
        }
    }
}
