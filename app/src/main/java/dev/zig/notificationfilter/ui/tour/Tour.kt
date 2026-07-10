package dev.zig.notificationfilter.ui.tour

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// ── Model ──────────────────────────────────────────────────────────────────────

/** One coach-mark. [targetKey] names the element to highlight; null → a centered caption. */
data class TourStep(
    val targetKey: String?,
    val text: String,
)

/**
 * Per-screen tour progress, owned by the screen's ViewModel so the current step and active flag
 * survive leaving and returning to the screen (pager tab switches). [onFinished] persists the
 * "seen" flag exactly once, when the tour is finished or skipped.
 */
class ScreenTourState(
    startActive: Boolean,
    private val onFinished: () -> Unit,
) {
    private val _active = MutableStateFlow(startActive)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _step = MutableStateFlow(0)
    val step: StateFlow<Int> = _step.asStateFlow()

    /** Advance to the next step, or finish if this was the last one. */
    fun advance(isLast: Boolean) {
        if (isLast) finish() else _step.value += 1
    }

    fun finish() {
        if (_active.value) {
            _active.value = false
            onFinished()
        }
    }
}

/**
 * Holds the on-screen bounds of each tour target, reported by [tourTarget] via layout. Only the
 * anchor positions live here (ephemeral, re-reported on every layout). The current step index and
 * whether the tour is active live in the screen's ViewModel, so progress survives leaving and
 * returning to the screen (e.g. switching tabs mid-tour).
 */
@Stable
class TourController {
    private val bounds: SnapshotStateMap<String, Rect> = mutableStateMapOf()
    fun report(key: String, rect: Rect) { bounds[key] = rect }
    fun boundsOf(key: String): Rect? = bounds[key]
}

@Composable
fun rememberTourController(): TourController = remember { TourController() }

/** Registers this element as a tour target under [key]. No-op when [controller] is null. */
fun Modifier.tourTarget(controller: TourController?, key: String): Modifier =
    if (controller == null) this
    else this.onGloballyPositioned { controller.report(key, it.boundsInRoot()) }

// ── Overlay ────────────────────────────────────────────────────────────────────

private const val SCRIM_ALPHA = 0.62f

/**
 * The spotlight overlay: dims everything except the current step's target (highlighted by leaving
 * a padded rounded rectangle un-dimmed), and shows a compact caption with Next / Skip. All pointer
 * events over the scrim are swallowed so the underlying screen is inert; the bottom navigation is
 * outside this overlay, so the user can still switch tabs (which pauses the tour) and return.
 */
