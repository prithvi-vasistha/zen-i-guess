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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.zig.notificationfilter.ui.theme.ZigGreen
import dev.zig.notificationfilter.ui.theme.ZigOnGreen

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

    // Move to the step's tab. The target composes on that page and reports its bounds,
    // which arrive asynchronously — the overlay recomposes and the cut-out settles.
    LaunchedEffect(controller.currentIndex) {
        if (pagerState.currentPage != step.tab) {
            pagerState.animateScrollToPage(step.tab)
        }
    }

    val targetRect: Rect? = step.targetKey?.let { controller.registry.bounds[it] }

    // Fade the spotlight in as it moves between targets.
    val reveal by animateFloatAsState(
        targetValue = if (step.targetKey == null || targetRect != null) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "spotlight_reveal",
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val padPx = with(LocalDensity.current) { 8.dp.toPx() }
        val cutout = targetRect?.inflate(padPx)

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
            if (cutout == null) {
                drawRect(color = SCRIM_COLOR)
            } else {
                // Four rectangles around the cut-out — avoids needing a compositing layer.
                drawRect(SCRIM_COLOR, topLeft = Offset.Zero, size = Size(size.width, cutout.top))
                drawRect(
                    SCRIM_COLOR,
                    topLeft = Offset(0f, cutout.bottom),
                    size = Size(size.width, size.height - cutout.bottom),
                )
                drawRect(
                    SCRIM_COLOR,
                    topLeft = Offset(0f, cutout.top),
                    size = Size(cutout.left, cutout.height),
                )
                drawRect(
                    SCRIM_COLOR,
                    topLeft = Offset(cutout.right, cutout.top),
                    size = Size(size.width - cutout.right, cutout.height),
                )
                drawRoundRect(
                    color = ZigGreen.copy(alpha = reveal),
                    topLeft = cutout.topLeft,
                    size = cutout.size,
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        // Anchor the tooltip clear of the spotlight: below a top-half target, above a
        // bottom-half one, centered when there's no target.
        val alignment = when {
            cutout == null -> Alignment.Center
            cutout.center.y < constraints.maxHeight / 2f -> Alignment.BottomCenter
            else -> Alignment.TopCenter
        }
        val tooltipPadding = when (alignment) {
            Alignment.BottomCenter -> Modifier.padding(bottom = 40.dp, start = 20.dp, end = 20.dp)
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
