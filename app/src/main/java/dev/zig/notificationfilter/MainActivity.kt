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
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.zig.notificationfilter.data.local.ContactsSyncManager
import dev.zig.notificationfilter.domain.NotificationPublisher
import dev.zig.notificationfilter.ui.MainScreen
import dev.zig.notificationfilter.ui.navigation.ZigScreen
import dev.zig.notificationfilter.ui.theme.ZigTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Compose state: changes in onResume() automatically trigger recomposition of
    // PermissionBootstrapper without needing lifecycle-runtime-compose as a dependency.
    private var nlsGranted by mutableStateOf(false)

    // Index of the tab to scroll to on launch (from a notification deep-link).
    // Defaults to -1 (no override). Set before setContent so the first composition reads it.
    private var startTab by mutableStateOf(-1)

    @Inject lateinit var contactsSyncManager: ContactsSyncManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startTab = resolveStartTab(intent)
        dismissNotificationIfRequested(intent)
        enableEdgeToEdge()
        setContent {
            ZigTheme {
                PermissionBootstrapper(nlsGranted = nlsGranted)
                MainScreen(startTab = startTab)
            }
        }
    }

    // Handles the case where MainActivity is already running — Android calls onNewIntent
    // instead of onCreate when FLAG_ACTIVITY_CLEAR_TOP brings an existing instance to front.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startTab = resolveStartTab(intent)
        dismissNotificationIfRequested(intent)
    }

    private fun resolveStartTab(intent: Intent?): Int {
        val target = intent?.getStringExtra(NotificationPublisher.EXTRA_NAVIGATE_TO) ?: return -1
        return when (target) {
            NotificationPublisher.NAV_TARGET_REVIEW -> ZigScreen.all.indexOf(ZigScreen.Review)
            else -> -1
        }
    }

    private fun dismissNotificationIfRequested(intent: Intent?) {
        val notifId = intent?.getIntExtra(
            NotificationPublisher.EXTRA_ZIG_NOTIF_TO_DISMISS, Int.MIN_VALUE,
        ) ?: return
        if (notifId != Int.MIN_VALUE) {
            NotificationManagerCompat.from(this).cancel(notifId)
        }
    }

    // Called every time the user navigates back to the app — including after returning
    // from the Notification Listener Settings screen. The dialog auto-dismisses because
    // nlsGranted flips to true and PermissionBootstrapper recomposes.
    //
    // register() is permission-gated and idempotent — it attaches the ContentObserver
    // the first time READ_CONTACTS is granted and is a no-op on every resume after that.
    // requestSyncIfNeeded() fires the initial contact sync once permission is held.
    override fun onResume() {
        super.onResume()
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        nlsGranted = flat != null && flat.contains(packageName)
        contactsSyncManager.register()
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
