package com.situ.aichat.ui.ourdays

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDialogTone
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppSwitch
import com.situ.aichat.ui.designsystem.AppTextArea
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import java.time.LocalDate

/**
 * 「改一改」sheet（卷三图纸 §4.7·提案 D-9·帧 10）：楷体编辑区（150dp 起高·sunken 底）+「这天别让 TA 记」开关 + 删除文字钮 + 保存主钮
 * （空白 / 未改动禁用·判据在 composable 体内每次求值·PITFALLS §1h）。关闭：有改动弹「放弃修改？」（Danger），无改动直接关。
 * 键盘：内容列 `imePadding() + verticalScroll`（照 `CharacterWalletEditSheet`·承重件 = verticalScroll）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OurDayEditSheet(
    date: LocalDate,
    draft: String,
    draftHidden: Boolean,
    isDirty: () -> Boolean,
    onDraftChange: (String) -> Unit,
    onHiddenChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    var discardOpen by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    AppSheet(onDismissRequest = { if (isDirty()) discardOpen = true else onClose() }, sheetState = sheetState) {
        OurDayEditSheetBody(date, draft, draftHidden, isDirty, onDraftChange, onHiddenChange, onSave, onDelete)
    }
    if (discardOpen) {
        AppDialog(
            onDismissRequest = { discardOpen = false },
            title = stringResource(R.string.our_days_discard_title),
            confirmText = stringResource(R.string.our_days_discard_confirm),
            confirmTone = AppDialogTone.Danger,
            onConfirm = { discardOpen = false; onClose() },
            dismissText = stringResource(R.string.our_days_discard_keep),
            onDismiss = { discardOpen = false },
        )
    }
}

@Composable
internal fun OurDayEditSheetBody(
    date: LocalDate,
    draft: String,
    draftHidden: Boolean,
    isDirty: () -> Boolean,
    onDraftChange: (String) -> Unit,
    onHiddenChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.our_days_edit_title, OurDaysFormat.date(date, stringResource(R.string.our_days_fmt_md))),
            style = AppTypography.titleSmall,
            color = colors.text.primary,
        )
        AppTextArea(
            value = draft,
            onValueChange = onDraftChange,
            minHeight = 150.dp,
            placeholder = stringResource(R.string.our_days_edit_placeholder),
            textStyle = AppTypography.kaiQuote.copy(fontSize = 15.sp, lineHeight = 28.sp),
            containerColor = colors.surface.sunken,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.our_days_edit_hide), style = AppTypography.label, color = colors.text.primary)
                Text(stringResource(R.string.our_days_edit_hide_sub), style = AppTypography.caption.copy(fontSize = 11.5.sp), color = colors.text.tertiary)
            }
            AppSwitch(checked = draftHidden, onCheckedChange = onHiddenChange)
        }
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AppButton(onClick = onDelete, style = AppButtonStyle.Text, danger = true) { Text(stringResource(R.string.our_days_edit_delete)) }
            AppButton(onClick = onSave, style = AppButtonStyle.Primary, enabled = draft.isNotBlank() && isDirty(), modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}