@Composable
fun TourOverlay(
    steps: List<TourStep>,
    currentStep: Int,
    controller: TourController,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (currentStep !in steps.indices) return
    val step = steps[currentStep]
    val isLast = currentStep == steps.lastIndex

    BackHandler(onBack = onSkip)

    // The overlay fills the same space as the screen. Anchor bounds come in root coordinates, so
    // subtract the overlay's own root origin to draw them in overlay-local space.
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }

    // Fade the whole overlay in so it never appears as an abrupt black flash.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(durationMillis = 220)) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .onGloballyPositioned { overlayOrigin = it.boundsInRoot().topLeft },
    ) {
        val heightPx = constraints.maxHeight.toFloat()
        val rawTarget = step.targetKey?.let { key ->
            controller.boundsOf(key)?.translate(-overlayOrigin.x, -overlayOrigin.y)
        }

        // Glide the spotlight between steps. Every anchor is on-screen from the start, so its
        // bounds are already reported when the step changes: the first target snaps in, later ones
        // animate. Held in an Animatable so a one-frame null can never collapse the hole.
        val spotlight = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
        var initialised by remember { mutableStateOf(false) }
        LaunchedEffect(rawTarget) {
            val tgt = rawTarget ?: return@LaunchedEffect
            if (!initialised) {
                spotlight.snapTo(tgt)
                initialised = true
            } else {
                spotlight.animateTo(tgt, tween(durationMillis = 300, easing = FastOutSlowInEasing))
            }
        }

        // Scrim with a single rounded hole (even-odd path) so the highlight is a clean pill, plus a
        // matching outline. Every gesture is consumed so the screen underneath can't scroll or be
        // tapped mid-tour — but a clean tap ON the highlighted element advances the tour, so a user
        // who taps the thing the caption points at (e.g. the glowing "Allow") is never met with a
        // dead tap.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(currentStep) {
                    val pad = 6.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { change ->
                                if (change.positionChange().getDistance() > viewConfiguration.touchSlop) {
                                    moved = true
                                }
                                change.consume()
                            }
                            if (event.changes.all { !it.pressed }) break
                        }
                        val rect = spotlight.value
                        val onHighlight = initialised && Rect(
                            rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad,
                        ).contains(down.position)
                        if (!moved && onHighlight) onNext()
                    }
                },
        ) {
            val scrim = Color.Black.copy(alpha = SCRIM_ALPHA)
            if (!initialised) {
                drawRect(scrim)
                return@Canvas
            }
            val pad = 6.dp.toPx()
            val radius = CornerRadius(16.dp.toPx(), 16.dp.toPx())
            val rect = spotlight.value
            val l = (rect.left - pad).coerceIn(0f, size.width)
            val t = (rect.top - pad).coerceIn(0f, size.height)
            val r = (rect.right + pad).coerceIn(0f, size.width)
            val b = (rect.bottom + pad).coerceIn(0f, size.height)
            val hole = Rect(l, t, r, b)
            val path = Path().apply {
                addRect(Rect(0f, 0f, size.width, size.height))
                addRoundRect(RoundRect(hole, radius))
                fillType = PathFillType.EvenOdd
            }
            drawPath(path, scrim)
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(l, t),
                size = Size(r - l, b - t),
                cornerRadius = radius,
                style = Stroke(width = 2.dp.toPx()),
            )
        }

        // Anchor the caption to the highlight: just below it when there's room, otherwise above.
        // Driven by the animated rect so the caption glides along with the spotlight between steps.
        val density = LocalDensity.current
        var captionHeightPx by remember { mutableStateOf(0) }
        val marginPx = with(density) { 16.dp.toPx() }
        val gapPx = with(density) { 14.dp.toPx() }
        val rect = spotlight.value
        val belowY = rect.bottom + gapPx
        val captionY = when {
            !initialised -> heightPx - captionHeightPx - marginPx          // no target yet: rest low
            belowY + captionHeightPx + marginPx <= heightPx -> belowY      // room below the target
            else -> (rect.top - gapPx - captionHeightPx).coerceAtLeast(marginPx)  // otherwise above
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset(0, captionY.roundToInt()) }
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .onGloballyPositioned { captionHeightPx = it.size.height },
        ) {
            TourCaption(
                text = step.text,
                stepIndex = currentStep,
                stepCount = steps.size,
                isLast = isLast,
                onNext = onNext,
                onSkip = onSkip,
            )
        }
    }
}

@Composable
private fun TourCaption(
    text: String,
    stepIndex: Int,
    stepCount: Int,
    isLast: Boolean,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            // Crossfade the copy so it changes in step with the gliding spotlight, and animate the
            // card's height between steps of different lengths rather than snapping.
            AnimatedContent(
                targetState = text,
                transitionSpec = {
                    fadeIn(tween(durationMillis = 200)) togetherWith fadeOut(tween(durationMillis = 150))
                },
                label = "tour_caption",
            ) { copy ->
                Text(
                    text = copy,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.size(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StepDots(stepIndex = stepIndex, stepCount = stepCount)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isLast) {
                        TextButton(onClick = onSkip) {
                            Text("Skip", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.size(4.dp))
                    }
                    Button(onClick = onNext) {
                        Text(if (isLast) "Got it" else "Next")
                    }
                }
            }
        }
    }
}

@Composable
private fun StepDots(stepIndex: Int, stepCount: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(stepCount) { i ->
            val active = i == stepIndex
            Box(
                modifier = Modifier
                    .size(if (active) 8.dp else 6.dp)
                    .background(
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        },
                        shape = CircleShape,
                    ),
            )
        }
    }
}
