package dev.zig.notificationfilter.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.zig.notificationfilter.ui.apps.ManagedAppsScreen
import dev.zig.notificationfilter.ui.rules.CustomRulesScreen

@Composable
fun ZigNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = ZigScreen.Apps.route,
        modifier = modifier,
    ) {
        composable(ZigScreen.Apps.route) { ManagedAppsScreen() }
        composable(ZigScreen.Rules.route) { CustomRulesScreen() }
    }
}
