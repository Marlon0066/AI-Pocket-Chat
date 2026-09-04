package com.situ.aichat.ui.character

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppFormBar
import com.situ.aichat.ui.designsystem.AppTextArea
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography

/**
 * 记忆手动编辑页（图纸 2026-09-01「记忆与防污染加固批」件③·D-2/D-3/D-4/D-5 已过审）。
 *
 * 分区态两节各一个 [AppTextArea]（节头只读·标题固定）；老记忆无标准分节时退化整段编辑并挂琥珀提示条。
 * 计数超上限只变色不拦保存（D-5）；未保存返回与保存冲突各一道确认弹窗。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryEditScreen(
    onClose: () -> Unit,
    viewModel: MemoryEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val closed by viewModel.closed.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(closed) { if (closed) onClose() }
    // 保存成功 toast（照资料页 memoryGuardToast 同款：Toast 挂 applicationContext 语境，页面被 pop 也照常出）。
    LaunchedEffect(Unit) {
        viewModel.savedToast.collect { resId -> Toast.makeText(context, resId, Toast.LENGTH_SHORT).show() }
    }

    // 保存中一律忽略返回（预测性返回审计 F3 教训：写入途中被撕会留半份状态）。
    val scrollState = rememberScrollState()

    BackHandler(enabled = !state.saving) { viewModel.requestClose() }

    Scaffold(
        topBar = {
            AppFormBar(
                title = stringResource(R.string.memory_edit_title),
                lifted = scrollState.value > 0,
                // ✕ → 文字「取消」（拍板④）；原 cd 挂的 memory_edit_discard_confirm 由确认弹窗继续使用。
                onCancel = { viewModel.requestClose() },
                trailing = {
                    AppButton(
                        onClick = { viewModel.save() },
                        enabled = state.canSave,
                    ) {
                        Text(stringResource(R.string.memory_edit_save))
                    }
                },
            )
        },
    ) { padding ->
        val colors = AppTheme.colors
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(scrollState) // 滚动承重在此（弹层键盘遮挡战役教训）
                .contentMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.memory_edit_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.text.tertiary,
            )

            when (val mode = state.mode) {
                is MemoryEditMode.Sections -> {
                    MemorySectionEditor(
                        header = MemorySummarySections.LONG_TERM_HEADER,
                        value = mode.longTermText,
                        onValueChange = viewModel::updateLongTerm,
                        enabled = !state.saving,
                    )
                    MemorySectionEditor(
                        header = MemorySummarySections.RECENT_HEADER,
                        value = mode.recentText,
                        onValueChange = viewModel::updateRecent,
                        enabled = !state.saving,
                    )
                }
                is MemoryEditMode.Whole -> {
                    MemoryFallbackNotice()
                    AppTextArea(
                        value = mode.text,
                        onValueChange = viewModel::updateWhole,
                        enabled = !state.saving,
                        minHeight = 240.dp,
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.mode is MemoryEditMode.Sections) {
                    Text(
                        stringResource(R.string.memory_edit_recent_tip),
                        fontSize = 11.5.sp,
                        color = colors.text.tertiary,
                    )
                } else {
                    Spacer(Modifier.size(0.dp))
                }
                MemoryEditCounter(count = state.count, maxLength = state.maxLength)
            }
        }
    }

    if (state.showDiscardDialog) {
        AppDialog(
            onDismissRequest = viewModel::dismissDiscardDialog,
            title = stringResource(R.string.memory_edit_discard_title),
            body = stringResource(R.string.memory_edit_discard_body),
            confirmText = stringResource(R.string.memory_edit_discard_confirm),
            onConfirm = viewModel::confirmDiscard,
            dismissText = stringResource(R.string.memory_edit_discard_cancel),
            onDismiss = viewModel::dismissDiscardDialog,
        )
    }

    if (state.conflict != null) {
        AppDialog(
            onDismissRequest = viewModel::reloadFromConflict,
            title = stringResource(R.string.memory_edit_conflict_title),
            body = stringResource(R.string.memory_edit_conflict_body),
            confirmText = stringResource(R.string.memory_edit_conflict_save),
            onConfirm = { viewModel.save(force = true) },
            dismissText = stringResource(R.string.memory_edit_conflict_reload),
            onDismiss = viewModel::reloadFromConflict,
        )
    }
}

/** 一节 = 只读节头行（标题固定小胶囊）+ 正文编辑框。 */
@Composable
private fun MemorySectionEditor(
    header: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
) {
    val colors = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                header,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.accent.text,
            )
            Text(
                stringResource(R.string.memory_edit_section_locked),
                fontSize = 10.5.sp,
                color = colors.text.tertiary,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.surface.sunken)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        AppTextArea(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            minHeight = 120.dp,
        )
    }
}

/** 整段退化态提示（琥珀条·与记忆卡遇阻条同族取色，无按钮）。 */
@Composable
private fun MemoryFallbackNotice() {
    val status = AppTheme.colors.status
    Text(
        stringResource(R.string.memory_edit_fallback_notice),
        fontSize = 12.sp,
        color = status.onWarning,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(status.warningContainer)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    )
}

/** 字数计数：超上限只变色警示，不拦保存（D-5）；上限 ≤0 = 用户关了上限，只报字数。 */
@Composable
private fun MemoryEditCounter(count: Int, maxLength: Int) {
    val colors = AppTheme.colors
    val over = maxLength > 0 && count > maxLength
    Text(
        text = if (maxLength > 0) {
            stringResource(R.string.memory_edit_count, count, maxLength)
        } else {
            stringResource(R.string.memory_edit_count_unlimited, count)
        },
        style = AppTypography.secondary,
        fontWeight = if (over) FontWeight.SemiBold else FontWeight.Normal,
        color = if (over) colors.status.onWarning else colors.text.tertiary,
    )
}
