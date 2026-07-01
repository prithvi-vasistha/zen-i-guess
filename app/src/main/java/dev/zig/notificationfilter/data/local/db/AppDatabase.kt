package dev.zig.notificationfilter.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NotificationLogEntity::class,
        KeywordRuleEntity::class,
        ManagedAppEntity::class,
        NotificationReviewEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(StringListConverter::class, ReviewEnumConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun notificationLogDao(): NotificationLogDao
    abstract fun keywordRuleDao(): KeywordRuleDao
    abstract fun managedAppDao(): ManagedAppDao
    abstract fun notificationReviewDao(): NotificationReviewDao

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

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // DEFAULT '' covers all pre-v4 rows; they appear in the log viewer
                // as single-step legacy traces with an empty jobId.
                db.execSQL(
                    "ALTER TABLE `notification_log` ADD COLUMN `jobId` TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_notification_log_jobId` " +
                        "ON `notification_log` (`jobId`)"
                )
            }
        }

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `notification_review` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `jobId` TEXT NOT NULL,
                        `packageName` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `systemDecision` TEXT NOT NULL,
                        `reviewState` TEXT NOT NULL DEFAULT 'PENDING',
                        `syncStatus` TEXT NOT NULL DEFAULT 'UNPROCESSED'
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_notification_review_jobId` " +
                        "ON `notification_review` (`jobId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_notification_review_packageName` " +
                        "ON `notification_review` (`packageName`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_notification_review_timestamp` " +
                        "ON `notification_review` (`timestamp`)"
                )
            }
        }
    }
}
