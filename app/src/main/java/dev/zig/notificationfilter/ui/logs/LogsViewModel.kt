package dev.zig.notificationfilter.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.zig.notificationfilter.data.local.db.NotificationLogDao
import dev.zig.notificationfilter.data.local.db.NotificationLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotificationTrace(
    val jobId: String,
    val packageName: String,
    val title: String,
    val content: String,
    val firstTimestamp: Long,
    val steps: List<NotificationLogEntity>,
)

@HiltViewModel
class LogsViewModel @Inject constructor(
    private val dao: NotificationLogDao,
) : ViewModel() {

    val traces: StateFlow<List<NotificationTrace>> = dao.getRecentLogs()
        .map { logs ->
            logs
                .groupBy { it.jobId }
                .values
                .map { steps ->
                    // Steps from the query are DESC; sort ASC within a trace so the
                    // pipeline reads top-to-bottom: RECEIVED → … → PUBLISHED/BLOCKED.
                    val ordered = steps.sortedBy { it.timestamp }
                    NotificationTrace(
                        jobId = ordered.first().jobId,
                        packageName = ordered.first().packageName,
                        title = ordered.first().title,
                        content = ordered.first().content,
                        firstTimestamp = ordered.first().timestamp,
                        steps = ordered,
                    )
                }
                // Most-recently-received traces first.
                .sortedByDescending { it.firstTimestamp }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun clearLogs() {
        viewModelScope.launch(Dispatchers.IO) { dao.deleteAll() }
    }
}
