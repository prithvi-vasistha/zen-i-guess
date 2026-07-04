package dev.zig.notificationfilter.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.zig.notificationfilter.R
import dev.zig.notificationfilter.data.local.db.KeywordRuleEntity
import dev.zig.notificationfilter.ui.common.BookDoodle
import dev.zig.notificationfilter.ui.common.ScrollFab
import dev.zig.notificationfilter.ui.common.ZigEmptyState
import dev.zig.notificationfilter.ui.tour.TourKeys
import dev.zig.notificationfilter.ui.tour.coachMark

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CustomRulesScreen(modifier: Modifier = Modifier) {
    val viewModel: CustomRulesViewModel = hiltViewModel()
    val rules by viewModel.rules.collectAsState()
    val listState = rememberLazyListState()
    var inputText by rememberSaveable { mutableStateOf("") }
    // Int? survives config changes via rememberSaveable without needing Parcelable.
    var editingRuleId by rememberSaveable { mutableStateOf<Int?>(null) }
    val isEditing = editingRuleId != null
    val focusRequester = remember { FocusRequester() }

    // Bring the keyboard up and focus the text field whenever edit mode is entered.
    LaunchedEffect(editingRuleId) {
        if (editingRuleId != null) focusRequester.requestFocus()
    }

    val onSaveOrAdd: () -> Unit = {
        if (inputText.isNotBlank()) {
            if (isEditing) {
                val target = rules.find { it.id == editingRuleId }
                if (target != null) viewModel.updateRule(target, inputText)
                editingRuleId = null
            } else {
                viewModel.addRule(inputText)
            }
            inputText = ""
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column(modifier = Modifier.coachMark(TourKeys.TITLE_RULES)) {
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

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = {
                    Text(
                        text = "Add keyword (use comma to chain: 'cab, arriving')",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSaveOrAdd() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .coachMark(TourKeys.RULES_INPUT),
            )
            IconButton(onClick = { onSaveOrAdd() }) {
                Icon(
                    imageVector = if (isEditing) Icons.Default.Check else Icons.Default.Add,
                    contentDescription = if (isEditing) "Save rule" else "Add rule",
                    tint = if (isEditing) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Text(
            text = "Keywords",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        if (rules.isEmpty()) {
            ZigEmptyState(
                title = "No rules yet",
                subtitle = "Add keywords above to get started.",
                doodle = { BookDoodle() },
            )
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(state = listState) {
                    items(rules, key = { it.id }) { rule ->
                        RuleRow(
                            rule = rule,
                            isBeingEdited = rule.id == editingRuleId,
                            onEdit = {
                                editingRuleId = rule.id
                                inputText = rule.conditions.joinToString(", ")
                            },
                            onDelete = {
                                // Exit edit mode before deleting so the text field doesn't retain
                                // stale content from the rule that's about to be removed.
                                if (editingRuleId == rule.id) {
                                    editingRuleId = null
                                    inputText = ""
                                }
                                viewModel.deleteRule(rule)
                            },
                        )
                        HorizontalDivider()
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleRow(
    rule: KeywordRuleEntity,
    isBeingEdited: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
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
                tint = if (isBeingEdited) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
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
