package com.situ.aichat.ui.story

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDialogTone
import com.situ.aichat.ui.designsystem.AppTheme

/**
 * 已归档书长按删除的两件套（2026-08-04 用户指令卷·微图纸 docs/handoff/2026-08-04-归档故事长按删除.md）：
 * 书架「档案」横排小卡与「结局档案全览」网格共用，菜单/弹窗只在此定义一份。
 * 归档书无暂停/归档语义，菜单只有删除一条危险行；确认弹窗点名书名防在列表面上删错本。
 */

/** 归档卡长按菜单：ST10-4 玻璃容器 [StoryGlassMenu] + 单条危险删除行（行样式与在读卡菜单删除行同源）。 */
@Composable
internal fun StoryArchivedCardMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.colors
    StoryGlassMenu(expanded = expanded, onDismiss = onDismiss) {
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(R.string.story_menu_delete),
                    style = AppTheme.typography.body,
                    color = colors.status.onError,
                )
            },
            leadingIcon = {
                Icon(
                    AppActionIcons.Delete,
                    contentDescription = null,
                    tint = colors.status.onError,
                    modifier = Modifier.size(20.dp),
                )
            },
            onClick = onDelete,
            modifier = Modifier.heightIn(min = 48.dp),
        )
    }
}

/**
 * 归档书删除确认弹窗（措辞族 = book hub 删除弹窗：红确认钮 + 「不可恢复」正文，正文带书名）。
 * 调用方只存目标 id、渲染时从当前流解析出 [story]（PITFALLS 1b）——书被并行删除时弹窗自然消失。
 */
@Composable
internal fun StoryArchivedDeleteDialog(
    story: StoryEntity,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.story_hub_delete_title),
        body = stringResource(R.string.story_archived_delete_body, story.title),
        confirmText = stringResource(R.string.action_delete),
        onConfirm = onConfirm,
        confirmTone = AppDialogTone.Danger,
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    )
}
