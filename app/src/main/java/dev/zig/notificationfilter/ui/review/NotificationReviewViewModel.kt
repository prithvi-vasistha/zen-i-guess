package dev.zig.notificationfilter.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.zig.notificationfilter.data.local.db.NotificationReviewDao
import dev.zig.notificationfilter.data.local.db.NotificationReviewEntity
import dev.zig.notificationfilter.data.local.db.ReviewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
)

sealed interface ReviewUiState {
    data object Loading : ReviewUiState
    data object Empty : ReviewUiState
    // Groups are keyed by packageName, ordered by the most recent notification per group.
    // groupBy() preserves insertion order, so the DAO's timestamp DESC ordering means
    // the app with the newest suppressed notification appears first.
    data class Content(val groups: Map<String, List<NotificationReviewUiItem>>) : ReviewUiState
}

@HiltViewModel
class NotificationReviewViewModel @Inject constructor(
    private val dao: NotificationReviewDao,
) : ViewModel() {

    private companion object {
        private const val TWENTY_FOUR_HOURS_MS = 24L * 60L * 60L * 1_000L
        // Re-subscribe to the DAO every hour so items age out of the active window
        // without requiring a new DB write to trigger re-emission.
        private const val CUTOFF_REFRESH_INTERVAL_MS = 60L * 60L * 1_000L
    }

    // Single ticker shared by both uiState and archiveUiState — they always operate
    // on the same boundary so active and archive are always complementary and never overlap.
    private val cutoffFlow = flow {
        while (true) {
            emit(System.currentTimeMillis() - TWENTY_FOUR_HOURS_MS)
            delay(CUTOFF_REFRESH_INTERVAL_MS)
        }
    }

    // Active inbox: LLM-blocked notifications within the last 24 hours.
    val uiState: StateFlow<ReviewUiState> = cutoffFlow
        .flatMapLatest { cutoff -> dao.getFilteredNotificationsFlow(cutoff) }
        .map { it.toReviewUiState() }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ReviewUiState.Loading,
        )

    // Archive: LLM-blocked notifications older than 24 hours.
    val archiveUiState: StateFlow<ReviewUiState> = cutoffFlow
        .flatMapLatest { cutoff -> dao.getArchivedNotificationsFlow(cutoff) }
        .map { it.toReviewUiState() }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ReviewUiState.Loading,
        )

    fun onAllowClicked(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateReviewState(id, ReviewState.ALLOWED.name)
        }
    }

    fun onBlockClicked(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateReviewState(id, ReviewState.BLOCKED.name)
        }
    }

    fun onUndoClicked(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.updateReviewState(id, ReviewState.PENDING.name)
        }
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
            )
        }
        return ReviewUiState.Content(items.groupBy { it.packageName })
    }
}
