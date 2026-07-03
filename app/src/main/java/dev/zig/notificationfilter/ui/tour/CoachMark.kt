package dev.zig.notificationfilter.ui.tour

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Shared, tour-scoped registry mapping a coach-mark key to the on-screen bounds
 * (root coordinates) of the composable that carries [Modifier.coachMark].
 *
 * The overlay reads these bounds to cut a spotlight around the target. Bounds are
 * stored in a snapshot-backed map so the overlay recomposes as targets settle after
 * a tab switch.
 */
class CoachMarkRegistry {
    val bounds = mutableStateMapOf<String, Rect>()
}

/**
 * Non-null only while a tour is active. When null, [Modifier.coachMark] is a no-op,
 * so instrumented screens pay zero layout cost during normal use.
 */
val LocalCoachMarkRegistry = staticCompositionLocalOf<CoachMarkRegistry?> { null }

/**
 * Report this composable's bounds under [key] so the active tour can spotlight it.
 * A no-op when no tour is running (registry absent from the composition).
 */
@Composable
fun Modifier.coachMark(key: String): Modifier {
    val registry = LocalCoachMarkRegistry.current ?: return this
    return this.onGloballyPositioned { registry.bounds[key] = it.boundsInRoot() }
}
