package dev.zig.notificationfilter.ui.review

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.zig.notificationfilter.data.local.db.ReviewState
import dev.zig.notificationfilter.domain.classifier.NotificationCategory
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

private fun SortBy.displayLabel(): String = when (this) {
    SortBy.TIME_DESC -> "Newest"
    SortBy.TIME_ASC  -> "Oldest"
    SortBy.APP_NAME  -> "App A–Z"
    SortBy.STATUS    -> "Status"
}

// Strip "CATEGORY_" prefix for compact display in chips.
private fun String.toDisplayCategory(): String = removePrefix("CATEGORY_")

// ── Entry point ─────────────────────────────────────────────────────────────

@Composable
fun NotificationReviewRoute(modifier: Modifier = Modifier) {
    val viewModel: NotificationReviewViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val archiveUiState by viewModel.archiveUiState.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val packageLabels by viewModel.packageLabels.collectAsState()
    val categoryOverrides by viewModel.categoryOverrides.collectAsState()

    var showArchive by remember { mutableStateOf(false) }

    NotificationReviewScreen(
        uiState = uiState,
        archiveUiState = archiveUiState,
        filter = filter,
        packageLabels = packageLabels,
        categoryOverrides = categoryOverrides,
        showArchive = showArchive,
        onToggleArchive = { showArchive = !showArchive },
        onQueryChange = viewModel::setQuery,
        onSortChange = viewModel::setSortBy,
        onAllowClicked = viewModel::onAllowClicked,
        onBlockClicked = viewModel::onBlockClicked,
        onUndoClicked = viewModel::onUndoClicked,
        onSetCategoryOverride = viewModel::setCategoryOverride,
        onSetUserCategory = viewModel::setUserAssignedCategory,
        modifier = modifier,
    )
}

