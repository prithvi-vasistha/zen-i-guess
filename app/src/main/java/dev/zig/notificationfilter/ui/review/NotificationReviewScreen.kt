package dev.zig.notificationfilter.ui.review

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import dev.zig.notificationfilter.domain.classifier.NotificationCategory
import androidx.compose.ui.res.painterResource
import dev.zig.notificationfilter.R
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
fun NotificationReviewRoute(
    modifier: Modifier = Modifier,
) {
    val viewModel: NotificationReviewViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val archiveUiState by viewModel.archiveUiState.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val packageLabels by viewModel.packageLabels.collectAsState()
    val appIcons by viewModel.appIcons.collectAsState()
    val categoryOverrides by viewModel.categoryOverrides.collectAsState()
    val archiveDateFilter by viewModel.archiveDateFilter.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.refreshCompleted.collect {
            snackbarHostState.showSnackbar("Up to date")
        }
    }

    // Swipe-to-dismiss on the inbox: soft-hide the row, then offer a one-tap Undo. The row
    // stays in the archive regardless, so an accidental dismiss loses nothing permanent.
    val onDismiss: (Long) -> Unit = { id ->
        viewModel.onDismiss(id)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Notification dismissed",
                actionLabel = "Undo",
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.onRestoreDismissed(id)
            }
        }
    }

    var showArchive by remember { mutableStateOf(false) }

    NotificationReviewScreen(
        uiState = uiState,
        archiveUiState = archiveUiState,
        filter = filter,
        packageLabels = packageLabels,
        appIcons = appIcons,
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
        onSetCategoryOverride = viewModel::setCategoryOverride,
        onSetUserCategory = viewModel::setUserAssignedCategory,
        onDismiss = onDismiss,
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
    appIcons: Map<String, ImageBitmap>,
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
    onSetCategoryOverride: (String, String?) -> Unit,
    onSetUserCategory: (Long, String?) -> Unit,
    onDismiss: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {

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
                            doodle = {
                                Image(
                                    painter = painterResource(R.drawable.ic_empty_notifications),
                                    contentDescription = null,
                                )
                            },
                        )
                    }
                    is ReviewUiState.Content -> {
                        ReviewListContent(
                            listState = inboxListState,
                            groupedItems = activeState.groups,
                            packageLabels = packageLabels,
                            appIcons = appIcons,
                            categoryOverrides = categoryOverrides,
                            onAllowClicked = onAllowClicked,
                            onBlockAndMuteClicked = onBlockAndMuteClicked,
                            onSetCategoryOverride = onSetCategoryOverride,
                            onSetUserCategory = onSetUserCategory,
                            onDismiss = onDismiss,
                        )
                    }
                    is ReviewUiState.DateGroupedContent -> {
                        val displayedGroups = if (archiveDateFilter != null) {
                            activeState.dateGroups.filter { it.date == archiveDateFilter }
                        } else {
                            activeState.dateGroups
                        }
                        if (displayedGroups.isEmpty()) {
                            ZigEmptyState(
                                title = "No notifications for this date",
                                doodle = {
                                    Image(
                                        painter = painterResource(R.drawable.ic_empty_notifications),
                                        contentDescription = null,
                                    )
                                },
                            )
                        } else {
                            ArchiveDateGroupedContent(
                                listState = archiveListState,
                                dateGroups = displayedGroups,
                                packageLabels = packageLabels,
                                appIcons = appIcons,
                                categoryOverrides = categoryOverrides,
                                onAllowClicked = onAllowClicked,
                                onBlockAndMuteClicked = onBlockAndMuteClicked,
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
    appIcons: Map<String, ImageBitmap>,
    categoryOverrides: Map<String, String>,
    onAllowClicked: (Long) -> Unit,
    onBlockAndMuteClicked: (Long) -> Unit,
    onSetCategoryOverride: (String, String?) -> Unit,
    onSetUserCategory: (Long, String?) -> Unit,
    onDismiss: (Long) -> Unit,
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
                    appIcon = appIcons[packageName],
                    currentOverride = categoryOverrides[packageName],
                    onSetCategoryOverride = { category -> onSetCategoryOverride(packageName, category) },
                    onAllowClicked = onAllowClicked,
                    onBlockAndMuteClicked = onBlockAndMuteClicked,
                    onSetUserCategory = onSetUserCategory,
                    // Inbox rows are swipe-to-dismiss; archive rows pass null (see below).
                    onDismiss = onDismiss,
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
    appIcon: ImageBitmap?,
    currentOverride: String?,
    onSetCategoryOverride: (String?) -> Unit,
    onAllowClicked: (Long) -> Unit,
    onBlockAndMuteClicked: (Long) -> Unit,
    onSetUserCategory: (Long, String?) -> Unit,
    // Non-null on the active inbox (enables swipe-to-dismiss); null on the archive.
    onDismiss: ((Long) -> Unit)? = null,
    spotlightFirstCategory: Boolean = false,
) {
    var expanded by remember { mutableStateOf(true) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            // Tour: spotlight the whole first group card so both the app-level chip (header)
            // and the per-notification chips fall inside the lit cut-out.
            .then(
                if (spotlightFirstCategory) Modifier.coachMark(TourKeys.REVIEW_CATEGORY) else Modifier,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (currentOverride != null)
                categoryChipColors(currentOverride).container
            else
                ZigGreen.copy(alpha = 0.10f),
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            AppGroupHeader(
                appLabel = appLabel,
                appIcon = appIcon,
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
                    items.forEach { item ->
                        // key() so each row keeps its own swipe state positionally and is
                        // discarded cleanly when the row leaves the list after a dismiss.
                        key(item.id) {
                            val card: @Composable () -> Unit = {
                                ReviewItemCard(
                                    item = item,
                                    onAllowClicked = onAllowClicked,
                                    onBlockAndMuteClicked = onBlockAndMuteClicked,
                                    onSetUserCategory = onSetUserCategory,
                                )
                            }
                            if (onDismiss != null) {
                                DismissibleReviewItem(id = item.id, onDismiss = onDismiss, content = card)
                            } else {
                                card()
                            }
                        }
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
    appIcon: ImageBitmap?,
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (appIcon != null) {
            Image(
                bitmap = appIcon,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ZigGreen.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = appLabel.take(1).uppercase(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = ZigGreen,
                )
            }
        }
        Text(
            text = appLabel,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = ZigGreen,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box {
            val overrideColors = currentOverride?.let { categoryChipColors(it) }
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
                colors = if (overrideColors != null) SuggestionChipDefaults.suggestionChipColors(
                    containerColor = overrideColors.container,
                    labelColor = overrideColors.onContainer,
                    iconContentColor = overrideColors.onContainer,
                ) else SuggestionChipDefaults.suggestionChipColors(),
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

// ── Swipe-to-dismiss wrapper (active inbox only) ──────────────────────────────

@Composable
private fun DismissibleReviewItem(
    id: Long,
    onDismiss: (Long) -> Unit,
    content: @Composable () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        // A completed swipe in either direction dismisses; Settled means the user let go
        // before the threshold, so the card springs back untouched.
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) {
                onDismiss(id)
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val alignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                Alignment.CenterStart
            } else {
                Alignment.CenterEnd
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 24.dp),
                contentAlignment = alignment,
            ) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        content = { content() },
    )
}

// ── Tier B: Notification card ─────────────────────────────────────────────────

@Composable
private fun ReviewItemCard(
    item: NotificationReviewUiItem,
    onAllowClicked: (Long) -> Unit,
    onBlockAndMuteClicked: (Long) -> Unit,
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
    val chipColors = categoryChipColors(displayCategory)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            SuggestionChip(
                modifier = chipModifier,
                onClick = { categoryMenuExpanded = true },
                label = { Text(displayCategory, style = MaterialTheme.typography.labelSmall) },
                icon = if (isUserAssigned) {
                    { Icon(Icons.Default.Check, contentDescription = "User assigned", modifier = Modifier.size(14.dp)) }
                } else null,
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = chipColors.container,
                    labelColor = chipColors.onContainer,
                    iconContentColor = chipColors.onContainer,
                ),
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
) {
    Column {
        when (action) {
            EffectiveCardAction.ALLOW_ONLY,
            EffectiveCardAction.ALLOW_WITH_UNDO -> {
                Button(
                    onClick = { onAllowClicked(id) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Allow") }
            }

            EffectiveCardAction.BLOCK_AND_MUTE_ONLY,
            EffectiveCardAction.BLOCK_AND_MUTE_WITH_UNDO -> {
                OutlinedButton(
                    onClick = { onBlockAndMuteClicked(id) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Block & Mute") }
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
    appIcons: Map<String, ImageBitmap>,
    categoryOverrides: Map<String, String>,
    onAllowClicked: (Long) -> Unit,
    onBlockAndMuteClicked: (Long) -> Unit,
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
                        appIcon = appIcons[packageName],
                        currentOverride = categoryOverrides[packageName],
                        onSetCategoryOverride = { category -> onSetCategoryOverride(packageName, category) },
                        onAllowClicked = onAllowClicked,
                        onBlockAndMuteClicked = onBlockAndMuteClicked,
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
