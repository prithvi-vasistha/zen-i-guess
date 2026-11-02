package dev.zig.notificationfilter.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Optional guidance; opening Android settings never implies that a setting was enabled. */
@Composable
internal fun NotificationDisplayDialog(
    onOpenAlerts: () -> Unit,
    onOpenPhoneSettings: () -> Unit,
    onDismiss: () -> Unit,
    doneLabel: String = "Done",
) {
    val device = remember { "${Build.MANUFACTURER} ${Build.BRAND}".lowercase(Locale.ROOT) }
    val (phoneTitle, instructions) = when {
        "samsung" in device -> "On Samsung" to
            "In Settings, look for Brief pop-ups or Lighting effect. Include ZiG and allow pop-ups while the screen is off. Also check your Always On Display options."
        "google" in device -> "On Pixel" to
            "Open your phone's Lock screen settings and turn on Wake screen for notifications. Allow ZiG notifications on the lock screen."
        "nothing" in device || "cmf" in device -> "On Nothing / CMF" to
            "Open Settings → Notifications → Notifications on lock screen and allow notifications. Check your phone's Always On Display and wake-screen options where available."
        else -> "On your phone" to
            "Allow ZiG notifications on the lock screen, then check your phone's Always On Display or screen-off notification options. Menu names vary by phone."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pop-ups & lock screen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Enable pop-ups and lock-screen notifications for ZiG's Important Alerts in Android settings.")
                Text(phoneTitle, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(instructions)
                Text("Your phone controls when the screen or AOD lights up. Message previews follow your lock-screen privacy settings.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onOpenPhoneSettings) { Text("Phone settings") }
            }
        },
        confirmButton = { TextButton(onClick = onOpenAlerts) { Text("ZiG alerts") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(doneLabel) } },
    )
}
