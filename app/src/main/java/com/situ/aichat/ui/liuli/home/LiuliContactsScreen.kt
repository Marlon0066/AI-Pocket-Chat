package com.situ.aichat.ui.liuli.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PeopleAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.ui.liuli.home.sheets.LiuliContactActionSheet
import com.situ.aichat.ui.contacts.ContactsViewModel
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBarIcons
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliCircleButton
import com.situ.aichat.ui.liuli.designsystem.LiuliDialog
import com.situ.aichat.ui.liuli.designsystem.LiuliSearchSlot
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed

/** 分享条落值（§4.4）：14 圆角 · 10/14 内距。 */
private val BANNER_SHAPE = RoundedCornerShape(14.dp)
private val BANNER_H_PAD = 14.dp
private val BANNER_V_PAD = 10.dp

/**
 * 琉璃联系人（图纸 2026-09-06 卷三 §4.3 B / §4.4 · 契约 §6 B 甲）。
 *
 * 薄壳：只订阅 [ContactsViewModel] 现成的四条流与两个瞬态旗标；分享落地、进会话、删除、动作面板全走
 * 与暖陶**同一份**方法。长按动作面板本卷仍借暖陶 `ContactActionSheet`（A-6·换壳挂卷三B）；
 * 删除确认换 [LiuliDialog]（文案逐字同暖陶）。
 */
@Composable
fun LiuliContactsScreen(
    onOpenChat: (String) -> Unit,
    onCreateCharacter: () -> Unit,
    onEditCharacter: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val fallbackUuids by viewModel.fallbackCharacterUuids.collectAsStateWithLifecycle()
    val shareText by viewModel.shareText.collectAsStateWithLifecycle()
    val shareMode = shareText != null
    var pendingDelete by remember { mutableStateOf<CharacterEntity?>(null) }
    var actionTarget by remember { mutableStateOf<CharacterEntity?>(null) }
    // 纪事相对天基准：进屏快照一次，跨午夜挂机不实时滚动（暖陶 F5 同口径）。
    val nowMillis = remember { System.currentTimeMillis() }

    LiuliContactsContent(
        rows = rows,
        query = query,
        shareMode = shareMode,
        fallbackUuids = fallbackUuids,
        nowMillis = nowMillis,
        onQueryChange = viewModel::setQuery,
        onCancelShare = viewModel::cancelShare,
        onCreateCharacter = onCreateCharacter,
        onOpenRow = { row ->
            if (shareMode) viewModel.shareTo(row.character, onOpenChat) else viewModel.openChat(row.character, onOpenChat)
        },
        onOpenProfile = { onOpenProfile(it.character.uuid) },
        onEdit = { onEditCharacter(it.character.uuid) },
        onRequestDelete = { pendingDelete = it.character },
        onLongPress = { actionTarget = it.character },
    )

    pendingDelete?.let { target ->
        LiuliDialog(
            onDismissRequest = { pendingDelete = null },
            title = stringResource(R.string.contacts_delete_title),
            body = stringResource(R.string.contacts_delete_message),
            confirmText = stringResource(R.string.contacts_delete_confirm, target.name),
            onConfirm = {
                viewModel.delete(target)
                pendingDelete = null
            },
            confirmDanger = true,
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { pendingDelete = null },
        )
    }

    actionTarget?.let { target ->
        LiuliContactActionSheet(
            character = target,
            onDismiss = { actionTarget = null },
            onViewProfile = { actionTarget = null; onOpenProfile(target.uuid) },
            onEdit = { actionTarget = null; onEditCharacter(target.uuid) },
            onDelete = { actionTarget = null; pendingDelete = target },
        )
    }
}

