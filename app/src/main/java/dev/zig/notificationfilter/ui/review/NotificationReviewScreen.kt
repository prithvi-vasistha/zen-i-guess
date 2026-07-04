package dev.zig.notificationfilter.ui.review

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Switch
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.zig.notificationfilter.domain.classifier.NotificationCategory
import dev.zig.notificationfilter.ui.common.BellDoodle
import dev.zig.notificationfilter.ui.common.ScrollFab
import dev.zig.notificationfilter.ui.common.ZigEmptyState
import dev.zig.notificationfilter.ui.theme.ZigGreen
import dev.zig.notificationfilter.ui.tour.TourKeys
import dev.zig.notificationfilter.ui.tour.coachMark
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Effective action: drives button display and background colour ─────────────
// Derived from systemDecision + userOverrideStatus at render time.

private enum class EffectiveCardAction {
    ALLOW_ONLY,               // MODEL_BLOCKED + NONE             → show Allow
    BLOCK_AND_MUTE_ONLY,      // PUBLISHED + NONE                 → show Block & Mute
    BLOCK_AND_MUTE_WITH_UNDO, // MODEL_BLOCKED + MANUALLY_ALLOWED → Block & Mute + Undo
    ALLOW_WITH_UNDO;          // PUBLISHED + MANUALLY_BLOCKED     → Allow + Undo

