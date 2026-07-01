package dev.zig.notificationfilter.data.local.db

import androidx.room.TypeConverter

class ReviewEnumConverters {

    @TypeConverter
    fun fromReviewState(value: ReviewState): String = value.name

    @TypeConverter
    fun toReviewState(value: String): ReviewState = ReviewState.valueOf(value)

    @TypeConverter
    fun fromSyncStatus(value: SyncStatus): String = value.name

    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus = SyncStatus.valueOf(value)
}
