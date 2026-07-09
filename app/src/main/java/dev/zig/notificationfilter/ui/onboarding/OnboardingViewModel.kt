package dev.zig.notificationfilter.ui.onboarding

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.zig.notificationfilter.data.local.ContactsSyncManager
import dev.zig.notificationfilter.data.local.NativeBridge
import dev.zig.notificationfilter.data.local.db.ManagedAppDao
import dev.zig.notificationfilter.data.local.db.ManagedAppEntity
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class OnboardingAppInfo(val packageName: String, val label: String)

data class StepUiState(
    val unlocked: Boolean,
    val completed: Boolean,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val contactsSyncManager: ContactsSyncManager,
    private val managedAppDao: ManagedAppDao,
    private val preferences: ZigUserPreferences,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _steps = MutableStateFlow(
        listOf(
            StepUiState(unlocked = true, completed = false),
            StepUiState(unlocked = false, completed = false),
            StepUiState(unlocked = false, completed = false),
            StepUiState(unlocked = false, completed = false),
        ),
    )
    val steps: StateFlow<List<StepUiState>> = _steps.asStateFlow()

    val allComplete: StateFlow<Boolean> = _steps
        .map { list -> list.all(StepUiState::completed) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _installedApps = MutableStateFlow<List<OnboardingAppInfo>>(emptyList())
    val installedApps: StateFlow<List<OnboardingAppInfo>> = _installedApps.asStateFlow()

    // Set when the user selects an app in Step 3; consumed by Step 4 to open that app's settings.
    private val _selectedAppPackage = MutableStateFlow<String?>(null)
    val selectedAppPackage: StateFlow<String?> = _selectedAppPackage.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { loadInstalledApps() }
    }

    // Called from the screen's ON_RESUME lifecycle observer when both POST_NOTIFICATIONS
    // and Notification Listener access are confirmed granted.
    fun onCoreAccessGranted() = completeStep(0)

    // Called after the contacts dialog resolves — whether the user tapped "Yes, Enable"
    // (contactsGranted = true after the permission result) or "Skip" (false).
    // The ContactObserver is only started when permission is actually held; an empty
    // Rust contact set is a valid, stable state — it simply means no contacts are
    // whitelisted, which is correct for a user who declined.
    fun onContactsBypassResolved(contactsGranted: Boolean) {
        preferences.contactsBypassEnabled = contactsGranted
        if (contactsGranted) {
            contactsSyncManager.register()
            contactsSyncManager.requestSyncIfNeeded()
        }
        completeStep(1)
    }

    // Called when the user selects an app from the bottom sheet.
    // Writes to Room and mirrors into the Rust managed-app set so filtering is
    // active for the selected app immediately after onboarding completes.
    fun onAppSelected(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            managedAppDao.insert(ManagedAppEntity(packageName = packageName))
            NativeBridge.addAppToManaged(packageName)
            withContext(Dispatchers.Main) {
                _selectedAppPackage.value = packageName
                completeStep(2)
            }
        }
    }

    // Called on ON_RESUME after the user has returned from the app's notification settings
    // (triggered by the silenceStepTapped flag in OnboardingScreen).
    fun onSilenceStepReturned() = completeStep(3)

    // Marks step[index] complete and unlocks step[index + 1].
    // Idempotent: a double-call (e.g. from a rapid lifecycle resume) is a no-op.
    private fun completeStep(index: Int) {
        _steps.update { current ->
            if (current[index].completed) return@update current
            current.mapIndexed { i, step ->
                when (i) {
                    index -> step.copy(completed = true)
                    index + 1 -> step.copy(unlocked = true)
                    else -> step
                }
            }
        }
    }

    private fun loadInstalledApps() {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(launcherIntent, PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .map { resolveInfo ->
                val pkg = resolveInfo.activityInfo.packageName
                val label = resolveInfo.loadLabel(pm).toString()
                OnboardingAppInfo(pkg, label)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
        _installedApps.value = apps
    }
}
