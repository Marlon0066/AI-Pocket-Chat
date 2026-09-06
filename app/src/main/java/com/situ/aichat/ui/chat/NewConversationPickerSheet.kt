package com.situ.aichat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography

/**
 * 聊天「+」发起聊天的角色选择器（Fable-5·过审 2026-06-19·见 [FABLE5_NEW_CONVERSATION_PICKER_PROPOSAL.md]）。
 *
 * 底部弹窗（[ModalBottomSheet]·设计语言 §5「包壳 M3 不重写」）：
 * - **有角色**：顶部常驻「新建角色」行（陶土深档 [AppColors.accent].text）+ 角色列表（头像/名字/人设·整行可点）。
 * - **无角色**：居中空态（图标 + 文案 + 深陶按钮），兜底引导去新建。
 *
 * 选完角色 → [onPick]（上层走 [ConversationRepository.getOrCreateForCharacter] 幂等取/建会话再进会话）。
 * 排序由 [ChatListViewModel.pickerCharacters]（活跃在前·最近倒序）保证，本件只负责呈现。
 * 整行 [clickableScale]（calm 回弹·reduceMotion 自动降级）+ `mergeDescendants` 单焦点 + [Role.Button]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewConversationPickerSheet(
    characters: List<CharacterEntity>,
    onPick: (CharacterEntity) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit,
    // 琉璃传「即现」态（首帧在位、不滑入·用户 2026-09-06）；暖陶默认不变。
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    val colors = AppTheme.colors
    AppSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
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
                RowDivider()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    characters.forEachIndexed { index, character ->
                        CharacterPickRow(character = character, onPick = { onPick(character) })
                        if (index < characters.lastIndex) RowDivider()
                    }
                }
            }
        }
    }
}

/** 顶部「新建角色」行（陶土浅 tile 圆 + 深档「+」与文字）。 */
@Composable
private fun CreateNewRow(onCreateNew: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickableScale(role = Role.Button) { onCreateNew() }
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(colors.accent.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = colors.accent.text,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = stringResource(R.string.contacts_create),
            style = AppTypography.bodyEmphasis,
            color = colors.accent.text,
        )
    }
}

/** 单个角色行：头像 + 名字 + 一行人设副标题，整行点击发起/进入对话。 */
@Composable
private fun CharacterPickRow(character: CharacterEntity, onPick: () -> Unit) {
    val colors = AppTheme.colors
    val openLabel = stringResource(R.string.a11y_contact_open_chat)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickableScale(onClickLabel = openLabel, role = Role.Button) { onPick() }
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CharacterAvatar(character.name, character.avatarPath, 44.dp)
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

/** 列表行分隔：发丝细线，缩进对齐到文字起点（让头像列连续）。 */
@Composable
private fun RowDivider() {
    AppListDivider(modifier = Modifier.padding(start = 74.dp), startInset = 0.dp)
}

/** 无角色空态：陶土圆图标 + 文案 + 深陶「新建角色」按钮（兜底引导）。 */
@Composable
private fun EmptyPicker(onCreateNew: () -> Unit) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(colors.accent.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.PersonAdd,
                contentDescription = null,
                tint = colors.accent.text,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.chat_picker_empty_title),
            style = AppTypography.bodyEmphasis,
            color = colors.text.primary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.chat_picker_empty_subtitle),
            style = AppTypography.secondary,
            color = colors.text.secondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier
                .clip(AppShapes.full)
                .clickableScale(role = Role.Button) { onCreateNew() }
                .semantics(mergeDescendants = true) {}
                .background(Brush.horizontalGradient(listOf(colors.accent.deepStart, colors.accent.deepEnd)))
                .heightIn(min = 48.dp)
                .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = colors.accent.onDeep,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.contacts_create),
                style = AppTypography.label,
                color = colors.accent.onDeep,
            )
        }
    }
}
