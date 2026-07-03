# Keep Hilt entry points and generated components
-keep class dagger.hilt.** { *; }
-keepclasseswithmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}

# Keep Room database, entity, and DAO classes
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Keep the notification listener service so the OS can bind to it
-keep class dev.zig.notificationfilter.service.** { *; }

# Keep WorkManager workers
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }

# AutoValue is a compile-time annotation processor pulled in transitively by Hilt/Room.
# It has no runtime presence; suppress R8's missing-class error for it.
-dontwarn com.google.auto.value.**

# javax.lang.model.* is the Java compiler annotation-processing API. MediaPipe tasks-text
# bundles AutoValue/JavaPoet code that references these classes, but they are never called
# at runtime on Android (they only exist in the JDK, not the Android SDK). Safe to ignore.
-dontwarn javax.lang.model.**
