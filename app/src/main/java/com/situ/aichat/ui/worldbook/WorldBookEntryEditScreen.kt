package com.situ.aichat.ui.worldbook

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.situ.aichat.R
import com.situ.aichat.ui.components.SettingsSwitchRow
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDialogTone
import com.situ.aichat.ui.designsystem.AppMenu
import com.situ.aichat.ui.designsystem.AppMenuItem
import com.situ.aichat.ui.designsystem.AppSegmentedControl
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTextArea
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.AppTopBarIcons

/**
 * 条目编辑器（WB7b·契约 §12.4/§12.10）：常用五件在前（标题 / 触发方式三段 / 关键词 chips /
 * 内容 + 字数 / 启用），高级四组收起在后（[EntryAdvancedSections]）。向导类别只换 placeholder
 * 与预选触发方式，不落假数据；返回时有未保存改动先确认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldBookEntryEditScreen(
    onDone: () -> Unit,
    viewModel: WorldBookEntryEditViewModel = hiltViewModel(),
) {
    val draft = viewModel.draft
    val colors = AppTheme.colors
    var showDiscard by remember { mutableStateOf(false) }
    var showDeleteEntry by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    BackHandler(enabled = viewModel.isDirty) { showDiscard = true }

    val entry = draft ?: return
    val guide = viewModel.guideCategory
    val mode = viewModel.triggerMode(entry)
    val requestBack: () -> Unit = { if (viewModel.isDirty) showDiscard = true else onDone() }

    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(if (viewModel.isEditing) R.string.wb_entry_edit_title else R.string.wb_entry_new_title),
                onBack = requestBack,
                lifted = scrollState.value > 0,
                actions = {
                    if (viewModel.isEditing) {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(AppTopBarIcons.More, contentDescription = stringResource(R.string.wb_cd_more_actions))
                            }
                            AppMenu(expanded = showMenu, onDismiss = { showMenu = false }) {
                                AppMenuItem(
                                    text = stringResource(R.string.wb_delete_entry),
                                    danger = true,
                                    onClick = {
                                        showMenu = false
                                        showDeleteEntry = true
                                    },
                                )
                            }
                        }
                    }
                    AppButton(style = AppButtonStyle.Text, onClick = { viewModel.save(onDone) }) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .contentMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppTextField(
                value = entry.comment,
                onValueChange = { v -> viewModel.update { it.copy(comment = v) } },
                label = stringResource(R.string.wb_entry_title_label),
                placeholder = stringResource(guide?.titlePlaceholderRes ?: R.string.wb_entry_title_placeholder),
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.wb_trigger_mode),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text.secondary,
                )
                AppSegmentedControl(
                    options = WorldBookTriggerMode.entries.toList(),
                    selected = mode,
                    onSelect = viewModel::setTriggerMode,
                ) { m ->
                    stringResource(
                        when (m) {
                            WorldBookTriggerMode.KEYWORD -> R.string.wb_mode_keyword
                            WorldBookTriggerMode.CONSTANT -> R.string.wb_mode_constant
                            WorldBookTriggerMode.VECTOR -> R.string.wb_mode_vector
                        },
                    )
                }
                Text(
                    stringResource(
                        when (mode) {
                            WorldBookTriggerMode.KEYWORD -> R.string.wb_mode_keyword_hint
                            WorldBookTriggerMode.CONSTANT -> R.string.wb_mode_constant_hint
                            WorldBookTriggerMode.VECTOR -> R.string.wb_mode_vector_hint
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text.secondary,
                )
            }

            if (mode == WorldBookTriggerMode.KEYWORD) {
                KeywordChipsEditor(
                    label = stringResource(R.string.wb_keywords_label),
                    keys = viewModel.keys(entry),
                    onAdd = { viewModel.addKeys(it) },
                    onRemove = { viewModel.removeKey(it) },
                    hint = stringResource(R.string.wb_keywords_hint),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppTextArea(
                    value = entry.content,
                    onValueChange = { v -> viewModel.update { it.copy(content = v) } },
                    label = stringResource(R.string.wb_content_label),
                    placeholder = guide?.contentPlaceholderRes?.let { stringResource(it) },
                    minHeight = 160.dp,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        stringResource(R.string.wb_content_macro_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.text.secondary,
                    )
                    Text(
                        stringResource(R.string.wb_char_count, entry.content.length),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.text.secondary,
                    )
                }
            }

            Surface(shape = AppShapes.medium, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                SettingsSwitchRow(
                    title = stringResource(R.string.wb_entry_enabled),
                    checked = entry.enabled,
                    onCheckedChange = { v -> viewModel.update { it.copy(enabled = v) } },
                )
            }

            Text(
                stringResource(R.string.wb_advanced),
                style = MaterialTheme.typography.titleSmall,
                color = colors.text.secondary,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
            EntryAdvancedSections(entry = entry, viewModel = viewModel)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDiscard) {
        AppDialog(
            onDismissRequest = { showDiscard = false },
            title = stringResource(R.string.wb_discard_title),
            body = stringResource(R.string.wb_discard_body),
            confirmText = stringResource(R.string.wb_discard_confirm),
            onConfirm = {
                showDiscard = false
                onDone()
            },
            confirmTone = AppDialogTone.Danger,
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showDiscard = false },
        )
    }
    if (showDeleteEntry) {
        AppDialog(
            onDismissRequest = { showDeleteEntry = false },
            title = stringResource(R.string.wb_delete_entry_title),
            body = stringResource(
                R.string.wb_delete_entry_body,
                entry.comment.ifBlank { stringResource(R.string.wb_entry_untitled) },
            ),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = {
                showDeleteEntry = false
                viewModel.deleteEntry(onDeleted = onDone)
            },
            confirmTone = AppDialogTone.Danger,
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showDeleteEntry = false },
        )
    }
}

/** 关键词 chips 编辑器（主 / 次关键词共用）：已加的词成 chips 点击即删；输入回车或点 + 加词，逗号可批量。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeywordChipsEditor(
    label: String,
    keys: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    hint: String? = null,
) {
    val colors = AppTheme.colors
    var input by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (keys.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                keys.forEach { key -> KeywordChip(key = key, onRemove = { onRemove(key) }) }
            }
        }
        AppTextField(
            value = input,
            onValueChange = { input = it },
            label = label,
            placeholder = stringResource(R.string.wb_keywords_input_placeholder),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (input.isNotBlank()) {
                        onAdd(input)
                        input = ""
                    }
                },
            ),
            trailingIcon = if (input.isNotBlank()) {
                {
                    IconButton(
                        onClick = {
                            onAdd(input)
                            input = ""
                        },
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.wb_cd_add_keyword),
                            tint = colors.accent.text,
                        )
                    }
                }
            } else {
                null
            },
        )
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.text.secondary)
        }
    }
}

/** 单个关键词 chip：陶土软填充 + 尾部 ×，整片可点删除（触达 ≥ 文本区，读屏播「删除关键词 X」）。 */
@Composable
private fun KeywordChip(key: String, onRemove: () -> Unit) {
    val colors = AppTheme.colors
    val cd = stringResource(R.string.wb_cd_remove_keyword, key)
    Row(
        modifier = Modifier
            .clip(AppShapes.full)
            .background(colors.accent.container)
            .clickable(onClickLabel = cd) { onRemove() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(key, style = MaterialTheme.typography.labelMedium, color = colors.accent.onContainer)
        Icon(Icons.Filled.Close, contentDescription = null, tint = colors.accent.onContainer, modifier = Modifier.size(14.dp))
    }
}
