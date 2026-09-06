package com.situ.aichat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDialogTone
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.AppListScreenHeader
import com.situ.aichat.ui.designsystem.AppSearchField
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBarIcons
import com.situ.aichat.util.DateFormatters
import com.situ.aichat.util.rememberTimeTick

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    onOpenChat: (String) -> Unit,
    onCreateCharacter: () -> Unit,
    bottomContentPadding: Dp = 0.dp,
    viewModel: ChatListViewModel = hiltViewModel(),
) {
    val rows by viewModel.visibleRows.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val searchTerm by viewModel.searchTerm.collectAsStateWithLifecycle()
    val scheduleStatus by viewModel.scheduleStatus.collectAsStateWithLifecycle()
    val pickerCharacters by viewModel.pickerCharacters.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<ChatListViewModel.Row?>(null) }
    var quickReplyTarget by remember { mutableStateOf<ChatListViewModel.Row?>(null) }
    var showPicker by remember { mutableStateOf(false) }

    val relStrings = rememberRelativeTimeStrings()
    val nowMillis = rememberTimeTick()
    val isSearching = searchTerm.isNotEmpty()
    val pinned = rows.filter { it.conversation.isPinned }
    val unpinned = rows.filterNot { it.conversation.isPinned }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 大标题 + 右上角「新建对话」圆钮（三边视觉等距·过审 2026-06-19）。取代原 M3 TopAppBar。
            AppListScreenHeader(
                title = stringResource(R.string.tab_chats),
                actionIcon = AppTopBarIcons.Add,
                actionContentDescription = stringResource(R.string.chat_list_empty_cta),
                onAction = { showPicker = true },
            )
            AppSearchField(
                value = query,
                onValueChange = viewModel::setQuery,
                placeholder = stringResource(R.string.chat_list_search_hint),
                clearContentDescription = stringResource(R.string.chat_list_search_clear),
                modifier = Modifier
                    .fillMaxWidth()
                    // top=0：紧接页眉底缘，三边等距由页眉 edgeMargin 提供。
                    .padding(start = AppSpacing.screenGutter, end = AppSpacing.screenGutter, bottom = 8.dp),
            )

            if (rows.isEmpty() && !isSearching) {
                EmptyChatList(onStartConversation = { showPicker = true })
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomContentPadding)) {
                    if (pinned.isNotEmpty()) {
                        item(key = "pinned-header") {
                            PinnedSectionHeader()
                        }
                        items(pinned, key = { it.conversation.uuid }) { row ->
                            Column(Modifier.animateItem()) {
                                SwipeableChatRow(
                                    row = row,
                                    scheduleStatus = scheduleStatus[row.conversation.characterUuid],
                                    nowMillis = nowMillis,
                                    relStrings = relStrings,
                                    onOpen = { onOpenChat(row.conversation.uuid) },
                                    onTogglePin = { viewModel.setPinned(row.conversation.uuid, !row.conversation.isPinned) },
                                    onRequestDelete = { pendingDelete = row },
                                    onQuickReply = { quickReplyTarget = row },
                                )
                                AppListDivider()
                            }
                        }
                    }
                    items(unpinned, key = { it.conversation.uuid }) { row ->
                        Column(Modifier.animateItem()) {
                            SwipeableChatRow(
                                row = row,
                                scheduleStatus = scheduleStatus[row.conversation.characterUuid],
                                nowMillis = nowMillis,
                                relStrings = relStrings,
                                onOpen = { onOpenChat(row.conversation.uuid) },
                                onTogglePin = { viewModel.setPinned(row.conversation.uuid, !row.conversation.isPinned) },
                                onRequestDelete = { pendingDelete = row },
                                onQuickReply = { quickReplyTarget = row },
                            )
                            AppListDivider()
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        ConversationDeleteDialog(
            onConfirm = {
                viewModel.delete(target.conversation.uuid)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    quickReplyTarget?.let { target ->
        QuickReplySheet(
            row = target,
            loadRecent = { viewModel.recentMessages(target.conversation.uuid) },
            onSend = { text -> viewModel.quickReply(target.conversation.uuid, text) },
            onDismiss = { quickReplyTarget = null },
        )
    }

    // 聊天「+」/空态 CTA → 发起聊天角色选择器（过审 2026-06-19）。选完走 VM 取/建会话再进会话；空态兜底新建。
    if (showPicker) {
        NewConversationPickerSheet(
            characters = pickerCharacters,
            onPick = { character ->
                viewModel.startConversationWith(character) { conversationUuid ->
                    showPicker = false
                    onOpenChat(conversationUuid)
                }
            },
            onCreateNew = {
                showPicker = false
                onCreateCharacter()
            },
            onDismiss = { showPicker = false },
        )
    }
}

/** 置顶/普通行的滑动包装：左滑露出 删除（行尾，安全侧），右滑露出 置顶/取消置顶（行首，对齐 iOS）。 */
@Composable
private fun SwipeableChatRow(
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
    val colors = MaterialTheme.colorScheme
    val pinned = row.conversation.isPinned
    val pinAction = SwipeAction(
        label = stringResource(if (pinned) R.string.chat_list_action_unpin else R.string.chat_list_action_pin),
        icon = Icons.Filled.PushPin,
        containerColor = colors.tertiary,
        contentColor = colors.onTertiary,
        onClick = onTogglePin,
    )
    val deleteAction = SwipeAction(
        label = stringResource(R.string.action_delete),
        icon = Icons.Filled.Delete,
        containerColor = colors.error,
        contentColor = colors.onError,
        onClick = onRequestDelete,
    )
    SwipeActionsRow(
        onRowClick = onOpen,
        leadingActions = listOf(pinAction),
        trailingActions = listOf(deleteAction),
        modifier = modifier,
        onRowLongClick = onQuickReply,
    ) {
        ChatRow(row = row, scheduleStatus = scheduleStatus, nowMillis = nowMillis, relStrings = relStrings)
    }
}

/**
 * 聊天列表单行视图，两行制（Fable-5·过审 2026-06-20）：头像 +
 * 第一行〔置顶钉 + 角色名 ·(小圆点) 当前状态 … 相对时间〕+ 第二行〔最后消息预览(用户消息带「你: 」) + 未读角标(>99 显 99+)〕。
 * 当前状态 = 日程进行中事件（[scheduleStatus]，无则第一行仅名字）；名字优先取空间、状态空间不足先省略，时间戳恒显示在行尾。
 */
@Composable
fun ChatRow(
    row: ChatListViewModel.Row,
    scheduleStatus: String?,
    nowMillis: Long,
    relStrings: DateFormatters.RelativeTimeStrings,
) {
    val conv = row.conversation
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CharacterAvatar(name = row.displayName, avatarPath = row.character?.avatarPath, size = 52.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // 第一行：置顶钉 + 角色名 ·(小圆点) 当前状态 … 相对时间
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (conv.isPinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = stringResource(R.string.chat_list_pinned_desc),
                        tint = colors.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                // 名字 + 状态聚成一簇并占满（weight 1f），把时间戳推到行尾。weighted 子项在 Row 里最后测量、
                // 只分剩余空间——所以名字必须**不带** weight（先测量、优先取空间），weight(fill=false) 挂在状态上
                // （只吃名字剩下的空间，不足省略号截断）。挂反=状态超长时名字被挤到 0 宽（2026-07-17 真机实证）。
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.displayName,
                        style = AppTheme.typography.listName,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!scheduleStatus.isNullOrEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .size(4.dp)
                                .background(colors.onSurfaceVariant.copy(alpha = 0.55f), CircleShape),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            scheduleStatus,
                            style = AppTheme.typography.secondary,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    DateFormatters.relativeTimeString(conv.lastMessageDate ?: conv.creationDate, nowMillis, relStrings),
                    style = AppTheme.typography.captionNumeric,
                    color = colors.onSurfaceVariant,
                )
            }
            // 第二行：最后消息预览 … 未读角标
            Row(verticalAlignment = Alignment.CenterVertically) {
                val preview = chatListPreviewText(
                    conv = conv,
                    youPrefix = stringResource(R.string.chat_list_you_prefix),
                    noMessage = stringResource(R.string.chat_list_no_message),
                    unavailable = stringResource(R.string.chat_list_message_unavailable),
                )
                Text(
                    preview,
                    style = AppTheme.typography.listPreview,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val unread = conv.cachedUnreadCount
                if (unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    UnreadBadge(unread)
                }
            }
        }
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            if (count > 99) "99+" else "$count",
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun PinnedSectionHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.PushPin,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(14.dp).height(14.dp),
        )
        Text(
            stringResource(R.string.chat_list_section_pinned),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyChatList(onStartConversation: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                modifier = Modifier.width(56.dp).height(56.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.chat_list_empty_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.chat_list_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            AppButton(onClick = onStartConversation, style = AppButtonStyle.Primary) {
                Text(stringResource(R.string.chat_list_empty_cta))
            }
        }
    }
}

/** 删除会话确认（1:1 iOS confirmationDialog：标题 + 正文 + 红色「删除对话」+ 取消）。 */
@Composable
fun ConversationDeleteDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.chat_list_delete_title),
        body = stringResource(R.string.chat_list_delete_message),
        confirmText = stringResource(R.string.chat_list_delete_confirm),
        onConfirm = onConfirm,
        confirmTone = AppDialogTone.Danger,
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    )
}

/** 从资源装配相对时间本地化串（聊天列表用）。 */
@Composable
fun rememberRelativeTimeStrings(): DateFormatters.RelativeTimeStrings =
    DateFormatters.RelativeTimeStrings(
        justNow = stringResource(R.string.relative_time_just_now),
        minutesAgo = stringResource(R.string.relative_time_minutes_ago),
        hoursAgo = stringResource(R.string.relative_time_hours_ago),
        yesterday = stringResource(R.string.relative_time_yesterday),
    )
