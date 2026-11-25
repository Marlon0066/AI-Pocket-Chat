package com.situ.aichat.ui.liuli.worldbook

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.situ.aichat.R
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBarIcons
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliDialogShell
import com.situ.aichat.ui.liuli.designsystem.LiuliField
import com.situ.aichat.ui.liuli.designsystem.LiuliMenuEntry
import com.situ.aichat.ui.liuli.designsystem.LiuliPopupMenu
import com.situ.aichat.ui.liuli.designsystem.LiuliSegmented
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.liuliCardMaterial
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageCircleAction
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliSectionHeader
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.liuli.page.rememberScrollCollapsed
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import com.situ.aichat.ui.worldbook.EntryAdvancedSections
import com.situ.aichat.ui.worldbook.KeywordChipsEditor
import com.situ.aichat.ui.worldbook.WorldBookEntryEditViewModel
import com.situ.aichat.ui.worldbook.WorldBookTriggerMode

/** 设定集条目编辑器（琉璃 2.0 脸）。 */
@Composable
fun LiuliWorldBookEntryEditScreen(
    onDone: () -> Unit,
    viewModel: WorldBookEntryEditViewModel = hiltViewModel(),
) {
    val draft = viewModel.draft
    val colors = AppTheme.colors
    val dark = LocalIsDarkTheme.current
    var showDiscard by remember { mutableStateOf(false) }
    var showDeleteEntry by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    BackHandler(enabled = viewModel.isDirty) { showDiscard = true }

    val entry = draft ?: return
    val guide = viewModel.guideCategory
    val mode = viewModel.triggerMode(entry)
    val requestBack: () -> Unit = { if (viewModel.isDirty) showDiscard = true else onDone() }

    val scrollState = rememberScrollState()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val title = stringResource(if (viewModel.isEditing) R.string.wb_entry_edit_title else R.string.wb_entry_new_title)

    LiuliPage(
        title = title,
        onBack = requestBack,
        collapsed = rememberScrollCollapsed(scrollState),
        actions = {
            if (viewModel.isEditing) {
                Box {
                    LiuliPageCircleAction(
                        onClick = { showMenu = true },
                        contentDescription = stringResource(R.string.wb_cd_more_actions),
                        icon = AppTopBarIcons.More,
                    )
                    LiuliPopupMenu(
                        expanded = showMenu,
                        onDismiss = { showMenu = false },
                        items = listOf(LiuliMenuEntry(stringResource(R.string.wb_delete_entry), danger = true) { showDeleteEntry = true }),
                    )
                }
            }
            LiuliButton(onClick = { viewModel.save(onDone) }, style = LiuliButtonStyle.Prominent) {
                Text(stringResource(R.string.action_save))
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(scrollState)
                .contentMaxWidth()
                .padding(horizontal = LiuliPageGeometry.gutter)
                .padding(top = LiuliPageGeometry.navRow + 4.dp, bottom = navBottom + LiuliPageGeometry.pageBottom),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LiuliLargeTitle(title)
            LiuliField(
                value = entry.comment,
                onValueChange = { v -> viewModel.update { it.copy(comment = v) } },
                label = stringResource(R.string.wb_entry_title_label),
                placeholder = stringResource(guide?.titlePlaceholderRes ?: R.string.wb_entry_title_placeholder),
            )
            Column(
                modifier = Modifier.fillMaxWidth().liuliCardMaterial(LiuliShapes.group, dark).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.wb_trigger_mode), style = AppTypography.caption, color = colors.text.secondary)
                LiuliSegmented(
                    options = WorldBookTriggerMode.entries.toList(),
                    selected = mode,
                    label = { m ->
                        stringResource(
                            when (m) {
                                WorldBookTriggerMode.KEYWORD -> R.string.wb_mode_keyword
                                WorldBookTriggerMode.CONSTANT -> R.string.wb_mode_constant
                                WorldBookTriggerMode.VECTOR -> R.string.wb_mode_vector
                            },
                        )
                    },
                    onSelect = viewModel::setTriggerMode,
                )
                Text(
                    stringResource(
                        when (mode) {
                            WorldBookTriggerMode.KEYWORD -> R.string.wb_mode_keyword_hint
                            WorldBookTriggerMode.CONSTANT -> R.string.wb_mode_constant_hint
                            WorldBookTriggerMode.VECTOR -> R.string.wb_mode_vector_hint
                        },
                    ),
                    style = AppTypography.caption,
                    color = colors.text.secondary,
                )
            }
            if (mode == WorldBookTriggerMode.KEYWORD) {
                Column(modifier = Modifier.fillMaxWidth().liuliCardMaterial(LiuliShapes.group, dark).padding(14.dp)) {
                    KeywordChipsEditor(
                        label = stringResource(R.string.wb_keywords_label),
                        keys = viewModel.keys(entry),
                        onAdd = { viewModel.addKeys(it) },
                        onRemove = { viewModel.removeKey(it) },
                        hint = stringResource(R.string.wb_keywords_hint),
                        inputValue = viewModel.pendingPrimaryKeyInput,
                        onInputValueChange = { viewModel.pendingPrimaryKeyInput = it },
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LiuliField(
                    value = entry.content,
                    onValueChange = { v -> viewModel.update { it.copy(content = v) } },
                    label = stringResource(R.string.wb_content_label),
                    placeholder = guide?.contentPlaceholderRes?.let { stringResource(it) },
                    singleLine = false,
                    minHeight = 148.dp,
                )
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.wb_content_macro_hint), style = AppTypography.caption, color = colors.text.secondary)
                    Text(stringResource(R.string.wb_char_count, entry.content.length), style = AppTypography.caption, color = colors.text.secondary)
                }
            }
            Column(modifier = Modifier.fillMaxWidth().liuliCardMaterial(LiuliShapes.group, dark)) {
                LiuliToggleRow(stringResource(R.string.wb_entry_enabled), checked = entry.enabled, onCheckedChange = { v -> viewModel.update { it.copy(enabled = v) } })
            }
            LiuliSectionHeader(stringResource(R.string.wb_advanced))
            EntryAdvancedSections(entry = entry, viewModel = viewModel, cardModifier = { Modifier.liuliCardMaterial(LiuliShapes.group, dark) })
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showDiscard) {
        LiuliConfirmDialog(
            title = stringResource(R.string.wb_discard_title),
            body = stringResource(R.string.wb_discard_body),
            confirmText = stringResource(R.string.wb_discard_confirm),
            onConfirm = { showDiscard = false; onDone() },
            onDismiss = { showDiscard = false },
        )
    }
    if (showDeleteEntry) {
        LiuliConfirmDialog(
            title = stringResource(R.string.wb_delete_entry_title),
            body = stringResource(R.string.wb_delete_entry_body, entry.comment.ifBlank { stringResource(R.string.wb_entry_untitled) }),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = { showDeleteEntry = false; viewModel.deleteEntry(onDeleted = onDone) },
            onDismiss = { showDeleteEntry = false },
        )
    }
}

@Composable
private fun LiuliConfirmDialog(
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    LiuliDialogShell(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = AppTypography.titleMedium, color = colors.text.primary)
            Text(body, style = AppTypography.dialogBody, color = colors.text.secondary)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiuliButton(onClick = onDismiss, style = LiuliButtonStyle.Glass, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_cancel))
                }
                LiuliButton(onClick = onConfirm, style = LiuliButtonStyle.Glass, modifier = Modifier.weight(1f)) {
                    Text(confirmText, color = colors.status.onError)
                }
            }
        }
    }
}
