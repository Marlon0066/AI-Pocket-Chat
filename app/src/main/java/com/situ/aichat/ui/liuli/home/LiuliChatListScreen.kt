package com.situ.aichat.ui.liuli.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.chat.ChatListViewModel
import com.situ.aichat.ui.liuli.home.sheets.LiuliNewConversationPickerSheet
import com.situ.aichat.ui.liuli.home.sheets.LiuliQuickReplySheet
import com.situ.aichat.ui.chat.rememberRelativeTimeStrings
import com.situ.aichat.ui.designsystem.AppTopBarIcons
import com.situ.aichat.ui.liuli.designsystem.LiuliCircleButton
import com.situ.aichat.ui.liuli.designsystem.LiuliDialog
import com.situ.aichat.ui.liuli.designsystem.LiuliSearchSlot
import com.situ.aichat.ui.liuli.designsystem.rememberLiuliInstantSheetState
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliSectionHeader
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.util.DateFormatters
import com.situ.aichat.util.rememberTimeTick

/**
 * 琉璃聊天列表（图纸 2026-09-06 卷三 §4.3 A · 契约 §6 A 甲）。
 *
 * 薄壳：只订阅 [ChatListViewModel] 现成的五条流与三个瞬态旗标，一个 VM 方法都不新增——列表数据 / 排序 /
 * 置顶 / 搜索过滤 / 删除 / 快速回复 / 新建对话全是与暖陶**同一份代码**。长相在 [LiuliChatListContent]
 * （无 VM·T2 可直接驱动）。
 *
 * 三个借用弹层（快速回复 / 新建对话选择器）本卷原样借暖陶壳（A-6·换壳挂卷三B）；删除确认已换
 * [LiuliDialog]（文案逐字同暖陶 `ConversationDeleteDialog`）。
 */
