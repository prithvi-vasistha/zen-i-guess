package dev.zig.notificationfilter.ui.navigation

import androidx.annotation.DrawableRes
import dev.zig.notificationfilter.R

sealed class ZigScreen(
    val route: String,
    val label: String,
    @DrawableRes val iconRes: Int,
) {
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

    companion object {
        val all = listOf(Apps, Rules)
    }
}
