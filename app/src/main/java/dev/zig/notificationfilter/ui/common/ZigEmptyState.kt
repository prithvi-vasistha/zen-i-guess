package dev.zig.notificationfilter.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun ZigEmptyState(
    title: String,
    subtitle: String = "Nothing to see here.",
    doodle: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        doodle()
        Spacer(Modifier.height(32.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Doodles ──────────────────────────────────────────────────────────────────

@Composable
fun BellDoodle(modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f)
    Canvas(modifier = modifier.size(110.dp)) {
        val sw = 3.dp.toPx()
        val stroke = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = size.width
        val h = size.height

        // Bell dome — two cubic beziers meeting at the top centre
        val bell = Path().apply {
            moveTo(w * .50f, h * .10f)
            cubicTo(w * .20f, h * .10f, w * .11f, h * .37f, w * .13f, h * .63f)
            lineTo(w * .87f, h * .63f)
            cubicTo(w * .89f, h * .37f, w * .80f, h * .10f, w * .50f, h * .10f)
        }
        drawPath(bell, ink, style = stroke)

        // Base bar
        drawLine(ink, Offset(w * .05f, h * .68f), Offset(w * .95f, h * .68f), sw, StrokeCap.Round)

        // Clapper
        drawCircle(ink, sw * 1.5f, Offset(w * .50f, h * .79f))

        // Hook at top
        drawLine(ink, Offset(w * .50f, h * .01f), Offset(w * .50f, h * .10f), sw, StrokeCap.Round)

        // Three ascending dots upper-right — "drifting away / nothing there"
        val dots = listOf(
            Offset(w * .71f, h * .21f) to sw * 1.3f,
            Offset(w * .80f, h * .13f) to sw * 1.0f,
            Offset(w * .88f, h * .06f) to sw * 0.7f,
        )
        dots.forEachIndexed { i, (center, r) ->
            drawCircle(ink.copy(alpha = 0.38f - i * 0.09f), r, center)
        }
    }
}

@Composable
fun BookDoodle(modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f)
    Canvas(modifier = modifier.size(110.dp)) {
        val sw = 3.dp.toPx()
        val stroke = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = size.width
        val h = size.height

        // Left page (angled inward at top — open book perspective)
        val leftPage = Path().apply {
            moveTo(w * .50f, h * .22f)
            lineTo(w * .10f, h * .30f)
            lineTo(w * .10f, h * .78f)
            lineTo(w * .50f, h * .70f)
            close()
        }
        drawPath(leftPage, ink, style = stroke)

        // Right page (mirror)
        val rightPage = Path().apply {
            moveTo(w * .50f, h * .22f)
            lineTo(w * .90f, h * .30f)
            lineTo(w * .90f, h * .78f)
            lineTo(w * .50f, h * .70f)
            close()
        }
        drawPath(rightPage, ink, style = stroke)

        // Spine
        drawLine(ink, Offset(w * .50f, h * .22f), Offset(w * .50f, h * .70f), sw, StrokeCap.Round)

        // Left page: 3 blank rule lines (slightly angled to match perspective)
        for (i in 0..2) {
            val t = 0.40f + i * 0.11f
            drawLine(
                ink.copy(alpha = 0.30f),
                Offset(w * .16f, h * (t + .025f)),
                Offset(w * .44f, h * t),
                sw * 0.65f,
                StrokeCap.Round,
            )
        }

        // Right page: 3 blank lines
        for (i in 0..2) {
            val t = 0.40f + i * 0.11f
            drawLine(
                ink.copy(alpha = 0.30f),
                Offset(w * .56f, h * t),
                Offset(w * .84f, h * (t + .025f)),
                sw * 0.65f,
                StrokeCap.Round,
            )
        }
    }
}

@Composable
fun ClipboardDoodle(modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f)
    Canvas(modifier = modifier.size(110.dp)) {
        val sw = 3.dp.toPx()
        val stroke = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val w = size.width
        val h = size.height

        // Clipboard body
        drawRoundRect(
            ink, Offset(w * .14f, h * .18f),
            Size(w * .72f, h * .74f),
            CornerRadius(5.dp.toPx()),
            stroke,
        )

        // Clip at top
        drawRoundRect(
            ink, Offset(w * .35f, h * .09f),
            Size(w * .30f, h * .14f),
            CornerRadius(3.dp.toPx()),
            stroke,
        )

        // 3 empty log-row slots: bullet dot + short line
        for (i in 0..2) {
            val y = h * (.38f + i * .16f)
            drawCircle(ink.copy(alpha = 0.35f), sw * 0.8f, Offset(w * .27f, y))
            drawLine(
                ink.copy(alpha = 0.28f),
                Offset(w * .36f, y),
                Offset(w * .58f - (if (i == 1) w * .06f else 0f), y),
                sw * 0.65f,
                StrokeCap.Round,
            )
        }
    }
}
