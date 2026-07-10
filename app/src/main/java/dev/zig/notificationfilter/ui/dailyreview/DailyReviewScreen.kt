package dev.zig.notificationfilter.ui.dailyreview

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.zig.notificationfilter.ui.theme.ZigGreen
import kotlinx.coroutines.launch

// ── Tunables ──────────────────────────────────────────────────────────────────
private val CARD_SHAPE = RoundedCornerShape(28.dp)
// Fraction of screen width past which a release commits the swipe (vs snapping back).
private const val SWIPE_THRESHOLD_FRACTION = 0.28f
// Max tilt (degrees) when the card is dragged a full screen-width away.
private const val MAX_ROTATION_DEG = 14f
// Only the top few cards are ever composed, so the deck is O(1) regardless of queue size.
private const val VISIBLE_CARDS = 3

// ── Entry point ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyReviewScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: DailyReviewViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val packageLabels by viewModel.packageLabels.collectAsState()
    val appIcons by viewModel.appIcons.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Daily Review", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            when (val state = uiState) {
                DailyReviewUiState.Loading -> { /* intentionally blank — avoids flicker */ }
                DailyReviewUiState.AllCaughtUp -> AllCaughtUp(onDone = onDone)
                is DailyReviewUiState.Deck -> CardDeck(
                    cards = state.cards,
                    packageLabels = packageLabels,
                    appIcons = appIcons,
                    onDecision = viewModel::onDecision,
                )
            }
        }
    }
}

// ── Deck ──────────────────────────────────────────────────────────────────────

@Composable
private fun CardDeck(
    cards: List<DailyReviewCard>,
    packageLabels: Map<String, String>,
    appIcons: Map<String, ImageBitmap>,
    onDecision: (DailyReviewCard, Boolean) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val visible = cards.take(VISIBLE_CARDS)

        // Draw back-to-front so the top card (depth 0) is composed last and sits on top.
        visible.reversed().forEachIndexed { reversedIndex, card ->
            val depth = visible.lastIndex - reversedIndex
            val label = packageLabels[card.packageName] ?: card.packageName.substringAfterLast('.')
            val icon = appIcons[card.packageName]

            if (depth == 0) {
                // key(): a fresh Animatable + gesture state per top card, discarded cleanly
                // when the card leaves the deck after a swipe.
                key(card.key) {
                    TopSwipeableCard(
                        card = card,
                        label = label,
                        icon = icon,
                        widthPx = widthPx,
                        onDecision = { allow -> onDecision(card, allow) },
                    )
                }
            } else {
                BackCard(depth = depth) {
                    CardContent(card = card, label = label, icon = icon)
                }
            }
        }
    }
}

// Static depth cards behind the top card: progressively scaled down and pushed up for a
// stacked-deck look. Non-interactive.
@Composable
private fun BackCard(
    depth: Int,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val scale = 1f - depth * 0.05f
                scaleX = scale
                scaleY = scale
                translationY = -depth * 18.dp.toPx()
            },
        shape = CARD_SHAPE,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
    ) { content() }
}

// The interactive top card. Owns the drag offset; binds it to rotation, colour overlays and
// stamps via graphicsLayer blocks so the transforms are read in the draw phase (no
// recomposition per frame — smooth at 60fps).
@Composable
private fun TopSwipeableCard(
    card: DailyReviewCard,
    label: String,
    icon: ImageBitmap?,
    widthPx: Float,
    onDecision: (Boolean) -> Unit,
) {
    val threshold = widthPx * SWIPE_THRESHOLD_FRACTION
    val flyOff = widthPx * 1.5f
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = offsetX.value
                rotationZ = (offsetX.value / widthPx) * MAX_ROTATION_DEG
            }
            .pointerInput(card.key) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                    },
                    onDragEnd = {
                        when {
                            offsetX.value > threshold -> scope.launch {
                                offsetX.animateTo(flyOff, tween(durationMillis = 220))
                                onDecision(true)
                            }
                            offsetX.value < -threshold -> scope.launch {
                                offsetX.animateTo(-flyOff, tween(durationMillis = 220))
                                onDecision(false)
                            }
                            else -> scope.launch {
                                offsetX.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = 0.65f,
                                        stiffness = Spring.StiffnessMedium,
                                    ),
                                )
                            }
                        }
                    },
                )
            },
        shape = CARD_SHAPE,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            CardContent(card = card, label = label, icon = icon)

            // Right drag → Allow (green). Left drag → Block (red). Progress is read inside the
            // graphicsLayer blocks, so dragging only re-draws — it never recomposes the card.
            SwipeOverlay(
                color = ZigGreen,
                stamp = "ALLOW",
                stampAlignment = Alignment.TopStart,
                stampRotation = -18f,
                progress = { (offsetX.value / threshold).coerceIn(0f, 1f) },
            )
            SwipeOverlay(
                color = MaterialTheme.colorScheme.error,
                stamp = "BLOCK",
                stampAlignment = Alignment.TopEnd,
                stampRotation = 18f,
                progress = { (-offsetX.value / threshold).coerceIn(0f, 1f) },
            )
        }
    }
}

// A colour scrim + rubber-stamp label whose opacity ramps with drag distance. [progress] is a
// lambda (not a value) so it is evaluated lazily in the draw phase.
@Composable
private fun BoxScope.SwipeOverlay(
    color: Color,
    stamp: String,
    stampAlignment: Alignment,
    stampRotation: Float,
    progress: () -> Float,
) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .graphicsLayer { alpha = progress() * 0.45f }
            .background(color = color, shape = CARD_SHAPE),
    )
    Text(
        text = stamp,
        color = color,
        fontWeight = FontWeight.Black,
        fontSize = 34.sp,
        modifier = Modifier
            .align(stampAlignment)
            .padding(28.dp)
            .graphicsLayer {
                alpha = progress()
                rotationZ = stampRotation
            }
            .border(width = 4.dp, color = color, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

// ── Card content (shared by top + back cards) ─────────────────────────────────

@Composable
private fun CardContent(
    card: DailyReviewCard,
    label: String,
    icon: ImageBitmap?,
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ZigGreen.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = ZigGreen,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append("ZiG ")
                        append(if (card.aiAllowed) "allowed this" else "silenced this")
                        if (card.count > 1) append(" · ${card.count} identical")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = card.title.ifBlank { card.packageName.substringAfterLast('.') },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        if (card.content.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = card.content,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 12,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "← Block & Mute",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = "Allow →",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = ZigGreen,
            )
        }
    }
}

// ── Inbox Zero success state ──────────────────────────────────────────────────

@Composable
private fun AllCaughtUp(
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(ZigGreen.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = ZigGreen,
                modifier = Modifier.size(72.dp),
            )
        }
        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = "All Caught Up!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "ZiG's AI is smarter thanks to you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onDone) {
            Text("Back to Inbox")
        }
    }
}
