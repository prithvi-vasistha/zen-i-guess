package dev.zig.notificationfilter.ui.apps

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.zig.notificationfilter.R

/**
 * Apps tab entry point. Hosts two views inside the same pager page — the list of
 * currently-managed apps and, behind the ＋ button, the picker for adding more.
 * Navigation between them is local state so the bottom bar stays put and the
 * MainScreen call site is unchanged. Both views share one ViewModel instance.
 */
@Composable
fun ManagedAppsScreen(modifier: Modifier = Modifier) {
    val viewModel: ManagedAppsViewModel = hiltViewModel()
    var showPicker by rememberSaveable { mutableStateOf(false) }

    if (showPicker) {
        AppPickerScreen(
            viewModel = viewModel,
            onBack = { showPicker = false },
            modifier = modifier,
        )
    } else {
        ManagedAppsList(
            viewModel = viewModel,
            onAddClick = { showPicker = true },
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManagedAppsList(
    viewModel: ManagedAppsViewModel,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val bannerVisible by viewModel.setupBannerVisible.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var searchQuery by rememberSaveable { mutableStateOf("") }
    // The app pending an unmanage confirmation, or null when no dialog is showing.
    var appToUnmanage by remember { mutableStateOf<ManagedAppsViewModel.InstalledApp?>(null) }

    val managedApps = uiState.apps.filter { it.isManaged }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Managed Apps",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = "Apps ZiG is filtering for you",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )

            when {
                uiState.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                managedApps.isEmpty() -> {
                    EmptyManagedState(modifier = Modifier.fillMaxSize())
                }

                else -> {
                    if (bannerVisible) {
                        SetupBanner(onDismiss = viewModel::dismissSetupBanner)
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search managed apps…") },
                        leadingIcon = {
                            Icon(imageVector = Icons.Default.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear search",
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )

                    val displayedApps = if (searchQuery.isBlank()) {
                        managedApps
                    } else {
                        managedApps.filter { it.appName.contains(searchQuery, ignoreCase = true) }
                    }

                    Text(
                        text = "Managed apps",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )

                    if (displayedApps.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No apps match \"$searchQuery\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(state = listState) {
                            items(displayedApps, key = { it.packageName }) { app ->
                                ManagedAppRow(
                                    app = app,
                                    onOpenSettings = { openNotificationSettings(context, app.packageName) },
                                    onRemove = { appToUnmanage = app },
                                )
                                HorizontalDivider(modifier = Modifier.padding(start = 68.dp))
                            }
                        }
                    }
                }
            }
        }

        // The ＋ button is available whether the list is empty or populated.
        if (!uiState.isLoading) {
            FloatingActionButton(
                onClick = onAddClick,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Add apps")
            }
        }
    }

    appToUnmanage?.let { app ->
        AlertDialog(
            onDismissRequest = { appToUnmanage = null },
            title = { Text("Stop managing ${app.appName}?") },
            text = {
                Text(
                    "ZiG will no longer intercept or filter this app's notifications. " +
                        "You can add it back anytime.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setManaged(app.packageName, false)
                        appToUnmanage = null
                    },
                ) { Text("Stop managing") }
            },
            dismissButton = {
                TextButton(onClick = { appToUnmanage = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmptyManagedState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_empty_managed_apps),
            contentDescription = null,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "No apps managed yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Tap ＋ to choose an app for ZiG to watch. Apps you don't add pass through untouched.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupBanner(onDismiss: () -> Unit) {
    val context = LocalContext.current
    Card(
        onClick = { context.startActivity(Intent("android.settings.NOTIFICATION_SETTINGS")) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Set managed apps to Silent",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Open system settings and set each managed app to Silent / No sound.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                )
            }
        }
    }
}

@Composable
private fun ManagedAppRow(
    app: ManagedAppsViewModel.InstalledApp,
    onOpenSettings: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Left zone: tapping the icon or name opens the app's system notification settings.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clickable { onOpenSettings() }
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
        ) {
            Image(
                bitmap = app.icon,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Right zone: remove from the managed set (asks for confirmation first).
        IconButton(
            onClick = onRemove,
            modifier = Modifier.padding(end = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Stop managing ${app.appName}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Opens the given package's system notification settings. */
internal fun openNotificationSettings(context: android.content.Context, packageName: String) {
    context.startActivity(
        Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName),
    )
}
