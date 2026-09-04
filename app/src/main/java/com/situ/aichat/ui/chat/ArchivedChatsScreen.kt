package com.situ.aichat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Unarchive
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.util.rememberTimeTick

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedChatsScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    viewModel: ArchivedChatsViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<ChatListViewModel.Row?>(null) }
    val relStrings = rememberRelativeTimeStrings()
    val nowMillis = rememberTimeTick()

    val listState = rememberLazyListState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.chat_archived_title),
                onBack = onBack,
                lifted = listState.canScrollBackward,
            )
        },
    ) { padding ->
        if (rows.isEmpty()) {
            ArchivedEmptyState(Modifier.fillMaxSize().padding(padding))
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)) {
                items(rows, key = { it.conversation.uuid }) { row ->
                    Column {
                        SwipeableArchivedRow(
                            row = row,
                            nowMillis = nowMillis,
                            relStrings = relStrings,
                            onOpen = { onOpenChat(row.conversation.uuid) },
                            onUnarchive = { viewModel.unarchive(row.conversation.uuid) },
                            onRequestDelete = { pendingDelete = row },
                        )
                        AppListDivider()
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
}

/** 归档行滑动：左滑露出 删除（行尾，走确认），右滑露出 取消归档（行首，立即）。 */
@Composable
private fun SwipeableArchivedRow(
    row: ChatListViewModel.Row,
    nowMillis: Long,
    relStrings: com.situ.aichat.util.DateFormatters.RelativeTimeStrings,
    onOpen: () -> Unit,
    onUnarchive: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val unarchiveAction = SwipeAction(
        label = stringResource(R.string.chat_list_action_unarchive),
        icon = Icons.Filled.Unarchive,
        containerColor = colors.secondary,
        contentColor = colors.onSecondary,
        onClick = onUnarchive,
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
        leadingActions = listOf(unarchiveAction),
        trailingActions = listOf(deleteAction),
    ) {
        // 归档页不显示日程状态（休眠会话，与 iOS 几乎不可感的细差，省去逐角色日程查询）。
        ChatRow(row = row, scheduleStatus = null, nowMillis = nowMillis, relStrings = relStrings)
    }
}

@Composable
private fun ArchivedEmptyState(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(
                Icons.Filled.Archive,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.chat_archived_empty_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.chat_archived_empty_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
