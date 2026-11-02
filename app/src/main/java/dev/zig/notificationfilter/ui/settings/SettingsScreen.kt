package dev.zig.notificationfilter.ui.settings

import dev.zig.notificationfilter.core.errors.AppErrors
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
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
import dev.zig.notificationfilter.ui.common.SafeLaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import dev.zig.notificationfilter.ui.theme.ZigFullscreenDialog
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import dev.zig.notificationfilter.R
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import dev.zig.notificationfilter.domain.summary.DailySummaryState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val dailySummaryEnabled by viewModel.dailySummaryEnabled.collectAsState()
    val dailySummaryHour by viewModel.dailySummaryHour.collectAsState()
    val dailySummaryMinute by viewModel.dailySummaryMinute.collectAsState()
    val dailySummaryState by viewModel.dailySummaryState.collectAsState()
    val sensitiveNotificationsEnabled by viewModel.sensitiveNotificationsEnabled.collectAsState()
    val hiddenContentAlertsEnabled by viewModel.hiddenContentAlertsEnabled.collectAsState()
    val contactsBypassEnabled by viewModel.contactsBypassEnabled.collectAsState()
    val imageNotificationsEnabled by viewModel.imageNotificationsEnabled.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val showClearMemoryDialog by viewModel.showClearMemoryDialog.collectAsState()
    val appearance by viewModel.appearance.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshDailySummary()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.refreshDailySummary()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val context = LocalContext.current
    var showSummaryPermissionDialog by rememberSaveable { mutableStateOf(false) }
    val summaryPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onSummaryPermissionReturned()
    }
    val onSummaryToggle: (Boolean) -> Unit = { enabled ->
        if (!viewModel.setDailySummaryEnabled(enabled)) showSummaryPermissionDialog = true
    }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.setContactsBypassEnabled(true)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    SafeLaunchedEffect(Unit) {
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
    var showAppearance by rememberSaveable { mutableStateOf(false) }
    var showNotificationDisplay by rememberSaveable { mutableStateOf(false) }

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
                item { SettingsSectionLabel("Appearance") }
                item {
                    SettingsActionRow(
                        title = "Theme & accent colour",
                        subtitle = "${appearance.mode.label} · ${appearance.style.label} · ${appearance.accent.label}",
                        icon = ImageVector.vectorResource(R.drawable.ic_palette),
                        onClick = { showAppearance = true },
                    )
                }
                item {
                    SettingsSectionLabel("Notifications")
                }
                item {
                    SettingsActionRow(
                        title = "Pop-ups & lock screen",
                        subtitle = "Choose how alerts appear when your phone is locked",
                        icon = Icons.Default.Notifications,
                        onClick = { showNotificationDisplay = true },
                    )
                }
                item {
                    DailySummarySettingRow(
                        checked = dailySummaryEnabled,
                        hour = dailySummaryHour,
                        minute = dailySummaryMinute,
                        onToggle = onSummaryToggle,
                        onRowClick = { showDailySummaryDetail = true },
                    )
                }
                item {
                    SettingsToggleRow(
                        title = "Sensitive notifications",
                        subtitle = "Skip text filtering on lock screen; image setting still applies",
                        checked = sensitiveNotificationsEnabled,
                        onCheckedChange = viewModel::setSensitiveNotificationsEnabled,
                    )
                }
                item {
                    SettingsToggleRow(
                        title = "Alert for hidden content",
                        subtitle = "Alert when Android hides a managed app's message, even with images off. Open the original app to read it.",
                        checked = hiddenContentAlertsEnabled,
                        onCheckedChange = viewModel::setHiddenContentAlertsEnabled,
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
                                    AppErrors.run("Requesting contact access") { contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS) }
                                }
                            }
                        },
                    )
                }
                item {
                    SettingsToggleRow(
                        title = "Allow image notifications",
                        subtitle = "When off, block images except from matched contacts",
                        checked = imageNotificationsEnabled,
                        onCheckedChange = viewModel::setImageNotificationsEnabled,
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
                        onClick = { AppErrors.run("Choosing backup destination") { exportLauncher.launch("zig-backup.json") } },
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
                    "This applies the backup's settings and adds its saved decisions. " +
                        "Your existing notification history and learned decisions are kept.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreConfirm = false
                        AppErrors.run("Choosing backup file") { importLauncher.launch(arrayOf("application/json")) }
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
            onToggle = onSummaryToggle,
            summaryState = dailySummaryState,
            onOpenChannel = { viewModel.openSummaryChannel() },
            onTimeConfirm = { h, m ->
                viewModel.setSummaryTime(h, m)
                showDailySummaryDetail = false
            },
        )
    }

    if (showNotificationDisplay) {
        NotificationDisplayDialog(
            onOpenAlerts = { viewModel.openAlertDisplaySettings() },
            onOpenPhoneSettings = { viewModel.openPhoneSettings() },
            onDismiss = { showNotificationDisplay = false },
        )
    }

    if (showAppearance) {
        AppearanceScreen(
            appearance = appearance,
            onModeChange = viewModel::setThemeMode,
            onStyleChange = viewModel::setThemeStyle,
            onAccentChange = viewModel::setAccentColor,
            onReset = viewModel::resetAppearance,
            onBack = { showAppearance = false },
        )
    }

    // Compose this after the detail dialog so it remains visible above that window.
    if (showSummaryPermissionDialog) {
        DailySummaryPermissionDialog(
            onAllow = {
                showSummaryPermissionDialog = false
                viewModel.requestSummaryAccess()?.let { intent ->
                    try { summaryPermissionLauncher.launch(intent) }
                    catch (failure: RuntimeException) {
                        AppErrors.report("Opening permission settings", failure)
                        viewModel.skipSummaryAccess()
                    }
                }
            },
            onSkip = { showSummaryPermissionDialog = false; viewModel.skipSummaryAccess() },
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
    summaryState: DailySummaryState,
    onOpenChannel: () -> Unit,
) {
    val timePickerState = rememberTimePickerState(
        initialHour = hour,
        initialMinute = minute,
        is24Hour = false,
    )

    ZigFullscreenDialog(onDismissRequest = onBack) {
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
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState()),
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

                    Text(
                        if (summaryState.timingAccess) "Timing access granted. The recap can arrive while your phone is asleep."
                        else "Daily Summary stays off until Alarms & reminders access is allowed.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    if (summaryState.lastStatus.isNotBlank()) {
                        Text("Last summary: ${summaryState.lastStatus}",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        if (summaryState.lastDetail.isNotBlank()) Text(summaryState.lastDetail,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = onOpenChannel) { Text("Summary notification settings") }

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
        color = MaterialTheme.colorScheme.primary,
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
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
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
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
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
        color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.08f else 0.04f),
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
                tint = MaterialTheme.colorScheme.primary,
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
