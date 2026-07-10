package dev.zig.notificationfilter.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.zig.notificationfilter.data.local.NativeBridge
import dev.zig.notificationfilter.data.local.db.KeywordRuleDao
import dev.zig.notificationfilter.data.local.db.KeywordRuleEntity
import dev.zig.notificationfilter.data.local.db.KeywordRuleType
import dev.zig.notificationfilter.data.preferences.ZigUserPreferences
import dev.zig.notificationfilter.ui.tour.ScreenTourState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CustomRulesViewModel @Inject constructor(
    private val keywordRuleDao: KeywordRuleDao,
    preferences: ZigUserPreferences,
) : ViewModel() {

    // First-visit coach-mark tour. Progress lives here so it survives tab switches.
    val tour = ScreenTourState(
        startActive = !preferences.rulesTourSeen,
        onFinished = { preferences.rulesTourSeen = true },
    )

    val allowRules: StateFlow<List<KeywordRuleEntity>> =
        keywordRuleDao.getByType(KeywordRuleType.ALLOW.name)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val blockRules: StateFlow<List<KeywordRuleEntity>> =
        keywordRuleDao.getByType(KeywordRuleType.BLOCK.name)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addRule(rawInput: String, type: KeywordRuleType) {
        val conditions = rawInput.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (conditions.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            keywordRuleDao.insert(KeywordRuleEntity(conditions = conditions, ruleType = type.name))
            val joined = conditions.joinToString("||")
            if (type == KeywordRuleType.BLOCK) {
                NativeBridge.addKeywordRuleToBlocklist(joined)
            } else {
                NativeBridge.addKeywordRuleToWhitelist(joined)
            }
        }
    }

    fun updateRule(entity: KeywordRuleEntity, rawInput: String) {
        val conditions = rawInput.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (conditions.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            keywordRuleDao.update(entity.copy(conditions = conditions))
            rebuildRustSet(entity.ruleType)
        }
    }

    fun deleteRule(entity: KeywordRuleEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            keywordRuleDao.delete(entity)
            rebuildRustSet(entity.ruleType)
        }
    }

    // Clears and repopulates only the Rust set that changed. Avoids touching the opposite
    // set so an ALLOW delete never disturbs in-flight BLOCK lookups and vice versa.
    private suspend fun rebuildRustSet(ruleType: String) {
        if (ruleType == KeywordRuleType.BLOCK.name) {
            val remaining = keywordRuleDao.getSnapshotByType(KeywordRuleType.BLOCK.name)
            NativeBridge.clearKeywordBlocklist()
            remaining.forEach { NativeBridge.addKeywordRuleToBlocklist(it.conditions.joinToString("||")) }
        } else {
            val remaining = keywordRuleDao.getSnapshotByType(KeywordRuleType.ALLOW.name)
            NativeBridge.clearKeywordWhitelist()
            remaining.forEach { NativeBridge.addKeywordRuleToWhitelist(it.conditions.joinToString("||")) }
        }
    }
}
