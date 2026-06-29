package dev.zig.notificationfilter

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.android.AndroidEntryPoint
import dev.zig.notificationfilter.data.local.ContactsSyncManager
import dev.zig.notificationfilter.ui.MainScreen
import dev.zig.notificationfilter.ui.theme.ZigTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Compose state: changes in onResume() automatically trigger recomposition of
    // PermissionBootstrapper without needing lifecycle-runtime-compose as a dependency.
    private var nlsGranted by mutableStateOf(false)

    @Inject lateinit var contactsSyncManager: ContactsSyncManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZigTheme {
                PermissionBootstrapper(nlsGranted = nlsGranted)
                MainScreen()
            }
        }
    }

    // Called every time the user navigates back to the app — including after returning
    // from the Notification Listener Settings screen. The dialog auto-dismisses because
    // nlsGranted flips to true and PermissionBootstrapper recomposes.
    //
    // requestSyncIfNeeded() fires the first contact sync after the user grants
    // READ_CONTACTS via the runtime prompt. It is a no-op on every subsequent resume
    // because ContactsSyncManager.initialSyncDone is set on the first invocation.
    override fun onResume() {
        super.onResume()
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        nlsGranted = flat != null && flat.contains(packageName)
        contactsSyncManager.requestSyncIfNeeded()
    }
}

@Composable
private fun PermissionBootstrapper(nlsGranted: Boolean) {
    val context = LocalContext.current

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Results are handled downstream:
        // POST_NOTIFICATIONS — NotificationPublisher.publish() checks before posting.
        // READ_CONTACTS      — background ContactsContract sync checks before reading.
    }

    // Request runtime permissions once on first composition.
    // POST_NOTIFICATIONS is an API 33+ runtime permission; READ_CONTACTS applies to all levels.
    LaunchedEffect(Unit) {
        val permissions = buildList {
            add(Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        permissionsLauncher.launch(permissions)
    }

    if (!nlsGranted) {
        AlertDialog(
            // Non-dismissible: the app cannot function without Notification Listener access.
            onDismissRequest = {},
            title = { Text("Notification Access Required") },
            text = {
                Text(
                    "ZiG needs Notification Access to intercept and filter your notifications. " +
                        "Tap Open Settings, find ZiG in the list, and enable it.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                        )
                    },
                ) {
                    Text("Open Settings")
                }
            },
        )
    }
}
