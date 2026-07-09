package dev.zig.notificationfilter.ui.rules

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.zig.notificationfilter.R
import dev.zig.notificationfilter.data.local.db.KeywordRuleEntity
import dev.zig.notificationfilter.data.local.db.KeywordRuleType
import dev.zig.notificationfilter.ui.common.ScrollFab
import dev.zig.notificationfilter.ui.common.ZigEmptyState
import dev.zig.notificationfilter.ui.theme.ZigGreen

private val ALLOW_SUGGESTIONS = listOf("otp", "verification code", "password", "bank")
private val BLOCK_SUGGESTIONS = listOf("promo", "discount", "offer", "sale", "crypto")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CustomRulesScreen(modifier: Modifier = Modifier) {
    val viewModel: CustomRulesViewModel = hiltViewModel()
    val allowRules by viewModel.allowRules.collectAsState()
    val blockRules by viewModel.blockRules.collectAsState()

    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var inputText by rememberSaveable { mutableStateOf("") }
    // Int? survives config changes via rememberSaveable without needing Parcelable.
    var editingRuleId by rememberSaveable { mutableStateOf<Int?>(null) }
    val isEditing = editingRuleId != null
    val focusRequester = remember { FocusRequester() }

    val isAllowTab = selectedTab == 0
    val activeRules = if (isAllowTab) allowRules else blockRules
    val activeType = if (isAllowTab) KeywordRuleType.ALLOW else KeywordRuleType.BLOCK
    val activeSuggestions = if (isAllowTab) ALLOW_SUGGESTIONS else BLOCK_SUGGESTIONS
    val activeColor = if (isAllowTab) ZigGreen else MaterialTheme.colorScheme.error
    val activeContainerColor = if (isAllowTab) ZigGreen.copy(alpha = 0.10f) else MaterialTheme.colorScheme.errorContainer
    val helperText = if (isAllowTab) "Words containing these will NEVER be blocked."
                     else "Words containing these will ALWAYS be silenced."
    val inputPlaceholder = if (isAllowTab) "Allow keyword (comma to chain)" else "Block keyword (comma to chain)"

    // Suggestions: hide chips whose keyword is already present as a single condition in any rule.
    val usedKeywords = remember(activeRules) { activeRules.flatMap { it.conditions }.toSet() }
    val visibleSuggestions = remember(activeSuggestions, usedKeywords) {
        activeSuggestions.filter { it !in usedKeywords }
    }

    LaunchedEffect(editingRuleId) {
        if (editingRuleId != null) focusRequester.requestFocus()
    }

    val onSaveOrAdd: () -> Unit = {
        if (inputText.isNotBlank()) {
            if (isEditing) {
                val target = activeRules.find { it.id == editingRuleId }
                if (target != null) viewModel.updateRule(target, inputText)
                editingRuleId = null
            } else {
                viewModel.addRule(inputText, activeType)
            }
            inputText = ""
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = "Custom Rules Vault",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "Your high-priority rules",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )

        TabRow(
            selectedTabIndex = selectedTab,
            indicator = { tabPositions ->
                Box(
                    modifier = with(TabRowDefaults) {
                        Modifier
                            .tabIndicatorOffset(tabPositions[selectedTab])
                            .height(3.dp)
                            .background(activeColor, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    },
                )
            },
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = {
                    if (selectedTab != 0) {
                        selectedTab = 0
                        editingRuleId = null
                        inputText = ""
                    }
                },
                selectedContentColor = ZigGreen,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    text = "Allowed Words",
                    modifier = Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Tab(
                selected = selectedTab == 1,
                onClick = {
                    if (selectedTab != 1) {
                        selectedTab = 1
                        editingRuleId = null
                        inputText = ""
                    }
                },
                selectedContentColor = MaterialTheme.colorScheme.error,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    text = "Blocked Words",
                    modifier = Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        // Helper microcopy — immediately under the tab so intent is obvious before the user types.
        Text(
            text = helperText,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = activeColor,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        // Manual input row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = {
                    Text(
                        text = inputPlaceholder,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSaveOrAdd() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
            IconButton(onClick = { onSaveOrAdd() }) {
                Icon(
                    imageVector = if (isEditing) Icons.Default.Check else Icons.Default.Add,
                    contentDescription = if (isEditing) "Save rule" else "Add rule",
                    tint = if (isEditing) activeColor else MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // Smart suggestion chips — tapping one adds it instantly and removes it from the row.
        if (visibleSuggestions.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visibleSuggestions, key = { it }) { suggestion ->
                    SuggestionChip(
                        onClick = { viewModel.addRule(suggestion, activeType) },
                        label = { Text(suggestion, style = MaterialTheme.typography.bodySmall) },
                    )
                }
            }
        }

        // Rules list — key on selectedTab so scroll state resets when switching tabs.
        key(selectedTab) {
            if (activeRules.isEmpty()) {
                ZigEmptyState(
                    title = "No rules yet",
                    subtitle = if (isAllowTab)
                        "Add keywords above to always let matching notifications through."
                    else
                        "Add keywords above to always silence matching notifications.",
                    doodle = {
                        Image(
                            painter = painterResource(R.drawable.ic_empty_rules),
                            contentDescription = null,
                        )
                    },
                )
            } else {
                val listState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(activeRules, key = { it.id }) { rule ->
                            RuleRow(
                                rule = rule,
                                isBeingEdited = rule.id == editingRuleId,
                                containerColor = activeContainerColor,
                                activeColor = activeColor,
                                onEdit = {
                                    editingRuleId = rule.id
                                    inputText = rule.conditions.joinToString(", ")
                                },
                                onDelete = {
                                    if (editingRuleId == rule.id) {
                                        editingRuleId = null
                                        inputText = ""
                                    }
                                    viewModel.deleteRule(rule)
                                },
                            )
                        }
                    }
                    ScrollFab(
                        listState = listState,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleRow(
    rule: KeywordRuleEntity,
    isBeingEdited: Boolean,
    containerColor: Color,
    activeColor: Color,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
        color = containerColor,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_drag_handle),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .padding(start = 4.dp),
            )
            FlowRow(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                rule.conditions.forEach { condition ->
                    SuggestionChip(
                        onClick = {},
                        label = { Text(condition) },
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit rule",
                    tint = if (isBeingEdited) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Delete rule",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
