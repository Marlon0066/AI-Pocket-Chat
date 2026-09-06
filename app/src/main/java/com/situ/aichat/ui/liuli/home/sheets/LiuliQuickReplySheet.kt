package com.situ.aichat.ui.liuli.home.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.ui.chat.MessagePreviewText
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliField
import com.situ.aichat.ui.liuli.designsystem.LiuliSheetShell
import com.situ.aichat.ui.chat.ChatListViewModel
import kotlinx.coroutines.launch

/** 头像 40（1:1 暖陶）。 */
private val AVATAR = 40.dp
/** 输入框行数上限（1:1 暖陶 `QuickReplySheet`）。 */
private const val QUICK_REPLY_MAX_LINES = 4

/**
 * 列表行长按的快捷回复面板（琉璃·换壳自暖陶 `QuickReplySheet`·图纸 A-13）。
 *
 * 顶部头像 + 名 + 最近几条可见消息预览 + 输入框 + 发送；发送 = 后台跑一轮 LLM 回复，面板随即关闭。
 * 文案 / 触觉（发送 `success()`、关闭 `light()`）/ 「立即清空防二次点击」的时序逐字同暖陶。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiuliQuickReplySheet(
    row: ChatListViewModel.Row,
    loadRecent: suspend () -> List<MessageEntity>,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptics = LocalAppHaptics.current
    var input by remember { mutableStateOf("") }
    var recent by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    LaunchedEffect(row.conversation.uuid) { recent = loadRecent() }

    fun closeAnimated() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    val youPrefix = stringResource(R.string.chat_list_you_prefix)

    LiuliSheetShell(onDismissRequest = { haptics.light(); onDismiss() }, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CharacterAvatar(name = row.displayName, avatarPath = row.character?.avatarPath, size = AVATAR)
                Text(row.displayName, style = AppTypography.titleSmall, color = colors.text.primary)
            }
            if (recent.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    recent.forEach { msg ->
                        val prefix = if (msg.roleRaw == "user") youPrefix else ""
                        Text(
                            prefix + MessagePreviewText.forMessage(msg),
                            style = AppTypography.secondary,
                            color = colors.text.secondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            LiuliField(
                value = input,
                onValueChange = { input = it },
                placeholder = stringResource(R.string.chat_quick_reply_hint),
                singleLine = false,
                maxLines = QUICK_REPLY_MAX_LINES, // 同暖陶 `QuickReplySheet` 的 maxLines 4（复核 R1 🔵-7）
                modifier = Modifier.fillMaxWidth(),
            )
            LiuliButton(
                onClick = {
                    haptics.success() // 发送成功（提交即成功——后台 LLM 轮次 fire-and-forget）
                    onSend(input.trim())
                    input = "" // 立即清空 → 按钮禁用，防 hide 动画期间二次点击多落一条重复消息。
                    closeAnimated()
                },
                style = LiuliButtonStyle.Prominent,
                enabled = input.isNotBlank(),
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.chat_quick_reply_send))
            }
        }
    }
}
