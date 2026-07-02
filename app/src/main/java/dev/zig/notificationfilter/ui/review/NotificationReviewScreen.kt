package dev.zig.notificationfilter.ui.review

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.zig.notificationfilter.data.local.db.ReviewState
import dev.zig.notificationfilter.ui.common.BellDoodle
import dev.zig.notificationfilter.ui.common.ZigEmptyState
import dev.zig.notificationfilter.ui.theme.ZigGreen
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Timestamp formatting ────────────────────────────────────────────────────

private val REVIEW_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM dd · HH:mm")

private fun formatReviewTimestamp(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(REVIEW_TIME_FORMATTER)

// ── Entry point ─────────────────────────────────────────────────────────────

@Composable
fun NotificationReviewRoute(modifier: Modifier = Modifier) {
    val viewModel: NotificationReviewViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val archiveUiState by viewModel.archiveUiState.collectAsState()

    var showArchive by remember { mutableStateOf(false) }

    NotificationReviewScreen(
        uiState = uiState,
        archiveUiState = archiveUiState,
        showArchive = showArchive,
        onToggleArchive = { showArchive = !showArchive },
        onAllowClicked = viewModel::onAllowClicked,
        onBlockClicked = viewModel::onBlockClicked,
        onUndoClicked = viewModel::onUndoClicked,
        modifier = modifier,
    )
}

// ── Screen ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationReviewScreen(
    uiState: ReviewUiState,
    archiveUiState: ReviewUiState,
    showArchive: Boolean,
    onToggleArchive: () -> Unit,
    onAllowClicked: (Long) -> Unit,
    onBlockClicked: (Long) -> Unit,
    onUndoClicked: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = if (showArchive) "Archive" else "Notifications",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                actions = {
                    TextButton(onClick = onToggleArchive) {
                        Text(if (showArchive) "Active" else "Archive")
                    }
                },
            )
        },
    ) { innerPadding ->
        val activeState = if (showArchive) archiveUiState else uiState

        when (activeState) {
            is ReviewUiState.Loading -> {
                // Room has not yet emitted — intentionally blank to avoid flicker.
            }
            is ReviewUiState.Empty -> {
                ZigEmptyState(
                    title = if (showArchive) "Archive is empty" else "You're all caught up",
                    doodle = { BellDoodle() },
                    modifier = Modifier.padding(innerPadding),
                )
            }
            is ReviewUiState.Content -> {
                ReviewListContent(
                    groupedItems = activeState.groups,
                    onAllowClicked = onAllowClicked,
                    onBlockClicked = onBlockClicked,
                    onUndoClicked = onUndoClicked,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }
}

// ── List ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReviewListContent(
    groupedItems: Map<String, List<NotificationReviewUiItem>>,
    onAllowClicked: (Long) -> Unit,
    onBlockClicked: (Long) -> Unit,
    onUndoClicked: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        groupedItems.forEach { (packageName, items) ->
            stickyHeader(key = "header_$packageName") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = packageName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = ZigGreen,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            items(items = items, key = { it.id }) { item ->
                ReviewItemCard(
                    item = item,
                    onAllowClicked = onAllowClicked,
                    onBlockClicked = onBlockClicked,
                    onUndoClicked = onUndoClicked,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

// ── Card ────────────────────────────────────────────────────────────────────

@Composable
private fun ReviewItemCard(
    item: NotificationReviewUiItem,
    onAllowClicked: (Long) -> Unit,
    onBlockClicked: (Long) -> Unit,
    onUndoClicked: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor by animateColorAsState(
        targetValue = when (item.reviewState) {
            ReviewState.ALLOWED -> ZigGreen.copy(alpha = 0.12f)
            ReviewState.BLOCKED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.20f)
            ReviewState.PENDING -> MaterialTheme.colorScheme.surface
        },
        animationSpec = tween(durationMillis = 300),
        label = "card_bg",
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        AnimatedContent(
            targetState = item.reviewState,
            transitionSpec = {
                // Outgoing content fades out quickly; incoming fades in slightly slower
                // so there is never a flash of both states simultaneously.
                fadeIn(animationSpec = tween(durationMillis = 220)) togetherWith
                    fadeOut(animationSpec = tween(durationMillis = 160))
            },
            label = "card_state",
        ) { reviewState ->
            when (reviewState) {
                ReviewState.PENDING -> PendingCardContent(
                    item = item,
                    onAllowClicked = onAllowClicked,
                    onBlockClicked = onBlockClicked,
                )
                ReviewState.ALLOWED -> ReviewedCardContent(
                    message = "Allowed future notifications like this",
                    messageColor = MaterialTheme.colorScheme.primary,
                    onUndoClicked = { onUndoClicked(item.id) },
                )
                ReviewState.BLOCKED -> ReviewedCardContent(
                    message = "Confirmed Blocked",
                    messageColor = MaterialTheme.colorScheme.error,
                    onUndoClicked = { onUndoClicked(item.id) },
                )
            }
        }
    }
}

@Composable
private fun PendingCardContent(
    item: NotificationReviewUiItem,
    onAllowClicked: (Long) -> Unit,
    onBlockClicked: (Long) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // Fall back to last package segment when title is blank (shouldn't happen
                // after pre-flight filters, but defensive).
                text = item.title.ifBlank { item.packageName.substringAfterLast('.') },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatReviewTimestamp(item.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (item.content.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { onAllowClicked(item.id) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text("Allow")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = { onBlockClicked(item.id) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Block")
            }
        }
    }
}

@Composable
private fun ReviewedCardContent(
    message: String,
    messageColor: Color,
    onUndoClicked: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelMedium,
            color = messageColor,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onUndoClicked,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                text = "Undo",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

