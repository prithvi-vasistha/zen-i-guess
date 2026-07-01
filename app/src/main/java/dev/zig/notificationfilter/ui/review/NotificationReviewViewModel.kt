package dev.zig.notificationfilter.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.zig.notificationfilter.data.local.db.NotificationReviewDao
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
        // Re-subscribe to the DAO every hour so stale items age out of the window
        // without requiring a new notification to trigger a DB emission.
        private const val CUTOFF_REFRESH_INTERVAL_MS = 60L * 60L * 1_000L
    }

    val uiState: StateFlow<ReviewUiState> = flow {
            while (true) {
                emit(System.currentTimeMillis() - TWENTY_FOUR_HOURS_MS)
                delay(CUTOFF_REFRESH_INTERVAL_MS)
            }
        }
        .flatMapLatest { cutoff -> dao.getFilteredNotificationsFlow(cutoff) }
        .map { entities ->
            if (entities.isEmpty()) {
                ReviewUiState.Empty
            } else {
                val items = entities.map { entity ->
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
                ReviewUiState.Content(items.groupBy { it.packageName })
            }
        }
        // Push the entity→UiItem mapping and groupBy off the main thread.
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
}
