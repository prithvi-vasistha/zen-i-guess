package dev.zig.notificationfilter.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zig.notificationfilter.core.errors.AppErrors
import dev.zig.notificationfilter.core.errors.ErrorReporter

@Composable
fun AppErrorDialog(reporter: ErrorReporter = AppErrors) {
    val pending by reporter.pending.collectAsStateWithLifecycle()
    val error = pending.firstOrNull() ?: return
    AlertDialog(
        onDismissRequest = { reporter.dismiss(error.id) },
        title = { Text("Something went wrong") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(error.operation, style = MaterialTheme.typography.titleSmall)
                SelectionContainer { Text(error.detail, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = { reporter.dismiss(error.id) }) { Text("OK") } },
    )
}