/** 联系人的长相（无 VM）。三分支 = 暖陶 F5：全空态 / 无结果态 / 列表。 */
@Composable
internal fun LiuliContactsContent(
    rows: List<ContactsViewModel.Row>,
    query: String,
    shareMode: Boolean,
    fallbackUuids: Set<String>,
    nowMillis: Long,
    onQueryChange: (String) -> Unit,
    onCancelShare: () -> Unit,
    onCreateCharacter: () -> Unit,
    onOpenRow: (ContactsViewModel.Row) -> Unit,
    onOpenProfile: (ContactsViewModel.Row) -> Unit,
    onEdit: (ContactsViewModel.Row) -> Unit,
    onRequestDelete: (ContactsViewModel.Row) -> Unit,
    onLongPress: (ContactsViewModel.Row) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    val title = stringResource(R.string.tab_contacts)
    LiuliHomeScaffold(
        title = title,
        collapsed = rememberLargeTitleCollapsed(listState),
        plus = {
            LiuliCircleButton(onClick = onCreateCharacter, contentDescription = stringResource(R.string.contacts_create)) {
                Icon(AppTopBarIcons.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        },
    ) {
        val bottomInset = LiuliHomeGeometry.listBottomInset +
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        if (rows.isEmpty()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = bottomInset)) {
                LiuliLargeTitle(title)
                if (shareMode) LiuliShareBanner(onCancelShare)
                LiuliContactsSearchSlot(query, onQueryChange)
                if (query.isBlank()) {
                    LiuliHomeEmpty(
                        icon = Icons.Filled.PeopleAlt,
                        title = stringResource(R.string.contacts_empty_title),
                        subtitle = stringResource(R.string.contacts_empty_subtitle),
                        ctaText = stringResource(R.string.contacts_create),
                        onCta = onCreateCharacter,
                    )
                } else {
                    LiuliNoResults(
                        text = stringResource(R.string.contacts_no_results),
                        clearText = stringResource(R.string.chat_list_search_clear),
                        onClear = { onQueryChange("") },
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                state = listState,
                contentPadding = PaddingValues(bottom = bottomInset),
            ) {
                item(key = "large-title") { LiuliLargeTitle(title) }
                if (shareMode) item(key = "share-banner") { LiuliShareBanner(onCancelShare) }
                item(key = "search") { LiuliContactsSearchSlot(query, onQueryChange) }
                items(rows, key = { it.character.uuid }) { row ->
                    Column(Modifier.animateItem()) {
                        LiuliContactRow(
                            row = row,
                            nowMillis = nowMillis,
                            hasFallback = row.character.uuid in fallbackUuids,
                            onOpen = { onOpenRow(row) },
                            onOpenProfile = { onOpenProfile(row) },
                            onEdit = { onEdit(row) },
                            onDelete = { onRequestDelete(row) },
                            onLongPress = { onLongPress(row) },
                        )
                        LiuliRowDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun LiuliContactsSearchSlot(query: String, onQueryChange: (String) -> Unit) {
    LiuliSearchSlot(
        value = query,
        onValueChange = onQueryChange,
        placeholder = stringResource(R.string.contacts_search_hint),
        clearContentDescription = stringResource(R.string.contacts_search_clear),
        modifier = Modifier.padding(
            start = LiuliHomeGeometry.gutter,
            end = LiuliHomeGeometry.gutter,
            top = LiuliHomeGeometry.titleGap,
            bottom = LiuliHomeGeometry.titleGap,
        ),
    )
}

/**
 * 分享落地选择条（§4.4）：14 圆角 `accent.container` 卡，位置 = 大标题带与搜索槽之间；
 * 文案照暖陶 `ShareSelectionBanner` 的资源名，一个字不改。
 */
@Composable
private fun LiuliShareBanner(onCancel: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LiuliHomeGeometry.gutter, vertical = LiuliHomeGeometry.titleGap)
            .background(colors.accent.container, BANNER_SHAPE)
            .padding(horizontal = BANNER_H_PAD, vertical = BANNER_V_PAD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.contacts_share_banner),
            style = AppTypography.secondary,
            color = colors.accent.onContainer,
            modifier = Modifier.weight(1f),
        )
        LiuliButton(onClick = onCancel, style = LiuliButtonStyle.Text) { Text(stringResource(R.string.action_cancel)) }
    }
}
