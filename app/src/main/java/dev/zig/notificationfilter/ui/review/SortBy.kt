package dev.zig.notificationfilter.ui.review

enum class SortBy {
    TIME_DESC,   // newest first (default)
    TIME_ASC,    // oldest first
    APP_NAME,    // alphabetical by packageName
    STATUS,      // grouped: MANUALLY_ALLOWED → NONE → MANUALLY_BLOCKED
}
