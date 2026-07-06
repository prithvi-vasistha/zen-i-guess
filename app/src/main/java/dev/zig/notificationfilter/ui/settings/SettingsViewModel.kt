package dev.zig.notificationfilter.ui.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import dev.zig.notificationfilter.domain.summary.DailySummaryScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: ZigUserPreferences,
    private val dailySummaryScheduler: DailySummaryScheduler,
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
}
