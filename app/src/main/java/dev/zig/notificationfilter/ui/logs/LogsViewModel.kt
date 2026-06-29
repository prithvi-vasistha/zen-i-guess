package dev.zig.notificationfilter.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.zig.notificationfilter.data.local.db.NotificationLogDao
import dev.zig.notificationfilter.data.local.db.NotificationLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

    val searchQuery = MutableStateFlow("")

    private val allTraces = dao.getRecentLogs()
        .map { logs ->
            logs
                .groupBy { it.jobId }
                .values
                .map { steps ->
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
                .sortedByDescending { it.firstTimestamp }
        }

    val traces: StateFlow<List<NotificationTrace>> = combine(allTraces, searchQuery) { traces, query ->
        if (query.isBlank()) traces else filterTraces(traces, query.trim())
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun setQuery(query: String) { searchQuery.value = query }

    fun clearLogs() {
        viewModelScope.launch(Dispatchers.IO) { dao.deleteAll() }
    }

    private fun filterTraces(traces: List<NotificationTrace>, query: String): List<NotificationTrace> {
        // Prefix syntax: "app:", "status:", "msg:" narrow to a specific field.
        // Bare terms search all fields simultaneously (grep-style).
        val term = query.lowercase()
        val (prefix, value) = when {
            term.startsWith("app:") -> "app" to term.removePrefix("app:")
            term.startsWith("status:") -> "status" to term.removePrefix("status:")
            term.startsWith("msg:") -> "msg" to term.removePrefix("msg:")
            else -> "" to term
        }
        if (value.isBlank()) return traces

        return traces.filter { trace ->
            when (prefix) {
                "app" -> trace.packageName.lowercase().contains(value)
                "status" -> trace.steps.any { it.status.lowercase().contains(value) }
                "msg" -> trace.title.lowercase().contains(value) ||
                    trace.content.lowercase().contains(value)
                else -> trace.packageName.lowercase().contains(value) ||
                    trace.title.lowercase().contains(value) ||
                    trace.content.lowercase().contains(value) ||
                    trace.steps.any {
                        it.status.lowercase().contains(value) ||
                            it.filterReason.lowercase().contains(value)
                    }
            }
        }
    }
}
