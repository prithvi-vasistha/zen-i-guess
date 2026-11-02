package dev.zig.notificationfilter.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.zig.notificationfilter.data.local.FilterState
import dev.zig.notificationfilter.data.local.db.KeywordRuleDao
import dev.zig.notificationfilter.data.local.db.KeywordRuleEntity
import dev.zig.notificationfilter.data.local.db.KeywordRuleType
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import dev.zig.notificationfilter.ui.tour.ScreenTourState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import dev.zig.notificationfilter.core.errors.recoverErrors
import dev.zig.notificationfilter.core.errors.launchSafely
import javax.inject.Inject

@HiltViewModel
class CustomRulesViewModel @Inject constructor(
    private val keywordRuleDao: KeywordRuleDao,
    private val filterState: FilterState,
    preferences: ZigUserPreferences,
) : ViewModel() {

    // First-visit coach-mark tour. Progress lives here so it survives tab switches.
    val tour = ScreenTourState(
        startActive = !preferences.rulesTourSeen,
        onFinished = { preferences.rulesTourSeen = true },
    )

    val allowRules: StateFlow<List<KeywordRuleEntity>> =
        keywordRuleDao.getByType(KeywordRuleType.ALLOW.name)
            .recoverErrors("Loading allow rules")
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val blockRules: StateFlow<List<KeywordRuleEntity>> =
        keywordRuleDao.getByType(KeywordRuleType.BLOCK.name)
            .recoverErrors("Loading block rules")
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addRule(rawInput: String, type: KeywordRuleType) {
        val conditions = rawInput.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (conditions.isEmpty()) return
        viewModelScope.launchSafely("Adding keyword rule", Dispatchers.IO) {
            keywordRuleDao.insert(KeywordRuleEntity(conditions = conditions, ruleType = type.name))
            filterState.refresh()
        }
    }

    fun updateRule(entity: KeywordRuleEntity, rawInput: String) {
        val conditions = rawInput.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (conditions.isEmpty()) return
        viewModelScope.launchSafely("Updating keyword rule", Dispatchers.IO) {
            keywordRuleDao.update(entity.copy(conditions = conditions))
            filterState.refresh()
        }
    }

    fun deleteRule(entity: KeywordRuleEntity) {
        viewModelScope.launchSafely("Deleting keyword rule", Dispatchers.IO) {
            keywordRuleDao.delete(entity)
            filterState.refresh()
        }
    }

}
