package dev.zig.notificationfilter.ui.review

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.zig.notificationfilter.data.local.db.AppCategoryOverrideDao
import dev.zig.notificationfilter.data.local.db.AppCategoryOverrideEntity
import dev.zig.notificationfilter.data.local.db.DemoDataSeeder
import dev.zig.notificationfilter.data.local.db.ManagedAppDao
import dev.zig.notificationfilter.data.local.db.NotificationReviewDao
import dev.zig.notificationfilter.data.local.db.NotificationReviewEntity
import dev.zig.notificationfilter.data.local.db.ReviewState
import dev.zig.notificationfilter.domain.memory.PersonalMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

data class NotificationReviewUiItem(
    val id: Long,
    val packageName: String,
    val title: String,
    val content: String,
    val timestamp: Long,
    val systemDecision: String,
    val reviewState: ReviewState,
    val modelConfidence: Float,
    val inferredCategory: String,
    val userAssignedCategory: String?,
    val userOverrideStatus: String,
)

sealed interface ReviewUiState {
    data object Loading : ReviewUiState
    data object Empty : ReviewUiState
    // Groups keyed by packageName, ordered by the most-recent notification per group.
    // groupBy() preserves insertion order — DAO returns timestamp DESC so the app
    // with the newest notification appears first.
    data class Content(val groups: Map<String, List<NotificationReviewUiItem>>) : ReviewUiState
    // Archive only: notifications grouped first by date (newest day first),
    // then by packageName within each day.
    data class DateGroupedContent(val dateGroups: List<DateGroup>) : ReviewUiState
}

