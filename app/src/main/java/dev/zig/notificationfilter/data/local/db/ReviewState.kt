package dev.zig.notificationfilter.data.local.db

enum class ReviewState {
    PENDING,
    ALLOWED,
    BLOCKED,

    // User swiped the notification away in the active inbox. The row is hidden from the
    // inbox but still counts toward the archive and is never exported as a training signal
    // (dismiss leaves userOverrideStatus / syncStatus untouched). Stored as TEXT, so adding
    // this value required no Room migration.
    DISMISSED,
}