// ── Screen ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationReviewScreen(
    uiState: ReviewUiState,
    archiveUiState: ReviewUiState,
    filter: ReviewFilter,
    packageLabels: Map<String, String>,
    categoryOverrides: Map<String, String>,
    showArchive: Boolean,
    onToggleArchive: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSortChange: (SortBy) -> Unit,
    onAllowClicked: (Long) -> Unit,
    onBlockClicked: (Long) -> Unit,
    onUndoClicked: (Long) -> Unit,
    onSetCategoryOverride: (String, String?) -> Unit,
    onSetUserCategory: (Long, String?) -> Unit,
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

        Column(modifier = Modifier.padding(innerPadding)) {

            // Search bar
            OutlinedTextField(
                value = filter.query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = {
                    Text(
                        text = "Search app, title, or content…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (filter.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                textStyle = MaterialTheme.typography.bodySmall,
            )

            // Sort chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SortBy.entries.forEach { sort ->
                    FilterChip(
                        selected = filter.sortBy == sort,
                        onClick = { onSortChange(sort) },
                        label = {
                            Text(
                                text = sort.displayLabel(),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }

            when (activeState) {
                is ReviewUiState.Loading -> {
                    // Room has not yet emitted — intentionally blank to avoid flicker.
                }
                is ReviewUiState.Empty -> {
                    ZigEmptyState(
                        title = if (showArchive) "Archive is empty" else "You're all caught up",
                        doodle = { BellDoodle() },
                    )
                }
                is ReviewUiState.Content -> {
                    ReviewListContent(
                        groupedItems = activeState.groups,
                        packageLabels = packageLabels,
                        categoryOverrides = categoryOverrides,
                        onAllowClicked = onAllowClicked,
                        onBlockClicked = onBlockClicked,
                        onUndoClicked = onUndoClicked,
                        onSetCategoryOverride = onSetCategoryOverride,
                        onSetUserCategory = onSetUserCategory,
                    )
                }
            }
        }
    }
}

// ── List ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReviewListContent(
    groupedItems: Map<String, List<NotificationReviewUiItem>>,
    packageLabels: Map<String, String>,
    categoryOverrides: Map<String, String>,
    onAllowClicked: (Long) -> Unit,
    onBlockClicked: (Long) -> Unit,
    onUndoClicked: (Long) -> Unit,
    onSetCategoryOverride: (String, String?) -> Unit,
    onSetUserCategory: (Long, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        groupedItems.forEach { (packageName, items) ->
            // ── Tier A: App-level sticky header ───────────────────────────────
            stickyHeader(key = "header_$packageName") {
                AppGroupHeader(
                    appLabel = packageLabels[packageName]
                        ?: packageName.substringAfterLast('.'),
                    currentOverride = categoryOverrides[packageName],
                    onSetOverride = { category -> onSetCategoryOverride(packageName, category) },
                )
            }
            // ── Tier B: Per-notification cards ────────────────────────────────
            items(items = items, key = { it.id }) { item ->
                ReviewItemCard(
                    item = item,
                    onAllowClicked = onAllowClicked,
                    onBlockClicked = onBlockClicked,
                    onUndoClicked = onUndoClicked,
                    onSetUserCategory = onSetUserCategory,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

// ── Tier A: App-level sticky header ─────────────────────────────────────────

@Composable
private fun AppGroupHeader(
    appLabel: String,
    currentOverride: String?,   // e.g. "FINANCE" — null if no override is set
    onSetOverride: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = appLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = ZigGreen,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box {
            SuggestionChip(
                onClick = { menuExpanded = true },
                label = {
                    Text(
                        text = currentOverride ?: "+ Category",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                icon = if (currentOverride != null) {
                    {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                } else null,
            )
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                NotificationCategory.entries.forEach { cat ->
                    DropdownMenuItem(
                        text = { Text(cat.name) },
                        onClick = {
                            onSetOverride(cat.name)
                            menuExpanded = false
                        },
                    )
                }
                if (currentOverride != null) {
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Clear override",
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            onSetOverride(null)
                            menuExpanded = false
                        },
                    )
                }
            }
        }
    }
}

// ── Tier B: Notification card ────────────────────────────────────────────────

@Composable
private fun ReviewItemCard(
    item: NotificationReviewUiItem,
    onAllowClicked: (Long) -> Unit,
    onBlockClicked: (Long) -> Unit,
    onUndoClicked: (Long) -> Unit,
    onSetUserCategory: (Long, String?) -> Unit,
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
                    onSetUserCategory = onSetUserCategory,
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
    onSetUserCategory: (Long, String?) -> Unit,
) {
    var categoryMenuExpanded by remember { mutableStateOf(false) }

    // User assignment takes precedence over the model-inferred category.
    val displayCategory = (item.userAssignedCategory ?: item.inferredCategory).toDisplayCategory()
    val isUserAssigned = item.userAssignedCategory != null

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
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

        Spacer(modifier = Modifier.height(8.dp))

        // ── Category row: chip + optional confidence score ────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                SuggestionChip(
                    onClick = { categoryMenuExpanded = true },
                    label = {
                        Text(
                            text = displayCategory,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    icon = if (isUserAssigned) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "User assigned",
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    } else null,
                )
                DropdownMenu(
                    expanded = categoryMenuExpanded,
                    onDismissRequest = { categoryMenuExpanded = false },
                ) {
                    NotificationCategory.entries.forEach { cat ->
                        DropdownMenuItem(
                            text = { Text(cat.name) },
                            onClick = {
                                onSetUserCategory(item.id, "CATEGORY_${cat.name}")
                                categoryMenuExpanded = false
                            },
                        )
                    }
                    if (isUserAssigned) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Reset to inferred") },
                            onClick = {
                                onSetUserCategory(item.id, null)
                                categoryMenuExpanded = false
                            },
                        )
                    }
                }
            }

            if (item.modelConfidence > 0f) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "P(block) ${"%.2f".format(item.modelConfidence)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Action buttons ────────────────────────────────────────────────────
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
