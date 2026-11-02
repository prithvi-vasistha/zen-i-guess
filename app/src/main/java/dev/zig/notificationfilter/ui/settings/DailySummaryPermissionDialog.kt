package dev.zig.notificationfilter.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
internal fun DailySummaryPermissionDialog(onAllow: () -> Unit, onSkip: () -> Unit, needsTimingAccess: Boolean = true) {
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("Daily Summary timing") },
        text = { Text(if (needsTimingAccess)
            "ZiG needs Alarms & reminders access to send your daily recap at your chosen time while your phone is asleep. You can skip this and enable it later in Settings."
            else "Send a daily recap at your chosen time, even while your phone is asleep. You can skip this and enable it later in Settings.") },
        confirmButton = { TextButton(onClick = onAllow) { Text("Enable reminders") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("Skip for now") } },
    )
}
