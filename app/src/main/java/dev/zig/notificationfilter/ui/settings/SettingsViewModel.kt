package dev.zig.notificationfilter.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.zig.notificationfilter.data.local.ContactsSyncManager
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import dev.zig.notificationfilter.data.preferences.AccentColor
import dev.zig.notificationfilter.data.preferences.AppearancePreferences
import dev.zig.notificationfilter.data.preferences.ThemeMode
import dev.zig.notificationfilter.data.preferences.ThemeStyle
import dev.zig.notificationfilter.domain.backup.BackupRestoreManager
import dev.zig.notificationfilter.domain.backup.IncompatibleBackupVersionException
import dev.zig.notificationfilter.domain.memory.PersonalMemory
import dev.zig.notificationfilter.domain.NotificationHealth
import dev.zig.notificationfilter.domain.NotificationPublisher
import dev.zig.notificationfilter.domain.summary.DailySummaryScheduler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import dev.zig.notificationfilter.core.errors.launchSafely
import javax.inject.Inject
import dev.zig.notificationfilter.core.errors.AppErrors

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: ZigUserPreferences,
    private val dailySummaryScheduler: DailySummaryScheduler,
    private val backupRestoreManager: BackupRestoreManager,
    private val contactsSyncManager: ContactsSyncManager,
    private val personalMemory: PersonalMemory,
    private val notificationHealth: NotificationHealth,
) : ViewModel() {

    val appearance = preferences.appearance

    fun setThemeMode(mode: ThemeMode) { AppErrors.run("Changing theme mode") { preferences.setAppearance(appearance.value.copy(mode = mode)) } }
    fun setThemeStyle(style: ThemeStyle) { AppErrors.run("Changing theme style") { preferences.setAppearance(appearance.value.copy(style = style)) } }
    fun setAccentColor(accent: AccentColor) { AppErrors.run("Changing accent colour") { preferences.setAppearance(appearance.value.copy(accent = accent)) } }
    fun resetAppearance() { AppErrors.run("Resetting appearance") { preferences.setAppearance(AppearancePreferences()) } }
    fun openAlertDisplaySettings() = notificationHealth.openAlertChannel()
    fun openPhoneSettings() = notificationHealth.openPhoneSettings()

    fun refreshDailySummary() {
        AppErrors.run("Restoring daily summary settings") { dailySummaryScheduler.resolvePermissionRequest() }
        _dailySummaryEnabled.value = preferences.dailySummaryEnabled
    }

    private val _dailySummaryEnabled = MutableStateFlow(preferences.dailySummaryEnabled)
    val dailySummaryEnabled: StateFlow<Boolean> = _dailySummaryEnabled.asStateFlow()
    val dailySummaryState = dailySummaryScheduler.state

    init {
        viewModelScope.launchSafely("Observing daily summary settings") {
            dailySummaryState.collect { _dailySummaryEnabled.value = it.enabled }
        }
    }

    private val _dailySummaryHour = MutableStateFlow(preferences.dailySummaryHour)
    val dailySummaryHour: StateFlow<Int> = _dailySummaryHour.asStateFlow()

    private val _dailySummaryMinute = MutableStateFlow(preferences.dailySummaryMinute)
    val dailySummaryMinute: StateFlow<Int> = _dailySummaryMinute.asStateFlow()

    fun setDailySummaryEnabled(enabled: Boolean): Boolean {
        val accepted = AppErrors.run("Changing daily summary setting") { dailySummaryScheduler.setEnabled(enabled) } ?: false
        _dailySummaryEnabled.value = preferences.dailySummaryEnabled
        return accepted
    }

    fun requestSummaryAccess(): Intent? = AppErrors.run("Requesting daily summary access") { dailySummaryScheduler.requestEnable() }
    fun onSummaryPermissionReturned() = refreshDailySummary()
    fun skipSummaryAccess() { AppErrors.run("Turning off daily summary") { dailySummaryScheduler.cancel() } }
    fun openSummaryChannel() = notificationHealth.openAlertChannel(NotificationPublisher.CHANNEL_ID_SUMMARY)

    fun setSummaryTime(hour: Int, minute: Int) {
        AppErrors.run("Saving daily summary time") {
            preferences.dailySummaryHour = hour
            preferences.dailySummaryMinute = minute
            _dailySummaryHour.value = hour
            _dailySummaryMinute.value = minute
            // Only reschedule the running job when the summary is active. If disabled, the
            // stored time is read from preferences when the user next enables the toggle.
            if (preferences.dailySummaryEnabled) dailySummaryScheduler.reschedule()
        }
    }

    private val _sensitiveNotificationsEnabled = MutableStateFlow(preferences.sensitiveNotificationsEnabled)
    val sensitiveNotificationsEnabled: StateFlow<Boolean> = _sensitiveNotificationsEnabled.asStateFlow()

    fun setSensitiveNotificationsEnabled(enabled: Boolean) {
        AppErrors.run("Saving sensitive notification setting") {
            preferences.sensitiveNotificationsEnabled = enabled
            _sensitiveNotificationsEnabled.value = enabled
        }
    }

    private val _hiddenContentAlertsEnabled = MutableStateFlow(preferences.hiddenContentAlertsEnabled)
    val hiddenContentAlertsEnabled: StateFlow<Boolean> = _hiddenContentAlertsEnabled.asStateFlow()

    fun setHiddenContentAlertsEnabled(enabled: Boolean) {
        AppErrors.run("Saving hidden content alert setting") {
            preferences.hiddenContentAlertsEnabled = enabled
            _hiddenContentAlertsEnabled.value = enabled
        }
    }

    // ── Contacts bypass ───────────────────────────────────────────────────────

    private val _contactsBypassEnabled = MutableStateFlow(preferences.contactsBypassEnabled)
    val contactsBypassEnabled: StateFlow<Boolean> = _contactsBypassEnabled.asStateFlow()

    // Enabling registers the observer and triggers a sync if permission is already held.
    // Disabling unregisters the observer and clears the Rust contact set.
    // The OS permission (READ_CONTACTS) is never revoked — that is the user's choice.
    // Permission acquisition on enable is handled in SettingsScreen before calling this.
    fun setContactsBypassEnabled(enabled: Boolean) {
        AppErrors.run("Saving contact bypass setting") {
            preferences.contactsBypassEnabled = enabled
            _contactsBypassEnabled.value = enabled
            if (enabled) {
                contactsSyncManager.register()
                contactsSyncManager.requestSyncIfNeeded()
            } else {
                contactsSyncManager.unregister()
            }
        }
    }

    private val _imageNotificationsEnabled = MutableStateFlow(preferences.imageNotificationsEnabled)
    val imageNotificationsEnabled: StateFlow<Boolean> = _imageNotificationsEnabled.asStateFlow()

    fun setImageNotificationsEnabled(enabled: Boolean) {
        AppErrors.run("Saving image notification setting") {
            preferences.imageNotificationsEnabled = enabled
            _imageNotificationsEnabled.value = enabled
        }
    }

    // ── AI Memory reset ───────────────────────────────────────────────────────

    private val _showClearMemoryDialog = MutableStateFlow(false)
    val showClearMemoryDialog: StateFlow<Boolean> = _showClearMemoryDialog.asStateFlow()

    fun requestClearAiMemory() { _showClearMemoryDialog.value = true }
    fun dismissClearMemoryDialog() { _showClearMemoryDialog.value = false }

    fun clearAiMemory() {
        _showClearMemoryDialog.value = false
        viewModelScope.launchSafely("Clearing AI memory") {
            try {
                personalMemory.clearAllMemory()
                _messages.send("AI memory cleared. The filter will start fresh from your rules.")
            } catch (e: Exception) {
                AppErrors.report("Clearing AI memory", e)
                _messages.send("Failed to clear AI memory: ${e.message ?: "unknown error"}")
            }
        }
    }

    // ── Backup & Restore ──────────────────────────────────────────────────────

    // True while an export/import is running; drives a busy indicator and disables the rows
    // so a second SAF pick can't overlap an in-flight operation.
    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    // One-shot user messages for the Settings Snackbar. A Channel (not StateFlow) so a
    // message is delivered exactly once and never re-emitted on recomposition/rotation.
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** Writes the backup JSON to the user-picked [uri] from the CreateDocument contract. */
    fun exportTo(uri: Uri) {
        if (_isBusy.value) return
        _isBusy.value = true
        viewModelScope.launchSafely("Exporting backup") {
            try {
                val stream = context.contentResolver.openOutputStream(uri)
                    ?: error("Could not open the selected file for writing.")
                backupRestoreManager.createBackup(stream)
                _messages.send("Backup exported. Keep this file private — it contains your notification text unencrypted.")
            } catch (e: Exception) {
                AppErrors.report("Exporting backup", e)
                _messages.send("Export failed: ${e.message ?: "unknown error"}")
            } finally {
                _isBusy.value = false
            }
        }
    }

    /** Reads and applies the backup JSON from the user-picked [uri] from OpenDocument. */
    fun importFrom(uri: Uri) {
        if (_isBusy.value) return
        _isBusy.value = true
        viewModelScope.launchSafely("Importing backup") {
            try {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: error("Could not open the selected file for reading.")
                val result = backupRestoreManager.restoreBackup(stream)
                // Reflect restored preference values in the toggles immediately.
                _dailySummaryEnabled.value = preferences.dailySummaryEnabled
                _dailySummaryHour.value = preferences.dailySummaryHour
                _dailySummaryMinute.value = preferences.dailySummaryMinute
                _sensitiveNotificationsEnabled.value = preferences.sensitiveNotificationsEnabled
                _hiddenContentAlertsEnabled.value = preferences.hiddenContentAlertsEnabled
                _imageNotificationsEnabled.value = preferences.imageNotificationsEnabled
                _messages.send(
                    "Restore complete: +${result.managedAppsAdded} apps, " +
                        "+${result.keywordRulesAdded} rules, " +
                        "${result.categoryOverridesRestored} categories, " +
                        "${result.overridesRestored} saved decisions.",
                )
            } catch (e: IncompatibleBackupVersionException) {
                AppErrors.report("Importing backup", e)
                _messages.send("This backup was made by a newer version of ZiG and can't be imported.")
            } catch (e: SerializationException) {
                AppErrors.report("Importing backup", e)
                _messages.send("Import failed: the file isn't a valid ZiG backup.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppErrors.report("Importing backup", e)
                Log.e("BackupRestore", "Could not finish importing backup", e)
                _messages.send("Import couldn't finish. Your existing memory has been kept. Please try again.")
            } finally {
                _isBusy.value = false
            }
        }
    }
}
