package dev.zig.notificationfilter.ui.review

data class ReviewFilter(
    val query: String = "",
    val sortBy: SortBy = SortBy.TIME_DESC,
)
