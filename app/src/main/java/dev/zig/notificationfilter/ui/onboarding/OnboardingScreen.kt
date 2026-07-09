package dev.zig.notificationfilter.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zig.notificationfilter.ui.theme.ZigGreen
import dev.zig.notificationfilter.ui.theme.ZigOnGreen

// ── Step metadata ─────────────────────────────────────────────────────────────

private data class StepDefinition(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

private val STEP_DEFINITIONS = listOf(
    StepDefinition(
        title = "Grant Core Access",
        subtitle = "Notification permission + Listener access",
        icon = Icons.Default.Notifications,
    ),
    StepDefinition(
        title = "Smart Contact Bypass",
        subtitle = "Auto-allow notifications from your contacts",
        icon = Icons.Default.Person,
    ),
    StepDefinition(
        title = "Add Your First App",
        subtitle = "Choose which app ZiG should monitor",
        icon = Icons.Default.Add,
    ),
    StepDefinition(
        title = "Configure Notification Sound",
        subtitle = "Set the selected app to silent to avoid duplicate alert sounds",
        icon = Icons.Default.Settings,
    ),
)

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onCompleted: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val steps by viewModel.steps.collectAsStateWithLifecycle()
    val allComplete by viewModel.allComplete.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val selectedAppPackage by viewModel.selectedAppPackage.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showCoreRationaleDialog by remember { mutableStateOf(false) }
    var showContactsDialog by remember { mutableStateOf(false) }
    var showAppSheet by remember { mutableStateOf(false) }

    // Tracks whether the user has tapped Step 4 and gone to system settings.
    // On the next ON_RESUME, the step is marked complete.
    var silenceStepTapped by remember { mutableStateOf(false) }

    // ── Step 1: permission launchers ──────────────────────────────────────────
    val postNotifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    val onStep1Tap: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val alreadyGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (alreadyGranted) {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } else {
                postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    // Detects Step 1 completion (NLS + POST_NOTIFICATIONS both granted) on every resume.
    // Also detects Step 4 completion — fires after the user returns from app notification settings.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Step 1
                val nlsGranted = Settings.Secure.getString(
                    context.contentResolver, "enabled_notification_listeners",
                )?.contains(context.packageName) == true

                val postGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED

                if (nlsGranted && postGranted) viewModel.onCoreAccessGranted()

                // Step 4 — only complete if the user actually tapped the card and went to settings
                if (silenceStepTapped) {
                    viewModel.onSilenceStepReturned()
                    silenceStepTapped = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── Step 2: contacts launcher ─────────────────────────────────────────────
    val contactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onContactsBypassResolved(granted)
    }

    // ── Step 4: silence action ────────────────────────────────────────────────
    val onStep4Tap: () -> Unit = {
        val pkg = selectedAppPackage
        if (pkg != null) {
            silenceStepTapped = true
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, pkg),
            )
        }
    }

    // ── Step action dispatch ───────────────────────────────────────────────────
    val stepActions: List<() -> Unit> = listOf(
        { showCoreRationaleDialog = true },
        { showContactsDialog = true },
        { showAppSheet = true },
        onStep4Tap,
    )

    // ── Root UI ────────────────────────────────────────────────────────────────
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            // Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 40.dp, bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = CircleShape,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = null,
                        tint = ZigGreen,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Welcome to ZiG",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Setup takes less than 30 seconds.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Step cards
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                steps.forEachIndexed { index, stepState ->
                    OnboardingStepCard(
                        stepNumber = index + 1,
                        definition = STEP_DEFINITIONS[index],
                        state = stepState,
                        onClick = stepActions[index],
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // "Enter ZiG" button — slides in once all four steps are complete.
            AnimatedVisibility(
                visible = allComplete,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider()
                    Button(
                        onClick = onCompleted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ZigGreen,
                            contentColor = ZigOnGreen,
                        ),
                    ) {
                        Text("Enter ZiG", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    // ── Overlay dialogs / sheets ───────────────────────────────────────────────
    if (showCoreRationaleDialog) {
        CoreAccessRationaleDialog(
            onConfirm = {
                showCoreRationaleDialog = false
                onStep1Tap()
            },
            onDismiss = { showCoreRationaleDialog = false },
        )
    }

    if (showContactsDialog) {
        ContactsBypassDialog(
            onConfirm = {
                showContactsDialog = false
                contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
            },
            onSkip = {
                showContactsDialog = false
                viewModel.onContactsBypassResolved(contactsGranted = false)
            },
        )
    }

    if (showAppSheet) {
        AppPickerSheet(
            apps = installedApps,
            onAppSelected = { pkg ->
                viewModel.onAppSelected(pkg)
                showAppSheet = false
            },
            onDismiss = { showAppSheet = false },
        )
    }
}

// ── Step card ─────────────────────────────────────────────────────────────────

@Composable
private fun OnboardingStepCard(
    stepNumber: Int,
    definition: StepDefinition,
    state: StepUiState,
    onClick: () -> Unit,
) {
    val alpha = if (state.unlocked) 1f else 0.38f
    val isActionable = state.unlocked && !state.completed

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .then(if (isActionable) Modifier.clickable(onClick = onClick) else Modifier),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepBadge(stepNumber = stepNumber, completed = state.completed)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = definition.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (state.unlocked) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = definition.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isActionable) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun StepBadge(stepNumber: Int, completed: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .background(
                color = if (completed) ZigGreen else MaterialTheme.colorScheme.surfaceVariant,
                shape = CircleShape,
            ),
    ) {
        if (completed) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Completed",
                tint = ZigOnGreen,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Text(
                text = stepNumber.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Core access rationale dialog ──────────────────────────────────────────────

@Composable
private fun CoreAccessRationaleDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                tint = ZigGreen,
            )
        },
        title = { Text("How ZiG uses notification access") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "ZiG requires two permissions to function:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Notification Listener",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Allows ZiG to observe notifications from your apps as they arrive and alert you only to those it determines are important. Notifications classified as unimportant are ignored gracefully without another notification sound.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Post Notifications",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Lets ZiG re-deliver important notifications through its own filtered channel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "All evaluation runs entirely on your device. No notification content is ever transmitted off it.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = ZigGreen,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Grant Access") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not Now") }
        },
    )
}

