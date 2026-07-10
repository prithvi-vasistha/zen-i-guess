package dev.zig.notificationfilter.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import dev.zig.notificationfilter.ui.apps.ManagedAppsScreen
import dev.zig.notificationfilter.ui.dailyreview.DailyReviewScreen
import dev.zig.notificationfilter.ui.logs.LogsScreen
import dev.zig.notificationfilter.ui.navigation.ZigScreen
import dev.zig.notificationfilter.ui.review.NotificationReviewRoute
import dev.zig.notificationfilter.ui.rules.CustomRulesScreen
import dev.zig.notificationfilter.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    startTab: Int = -1,
    // Increments each time the daily-summary notification asks to open the review deck. A nonce
    // (not a Boolean) so a repeat tap re-triggers the LaunchedEffect even if the flag was reset.
    dailyReviewNonce: Int = 0,
) {
    val pagerState = rememberPagerState(
        initialPage = ZigScreen.all.indexOf(ZigScreen.Apps),
        pageCount = { ZigScreen.all.size },
    )
    val coroutineScope = rememberCoroutineScope()

    // Jump to the requested tab when launched from a notification deep-link.
    // scrollToPage (not animate) so there is no visible swipe on cold launch.
    LaunchedEffect(startTab) {
        if (startTab in ZigScreen.all.indices) {
            pagerState.scrollToPage(startTab)
        }
    }

    // The Daily Review deck is a full-screen overlay above the pager (its horizontal swipe would
    // otherwise fight the pager). Opened by the notification (nonce) or the in-app CTA.
    var showDailyReview by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(dailyReviewNonce) {
        if (dailyReviewNonce > 0) showDailyReview = true
    }
    val closeDailyReview: () -> Unit = {
        showDailyReview = false
        // "Back to Inbox": land on the Notifications feed.
        coroutineScope.launch { pagerState.scrollToPage(ZigScreen.all.indexOf(ZigScreen.Review)) }
    }

    Box(modifier = Modifier.fillMaxSize()) {

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
            // A tour should only run on the page the user is actually looking at, not on the
            // pages the pager pre-composes off-screen.
            val isCurrent = pagerState.currentPage == page
            when (ZigScreen.all[page]) {
                ZigScreen.Review -> NotificationReviewRoute(
                    onStartDailyReview = { showDailyReview = true },
                    isCurrentPage = isCurrent,
                )
                ZigScreen.Apps -> ManagedAppsScreen()
                ZigScreen.Rules -> CustomRulesScreen(isCurrentPage = isCurrent)
                ZigScreen.Settings -> SettingsScreen()
                ZigScreen.Logs -> LogsScreen()
            }
        }
    }

        if (showDailyReview) {
            // System back exits the deck rather than the app.
            BackHandler(onBack = closeDailyReview)
            DailyReviewScreen(
                onDone = closeDailyReview,
                modifier = Modifier.fillMaxSize(),
            )
        }
    } // Box
}