    companion object {
        fun from(systemDecision: String, userOverrideStatus: String): EffectiveCardAction {
            val isBlocked = systemDecision == "MODEL_BLOCKED" || systemDecision == "LLM_BLOCKED"
            return when {
                isBlocked && userOverrideStatus == "MANUALLY_ALLOWED" -> BLOCK_AND_MUTE_WITH_UNDO
                isBlocked                                              -> ALLOW_ONLY
                systemDecision == "PUBLISHED" && userOverrideStatus == "MANUALLY_BLOCKED" -> ALLOW_WITH_UNDO
                else                                                   -> BLOCK_AND_MUTE_ONLY
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private val REVIEW_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM dd · HH:mm")

private fun formatReviewTimestamp(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(REVIEW_TIME_FORMATTER)

private fun SortField.displayLabel(): String = when (this) {
    SortField.TIME     -> "Time"
    SortField.APP_NAME -> "App Name"
    SortField.STATUS   -> "Status"
}

private fun SortDirection.displayLabel(sortField: SortField): String = when (sortField) {
    SortField.TIME     -> if (this == SortDirection.DESC) "Newest first" else "Oldest first"
    SortField.APP_NAME -> if (this == SortDirection.DESC) "Z → A" else "A → Z"
    SortField.STATUS   -> if (this == SortDirection.DESC) "Blocked first" else "Allowed first"
}

private fun String.toDisplayCategory(): String = removePrefix("CATEGORY_")

// ── Entry point ───────────────────────────────────────────────────────────────

@Composable
fun NotificationReviewRoute(modifier: Modifier = Modifier) {
    val viewModel: NotificationReviewViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val archiveUiState by viewModel.archiveUiState.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val packageLabels by viewModel.packageLabels.collectAsState()
    val categoryOverrides by viewModel.categoryOverrides.collectAsState()
    val archiveDateFilter by viewModel.archiveDateFilter.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val dailySummaryEnabled by viewModel.dailySummaryEnabled.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.refreshCompleted.collect {
            snackbarHostState.showSnackbar("Up to date")
        }
    }

    var showArchive by remember { mutableStateOf(false) }

    NotificationReviewScreen(
        uiState = uiState,
        archiveUiState = archiveUiState,
        filter = filter,
        packageLabels = packageLabels,
        categoryOverrides = categoryOverrides,
        showArchive = showArchive,
        archiveDateFilter = archiveDateFilter,
        isRefreshing = isRefreshing,
        snackbarHostState = snackbarHostState,
        onToggleArchive = { showArchive = !showArchive },
        onRefresh = viewModel::refresh,
        onQueryChange = viewModel::setQuery,
        onSortFieldChange = viewModel::setSortField,
        onSortDirectionChange = viewModel::setSortDirection,
        onArchiveDateFilterChange = viewModel::setArchiveDateFilter,
        onAllowClicked = viewModel::onAllowClicked,
        onBlockAndMuteClicked = viewModel::onBlockAndMuteClicked,
        onUndoClicked = viewModel::onUndoClicked,
        onSetCategoryOverride = viewModel::setCategoryOverride,
        onSetUserCategory = viewModel::setUserAssignedCategory,
        dailySummaryEnabled = dailySummaryEnabled,
        onDailySummaryToggled = viewModel::setDailySummaryEnabled,
        modifier = modifier,
    )
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationReviewScreen(
    uiState: ReviewUiState,
    archiveUiState: ReviewUiState,
    filter: ReviewFilter,
    packageLabels: Map<String, String>,
    categoryOverrides: Map<String, String>,
    showArchive: Boolean,
    archiveDateFilter: LocalDate?,
    isRefreshing: Boolean,
    snackbarHostState: SnackbarHostState,
    onToggleArchive: () -> Unit,
    onRefresh: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSortFieldChange: (SortField) -> Unit,
    onSortDirectionChange: (SortDirection) -> Unit,
    onArchiveDateFilterChange: (LocalDate?) -> Unit,
    onAllowClicked: (Long) -> Unit,
    onBlockAndMuteClicked: (Long) -> Unit,
    onUndoClicked: (Long) -> Unit,
    onSetCategoryOverride: (String, String?) -> Unit,
    onSetUserCategory: (Long, String?) -> Unit,
    dailySummaryEnabled: Boolean,
    onDailySummaryToggled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSettingsMenu by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = if (showArchive) "Archive" else "Notifications",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.coachMark(TourKeys.TITLE_REVIEW),
                    )
                },
                actions = {
                    TextButton(
                        onClick = onToggleArchive,
                        modifier = Modifier.coachMark(TourKeys.REVIEW_ARCHIVE),
                    ) {
                        Text(if (showArchive) "Active" else "Archive")
                    }
                    Box {
                        IconButton(
                            onClick = { showSettingsMenu = true },
                            modifier = Modifier.coachMark(TourKeys.REVIEW_SETTINGS),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                            )
                        }
                        DropdownMenu(
                            expanded = showSettingsMenu,
                            onDismissRequest = { showSettingsMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Daily Summary",
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            Text(
                                                text = "8 PM recap notification",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Switch(
                                            checked = dailySummaryEnabled,
                                            onCheckedChange = onDailySummaryToggled,
                                        )
                                    }
                                },
                                onClick = { onDailySummaryToggled(!dailySummaryEnabled) },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        val activeState = if (showArchive) archiveUiState else uiState
        val pullToRefreshState = rememberPullToRefreshState()
        val inboxListState = rememberLazyListState()
        val archiveListState = rememberLazyListState()
        val activeListState = if (showArchive) archiveListState else inboxListState

        LaunchedEffect(filter.sortField, filter.sortDirection) {
            activeListState.scrollToItem(0)
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            state = pullToRefreshState,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullToRefreshState,
                    isRefreshing = isRefreshing,
                    color = ZigGreen,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
        ) {
        Column(modifier = Modifier.fillMaxSize()) {
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SortFieldMenu(
                    current = filter.sortField,
                    onSelect = onSortFieldChange,
                    modifier = Modifier.weight(1f),
                )
                SortDirectionMenu(
                    current = filter.sortDirection,
                    sortField = filter.sortField,
                    onSelect = onSortDirectionChange,
                    modifier = Modifier.weight(1f),
                )
            }

            if (showArchive) {
                val availableDates = (activeState as? ReviewUiState.DateGroupedContent)
                    ?.dateGroups?.map { it.date to it.label }
                    ?: emptyList()
                if (availableDates.isNotEmpty()) {
                    DateFilterChip(
                        currentDate = archiveDateFilter,
                        availableDates = availableDates,
                        onSelect = onArchiveDateFilterChange,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when (activeState) {
                    is ReviewUiState.Loading -> { /* intentionally blank — avoids flicker */ }
                    is ReviewUiState.Empty -> {
                        ZigEmptyState(
                            title = if (showArchive) "Archive is empty" else "You're all caught up",
                            doodle = { BellDoodle() },
                        )
                    }
                    is ReviewUiState.Content -> {
                        ReviewListContent(
                            listState = inboxListState,
                            groupedItems = activeState.groups,
                            packageLabels = packageLabels,
                            categoryOverrides = categoryOverrides,
                            onAllowClicked = onAllowClicked,
                            onBlockAndMuteClicked = onBlockAndMuteClicked,
                            onUndoClicked = onUndoClicked,
                            onSetCategoryOverride = onSetCategoryOverride,
                            onSetUserCategory = onSetUserCategory,
                        )
                    }
                    is ReviewUiState.DateGroupedContent -> {
                        val displayedGroups = if (archiveDateFilter != null) {
                            activeState.dateGroups.filter { it.date == archiveDateFilter }
                        } else {
                            activeState.dateGroups
                        }
                        if (displayedGroups.isEmpty()) {
                            ZigEmptyState(title = "No notifications for this date", doodle = { BellDoodle() })
                        } else {
                            ArchiveDateGroupedContent(
                                listState = archiveListState,
                                dateGroups = displayedGroups,
                                packageLabels = packageLabels,
                                categoryOverrides = categoryOverrides,
                                onAllowClicked = onAllowClicked,
                                onBlockAndMuteClicked = onBlockAndMuteClicked,
                                onUndoClicked = onUndoClicked,
                                onSetCategoryOverride = onSetCategoryOverride,
                                onSetUserCategory = onSetUserCategory,
                            )
                        }
                    }
                }
                ScrollFab(
                    listState = activeListState,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                )
            }
        }
        } // PullToRefreshBox
    }
}

// ── Sort dropdowns ────────────────────────────────────────────────────────────
// Use Box + transparent clickable overlay over a read-only OutlinedTextField so
// clicks are reliably captured without ExposedDropdownMenuBox event-handling quirks.

@Composable
private fun SortFieldMenu(
    current: SortField,
    onSelect: (SortField) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedTextField(
            value = current.displayLabel(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Sort by", style = MaterialTheme.typography.labelSmall) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SortField.entries.forEach { field ->
                DropdownMenuItem(
                    text = { Text(field.displayLabel(), style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        onSelect(field)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SortDirectionMenu(
    current: SortDirection,
    sortField: SortField,
    onSelect: (SortDirection) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedTextField(
            value = current.displayLabel(sortField),
            onValueChange = {},
            readOnly = true,
            label = { Text("Order", style = MaterialTheme.typography.labelSmall) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SortDirection.entries.forEach { direction ->
                DropdownMenuItem(
                    text = { Text(direction.displayLabel(sortField), style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        onSelect(direction)
                        expanded = false
                    },
                )
            }
        }
    }
}

// ── List ──────────────────────────────────────────────────────────────────────

@Composable
private fun ReviewListContent(
    listState: LazyListState,
    groupedItems: Map<String, List<NotificationReviewUiItem>>,
    packageLabels: Map<String, String>,
    categoryOverrides: Map<String, String>,
    onAllowClicked: (Long) -> Unit,
    onBlockAndMuteClicked: (Long) -> Unit,
    onUndoClicked: (Long) -> Unit,
    onSetCategoryOverride: (String, String?) -> Unit,
    onSetUserCategory: (Long, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        groupedItems.entries.forEachIndexed { groupIndex, (packageName, items) ->
            item(key = "group_$packageName") {
                AppGroupCard(
                    packageName = packageName,
                    items = items,
                    appLabel = packageLabels[packageName] ?: packageName.substringAfterLast('.'),
                    currentOverride = categoryOverrides[packageName],
                    onSetCategoryOverride = { category -> onSetCategoryOverride(packageName, category) },
                    onAllowClicked = onAllowClicked,
                    onBlockAndMuteClicked = onBlockAndMuteClicked,
                    onUndoClicked = onUndoClicked,
                    onSetUserCategory = onSetUserCategory,
                    // Spotlight the very first card's category chip during the onboarding tour.
                    spotlightFirstCategory = groupIndex == 0,
                )
            }
        }
    }
}

// ── Tier A: App group card (header + all notifications for one app) ───────────

@Composable
private fun AppGroupCard(
    packageName: String,
    items: List<NotificationReviewUiItem>,
    appLabel: String,
    currentOverride: String?,
    onSetCategoryOverride: (String?) -> Unit,
    onAllowClicked: (Long) -> Unit,
    onBlockAndMuteClicked: (Long) -> Unit,
    onUndoClicked: (Long) -> Unit,
    onSetUserCategory: (Long, String?) -> Unit,
    spotlightFirstCategory: Boolean = false,
) {
    var expanded by remember { mutableStateOf(true) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = ZigGreen.copy(alpha = 0.10f)),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            AppGroupHeader(
                appLabel = appLabel,
                currentOverride = currentOverride,
                onSetOverride = onSetCategoryOverride,
                expanded = expanded,
                onToggle = { expanded = !expanded },
            )
            if (expanded) {
                HorizontalDivider(color = ZigGreen.copy(alpha = 0.18f), thickness = 0.5.dp)
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items.forEachIndexed { itemIndex, item ->
                        ReviewItemCard(
                            item = item,
                            onAllowClicked = onAllowClicked,
                            onBlockAndMuteClicked = onBlockAndMuteClicked,
                            onUndoClicked = onUndoClicked,
                            onSetUserCategory = onSetUserCategory,
                            // Only the first card of the first group carries the tour spotlight.
                            categoryChipModifier = if (spotlightFirstCategory && itemIndex == 0) {
                                Modifier.coachMark(TourKeys.REVIEW_CATEGORY)
                            } else {
                                Modifier
                            },
                        )
                    }
                }
            }
        }
    }
}

// ── App group header (lives inside the group card) ────────────────────────────

@Composable
private fun AppGroupHeader(
    appLabel: String,
    currentOverride: String?,
    onSetOverride: (String?) -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onToggle() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = appLabel,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
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
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
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
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse" else "Expand",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Tier B: Notification card ─────────────────────────────────────────────────

@Composable
private fun ReviewItemCard(
    item: NotificationReviewUiItem,
    onAllowClicked: (Long) -> Unit,
    onBlockAndMuteClicked: (Long) -> Unit,
    onUndoClicked: (Long) -> Unit,
    onSetUserCategory: (Long, String?) -> Unit,
    modifier: Modifier = Modifier,
    categoryChipModifier: Modifier = Modifier,
) {
    val effectiveAction = EffectiveCardAction.from(item.systemDecision, item.userOverrideStatus)

    val containerColor by animateColorAsState(
        targetValue = when (effectiveAction) {
            EffectiveCardAction.BLOCK_AND_MUTE_WITH_UNDO -> ZigGreen.copy(alpha = 0.15f)
            EffectiveCardAction.ALLOW_WITH_UNDO          -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.22f)
            else                                         -> MaterialTheme.colorScheme.surface
        },
        animationSpec = tween(durationMillis = 300),
        label = "card_bg",
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
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
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            CategoryRow(
                item = item,
                onSetUserCategory = onSetUserCategory,
                chipModifier = categoryChipModifier,
            )

            Spacer(modifier = Modifier.height(8.dp))

            AnimatedContent(
                targetState = effectiveAction,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(160))
                },
                label = "action_row",
            ) { action ->
                ActionRow(
                    action = action,
                    id = item.id,
                    isPublishedRow = item.systemDecision == "PUBLISHED",
                    onAllowClicked = onAllowClicked,
                    onBlockAndMuteClicked = onBlockAndMuteClicked,
                    onUndoClicked = onUndoClicked,
                )
            }
        }
    }
}

// ── Category chip ─────────────────────────────────────────────────────────────

@Composable
private fun CategoryRow(
    item: NotificationReviewUiItem,
    onSetUserCategory: (Long, String?) -> Unit,
    chipModifier: Modifier = Modifier,
) {
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    val displayCategory = (item.userAssignedCategory ?: item.inferredCategory).toDisplayCategory()
    val isUserAssigned = item.userAssignedCategory != null

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            SuggestionChip(
                modifier = chipModifier,
                onClick = { categoryMenuExpanded = true },
                label = { Text(displayCategory, style = MaterialTheme.typography.labelSmall) },
                icon = if (isUserAssigned) {
                    { Icon(Icons.Default.Check, contentDescription = "User assigned", modifier = Modifier.size(14.dp)) }
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
}

// ── Action row ────────────────────────────────────────────────────────────────

@Composable
private fun ActionRow(
    action: EffectiveCardAction,
    id: Long,
    isPublishedRow: Boolean,
    onAllowClicked: (Long) -> Unit,
    onBlockAndMuteClicked: (Long) -> Unit,
    onUndoClicked: (Long) -> Unit,
) {
    Column {
        when (action) {
            EffectiveCardAction.ALLOW_ONLY -> {
                Button(
                    onClick = { onAllowClicked(id) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Allow") }
            }

            EffectiveCardAction.BLOCK_AND_MUTE_ONLY -> {
                OutlinedButton(
                    onClick = { onBlockAndMuteClicked(id) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Block & Mute") }
            }

            EffectiveCardAction.BLOCK_AND_MUTE_WITH_UNDO -> {
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { onBlockAndMuteClicked(id) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Block & Mute") }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = { onUndoClicked(id) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Undo") }
                }
            }

            EffectiveCardAction.ALLOW_WITH_UNDO -> {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onAllowClicked(id) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) { Text("Allow") }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = { onUndoClicked(id) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Undo") }
                }
            }
        }

        if (isPublishedRow) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Won't suppress already-delivered notifications · Recorded as training signal",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

// ── Archive: date-grouped list ────────────────────────────────────────────────

@Composable
private fun ArchiveDateGroupedContent(
    listState: LazyListState,
    dateGroups: List<DateGroup>,
    packageLabels: Map<String, String>,
    categoryOverrides: Map<String, String>,
    onAllowClicked: (Long) -> Unit,
    onBlockAndMuteClicked: (Long) -> Unit,
    onUndoClicked: (Long) -> Unit,
    onSetCategoryOverride: (String, String?) -> Unit,
    onSetUserCategory: (Long, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        dateGroups.forEach { dateGroup ->
            item(key = "date_${dateGroup.date}") {
                DateGroupHeader(label = dateGroup.label)
            }
            dateGroup.appGroups.forEach { (packageName, items) ->
                item(key = "group_${dateGroup.date}_$packageName") {
                    AppGroupCard(
                        packageName = packageName,
                        items = items,
                        appLabel = packageLabels[packageName] ?: packageName.substringAfterLast('.'),
                        currentOverride = categoryOverrides[packageName],
                        onSetCategoryOverride = { category -> onSetCategoryOverride(packageName, category) },
                        onAllowClicked = onAllowClicked,
                        onBlockAndMuteClicked = onBlockAndMuteClicked,
                        onUndoClicked = onUndoClicked,
                        onSetUserCategory = onSetUserCategory,
                    )
                }
            }
        }
    }
}

@Composable
private fun DateGroupHeader(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        )
    }
}

// ── Date filter chip (archive tab only) ───────────────────────────────────────

@Composable
private fun DateFilterChip(
    currentDate: LocalDate?,
    availableDates: List<Pair<LocalDate, String>>,
    onSelect: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        FilterChip(
            selected = currentDate != null,
            onClick = { expanded = true },
            label = {
                Text(
                    text = if (currentDate != null) {
                        availableDates.find { it.first == currentDate }?.second ?: "Date"
                    } else {
                        "All dates"
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("All dates", style = MaterialTheme.typography.bodySmall) },
                onClick = { onSelect(null); expanded = false },
            )
            HorizontalDivider()
            availableDates.forEach { (date, label) ->
                DropdownMenuItem(
                    text = { Text(label, style = MaterialTheme.typography.bodySmall) },
                    onClick = { onSelect(date); expanded = false },
                )
            }
        }
    }
}
