package dev.zig.notificationfilter.core.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.zig.notificationfilter.data.local.db.AppCategoryOverrideDao
import dev.zig.notificationfilter.data.local.db.AppDatabase
import dev.zig.notificationfilter.data.local.db.KeywordRuleDao
import dev.zig.notificationfilter.data.local.db.ManagedAppDao
import dev.zig.notificationfilter.data.local.db.NotificationLogDao
import dev.zig.notificationfilter.data.local.db.NotificationReviewDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "zig_notification_log.db"
        )
            // All future schema changes must be supplied here via addMigrations(Migration(...)).
            // fallbackToDestructiveMigration() must never be called on production builds.
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
            )
            .build()

    @Provides
    @Singleton
    fun provideNotificationLogDao(database: AppDatabase): NotificationLogDao =
        database.notificationLogDao()

    @Provides
    @Singleton
    fun provideKeywordRuleDao(database: AppDatabase): KeywordRuleDao =
        database.keywordRuleDao()

    @Provides
    @Singleton
    fun provideManagedAppDao(database: AppDatabase): ManagedAppDao =
        database.managedAppDao()

    @Provides
    @Singleton
    fun provideNotificationReviewDao(database: AppDatabase): NotificationReviewDao =
        database.notificationReviewDao()

    @Provides
    @Singleton
    fun provideAppCategoryOverrideDao(database: AppDatabase): AppCategoryOverrideDao =
        database.appCategoryOverrideDao()
}
