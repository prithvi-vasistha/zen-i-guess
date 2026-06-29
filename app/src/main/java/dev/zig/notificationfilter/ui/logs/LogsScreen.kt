package dev.zig.notificationfilter.ui.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.zig.notificationfilter.data.local.db.NotificationLogEntity
import dev.zig.notificationfilter.ui.theme.ZigGreen
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Pipeline stage colors — used outside Composable scope so they are plain Color constants.
private val COLOR_GREEN = ZigGreen
private val COLOR_RED = Color(0xFFE57373)
private val COLOR_AMBER = Color(0xFFFFB74D)
private val COLOR_BLUE = Color(0xFF64B5F6)
private val COLOR_NEUTRAL = Color(0xFF90A4AE)

private fun statusColor(status: String): Color = when (status) {
    "RECEIVED" -> COLOR_BLUE
    "MANAGED_PASS", "CONTACT_MISS", "KEYWORD_MISS" -> COLOR_NEUTRAL
    "MANAGED_FAIL", "LLM_BLOCKED" -> COLOR_RED
    "CONTACT_PASS", "KEYWORD_PASS", "LLM_ALLOWED", "PUBLISHED" -> COLOR_GREEN
    "LLM_INVOKED" -> COLOR_AMBER
    else -> COLOR_NEUTRAL // legacy single-step rows or unknown future stages
}

private val TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM dd HH:mm:ss")

private fun formatTimestamp(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(TIME_FORMATTER)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(modifier: Modifier = Modifier) {
    val viewModel: LogsViewModel = hiltViewModel()
    val traces by viewModel.traces.collectAsState()
    val query by viewModel.searchQuery.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = "Notification Log",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "Last 500 pipeline events",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = {
                IconButton(onClick = { viewModel.clearLogs() }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear logs",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )

        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.setQuery(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            placeholder = {
                Text(
                    text = "Search — app:, status:, msg: or bare term",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            leadingIcon = {
                Icon(imageVector = Icons.Default.Search, contentDescription = null)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setQuery("") }) {
                        Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            textStyle = MaterialTheme.typography.bodySmall,
        )

        if (traces.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (query.isBlank()) "No logs yet." else "No results for \"$query\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(traces, key = { it.jobId.ifBlank { it.firstTimestamp.toString() } }) { trace ->
                    TraceCard(trace = trace)
                }
            }
        }
    }
}

@Composable
private fun TraceCard(trace: NotificationTrace) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {

            // Header: jobId chip · packageName                       timestamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // jobId monospace chip
                Box(
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (trace.jobId.isBlank()) "legacy" else trace.jobId,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "  ${trace.packageName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatTimestamp(trace.firstTimestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (trace.title.isNotBlank()) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = trace.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trace.content.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = trace.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(6.dp))

            // One row per pipeline stage
            trace.steps.forEach { step ->
                StepRow(step = step)
            }
        }
    }
}

@Composable
private fun StepRow(step: NotificationLogEntity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Fixed-minimum-width status label so detail column aligns across all steps.
        Text(
            text = step.status,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = statusColor(step.status),
            modifier = Modifier.widthIn(min = 116.dp),
            maxLines = 1,
        )
        Text(
            text = step.filterReason,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}
