package dev.zig.notificationfilter.ui.tour

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.zig.notificationfilter.ui.theme.ZigGreen
import dev.zig.notificationfilter.ui.theme.ZigOnGreen
import kotlin.math.max

private val SCRIM_COLOR = Color.Black.copy(alpha = 0.78f)

/**
 * Full-screen tour layer drawn above the real app. For each step it drives the pager
 * to the step's tab, dims everything except the spotlighted target, and shows a tooltip.
 *
 * The scrim consumes all touches so the underlying UI can't be interacted with mid-tour;
 * only the tooltip's own buttons advance it.
 */
@Composable
fun TourOverlay(
    controller: TourController,
    pagerState: PagerState,
    modifier: Modifier = Modifier,
) {
    val step = controller.currentStep

    // Controls whether the current step's target spotlight (cut-out + border + tooltip) is
    // shown. The bottom nav bar stays visible regardless. When a step changes tabs we first
    // remove the old page's highlight, animate to the target page, and only then reveal the
    // new highlight — so the spotlight never appears at a stale location mid-transition.
    var spotlightVisible by remember { mutableStateOf(false) }
    LaunchedEffect(controller.currentIndex) {
        if (pagerState.currentPage != step.tab) {
            spotlightVisible = false                  // remove highlighting on the current page
            pagerState.animateScrollToPage(step.tab)  // animate the navigation
        }
        spotlightVisible = true                       // call the highlighters for this page
    }

    val targetRect: Rect? = step.targetKey?.let { controller.registry.bounds[it] }
    val navRect: Rect? = controller.registry.bounds[TourKeys.NAV_BAR]

    // Fade the spotlight in once its page is settled and the target has reported bounds.
    val reveal by animateFloatAsState(
        targetValue = if (spotlightVisible && (step.targetKey == null || targetRect != null)) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "spotlight_reveal",
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val padPx = with(LocalDensity.current) { 4.dp.toPx() }
        val marginPx = with(LocalDensity.current) { 6.dp.toPx() }
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()

        // Center-preserving clamp: if the padded target overflows the screen, shrink it
        // symmetrically around its center rather than cropping one edge — so the highlight
        // stays centered on the target (e.g. the settings gear, the right-edge switch) while
        // its border stays fully on-screen.
        val cutout = if (spotlightVisible) targetRect?.inflate(padPx)?.let { r ->
            val shrinkX = max(
                (marginPx - r.left).coerceAtLeast(0f),
                (r.right - (maxW - marginPx)).coerceAtLeast(0f),
            )
            val shrinkY = max(
                (marginPx - r.top).coerceAtLeast(0f),
                (r.bottom - (maxH - marginPx)).coerceAtLeast(0f),
            )
            Rect(r.left + shrinkX, r.top + shrinkY, r.right - shrinkX, r.bottom - shrinkY)
        } else null

        // Scrim + spotlight. This layer also blocks every touch on the app beneath.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                },
        ) {
            val cornerPx = 12.dp.toPx()
            // Scrim = full screen minus the always-visible nav bar and the current spotlight.
            // Path.op(Difference) cuts each hole without a compositing layer (no BlendMode.Clear).
            val holes = listOfNotNull(
                navRect?.let { it to 0f },
                cutout?.let { it to cornerPx },
            )
            if (holes.isEmpty()) {
                drawRect(color = SCRIM_COLOR)
            } else {
                var scrim = Path().apply { addRect(Rect(0f, 0f, size.width, size.height)) }
                holes.forEach { (rect, radius) ->
                    val hole = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(radius))) }
                    scrim = Path().apply { op(scrim, hole, PathOperation.Difference) }
                }
                drawPath(scrim, SCRIM_COLOR)
            }

            // Green highlight border around the step's target only — never the nav bar.
            cutout?.let {
                drawRoundRect(
                    color = ZigGreen.copy(alpha = reveal),
                    topLeft = it.topLeft,
                    size = it.size,
                    cornerRadius = CornerRadius(cornerPx),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        // Show the tooltip only while this page's spotlight is active — hidden during the
        // navigation animation so it doesn't flash at the wrong position.
        if (spotlightVisible) {
            // Anchor the tooltip clear of the spotlight: below a top-half target, above a
            // bottom-half one, centered when there's no target.
            val alignment = when {
                cutout == null -> Alignment.Center
                cutout.center.y < maxH / 2f -> Alignment.BottomCenter
                else -> Alignment.TopCenter
            }
            // Bottom-anchored tooltips must clear the always-visible nav bar so they never
            // cover the bottom menus. Use the nav bar's real top edge; fall back to a safe
            // inset before its bounds arrive.
            val density = LocalDensity.current
            val bottomInset = navRect?.let { with(density) { (maxH - it.top).toDp() } + 12.dp } ?: 96.dp
            val tooltipPadding = when (alignment) {
                Alignment.BottomCenter -> Modifier.padding(bottom = bottomInset, start = 20.dp, end = 20.dp)
                Alignment.TopCenter -> Modifier.padding(top = 56.dp, start = 20.dp, end = 20.dp)
                else -> Modifier.padding(horizontal = 24.dp)
            }

            TourTooltip(
                controller = controller,
                step = step,
                modifier = Modifier
                    .align(alignment)
                    .then(tooltipPadding),
            )
        }
    }
}

@Composable
private fun TourTooltip(
    controller: TourController,
    step: TourStep,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.widthIn(max = 420.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "${controller.currentIndex + 1} / ${controller.steps.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(top = 6.dp))
            Text(
                text = step.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.padding(top = 6.dp))
            Text(
                text = step.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(top = 16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = controller::skip) {
                    Text("Skip")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!controller.isFirst) {
                        TextButton(onClick = controller::back) {
                            Text("Back")
                        }
                        Spacer(Modifier.padding(start = 4.dp))
                    }
                    Button(
                        onClick = controller::next,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ZigGreen,
                            contentColor = ZigOnGreen,
                        ),
                    ) {
                        Text(if (controller.isLast) "Get Started" else "Next")
                    }
                }
            }
        }
    }
}
