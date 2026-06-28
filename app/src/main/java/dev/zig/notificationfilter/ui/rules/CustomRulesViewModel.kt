package dev.zig.notificationfilter.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.zig.notificationfilter.data.local.NativeBridge
import dev.zig.notificationfilter.data.local.db.KeywordRuleDao
import dev.zig.notificationfilter.data.local.db.KeywordRuleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CustomRulesViewModel @Inject constructor(
    private val keywordRuleDao: KeywordRuleDao,
) : ViewModel() {

    val rules: StateFlow<List<KeywordRuleEntity>> = keywordRuleDao.getAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun addRule(rawInput: String) {
        val conditions = rawInput.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (conditions.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            keywordRuleDao.insert(KeywordRuleEntity(conditions = conditions))
            NativeBridge.addKeywordRuleToWhitelist(conditions.joinToString("||"))
        }
    }

    fun updateRule(entity: KeywordRuleEntity, rawInput: String) {
        val conditions = rawInput.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (conditions.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            keywordRuleDao.update(entity.copy(conditions = conditions))
            // Rebuild Rust KEYWORD_WHITELIST from post-update snapshot — same pattern as deleteRule.
            val remaining = keywordRuleDao.getAllSnapshot()
            NativeBridge.clearKeywordWhitelist()
            remaining.forEach { rule ->
                NativeBridge.addKeywordRuleToWhitelist(rule.conditions.joinToString("||"))
            }
        }
    }

    fun deleteRule(entity: KeywordRuleEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            keywordRuleDao.delete(entity)
            // Rebuild the Rust KEYWORD_WHITELIST from the post-delete Room snapshot.
            // clear() + repopulate is the only safe way to remove a specific rule from
            // a Vec without tracking per-rule indices across the JNI boundary.
            val remaining = keywordRuleDao.getAllSnapshot()
            NativeBridge.clearKeywordWhitelist()
            remaining.forEach { rule ->
                NativeBridge.addKeywordRuleToWhitelist(rule.conditions.joinToString("||"))
            }
        }
    }
}
