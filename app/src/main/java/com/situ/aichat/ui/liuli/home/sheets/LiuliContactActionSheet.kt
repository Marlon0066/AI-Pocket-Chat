package com.situ.aichat.ui.liuli.home.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.rememberModalBottomSheetState
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliSheetShell
import com.situ.aichat.ui.liuli.page.LiuliRowBase

/** 动作行高 52（≥48 触达·微信 ActionSheet 式居中）与头像 40（1:1 暖陶）。 */
private val ACTION_ROW = 52.dp
private val AVATAR = 40.dp

/**
 * 联系人长按动作面板（琉璃·换壳自暖陶 `ContactActionSheet`·图纸 A-13）。
 *
 * 三行居中动作（查看资料 / 编辑 / 删除·删除走 `status.error`）+ 顶部头像名；
 * 文案 / 回调 / 触觉（每次点 `light()`、关闭也 `light()`）逐字同暖陶。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiuliContactActionSheet(
    character: CharacterEntity,
    onDismiss: () -> Unit,
    onViewProfile: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    LiuliSheetShell(
        onDismissRequest = { haptics.light(); onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            LiuliRowBase(divider = false, minHeight = 0.dp, verticalPadding = 4.dp) {
                CharacterAvatar(name = character.name, avatarPath = character.avatarPath, size = AVATAR)
                Box(Modifier.padding(start = 12.dp)) {
                    Text(
                        character.name,
                        style = AppTypography.titleSmall,
                        color = colors.text.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(Modifier.height(12.dp))
            ActionRow(stringResource(R.string.a11y_contact_open_profile), colors.text.primary, divider = false, onClick = onViewProfile)
            ActionRow(stringResource(R.string.action_edit), colors.text.primary, divider = true, onClick = onEdit)
            ActionRow(stringResource(R.string.action_delete), colors.status.onError, divider = true, onClick = onDelete)
        }
    }
}

/** 动作行：52 高、文字居中、点按 `light()` 触觉；不带图标（不为 3 项扩图标族·同暖陶）。 */
@Composable
private fun ActionRow(text: String, color: Color, divider: Boolean, onClick: () -> Unit) {
    val haptics = LocalAppHaptics.current
    val stroke = AppTheme.colors.surface.stroke
    Box(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ACTION_ROW)
                .clickable { haptics.light(); onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = AppTypography.bodyEmphasis, color = color)
        }
        if (divider) {
            Box(Modifier.align(Alignment.TopStart).fillMaxWidth().height(0.5.dp).background(stroke))
        }
    }
}
