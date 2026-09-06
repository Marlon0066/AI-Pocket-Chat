package com.situ.aichat.ui.liuli.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.chat.ChatListViewModel
import com.situ.aichat.ui.chat.SwipeAction
import com.situ.aichat.ui.chat.SwipeActionsRow
import com.situ.aichat.ui.chat.chatListPreviewText
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.util.DateFormatters

/** 行内小件的落值（§3.2「列表行」）：钉 14 · 状态点 4 · 名与状态 / 状态与时间之间的缝。 */
private val PIN_ICON = 14.dp
private val STATUS_DOT = 4.dp
private val INLINE_GAP = 6.dp
private val TIME_GAP = 8.dp
private const val STATUS_DOT_ALPHA = 0.55f

/**
 * 琉璃聊天列表一行（图纸 2026-09-06 卷三 §4.3 A · 契约 §6 A 甲）。
 *
 * **机制照抄暖陶、皮不抄**：左滑露出「置顶 / 删除」仍是暖陶那具 [SwipeActionsRow]（吸附阈值 / 触感 / 手势
 * 认领一个字不改·A-7 只换色 token），长按 = 快速回复，预览文案走同一个 `chatListPreviewText`。
 * 长相走 [LiuliListRow]：头像 54 · 名 16/600 · 预览 14 · 时间 12 tnum · 未读钴蓝丸。
 *
 * 对版稿画的 44 玻璃圆钮动作是卷三B 的挂账（机制不重写·A-7）。
 */
@Composable
fun LiuliChatListRow(
    row: ChatListViewModel.Row,
    scheduleStatus: String?,
    nowMillis: Long,
    relStrings: DateFormatters.RelativeTimeStrings,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onRequestDelete: () -> Unit,
    onQuickReply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val conv = row.conversation
    val pinAction = SwipeAction(
        label = stringResource(if (conv.isPinned) R.string.chat_list_action_unpin else R.string.chat_list_action_pin),
        icon = Icons.Filled.PushPin,
        // 面底由 `LiuliSwipeActionFace` 自己画纸面，故这里给透明；contentColor 只喂图标（A-12）。
        containerColor = Color.Transparent,
        contentColor = colors.accent.text,
        onClick = onTogglePin,
    )
    val deleteAction = SwipeAction(
        label = stringResource(R.string.action_delete),
        icon = Icons.Filled.Delete,
        containerColor = Color.Transparent,
        contentColor = colors.status.onError,
        onClick = onRequestDelete,
    )
    SwipeActionsRow(
        onRowClick = onOpen,
        leadingActions = listOf(pinAction),
        trailingActions = listOf(deleteAction),
        modifier = modifier,
        onRowLongClick = onQuickReply,
        actionFace = { action, faceModifier, onClick -> LiuliSwipeActionFace(action, faceModifier, onClick) },
    ) {
        LiuliListRow(
            avatar = { CharacterAvatar(name = row.displayName, avatarPath = row.character?.avatarPath, size = LiuliHomeGeometry.rowAvatar) },
            primary = {
                if (conv.isPinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = stringResource(R.string.chat_list_pinned_desc),
                        tint = colors.accent.text,
                        modifier = Modifier.size(PIN_ICON),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                // 名字**不带** weight（先测量、优先取空间），weight(fill=false) 挂在状态上——挂反 = 状态超长时
                // 名字被挤到 0 宽（暖陶 2026-07-17 真机实证·F4 原注）。
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.displayName,
                        style = AppTypography.listName,
                        color = colors.text.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!scheduleStatus.isNullOrEmpty()) {
                        Spacer(Modifier.width(INLINE_GAP))
                        Box(Modifier.size(STATUS_DOT).background(colors.text.tertiary.copy(alpha = STATUS_DOT_ALPHA), CircleShape))
                        Spacer(Modifier.width(INLINE_GAP))
                        Text(
                            scheduleStatus,
                            style = AppTypography.secondary,
                            color = colors.text.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
                Spacer(Modifier.width(TIME_GAP))
                Text(
                    DateFormatters.relativeTimeString(conv.lastMessageDate ?: conv.creationDate, nowMillis, relStrings),
                    style = AppTypography.captionNumeric.copy(fontSize = 12.sp),
                    color = colors.text.tertiary,
                )
            },
            secondary = {
                Text(
                    chatListPreviewText(
                        conv = conv,
                        youPrefix = stringResource(R.string.chat_list_you_prefix),
                        noMessage = stringResource(R.string.chat_list_no_message),
                        unavailable = stringResource(R.string.chat_list_message_unavailable),
                    ),
                    style = AppTypography.listPreview,
                    color = colors.text.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (conv.cachedUnreadCount > 0) {
                    Spacer(Modifier.width(TIME_GAP))
                    LiuliUnreadPill(conv.cachedUnreadCount)
                }
            },
        )
    }
}
