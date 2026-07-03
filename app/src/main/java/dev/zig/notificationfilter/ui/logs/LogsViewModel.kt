package dev.zig.notificationfilter.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.zig.notificationfilter.data.local.db.NotificationLogDao
import dev.zig.notificationfilter.data.local.db.NotificationLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private companion object {
        const val RECENT_LIMIT = 500
        const val SEARCH_DEBOUNCE_MS = 300L
    }

    val searchQuery = MutableStateFlow("")

    private val _isSearchingDb = MutableStateFlow(false)
    val isSearchingDb: StateFlow<Boolean> = _isSearchingDb.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val traces: StateFlow<List<NotificationTrace>> = searchQuery
        .flatMapLatest { rawQuery ->
            if (rawQuery.isBlank()) {
                // Live view: Room re-emits on every insert/delete, giving real-time updates.
                dao.getRecent(RECENT_LIMIT).map { logs -> buildTraces(logs) }
            } else {
                // Search: one-shot against a recent snapshot (phase 1), then falls back to
                // a full DB search (phase 2) when phase 1 finds nothing. Not reactive to new
                // inserts while the query is active — clear the field to resume live view.
                flow {
                    emit(emptyList())
                    val recent = withContext(Dispatchers.IO) { dao.getRecentSnapshot(RECENT_LIMIT) }
                    val phase1 = filterTraces(buildTraces(recent), rawQuery.trim())
                    if (phase1.isNotEmpty()) {
                        emit(phase1)
                    } else {
                        try {
                            _isSearchingDb.value = true
                            delay(SEARCH_DEBOUNCE_MS)
                            val value = extractSearchValue(rawQuery)
                            val jobIds = if (value.isNotBlank()) {
                                withContext(Dispatchers.IO) { dao.searchMatchingJobIds(value) }
                            } else emptyList()
                            val logs = if (jobIds.isNotEmpty()) {
                                withContext(Dispatchers.IO) { dao.getLogsForJobs(jobIds) }
                            } else emptyList()
                            emit(filterTraces(buildTraces(logs), rawQuery.trim()))
                        } finally {
                            _isSearchingDb.value = false
                        }
                    }
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun setQuery(query: String) { searchQuery.value = query }

    fun clearLogs() {
        viewModelScope.launch(Dispatchers.IO) { dao.deleteAll() }
    }

    private fun buildTraces(logs: List<NotificationLogEntity>): List<NotificationTrace> =
        logs.groupBy { it.jobId }
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

    private fun extractSearchValue(query: String): String {
        val term = query.lowercase()
        return when {
            term.startsWith("app:") -> term.removePrefix("app:")
            term.startsWith("status:") -> term.removePrefix("status:")
            term.startsWith("msg:") -> term.removePrefix("msg:")
            else -> term
        }
    }

    private fun filterTraces(traces: List<NotificationTrace>, query: String): List<NotificationTrace> {
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
