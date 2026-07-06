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
