package dev.zig.notificationfilter.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import dev.zig.notificationfilter.ui.apps.ManagedAppsScreen
import dev.zig.notificationfilter.ui.logs.LogsScreen
import dev.zig.notificationfilter.ui.navigation.ZigScreen
import dev.zig.notificationfilter.ui.review.NotificationReviewRoute
import dev.zig.notificationfilter.ui.rules.CustomRulesScreen
import dev.zig.notificationfilter.ui.tour.CoachMarkRegistry
import dev.zig.notificationfilter.ui.tour.LocalCoachMarkRegistry
import dev.zig.notificationfilter.ui.tour.TOUR_STEPS
import dev.zig.notificationfilter.ui.tour.TourKeys
import dev.zig.notificationfilter.ui.tour.TourOverlay
import dev.zig.notificationfilter.ui.tour.coachMark
import dev.zig.notificationfilter.ui.tour.navKeyFor
import dev.zig.notificationfilter.ui.tour.rememberTourController
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    startTab: Int = -1,
    startTour: Boolean = false,
    onTourStarted: () -> Unit = {},
    onTourFinished: () -> Unit = {},
) {
    val pagerState = rememberPagerState(pageCount = { ZigScreen.all.size })
    val coroutineScope = rememberCoroutineScope()

    // Coach-mark bounds registry; provided into the tree only while the tour runs so
    // instrumented screens attach no layout listeners during normal use.
    val registry = remember { CoachMarkRegistry() }
    var tourActive by remember { mutableStateOf(startTour) }

    // Mark onboarding complete on disk the moment the tour starts, so a force-quit
    // partway through does not replay it on next launch.
    LaunchedEffect(Unit) {
        if (startTour) onTourStarted()
    }

    // Jump to the requested tab when launched from a notification deep-link. Suppressed
    // during the tour, which drives its own navigation. scrollToPage (not animate) so
    // there is no visible swipe on cold launch.
    LaunchedEffect(startTab) {
        if (!tourActive && startTab in ZigScreen.all.indices) {
            pagerState.scrollToPage(startTab)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    CompositionLocalProvider(
        LocalCoachMarkRegistry provides registry.takeIf { tourActive },
    ) {
    Scaffold(
        bottomBar = {
            NavigationBar(modifier = Modifier.coachMark(TourKeys.NAV_BAR)) {
                ZigScreen.all.forEachIndexed { index, screen ->
                    val selected = pagerState.currentPage == index
                    NavigationBarItem(
                        modifier = Modifier.coachMark(navKeyFor(screen)),
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
    } // CompositionLocalProvider

        if (tourActive) {
            val controller = rememberTourController(
                steps = TOUR_STEPS,
                registry = registry,
                onFinish = {
                    tourActive = false
                    onTourFinished()
                },
            )
            TourOverlay(controller = controller, pagerState = pagerState)
        }
    } // Box
}
