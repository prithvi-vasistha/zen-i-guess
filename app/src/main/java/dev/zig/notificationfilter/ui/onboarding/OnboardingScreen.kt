package dev.zig.notificationfilter.ui.onboarding

// SUPERSEDED: this static 4-slide carousel has been replaced by the interactive
// coach-mark tour in ui/tour/ (TourOverlay), which walks the user through the real
// screens. Kept commented-out for reference; delete once the tour is settled.
/*
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.zig.notificationfilter.R
import dev.zig.notificationfilter.ui.theme.ZigGreen
import dev.zig.notificationfilter.ui.theme.ZigOnGreen
import kotlinx.coroutines.launch

private data class OnboardingPage(
    @DrawableRes val iconRes: Int,
    val headline: String,
    val body: String,
)

private val pages = listOf(
    OnboardingPage(
        iconRes = R.drawable.ic_nav_apps,
        headline = "Choose What ZiG Watches",
        body = "Select the apps whose notifications ZiG should intercept. Unselected apps pass through completely unfiltered.",
    ),
    OnboardingPage(
        iconRes = R.drawable.ic_nav_review,
        headline = "Review & Train ZiG",
        body = "The Notifications tab shows everything ZiG processed in the last 24 hours. Allow or Block each one — your decisions teach ZiG your personal preferences.",
    ),
    OnboardingPage(
        iconRes = R.drawable.ic_nav_logs,
        headline = "30-Day Archive",
        body = "The Archive tab inside Notifications keeps a 30-day history. If you mislabelled something more than 24 hours ago, you can find and fix it here.",
    ),
    OnboardingPage(
        iconRes = R.drawable.ic_nav_rules,
        headline = "Zero-Delay Rules",
        body = "Define keywords like OTP or Verification in the Rules tab. ZiG always passes these through instantly — before the AI even runs — so you never miss a time-sensitive code.",
    ),
)

@Composable
fun OnboardingScreen(
    onMarkCompleted: () -> Unit,
    onFinished: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    // Write the completed flag immediately on entry so a force-quit doesn't re-show the tour.
    LaunchedEffect(Unit) { onMarkCompleted() }

    // Back navigates to the previous slide; on the first slide the system default applies
    // (which closes the Activity — acceptable since onMarkCompleted already ran).
    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Skip button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            TextButton(
                onClick = onFinished,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp),
            ) {
                Text("Skip")
            }
        }

        // Slides
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            val slide = pages[page]
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painter = painterResource(slide.iconRes),
                    contentDescription = null,
                    tint = ZigGreen,
                    modifier = Modifier.size(80.dp),
                )
                Spacer(Modifier.height(32.dp))
                Text(
                    text = slide.headline,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = slide.body,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Dot indicators + navigation row
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DotIndicators(
                pageCount = pages.size,
                currentPage = pagerState.currentPage,
            )
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pagerState.currentPage > 0) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            }
                        },
                    ) {
                        Text("Back")
                    }
                } else {
                    Spacer(Modifier.width(72.dp))
                }

                if (pagerState.currentPage == pages.size - 1) {
                    Button(
                        onClick = onFinished,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ZigGreen,
                            contentColor = ZigOnGreen,
                        ),
                    ) {
                        Text("Get Started")
                    }
                } else {
                    Button(
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ZigGreen,
                            contentColor = ZigOnGreen,
                        ),
                    ) {
                        Text("Next")
                    }
                }
            }
        }
    }
}

@Composable
private fun DotIndicators(pageCount: Int, currentPage: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            Box(
                modifier = Modifier
                    .size(if (index == currentPage) 10.dp else 8.dp)
                    .background(
                        color = if (index == currentPage) {
                            ZigGreen
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape,
                    ),
            )
        }
    }
}
*/