// ── Contacts dialog ───────────────────────────────────────────────────────────

@Composable
private fun ContactsBypassDialog(
    onConfirm: () -> Unit,
    onSkip: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        icon = {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = ZigGreen,
            )
        },
        title = { Text("Smart Contact Bypass") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "ZiG reads your contact names to automatically allow notifications from people you know.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Your contact list is processed in real time by an embedded Rust engine. No names, no contacts, and no notification content are ever uploaded, stored remotely, or shared.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "You can disable this at any time from Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = ZigGreen,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Enable") }
        },
        dismissButton = {
            TextButton(onClick = onSkip) { Text("Skip") }
        },
    )
}

// ── App picker bottom sheet ───────────────────────────────────────────────────

// Loads the launcher icon for [packageName] asynchronously on IO so the list
// never blocks the main thread. Returns null while loading; the caller shows a
// letter-initial placeholder until the real icon arrives.
@Composable
private fun rememberAppIconPainter(packageName: String): BitmapPainter? {
    val context = LocalContext.current
    return produceState<BitmapPainter?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            try {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                BitmapPainter(drawable.toBitmap().asImageBitmap())
            } catch (_: Exception) {
                null
            }
        }
    }.value
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerSheet(
    apps: List<OnboardingAppInfo>,
    onAppSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val filtered = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) apps
        else apps.filter { it.label.contains(searchQuery, ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            text = "Choose an app to monitor",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 4.dp),
        )
        Text(
            text = "ZiG will intercept and filter notifications from this app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp),
        )
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search apps") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 8.dp),
        )
        HorizontalDivider()
        LazyColumn {
            items(filtered, key = { it.packageName }) { app ->
                ListItem(
                    headlineContent = {
                        Text(app.label, fontWeight = FontWeight.Medium)
                    },
                    supportingContent = {
                        Text(
                            text = app.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = {
                        val icon = rememberAppIconPainter(app.packageName)
                        if (icon != null) {
                            Image(
                                painter = icon,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                            )
                        } else {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = CircleShape,
                                    ),
                            ) {
                                Text(
                                    text = app.label.first().uppercaseChar().toString(),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    },
                    modifier = Modifier.clickable { onAppSelected(app.packageName) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
