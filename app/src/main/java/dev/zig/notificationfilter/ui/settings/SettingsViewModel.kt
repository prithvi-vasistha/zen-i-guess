package dev.zig.notificationfilter.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import dev.zig.notificationfilter.domain.backup.BackupRestoreManager
import dev.zig.notificationfilter.domain.backup.IncompatibleBackupVersionException
import dev.zig.notificationfilter.domain.summary.DailySummaryScheduler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: ZigUserPreferences,
    private val dailySummaryScheduler: DailySummaryScheduler,
    private val backupRestoreManager: BackupRestoreManager,
) : ViewModel() {

    private val _dailySummaryEnabled = MutableStateFlow(preferences.dailySummaryEnabled)
    val dailySummaryEnabled: StateFlow<Boolean> = _dailySummaryEnabled.asStateFlow()

    fun setDailySummaryEnabled(enabled: Boolean) {
        preferences.dailySummaryEnabled = enabled
        _dailySummaryEnabled.value = enabled
        if (enabled) dailySummaryScheduler.schedule() else dailySummaryScheduler.cancel()
    }

    private val _sensitiveNotificationsEnabled = MutableStateFlow(preferences.sensitiveNotificationsEnabled)
    val sensitiveNotificationsEnabled: StateFlow<Boolean> = _sensitiveNotificationsEnabled.asStateFlow()

    fun setSensitiveNotificationsEnabled(enabled: Boolean) {
        preferences.sensitiveNotificationsEnabled = enabled
        _sensitiveNotificationsEnabled.value = enabled
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
        viewModelScope.launch {
            try {
                val stream = context.contentResolver.openOutputStream(uri)
                    ?: error("Could not open the selected file for writing.")
                backupRestoreManager.createBackup(stream)
                _messages.send("Backup exported. Keep this file private — it contains your notification text unencrypted.")
            } catch (e: Exception) {
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
        viewModelScope.launch {
            try {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: error("Could not open the selected file for reading.")
                val result = backupRestoreManager.restoreBackup(stream)
                // Reflect restored preference values in the toggles immediately.
                _dailySummaryEnabled.value = preferences.dailySummaryEnabled
                _sensitiveNotificationsEnabled.value = preferences.sensitiveNotificationsEnabled
                _messages.send("Restored ${result.overridesRestored} saved decisions.")
            } catch (e: IncompatibleBackupVersionException) {
                _messages.send("This backup was made by a newer version of ZiG and can't be imported.")
            } catch (e: Exception) {
                _messages.send("Import failed: the file isn't a valid ZiG backup.")
            } finally {
                _isBusy.value = false
            }
        }
    }
}
