package dev.zig.notificationfilter.domain.summary

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.zig.notificationfilter.data.local.db.AppDatabase
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import dev.zig.notificationfilter.domain.NotificationPublisher

// Instrumentation uses the real application graph; keep this access out of release builds.
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SummaryTestDependencies {
    fun scheduler(): DailySummaryScheduler
    fun delivery(): DailySummaryDelivery
    fun database(): AppDatabase
    fun preferences(): ZigUserPreferences
    fun publisher(): NotificationPublisher
}
