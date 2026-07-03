package dev.zig.notificationfilter.ui.review

import java.time.LocalDate

data class DateGroup(
    val label: String,
    val date: LocalDate,
    val appGroups: Map<String, List<NotificationReviewUiItem>>,
)
