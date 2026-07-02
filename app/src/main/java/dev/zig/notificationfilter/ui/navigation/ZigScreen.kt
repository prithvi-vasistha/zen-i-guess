package dev.zig.notificationfilter.ui.navigation

import androidx.annotation.DrawableRes
import dev.zig.notificationfilter.R

sealed class ZigScreen(
    val route: String,
    val label: String,
    @DrawableRes val iconRes: Int,
) {
    data object Review : ZigScreen(
        route = "review",
        label = "Notifications",
        iconRes = R.drawable.ic_nav_review,
    )

    data object Apps : ZigScreen(
        route = "apps",
        label = "Apps",
        iconRes = R.drawable.ic_nav_apps,
    )

    data object Rules : ZigScreen(
        route = "rules",
        label = "Rules",
        iconRes = R.drawable.ic_nav_rules,
    )

    data object Logs : ZigScreen(
        route = "logs",
        label = "Logs",
        iconRes = R.drawable.ic_nav_logs,
    )

    companion object {
        val all = listOf(Review, Apps, Rules, Logs)
    }
}
