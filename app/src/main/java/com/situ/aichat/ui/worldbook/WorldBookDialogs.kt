package com.situ.aichat.ui.worldbook

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.worldbook.WorldBookCodec
import com.situ.aichat.data.worldbook.WorldBookRepository
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDialogTone
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppTextArea
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTheme

/**
 * 设定集共用弹窗件（WB7a·契约 §12.2/§12.6）：新建/编辑名简介、删除确认、导入成功弹层、导入失败人话弹窗。
 * 对话框 confirm/dismiss 槽照按钮族 P2 拍板留 M3 [TextButton]。
 */

/** 新建 / 编辑「名字与简介」共用（新建 = 名字 + 可选简介，契约 §12.10）。 */
@Composable
internal fun BookMetaDialog(
    title: String,
    initialName: String,
    initialDescription: String,
    onConfirm: (name: String, description: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }
    AppDialog(
        onDismissRequest = onDismiss,
        title = title,
        confirmText = stringResource(R.string.action_save),
        onConfirm = { onConfirm(name, description) },
        confirmEnabled = name.isNotBlank(),
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.wb_name_label),
                    placeholder = stringResource(R.string.wb_name_placeholder),
                )
                AppTextArea(
                    value = description,
                    onValueChange = { description = it },
                    label = stringResource(R.string.wb_desc_label),
                    placeholder = stringResource(R.string.wb_desc_placeholder),
                    minHeight = 88.dp,
                    // 限 6 行防超长简介撑爆弹窗正文区，超出走 AppTextArea 内部滚动（2026-07-31 修缮拍板）。
                    maxLines = 6,
                )
            }
        },
    )
}

/** 删除整本书确认（不可恢复·红档动作字）。 */
@Composable
internal fun DeleteBookDialog(
    bookName: String,
    entryCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.wb_delete_confirm_title),
        body = stringResource(R.string.wb_delete_confirm_body, bookName, entryCount),
        confirmText = stringResource(R.string.action_delete),
        onConfirm = onConfirm,
        confirmTone = AppDialogTone.Danger,
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    )
}

/** 导入成功底部弹层：书名 + 条数 + 识别格式 + 跳过数（有则显）→ 去看看 / 完成。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportSuccessSheet(
    result: WorldBookRepository.ImportResult,
    onView: (bookUuid: String) -> Unit,
    onDone: () -> Unit,
) {
    val colors = AppTheme.colors
    AppSheet(onDismissRequest = onDone) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(colors.status.successContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppActionIcons.Check, contentDescription = null, tint = colors.status.onSuccess)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.wb_import_success_title, result.bookName),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text.primary,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.wb_import_success_meta, result.entryCount, importFormatName(result.format)),
                style = MaterialTheme.typography.bodySmall,
                color = colors.text.secondary,
            )
            if (result.skippedEntryCount > 0) {
                Text(
                    stringResource(R.string.wb_import_skipped, result.skippedEntryCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.status.onWarning,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppButton(onClick = { onView(result.bookUuid) }) { Text(stringResource(R.string.wb_import_view)) }
                AppButton(onClick = onDone, style = AppButtonStyle.Text) { Text(stringResource(R.string.wb_import_done)) }
            }
        }
    }
}

@Composable
private fun importFormatName(format: WorldBookCodec.WorldBookFormat): String = stringResource(
    when (format) {
        WorldBookCodec.WorldBookFormat.ST_STANDALONE -> R.string.wb_format_standalone
        WorldBookCodec.WorldBookFormat.CHARACTER_CARD -> R.string.wb_format_character_card
        WorldBookCodec.WorldBookFormat.CHARACTER_BOOK -> R.string.wb_format_character_book
    },
)

/** 导入失败弹窗：正文 = 解析器人话原文直显（null = 文件读取失败文案）+ 支持格式一句话（契约 §12.6）。 */
@Composable
internal fun ImportFailureDialog(parseMessage: String?, onDismiss: () -> Unit) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.wb_import_error_title),
        confirmText = stringResource(R.string.wb_got_it),
        onConfirm = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(parseMessage ?: stringResource(R.string.wb_import_error_read))
                Text(
                    stringResource(R.string.wb_import_error_supported),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.text.secondary,
                )
            }
        },
    )
}
