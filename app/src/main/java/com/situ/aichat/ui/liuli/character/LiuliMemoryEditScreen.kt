package com.situ.aichat.ui.liuli.character

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.situ.aichat.R
import com.situ.aichat.prompt.memory.MemoryEditMode
import com.situ.aichat.prompt.memory.MemorySummarySections
import com.situ.aichat.ui.character.MemoryEditViewModel
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliDialogShell
import com.situ.aichat.ui.liuli.designsystem.LiuliField
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.liuliCardMaterial
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberScrollCollapsed
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/** 记忆手动编辑页（琉璃 2.0 脸）。 */
@Composable
fun LiuliMemoryEditScreen(
    onClose: () -> Unit,
    viewModel: MemoryEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val closed by viewModel.closed.collectAsState()
    val context = LocalContext.current
    val colors = AppTheme.colors
    val dark = LocalIsDarkTheme.current

    LaunchedEffect(closed) { if (closed) onClose() }
    LaunchedEffect(Unit) {
        viewModel.savedToast.collect { resId -> Toast.makeText(context, resId, Toast.LENGTH_SHORT).show() }
    }

    val scrollState = rememberScrollState()
    val collapsed = rememberScrollCollapsed(scrollState)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val title = stringResource(R.string.memory_edit_title)

    BackHandler(enabled = !state.saving) { viewModel.requestClose() }

    LiuliPage(
        title = title,
        onBack = { viewModel.requestClose() },
        backEnabled = !state.saving,
        collapsed = collapsed,
        actions = {
            LiuliButton(
                onClick = { viewModel.save() },
                style = LiuliButtonStyle.Prominent,
                enabled = state.canSave,
            ) { Text(stringResource(R.string.memory_edit_save)) }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(scrollState)
                .padding(horizontal = LiuliPageGeometry.gutter)
                .padding(top = LiuliPageGeometry.navRow + 4.dp, bottom = navBottom + LiuliPageGeometry.pageBottom),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LiuliLargeTitle(title)
            Text(stringResource(R.string.memory_edit_hint), style = AppTypography.caption, color = colors.text.tertiary)

            when (val mode = state.mode) {
                is MemoryEditMode.Sections -> {
                    LiuliMemorySectionCard(MemorySummarySections.LONG_TERM_HEADER, mode.longTermText, viewModel::updateLongTerm, !state.saving, dark)
                    LiuliMemorySectionCard(MemorySummarySections.RECENT_HEADER, mode.recentText, viewModel::updateRecent, !state.saving, dark)
                }
                is MemoryEditMode.Whole -> {
                    Text(
                        stringResource(R.string.memory_edit_fallback_notice),
                        fontSize = 12.sp,
                        color = colors.status.onWarning,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.status.warningContainer)
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                    )
                    Column(Modifier.fillMaxWidth().liuliCardMaterial(LiuliShapes.group, dark).padding(14.dp)) {
                        LiuliField(
                            value = mode.text,
                            onValueChange = { if (!state.saving) viewModel.updateWhole(it) },
                            singleLine = false,
                            minHeight = 220.dp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (state.mode is MemoryEditMode.Sections) {
                    Text(stringResource(R.string.memory_edit_recent_tip), fontSize = 11.5.sp, color = colors.text.tertiary)
                } else {
                    Spacer(Modifier.size(0.dp))
                }
                val over = state.maxLength > 0 && state.count > state.maxLength
                Text(
                    text = if (state.maxLength > 0) stringResource(R.string.memory_edit_count, state.count, state.maxLength)
                    else stringResource(R.string.memory_edit_count_unlimited, state.count),
                    style = AppTypography.secondary,
                    fontWeight = if (over) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (over) colors.status.onWarning else colors.text.tertiary,
                )
            }
        }
    }

    if (state.showDiscardDialog) {
        LiuliMemoryConfirmDialog(
            title = stringResource(R.string.memory_edit_discard_title),
            body = stringResource(R.string.memory_edit_discard_body),
            confirmText = stringResource(R.string.memory_edit_discard_confirm),
            dismissText = stringResource(R.string.memory_edit_discard_cancel),
            onConfirm = viewModel::confirmDiscard,
            onDismiss = viewModel::dismissDiscardDialog,
        )
    }

    if (state.conflict != null) {
        LiuliMemoryConfirmDialog(
            title = stringResource(R.string.memory_edit_conflict_title),
            body = stringResource(R.string.memory_edit_conflict_body),
            confirmText = stringResource(R.string.memory_edit_conflict_save),
            dismissText = stringResource(R.string.memory_edit_conflict_reload),
            onConfirm = { viewModel.save(force = true) },
            onDismiss = viewModel::reloadFromConflict,
        )
    }
}

@Composable
private fun LiuliMemorySectionCard(
    header: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    dark: Boolean,
) {
    val colors = AppTheme.colors
    Column(
        Modifier.fillMaxWidth().liuliCardMaterial(LiuliShapes.group, dark).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(header, style = AppTypography.label, fontWeight = FontWeight.SemiBold, color = colors.accent.text)
            Text(
                stringResource(R.string.memory_edit_section_locked),
                fontSize = 10.5.sp,
                color = colors.text.tertiary,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(colors.surface.sunken).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        LiuliField(
            value = value,
            onValueChange = { if (enabled) onValueChange(it) },
            singleLine = false,
            minHeight = 120.dp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LiuliMemoryConfirmDialog(
    title: String,
    body: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    LiuliDialogShell(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = AppTypography.titleSmall, color = colors.text.primary)
            Text(body, style = AppTypography.dialogBody, color = colors.text.secondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiuliButton(onClick = onDismiss, style = LiuliButtonStyle.Glass, modifier = Modifier.weight(1f)) { Text(dismissText) }
                LiuliButton(onClick = onConfirm, style = LiuliButtonStyle.Prominent, modifier = Modifier.weight(1f)) { Text(confirmText) }
            }
        }
    }
}
