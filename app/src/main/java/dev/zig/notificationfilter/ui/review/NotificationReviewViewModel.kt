package dev.zig.notificationfilter.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    // Groups keyed by packageName, ordered by most-recent notification per group.
    // groupBy() preserves insertion order — DAO returns timestamp DESC so the app
    // with the newest notification always appears first.
    data class Content(val groups: Map<String, List<NotificationReviewUiItem>>) : ReviewUiState
}

@HiltViewModel
class NotificationReviewViewModel @Inject constructor(
    private val dao: NotificationReviewDao,
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

    fun setSortBy(sortBy: SortBy) {
        _filter.value = _filter.value.copy(sortBy = sortBy)
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
            .map { list -> list.applySort(filter.sortBy).toReviewUiState() }
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
            .map { list -> list.applySort(filter.sortBy).toReviewUiState() }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ReviewUiState.Loading,
        )

    // ── User actions ──────────────────────────────────────────────────────────

    fun onAllowClicked(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateReviewState(id, ReviewState.ALLOWED.name)
            dao.updateOverrideStatus(id, "MANUALLY_ALLOWED")
        }
    }

    fun onBlockClicked(id: Long) {
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun List<NotificationReviewEntity>.applySort(sortBy: SortBy): List<NotificationReviewEntity> =
        when (sortBy) {
            SortBy.TIME_DESC -> sortedByDescending { it.timestamp }
            SortBy.TIME_ASC  -> sortedBy { it.timestamp }
            SortBy.APP_NAME  -> sortedBy { it.packageName }
            SortBy.STATUS    -> sortedBy { statusSortKey(it.userOverrideStatus) }
        }

    // MANUALLY_ALLOWED (user unblocked) → 0, NONE (model decision) → 1, MANUALLY_BLOCKED → 2
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
