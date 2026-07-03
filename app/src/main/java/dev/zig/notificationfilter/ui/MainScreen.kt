package dev.zig.notificationfilter.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import dev.zig.notificationfilter.ui.apps.ManagedAppsScreen
import dev.zig.notificationfilter.ui.logs.LogsScreen
import dev.zig.notificationfilter.ui.navigation.ZigScreen
import dev.zig.notificationfilter.ui.review.NotificationReviewRoute
import dev.zig.notificationfilter.ui.rules.CustomRulesScreen
import kotlinx.coroutines.launch

@Composable
fun MainScreen(startTab: Int = -1) {
    val pagerState = rememberPagerState(pageCount = { ZigScreen.all.size })
    val coroutineScope = rememberCoroutineScope()

    // Jump to the requested tab when launched from a notification deep-link.
    // scrollToItem (not animate) so there is no visible swipe on cold launch.
    LaunchedEffect(startTab) {
        if (startTab in ZigScreen.all.indices) {
            pagerState.scrollToPage(startTab)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                ZigScreen.all.forEachIndexed { index, screen ->
                    val selected = pagerState.currentPage == index
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        icon = {
                            Icon(
                                painter = painterResource(screen.iconRes),
                                contentDescription = screen.label,
                            )
                        },
                        label = {
                            Text(
                                text = screen.label,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.padding(innerPadding),
        ) { page ->
            when (ZigScreen.all[page]) {
                ZigScreen.Review -> NotificationReviewRoute()
                ZigScreen.Apps -> ManagedAppsScreen()
                ZigScreen.Rules -> CustomRulesScreen()
                ZigScreen.Logs -> LogsScreen()
            }
        }
    }
}
