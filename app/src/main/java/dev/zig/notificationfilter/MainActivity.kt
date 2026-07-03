package dev.zig.notificationfilter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.zig.notificationfilter.data.local.ContactsSyncManager
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import dev.zig.notificationfilter.domain.NotificationPublisher
import dev.zig.notificationfilter.ui.MainScreen
import dev.zig.notificationfilter.ui.navigation.ZigScreen
import dev.zig.notificationfilter.ui.onboarding.TermsAndConditionsScreen
import dev.zig.notificationfilter.ui.theme.ZigTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Compose state: changes in onResume() automatically trigger recomposition of
    // PermissionBootstrapper without needing lifecycle-runtime-compose as a dependency.
    private var nlsGranted by mutableStateOf(false)

    // Runtime permission state, recomputed in onResume() so returning from the system
    // permission dialog or App Settings re-evaluates the gate.
    private var contactsGranted by mutableStateOf(false)
    private var postNotificationsGranted by mutableStateOf(false)

    // T&C and onboarding only begin once every required permission is held.
    private val allPermissionsGranted: Boolean
        get() = nlsGranted && contactsGranted && postNotificationsGranted

    // Index of the tab to scroll to on launch (from a notification deep-link).
    // Defaults to -1 (no override). Set before setContent so the first composition reads it.
    private var startTab by mutableStateOf(-1)

    // First-run gate state — both read from prefs before setContent so the first
    // composition sees the correct initial values without a recomposition flash.
    private var termsAccepted by mutableStateOf(false)
    private var onboardingCompleted by mutableStateOf(false)

    @Inject lateinit var contactsSyncManager: ContactsSyncManager
    @Inject lateinit var preferences: ZigUserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        termsAccepted = preferences.termsAccepted
        onboardingCompleted = preferences.onboardingCompleted
        startTab = resolveStartTab(intent)
        dismissNotificationIfRequested(intent)
        enableEdgeToEdge()
        setContent {
            ZigTheme {
                // PermissionBootstrapper is always composed so it can request the runtime
                // permissions and gate the app until every required permission is held.
                PermissionBootstrapper(
                    nlsGranted = nlsGranted,
                    contactsGranted = contactsGranted,
                    postNotificationsGranted = postNotificationsGranted,
                )
                if (allPermissionsGranted) {
                    when {
                        !termsAccepted -> TermsAndConditionsScreen(
                            onAccepted = {
                                preferences.termsAccepted = true
                                termsAccepted = true
                            },
                        )
                        // The interactive onboarding tour renders on top of MainScreen,
                        // so MainScreen is composed here whether or not the tour runs.
                        else -> MainScreen(
                            startTab = startTab,
                            startTour = !onboardingCompleted,
                            // Force-quit safety: persist completion the instant the tour begins.
                            onTourStarted = { preferences.onboardingCompleted = true },
                            onTourFinished = { onboardingCompleted = true },
                        )
                    }
                }
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
        contactsGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED
        // POST_NOTIFICATIONS is only a runtime permission on API 33+; implicitly held below that.
        postNotificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        contactsSyncManager.register()
        contactsSyncManager.requestSyncIfNeeded()
    }
}

@Composable
private fun PermissionBootstrapper(
    nlsGranted: Boolean,
    contactsGranted: Boolean,
    postNotificationsGranted: Boolean,
) {
    val context = LocalContext.current
    val runtimeGranted = contactsGranted && postNotificationsGranted

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Grants are re-read in onResume(), which fires when the system dialog dismisses.
        // Downstream, NotificationPublisher and the ContactsContract sync also re-check.
    }

    val requestRuntimePermissions: () -> Unit = {
        val permissions = buildList {
            if (!contactsGranted) add(Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !postNotificationsGranted) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) permissionsLauncher.launch(permissions.toTypedArray())
    }

    // Prompt for the runtime permissions once on first composition.
    LaunchedEffect(Unit) { requestRuntimePermissions() }

    // Everything granted — no gate needed.
    if (nlsGranted && runtimeGranted) return

    val openAppSettings: () -> Unit = {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ),
        )
    }

    // A single, non-dismissible gate. Runtime permissions are resolved first (they can be
    // requested in-app); Notification Access is last (it only lives in system settings).
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(
                if (!runtimeGranted) "Permissions Required" else "Notification Access Required",
            )
        },
        text = {
            Text(
                if (!runtimeGranted) {
                    "ZiG needs Contacts and Notification permissions to filter your alerts on-device. " +
                        "Tap Grant to allow them. If a prompt no longer appears, use App Settings."
                } else {
                    "ZiG needs Notification Access to intercept and filter your notifications. " +
                        "Tap Open Settings, find ZiG in the list, and enable it."
                },
            )
        },
        confirmButton = {
            if (!runtimeGranted) {
                TextButton(onClick = requestRuntimePermissions) { Text("Grant") }
            } else {
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                        )
                    },
                ) { Text("Open Settings") }
            }
        },
        dismissButton = {
            // Fallback path when a runtime permission was permanently denied and the
            // system will no longer show its prompt.
            if (!runtimeGranted) {
                TextButton(onClick = openAppSettings) { Text("App Settings") }
            }
        },
    )
}
