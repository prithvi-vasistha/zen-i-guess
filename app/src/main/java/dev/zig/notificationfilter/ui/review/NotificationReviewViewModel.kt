package dev.zig.notificationfilter.ui.review

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.zig.notificationfilter.data.local.db.AppCategoryOverrideDao
import dev.zig.notificationfilter.data.local.db.AppCategoryOverrideEntity
import dev.zig.notificationfilter.data.local.db.NotificationReviewDao
import dev.zig.notificationfilter.data.local.db.NotificationReviewEntity
import dev.zig.notificationfilter.data.local.db.ReviewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
}

@HiltViewModel
class NotificationReviewViewModel @Inject constructor(
    private val dao: NotificationReviewDao,
    private val overrideDao: AppCategoryOverrideDao,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private companion object {
        private const val TWENTY_FOUR_HOURS_MS = 24L * 60L * 60L * 1_000L
        private const val CUTOFF_REFRESH_INTERVAL_MS = 60L * 60L * 1_000L
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

    // ── Cutoff ticker (shared between active and archive) ─────────────────────

    private val cutoffFlow = flow {
        while (true) {
            emit(System.currentTimeMillis() - TWENTY_FOUR_HOURS_MS)
            delay(CUTOFF_REFRESH_INTERVAL_MS)
        }
    }

    // ── Active inbox ──────────────────────────────────────────────────────────

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ReviewUiState> = combine(cutoffFlow, _filter) { cutoff, filter ->
        Pair(cutoff, filter)
    }.flatMapLatest { (cutoff, filter) ->
        dao.searchActiveFlow(cutoff, filter.query)
            .map { list -> list.applySort(filter.sortField, filter.sortDirection).toReviewUiState() }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ReviewUiState.Loading,
        )

    // ── Archive ───────────────────────────────────────────────────────────────

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val archiveUiState: StateFlow<ReviewUiState> = combine(cutoffFlow, _filter) { cutoff, filter ->
        Pair(cutoff, filter)
    }.flatMapLatest { (cutoff, filter) ->
        dao.searchArchiveFlow(cutoff, filter.query)
            .map { list -> list.applySort(filter.sortField, filter.sortDirection).toReviewUiState() }
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

    // ── Category overrides ────────────────────────────────────────────────────

    val categoryOverrides: StateFlow<Map<String, String>> = overrideDao.getAllFlow()
        .map { list -> list.associate { it.packageName to it.defaultCategory } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap(),
        )

    // ── Init: resolve labels for any packages that appear in either list ──────

    init {
        viewModelScope.launch {
            merge(uiState, archiveUiState).collect { state ->
                if (state is ReviewUiState.Content) {
                    resolveLabelsIfNeeded(state.groups.keys)
                }
            }
        }
    }

    private suspend fun resolveLabelsIfNeeded(packageNames: Set<String>) {
        val toResolve = packageNames.filter { !labelCache.containsKey(it) }
        if (toResolve.isEmpty()) return
        withContext(Dispatchers.IO) {
            toResolve.forEach { pkg ->
                val label = try {
                    val info = context.packageManager.getApplicationInfo(pkg, 0)
                    context.packageManager.getApplicationLabel(info).toString()
                } catch (_: Exception) {
                    pkg.substringAfterLast('.')
                }
                labelCache[pkg] = label
            }
        }
        _packageLabels.value = HashMap(labelCache)
    }

    // ── User actions ──────────────────────────────────────────────────────────

    fun onAllowClicked(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateReviewState(id, ReviewState.ALLOWED.name)
            dao.updateOverrideStatus(id, "MANUALLY_ALLOWED")
        }
    }

    fun onBlockAndMuteClicked(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateReviewState(id, ReviewState.BLOCKED.name)
            dao.updateOverrideStatus(id, "MANUALLY_BLOCKED")
        }
    }

    fun onUndoClicked(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateReviewState(id, ReviewState.PENDING.name)
            dao.updateOverrideStatus(id, "NONE")
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

    private fun List<NotificationReviewEntity>.toReviewUiState(): ReviewUiState {
        if (isEmpty()) return ReviewUiState.Empty
        val items = map { entity ->
            NotificationReviewUiItem(
                id = entity.id,
                packageName = entity.packageName,
                title = entity.title,
                content = entity.content,
                timestamp = entity.timestamp,
                systemDecision = entity.systemDecision,
                reviewState = entity.reviewState,
                modelConfidence = entity.modelConfidence,
                inferredCategory = entity.inferredCategory,
                userAssignedCategory = entity.userAssignedCategory,
                userOverrideStatus = entity.userOverrideStatus,
            )
        }
        return ReviewUiState.Content(items.groupBy { it.packageName })
    }
}