@OptIn(ExperimentalMaterial3Api::class) // `SheetState`（即现弹层态）
@Composable
fun LiuliChatListScreen(
    onOpenChat: (String) -> Unit,
    onCreateCharacter: () -> Unit,
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

    LiuliChatListContent(
        rows = rows,
        query = query,
        isSearching = searchTerm.isNotEmpty(),
        scheduleStatus = scheduleStatus,
        nowMillis = rememberTimeTick(),
        relStrings = rememberRelativeTimeStrings(),
        onQueryChange = viewModel::setQuery,
        onOpenChat = { onOpenChat(it.conversation.uuid) },
        onTogglePin = { viewModel.setPinned(it.conversation.uuid, !it.conversation.isPinned) },
        onRequestDelete = { pendingDelete = it },
        onQuickReply = { quickReplyTarget = it },
        onNewConversation = { showPicker = true },
    )

    pendingDelete?.let { target ->
        LiuliDialog(
            onDismissRequest = { pendingDelete = null },
            title = stringResource(R.string.chat_list_delete_title),
            body = stringResource(R.string.chat_list_delete_message),
            confirmText = stringResource(R.string.chat_list_delete_confirm),
            onConfirm = {
                viewModel.delete(target.conversation.uuid)
                pendingDelete = null
            },
            confirmDanger = true,
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { pendingDelete = null },
        )
    }

    quickReplyTarget?.let { target ->
        LiuliQuickReplySheet(
            row = target,
            loadRecent = { viewModel.recentMessages(target.conversation.uuid) },
            onSend = { text -> viewModel.quickReply(target.conversation.uuid, text) },
            onDismiss = { quickReplyTarget = null },
        )
    }

    if (showPicker) {
        LiuliNewConversationPickerSheet(
            // 即现（不滑入）：用户 09-06 说点「+」等动画像卡住。
            sheetState = rememberLiuliInstantSheetState(),
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

/**
 * 聊天列表的长相（无 VM）。大标题带与搜索槽是列表的第 0 / 1 个 item（随内容滚走·Telegram 做法），
 * 「大标题是否已滚出」= 收起玻璃顶栏的判据（§4.2）。
 */
@Composable
internal fun LiuliChatListContent(
    rows: List<ChatListViewModel.Row>,
    query: String,
    isSearching: Boolean,
    scheduleStatus: Map<String, String>,
    nowMillis: Long,
    relStrings: DateFormatters.RelativeTimeStrings,
    onQueryChange: (String) -> Unit,
    onOpenChat: (ChatListViewModel.Row) -> Unit,
    onTogglePin: (ChatListViewModel.Row) -> Unit,
    onRequestDelete: (ChatListViewModel.Row) -> Unit,
    onQuickReply: (ChatListViewModel.Row) -> Unit,
    onNewConversation: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    val pinned = rows.filter { it.conversation.isPinned }
    val unpinned = rows.filterNot { it.conversation.isPinned }
    LiuliHomeScaffold(
        title = stringResource(R.string.tab_chats),
        collapsed = rememberLargeTitleCollapsed(listState),
        plus = {
            LiuliCircleButton(
                onClick = onNewConversation,
                contentDescription = stringResource(R.string.chat_list_empty_cta),
            ) {
                Icon(AppTopBarIcons.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        },
    ) {
        val bottomInset = LiuliHomeGeometry.listBottomInset +
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        if (rows.isEmpty() && !isSearching) {
            // 空态：标题带 + 搜索槽照常在位，空态块占满剩余（照暖陶 F4 的三分支结构）。
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = bottomInset)) {
                LiuliLargeTitle(stringResource(R.string.tab_chats))
                LiuliChatSearchSlot(query, onQueryChange)
                LiuliHomeEmpty(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    title = stringResource(R.string.chat_list_empty_title),
                    subtitle = stringResource(R.string.chat_list_empty_subtitle),
                    ctaText = stringResource(R.string.chat_list_empty_cta),
                    onCta = onNewConversation,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                state = listState,
                contentPadding = PaddingValues(bottom = bottomInset),
            ) {
                item(key = "large-title") { LiuliLargeTitle(stringResource(R.string.tab_chats)) }
                item(key = "search") { LiuliChatSearchSlot(query, onQueryChange) }
                if (pinned.isNotEmpty()) {
                    item(key = "pinned-header") { LiuliSectionHeader(stringResource(R.string.chat_list_section_pinned)) }
                    chatRows(pinned, scheduleStatus, nowMillis, relStrings, onOpenChat, onTogglePin, onRequestDelete, onQuickReply)
                }
                chatRows(unpinned, scheduleStatus, nowMillis, relStrings, onOpenChat, onTogglePin, onRequestDelete, onQuickReply)
            }
        }
    }
}

/** 搜索槽在列表里与空态里是同一副样子（左右 20 · 标题带下 12 · 首行上 12）。 */
@Composable
private fun LiuliChatSearchSlot(query: String, onQueryChange: (String) -> Unit) {
    LiuliSearchSlot(
        value = query,
        onValueChange = onQueryChange,
        placeholder = stringResource(R.string.chat_list_search_hint),
        clearContentDescription = stringResource(R.string.chat_list_search_clear),
        modifier = Modifier.padding(
            start = LiuliHomeGeometry.gutter,
            end = LiuliHomeGeometry.gutter,
            top = LiuliHomeGeometry.titleGap,
            bottom = LiuliHomeGeometry.titleGap,
        ),
    )
}

private fun LazyListScope.chatRows(
    rows: List<ChatListViewModel.Row>,
    scheduleStatus: Map<String, String>,
    nowMillis: Long,
    relStrings: DateFormatters.RelativeTimeStrings,
    onOpenChat: (ChatListViewModel.Row) -> Unit,
    onTogglePin: (ChatListViewModel.Row) -> Unit,
    onRequestDelete: (ChatListViewModel.Row) -> Unit,
    onQuickReply: (ChatListViewModel.Row) -> Unit,
) {
    items(rows, key = { it.conversation.uuid }) { row ->
        Column(Modifier.animateItem()) {
            LiuliChatListRow(
                row = row,
                scheduleStatus = scheduleStatus[row.conversation.characterUuid],
                nowMillis = nowMillis,
                relStrings = relStrings,
                onOpen = { onOpenChat(row) },
                onTogglePin = { onTogglePin(row) },
                onRequestDelete = { onRequestDelete(row) },
                onQuickReply = { onQuickReply(row) },
            )
            LiuliRowDivider()
        }
    }
}
