package com.situ.aichat.ui.liuli.home.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliSheetShell
import com.situ.aichat.ui.liuli.designsystem.rememberLiuliInstantSheetState
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry

/** 列表定高滚动窗 380 / 头像与 tile 44 / 空态圆 64（1:1 暖陶）。 */
private val LIST_MAX = 380.dp
private val ROW_AVATAR = 44.dp
/** 头像 ↔ 文字的缝（1:1 暖陶）。 */
private val ROW_GAP = 14.dp
private val EMPTY_CIRCLE = 64.dp
private const val TILE_BG_ALPHA = 0.15f

/**
 * 发起对话的角色选择器（琉璃·换壳自暖陶 `NewConversationPickerSheet`·图纸 A-13）。
 *
 * 结构、文案、排序、列表上限 380、空态兜底引导逐字同暖陶；壳换 [LiuliSheetShell]，
 * 面板走 [rememberLiuliInstantSheetState]（**即现**·首帧在位不滑入·用户 2026-09-06 拍板 C5）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiuliNewConversationPickerSheet(
    characters: List<CharacterEntity>,
    onPick: (CharacterEntity) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberLiuliInstantSheetState(),
) {
    val colors = AppTheme.colors
    LiuliSheetShell(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(
                text = stringResource(R.string.chat_picker_title),
                style = AppTypography.titleSmall,
                color = colors.text.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
            )
            if (characters.isEmpty()) {
                EmptyPicker(onCreateNew = onCreateNew)
            } else {
                CreateNewRow(onCreateNew = onCreateNew)
                Column(Modifier.fillMaxWidth().heightIn(max = LIST_MAX).verticalScroll(rememberScrollState())) {
                    characters.forEach { character ->
                        CharacterPickRow(character = character, onPick = { onPick(character) })
                    }
                }
            }
        }
    }
}

/** 顶部「新建角色」行（accent 淡 tile 圆 + 「+」与文字）。 */
@Composable
private fun CreateNewRow(onCreateNew: () -> Unit) {
    val colors = AppTheme.colors
    LiuliRowBase(onClick = onCreateNew, divider = false, minHeight = 0.dp, verticalPadding = 10.dp) {
        Box(
            Modifier.size(ROW_AVATAR).clip(CircleShape).background(colors.accent.primary.copy(alpha = TILE_BG_ALPHA)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(stringResource(R.string.contacts_create), style = AppTypography.bodyEmphasis, color = colors.accent.text)
    }
}

/** 单个角色行：头像 + 名字 + 一行人设副标题，整行点击发起 / 进入对话。 */
@Composable
private fun CharacterPickRow(character: CharacterEntity, onPick: () -> Unit) {
    val colors = AppTheme.colors
    // 发丝起点对齐文字（16 + 头像 44 + 缝 14 = 74·同暖陶 `RowDivider`）；onClickLabel 同暖陶 `a11y_contact_open_chat`（复核 R1 🔵-6）。
    LiuliRowBase(
        onClick = onPick,
        onClickLabel = stringResource(R.string.a11y_contact_open_chat),
        minHeight = 0.dp,
        verticalPadding = 10.dp,
        dividerInset = LiuliPageGeometry.groupPadH + ROW_AVATAR + ROW_GAP,
    ) {
        CharacterAvatar(character.name, character.avatarPath, ROW_AVATAR)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = character.name,
                style = AppTypography.bodyEmphasis,
                color = colors.text.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (character.personalityDescription.isNotBlank()) {
                Text(
                    text = character.personalityDescription,
                    style = AppTypography.secondary,
                    color = colors.text.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 无角色空态：accent 圆图标 + 文案 + 「新建角色」主钮（兜底引导·同暖陶）。 */
@Composable
private fun EmptyPicker(onCreateNew: () -> Unit) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 16.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(EMPTY_CIRCLE).clip(CircleShape).background(colors.accent.primary.copy(alpha = TILE_BG_ALPHA)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.PersonAdd, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.chat_picker_empty_title), style = AppTypography.bodyEmphasis, color = colors.text.primary)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.chat_picker_empty_subtitle),
            style = AppTypography.secondary,
            color = colors.text.secondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        LiuliButton(onClick = onCreateNew, style = LiuliButtonStyle.Prominent) {
            Text(stringResource(R.string.contacts_create))
        }
    }
}
