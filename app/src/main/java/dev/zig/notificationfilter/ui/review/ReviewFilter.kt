package dev.zig.notificationfilter.ui.review

data class ReviewFilter(
    val query: String = "",
    val sortField: SortField = SortField.TIME,
    val sortDirection: SortDirection = SortDirection.DESC,
)