@HiltViewModel
class NotificationReviewViewModel @Inject constructor(
    private val dao: NotificationReviewDao,
    private val overrideDao: AppCategoryOverrideDao,
    private val managedAppDao: ManagedAppDao,
    private val personalMemory: PersonalMemory,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private companion object {
        private const val TWENTY_FOUR_HOURS_MS = 24L * 60L * 60L * 1_000L
        private const val THIRTY_DAYS_MS = 30L * 24L * 60L * 60L * 1_000L
        private const val CUTOFF_REFRESH_INTERVAL_MS = 60L * 60L * 1_000L
        private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")

        // Chip-filter membership. AI_DECISIONS = rows the classifier decided (actionable);
        // BLOCKED = anything silenced, whether by the model or a keyword rule.
        private val AI_DECISION_SET = setOf("MODEL_BLOCKED", "PUBLISHED", "LLM_BLOCKED")
        private val BLOCKED_SET = setOf("MODEL_BLOCKED", "LLM_BLOCKED", "KEYWORD_BLOCKED")
    }

    // ── Filter state ──────────────────────────────────────────────────────────

    private val _filter = MutableStateFlow(ReviewFilter())
    val filter: StateFlow<ReviewFilter> = _filter.asStateFlow()

    fun setQuery(query: String) {
        _filter.value = _filter.value.copy(query = query)
    }

    fun setSortField(sortField: SortField) {
        _filter.value = _filter.value.copy(sortField = sortField)
    }

    fun setSortDirection(sortDirection: SortDirection) {
        _filter.value = _filter.value.copy(sortDirection = sortDirection)
    }

    fun setChipFilter(chipFilter: ChipFilter) {
        _filter.value = _filter.value.copy(chipFilter = chipFilter)
    }

    private val _archiveDateFilter = MutableStateFlow<LocalDate?>(null)
    val archiveDateFilter: StateFlow<LocalDate?> = _archiveDateFilter.asStateFlow()

    fun setArchiveDateFilter(date: LocalDate?) {
        _archiveDateFilter.value = date
    }

    // ── Refresh ───────────────────────────────────────────────────────────────
    // _refreshSignal is merged into both cutoff flows so pull-to-refresh forces
    // an immediate re-emission with the current timestamp instead of waiting for
    // the hourly tick.

    private val _refreshSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _refreshCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val refreshCompleted: SharedFlow<Unit> = _refreshCompleted.asSharedFlow()

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _refreshSignal.tryEmit(Unit)
            delay(700)
            _isRefreshing.value = false
            _refreshCompleted.tryEmit(Unit)
        }
    }

    // ── Cutoff ticker ─────────────────────────────────────────────────────────

    private val cutoffFlow = merge(
        flow { while (true) { emit(Unit); delay(CUTOFF_REFRESH_INTERVAL_MS) } },
        _refreshSignal,
    ).map { System.currentTimeMillis() - TWENTY_FOUR_HOURS_MS }

    private val archiveCutoffFlow = merge(
        flow { while (true) { emit(Unit); delay(CUTOFF_REFRESH_INTERVAL_MS) } },
        _refreshSignal,
    ).map { System.currentTimeMillis() - THIRTY_DAYS_MS }

    // Start-of-today epoch ms, recomputed on the same hourly tick so the day boundary rolls over
    // without needing an exact-midnight alarm.
    private val todayStartFlow = merge(
        flow { while (true) { emit(Unit); delay(CUTOFF_REFRESH_INTERVAL_MS) } },
        _refreshSignal,
    ).map {
        LocalDate.now(ZoneId.systemDefault())
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    // ── Active inbox ──────────────────────────────────────────────────────────

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ReviewUiState> = combine(cutoffFlow, _filter) { cutoff, filter ->
        Pair(cutoff, filter)
    }.flatMapLatest { (cutoff, filter) ->
        dao.searchActiveFlow(cutoff, filter.query)
            .map { list ->
                list.applyChipFilter(filter.chipFilter)
                    .applySort(filter.sortField, filter.sortDirection)
                    .toReviewUiState()
            }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ReviewUiState.Loading,
        )

    // ── Archive ───────────────────────────────────────────────────────────────
    // 30-day window (>= archiveCutoffTimestamp) — overlaps with the active inbox
    // so today's notifications appear in both tabs. Grouped by date then by app.

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val archiveUiState: StateFlow<ReviewUiState> = combine(archiveCutoffFlow, _filter) { cutoff, filter ->
        Pair(cutoff, filter)
    }.flatMapLatest { (cutoff, filter) ->
        dao.searchArchiveFlow(cutoff, filter.query)
            .map { list ->
                list.applyChipFilter(filter.chipFilter)
                    .applySort(filter.sortField, filter.sortDirection)
                    .toDateGroupedUiState()
            }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ReviewUiState.Loading,
        )

    // ── Package labels ────────────────────────────────────────────────────────
    // Labels are resolved once per package on Dispatchers.IO and cached.
    // ConcurrentHashMap ensures safe concurrent reads and writes from IO threads.

    private val labelCache = ConcurrentHashMap<String, String>()
    private val _packageLabels = MutableStateFlow<Map<String, String>>(emptyMap())
    val packageLabels: StateFlow<Map<String, String>> = _packageLabels.asStateFlow()

    private val iconCache = ConcurrentHashMap<String, ImageBitmap>()
    private val _appIcons = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val appIcons: StateFlow<Map<String, ImageBitmap>> = _appIcons.asStateFlow()

    // ── Category overrides ────────────────────────────────────────────────────

    val categoryOverrides: StateFlow<Map<String, String>> = overrideDao.getAllFlow()
        .map { list -> list.associate { it.packageName to it.defaultCategory } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap(),
        )

    // ── Managed-apps count ────────────────────────────────────────────────────
    // Drives the feed footer ("ZiG is currently monitoring X apps…"). Reflects the
    // number of apps the user has opted into filtering.

    val managedAppCount: StateFlow<Int> = managedAppDao.getAll()
        .map { it.size }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0,
        )

    // ── Daily Review call-to-action ───────────────────────────────────────────
    // Number of deduplicated cards in today's training deck. Drives the CTA banner (inbox) and
    // the Train-screen button; both hide when this is 0.

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val todayReviewCount: StateFlow<Int> = todayStartFlow
        .flatMapLatest { startOfDay -> dao.countTodayAiUndecidedFlow(startOfDay) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0,
        )

    // ── Init: resolve labels for any packages that appear in either list ──────

    init {
        viewModelScope.launch {
            merge(uiState, archiveUiState).collect { state ->
                when (state) {
                    is ReviewUiState.Content -> resolveLabelsIfNeeded(state.groups.keys)
                    is ReviewUiState.DateGroupedContent -> {
                        val packages = state.dateGroups.flatMap { it.appGroups.keys }.toSet()
                        resolveLabelsIfNeeded(packages)
                    }
                    else -> {}
                }
            }
        }
    }

    private suspend fun resolveLabelsIfNeeded(packageNames: Set<String>) {
        val toResolve = packageNames.filter { !labelCache.containsKey(it) }
        if (toResolve.isEmpty()) return
        withContext(Dispatchers.IO) {
            toResolve.forEach { pkg ->
                val label = if (pkg == DemoDataSeeder.DEMO_PACKAGE) {
                    // Synthetic demo package — not installed, so name it explicitly.
                    DemoDataSeeder.DEMO_APP_LABEL
                } else try {
                    val info = context.packageManager.getApplicationInfo(pkg, 0)
                    context.packageManager.getApplicationLabel(info).toString()
                } catch (_: Exception) {
                    pkg.substringAfterLast('.')
                }
                labelCache[pkg] = label

                // For the demo package, load the real ZiG app icon instead.
                val iconPkg = if (pkg == DemoDataSeeder.DEMO_PACKAGE) "dev.zig.notificationfilter" else pkg
                val icon = loadIcon(iconPkg)
                if (icon != null) iconCache[pkg] = icon
            }
        }
        _packageLabels.value = HashMap(labelCache)
        _appIcons.value = HashMap(iconCache)
    }

    // Icons are drawn into a fixed 48×48 px bitmap to bound memory use (same approach as
    // ManagedAppsViewModel). Adaptive icons can produce very large drawables; the Canvas
    // draw bounds them without OOM risk on low-end devices.
    private fun loadIcon(packageName: String): ImageBitmap? = try {
        val drawable = context.packageManager.getApplicationIcon(packageName)
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, 48, 48)
        drawable.draw(canvas)
        bitmap.asImageBitmap()
    } catch (_: Exception) {
        null
    }

    // ── User actions ──────────────────────────────────────────────────────────

    fun onAllowClicked(id: Long) = cascadeDecision(id, ReviewState.ALLOWED, "MANUALLY_ALLOWED")

    fun onBlockAndMuteClicked(id: Long) = cascadeDecision(id, ReviewState.BLOCKED, "MANUALLY_BLOCKED")

    // Cascading state synchronisation: one tap applies the decision to every identical
    // notification (same package/title/content) inside the active 24-hour window via a single
    // bulk UPDATE. The DB Flow re-emits, so all matching cards update at once — no separate
    // in-memory list to keep in sync. Only the tapped row is embedded into Personal Memory so
    // the KNN corpus doesn't accumulate duplicate vectors for the same text.
    private fun cascadeDecision(id: Long, state: ReviewState, overrideStatus: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val row = dao.getById(id) ?: return@launch
            val cutoff = System.currentTimeMillis() - TWENTY_FOUR_HOURS_MS
            dao.cascadeOverride(
                packageName = row.packageName,
                title = row.title,
                content = row.content,
                state = state.name,
                status = overrideStatus,
                cutoff = cutoff,
                tappedId = id,
            )
            // Status written first (by the cascade): rememberOverride reads it back to label
            // the embedding.
            personalMemory.rememberOverride(id)
        }
    }

    fun onUndoClicked(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateReviewState(id, ReviewState.PENDING.name)
            dao.updateOverrideStatus(id, "NONE")
            // Row no longer represents a user decision — drop it from Personal Memory.
            personalMemory.forgetOverride(id)
        }
    }

    // Remembers each swiped row's prior reviewState so Undo restores it exactly, rather than
    // assuming PENDING (a dismissed card may have already been ALLOWED or BLOCKED).
    private val dismissedPriorState = ConcurrentHashMap<Long, ReviewState>()

    // Swipe-to-dismiss (inbox only): hide the row from the active inbox without touching the
    // override/sync state, so it still lives in the archive and creates no training signal.
    fun onDismiss(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val prior = dao.getById(id)?.reviewState ?: ReviewState.PENDING
            dismissedPriorState[id] = prior
            dao.setReviewStateOnly(id, ReviewState.DISMISSED.name)
        }
    }

    fun onRestoreDismissed(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val prior = dismissedPriorState.remove(id) ?: ReviewState.PENDING
            dao.setReviewStateOnly(id, prior.name)
        }
    }

    // Null category clears the app-level override; non-null upserts it.
    fun setCategoryOverride(packageName: String, category: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            if (category == null) {
                overrideDao.delete(packageName)
            } else {
                overrideDao.upsert(AppCategoryOverrideEntity(packageName = packageName, defaultCategory = category))
            }
        }
    }

    // Null category resets the per-notification user assignment back to the inferred value.
    fun setUserAssignedCategory(id: Long, category: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateUserAssignedCategory(id, category)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // Restricts the emitted list to the selected quick-filter chip. Applied before sorting
    // and grouping. ALL is a no-op passthrough.
    private fun List<NotificationReviewEntity>.applyChipFilter(
        chip: ChipFilter,
    ): List<NotificationReviewEntity> = when (chip) {
        ChipFilter.ALL          -> this
        ChipFilter.AI_DECISIONS -> filter { it.systemDecision in AI_DECISION_SET }
        ChipFilter.BLOCKED      -> filter { it.systemDecision in BLOCKED_SET }
    }

    private fun List<NotificationReviewEntity>.applySort(
        sortField: SortField,
        sortDirection: SortDirection,
    ): List<NotificationReviewEntity> {
        val sorted = when (sortField) {
            SortField.TIME     -> sortedBy { it.timestamp }
            SortField.APP_NAME -> sortedBy { it.packageName }
            SortField.STATUS   -> sortedBy { statusSortKey(it.userOverrideStatus) }
        }
        return if (sortDirection == SortDirection.DESC) sorted.reversed() else sorted
    }

    private fun statusSortKey(overrideStatus: String): Int = when (overrideStatus) {
        "MANUALLY_ALLOWED"  -> 0
        "NONE"              -> 1
        "MANUALLY_BLOCKED"  -> 2
        else                -> 1
    }

    private fun NotificationReviewEntity.toUiItem() = NotificationReviewUiItem(
        id = id,
        packageName = packageName,
        title = title,
        content = content,
        timestamp = timestamp,
        systemDecision = systemDecision,
        reviewState = reviewState,
        modelConfidence = modelConfidence,
        inferredCategory = inferredCategory,
        userAssignedCategory = userAssignedCategory,
        userOverrideStatus = userOverrideStatus,
    )

    private fun List<NotificationReviewEntity>.toReviewUiState(): ReviewUiState {
        if (isEmpty()) return ReviewUiState.Empty
        return ReviewUiState.Content(map { it.toUiItem() }.groupBy { it.packageName })
    }

    private fun List<NotificationReviewEntity>.toDateGroupedUiState(): ReviewUiState {
        if (isEmpty()) return ReviewUiState.Empty
        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)
        val yesterday = today.minusDays(1)
        val dateGroups = groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zoneId).toLocalDate() }
            .map { (date, entities) ->
                val label = when (date) {
                    today     -> "Today"
                    yesterday -> "Yesterday"
                    else      -> date.format(DATE_FORMATTER)
                }
                DateGroup(
                    label = label,
                    date = date,
                    appGroups = entities.map { it.toUiItem() }.groupBy { it.packageName },
                )
            }
        return ReviewUiState.DateGroupedContent(dateGroups)
    }
}
