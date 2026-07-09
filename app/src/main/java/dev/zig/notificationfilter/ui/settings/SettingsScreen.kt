package dev.zig.notificationfilter.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import dev.zig.notificationfilter.ui.theme.ZigGreen
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val dailySummaryEnabled by viewModel.dailySummaryEnabled.collectAsState()
    val dailySummaryHour by viewModel.dailySummaryHour.collectAsState()
    val dailySummaryMinute by viewModel.dailySummaryMinute.collectAsState()
    val sensitiveNotificationsEnabled by viewModel.sensitiveNotificationsEnabled.collectAsState()
    val contactsBypassEnabled by viewModel.contactsBypassEnabled.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val showClearMemoryDialog by viewModel.showClearMemoryDialog.collectAsState()

    val context = LocalContext.current

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.setContactsBypassEnabled(true)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportTo) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importFrom) }

    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showDailySummaryDetail by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Settings",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = "Configure ZiG's behaviour",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    SettingsSectionLabel("Notifications")
                }
                item {
                    DailySummarySettingRow(
                        checked = dailySummaryEnabled,
                        hour = dailySummaryHour,
                        minute = dailySummaryMinute,
                        onToggle = viewModel::setDailySummaryEnabled,
                        onRowClick = { showDailySummaryDetail = true },
                    )
                }
                item {
                    SettingsToggleRow(
                        title = "Sensitive notifications",
                        subtitle = "Show instantly on lock screen (skip filtering)",
                        checked = sensitiveNotificationsEnabled,
                        onCheckedChange = viewModel::setSensitiveNotificationsEnabled,
                    )
                }
                item {
                    SettingsToggleRow(
                        title = "Smart Contact Bypass",
                        subtitle = "Automatically allow notifications from your contacts",
                        checked = contactsBypassEnabled,
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                viewModel.setContactsBypassEnabled(false)
                            } else {
                                val hasPermission = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.READ_CONTACTS,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasPermission) {
                                    viewModel.setContactsBypassEnabled(true)
                                } else {
                                    contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                                }
                            }
                        },
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    SettingsSectionLabel("Backup")
                }
                item {
                    SettingsActionRow(
                        title = "Export backup",
                        subtitle = "Save settings & learned decisions to a file",
                        icon = Icons.Default.Lock,
                        enabled = !isBusy,
                        onClick = { exportLauncher.launch("zig-backup.json") },
                    )
                }
                item {
                    SettingsActionRow(
                        title = "Restore backup",
                        subtitle = "Import settings & decisions from a file",
                        icon = Icons.Default.Refresh,
                        enabled = !isBusy,
                        onClick = { showRestoreConfirm = true },
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    SettingsSectionLabel("AI Data")
                }
                item {
                    SettingsDestructiveActionRow(
                        title = "Clear AI Memory",
                        subtitle = "Reset all learned notification preferences",
                        onClick = viewModel::requestClearAiMemory,
                    )
                }
            }

            if (isBusy) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    if (showClearMemoryDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClearMemoryDialog,
            title = { Text("Clear AI Memory?") },
            text = {
                Text(
                    "This will permanently delete all your trained AI decisions and reset the " +
                        "filter to its default state. Your Managed Apps and Custom Rules Vault " +
                        "will not be affected. This action cannot be undone.",
                )
            },
            confirmButton = {
                Button(
                    onClick = viewModel::clearAiMemory,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Clear Memory") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissClearMemoryDialog) { Text("Cancel") }
            },
        )
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("Restore from backup?") },
            text = {
                Text(
                    "This overwrites your current settings and replaces any previously " +
                        "restored decisions. Your existing notification history is left untouched.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreConfirm = false
                        importLauncher.launch(arrayOf("application/json"))
                    },
                ) { Text("Choose file") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (showDailySummaryDetail) {
        DailySummaryDetailScreen(
            enabled = dailySummaryEnabled,
            hour = dailySummaryHour,
            minute = dailySummaryMinute,
            onBack = { showDailySummaryDetail = false },
            onToggle = viewModel::setDailySummaryEnabled,
            onTimeConfirm = { h, m ->
                viewModel.setSummaryTime(h, m)
                showDailySummaryDetail = false
            },
        )
    }
}

// Full-screen dialog that owns both the enable toggle and the clock picker.
// The toggle takes effect immediately; the clock is confirmed with "Save time" which
// also closes the screen. The back button closes without saving a pending time change.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DailySummaryDetailScreen(
    enabled: Boolean,
    hour: Int,
    minute: Int,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onTimeConfirm: (Int, Int) -> Unit,
) {
    val timePickerState = rememberTimePickerState(
        initialHour = hour,
        initialMinute = minute,
        is24Hour = false,
    )

    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Daily Summary") },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                )
                            }
                        },
                    )
                },
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(modifier = Modifier.height(8.dp))

                    SettingsToggleRow(
                        title = "Enable",
                        subtitle = if (enabled) "Daily recap notification is active"
                                   else "Daily recap notification is paused",
                        checked = enabled,
                        onCheckedChange = onToggle,
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    SettingsSectionLabel("Notification time")

                    Spacer(modifier = Modifier.height(16.dp))

                    TimePicker(state = timePickerState)

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { onTimeConfirm(timePickerState.hour, timePickerState.minute) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Save time")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = ZigGreen,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

// Tapping the Switch directly toggles the feature on/off (onToggle).
// Tapping anywhere else on the row opens the detail screen (onRowClick).
// Compose routes the events correctly: Switch consumes its own pointer event so
// the Surface's onClick does not fire when the thumb is tapped.
@Composable
private fun DailySummarySettingRow(
    checked: Boolean,
    hour: Int,
    minute: Int,
    onToggle: (Boolean) -> Unit,
    onRowClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeLabel = remember(hour, minute) {
        val formatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
        LocalTime.of(hour, minute).format(formatter)
    }
    Surface(
        onClick = onRowClick,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        color = ZigGreen.copy(alpha = 0.08f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Daily Summary",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Daily recap at $timeLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onToggle,
            )
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        color = ZigGreen.copy(alpha = 0.08f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        color = ZigGreen.copy(alpha = if (enabled) 0.08f else 0.04f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ZigGreen,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsDestructiveActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val errorColor = MaterialTheme.colorScheme.error
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        color = errorColor.copy(alpha = 0.08f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = null,
                tint = errorColor,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = errorColor,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
