package dev.zig.notificationfilter.ui.tour

import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.alpha
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
import kotlinx.coroutines.delay
import kotlin.math.max

private val SCRIM_COLOR = Color.Black.copy(alpha = 0.78f)

// Step-transition timing — snappy, in line with common product tours; quick enough to feel
// responsive without an instant snap.
private const val EXIT_FADE_MS = 120      // fade the current spotlight out before moving
private const val SETTLE_MS = 180         // brief pause on the freshly-loaded page before revealing
private const val SAME_PAGE_GAP_MS = 60   // beat between two targets on the same page
private const val REVEAL_MS = 220         // eased fade of the spotlight and tooltip

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

    val targetRect: Rect? = step.targetKey?.let { controller.registry.bounds[it] }
    val navRect: Rect? = controller.registry.bounds[TourKeys.NAV_BAR]

    // The step index whose spotlight and tooltip are allowed to show. Kept as an index rather
    // than a boolean so that the instant controller.currentIndex advances, `armed` derives to
    // false in the SAME recomposition — otherwise the incoming step (already reflected in
    // `step`) would flash its card at the old position for a frame before the effect below
    // runs. Nothing shows again until this effect explicitly re-arms the new index.
    var armedIndex by remember { mutableIntStateOf(-1) }
    val armed = armedIndex == controller.currentIndex

    // A fluid step change: the index bump has already hidden the outgoing card, so wait for its
    // fade-out, animate to the step's page, give the user a beat to take it in, then arm this
    // step to fade the new spotlight in. Only timing and opacity change — never position.
    LaunchedEffect(controller.currentIndex) {
        delay(EXIT_FADE_MS.toLong())
        if (pagerState.currentPage != step.tab) {
            pagerState.animateScrollToPage(step.tab)      // smooth page navigation
            delay(SETTLE_MS.toLong())                     // let the user take the new page in
        } else {
            delay(SAME_PAGE_GAP_MS.toLong())              // brief beat between same-page targets
        }
        armedIndex = controller.currentIndex             // fade the new spotlight in
    }

    // Eased fade for the spotlight and tooltip; reaches full only once the step is armed and
    // its target has reported bounds.
    val reveal by animateFloatAsState(
        targetValue = if (armed && (step.targetKey == null || targetRect != null)) 1f else 0f,
        animationSpec = tween(durationMillis = REVEAL_MS, easing = FastOutSlowInEasing),
        label = "spotlight_reveal",
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val padPx = with(LocalDensity.current) { 2.dp.toPx() }
        val marginPx = with(LocalDensity.current) { 6.dp.toPx() }
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()

        // Center-preserving clamp (unchanged): if the padded target overflows the screen,
        // shrink it symmetrically around its center rather than cropping one edge — so the
        // highlight stays centered on the target (settings gear, right-edge switch) while its
        // border stays fully on-screen.
        val targetCutout: Rect? = targetRect?.inflate(padPx)?.let { r ->
            val shrinkX = max(
                (marginPx - r.left).coerceAtLeast(0f),
                (r.right - (maxW - marginPx)).coerceAtLeast(0f),
            )
            val shrinkY = max(
                (marginPx - r.top).coerceAtLeast(0f),
                (r.bottom - (maxH - marginPx)).coerceAtLeast(0f),
            )
            Rect(r.left + shrinkX, r.top + shrinkY, r.right - shrinkX, r.bottom - shrinkY)
        }

        // The cut-out actually drawn. Held to the outgoing target while the spotlight fades out
        // (armed == false) so the exit animates from the old position, then swapped to the new
        // target once the step is armed. Identical geometry to targetCutout — never repositioned.
        var shownCutout by remember { mutableStateOf<Rect?>(null) }
        LaunchedEffect(armed, targetCutout) {
            if (armed) shownCutout = targetCutout
        }
        // What actually draws this frame: the shown cut-out, only while the fade is visible.
        val spotlight = shownCutout?.takeIf { reveal > 0.01f }

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
                spotlight?.let { it to cornerPx },
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
            spotlight?.let {
                drawRoundRect(
                    color = ZigGreen.copy(alpha = reveal),
                    topLeft = it.topLeft,
                    size = it.size,
                    cornerRadius = CornerRadius(cornerPx),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        // Show the tooltip only while this step is armed — hidden during the navigation
        // animation so it doesn't flash at the wrong position — and fade it in with the
        // spotlight. Positioning is unchanged.
        if (armed) {
            val density = LocalDensity.current
            val sc = shownCutout

            // The band the tooltip is centred within. It clears the status bar at the top and
            // the always-visible nav bar at the bottom, then picks the larger open gap — above
            // or below the target — so the card sits in free space instead of jammed against a
            // screen edge. No target → centre in the whole band.
            val topInsetPx = with(density) { 40.dp.toPx() }
            val bottomLimitPx = (navRect?.top ?: (maxH - with(density) { 84.dp.toPx() })) -
                with(density) { 12.dp.toPx() }

            var regionTopPx = topInsetPx
            var regionBottomPx = bottomLimitPx
            if (sc != null) {
                if ((bottomLimitPx - sc.bottom) >= (sc.top - topInsetPx)) {
                    regionTopPx = sc.bottom.coerceIn(topInsetPx, bottomLimitPx)
                } else {
                    regionBottomPx = sc.top.coerceIn(topInsetPx, bottomLimitPx)
                }
            }
            // If the chosen gap is too short to hold the card, fall back to the whole band.
            if (regionBottomPx - regionTopPx < with(density) { 220.dp.toPx() }) {
                regionTopPx = topInsetPx
                regionBottomPx = bottomLimitPx
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = with(density) { regionTopPx.toDp() },
                        bottom = with(density) { (maxH - regionBottomPx).toDp() },
                        start = 20.dp,
                        end = 20.dp,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                TourTooltip(
                    controller = controller,
                    step = step,
                    modifier = Modifier.alpha(reveal),
                )
            }
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
