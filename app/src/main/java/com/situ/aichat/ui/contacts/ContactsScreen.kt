package com.situ.aichat.ui.contacts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PeopleAlt
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.LocalAppHaptics
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
import com.situ.aichat.util.StreakManager

/**
 * Contacts tab：关系养成型两行联系人行（Fable-5·过审 2026-06-20），尺寸与聊天列表行逐值统一。
 * 头像 + 第一行〔角色名 + 关系称谓徽章（最新里程碑「关系名·时期」）〕+ 第二行〔14 天内最近纪事 → 职业 → 神秘占位〕
 * + 右侧〔连续火花 🔥N，放大·跨行垂直居中〕。点行进会话、点头像进资料页、长按弹动作面板（查看资料/编辑/删除·#4）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onOpenChat: (String) -> Unit,
    onCreateCharacter: () -> Unit,
    onEditCharacter: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    bottomContentPadding: Dp = 0.dp,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    // 见面兜底红点（14.1b）：有「简版」见面摘要的角色 uuid 集。
    val fallbackUuids by viewModel.fallbackCharacterUuids.collectAsStateWithLifecycle()
    // 13.10a 分享给角色：非空 = 处于「点选收件角色」模式（通用分享落地，点行即发而非进会话）。
    val shareText by viewModel.shareText.collectAsStateWithLifecycle()
    val shareMode = shareText != null
    var pendingDelete by remember { mutableStateOf<CharacterEntity?>(null) }
    // #4 长按动作面板目标（非空 = 弹 ContactActionSheet）；持角色快照，动作走现有幂等链（J6）。
    var actionTarget by remember { mutableStateOf<CharacterEntity?>(null) }
    // #5 纪事相对天基准（J2）：进屏快照一次，跨午夜挂机不实时滚动（联系人页无 TimeTick 现状，不为此引入分钟级重组）。
    val nowMillis = remember { System.currentTimeMillis() }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 大标题 + 右上角「新建角色」圆钮（三边视觉等距·过审 2026-06-19）。取代原 M3 TopAppBar。
            AppListScreenHeader(
                title = stringResource(R.string.tab_contacts),
                actionIcon = AppTopBarIcons.Add,
                actionContentDescription = stringResource(R.string.contacts_create),
                onAction = onCreateCharacter,
            )
            // 13.10a：通用分享落地的选择条——提示用户点一个角色把分享内容发过去，或取消退出选择模式。
            if (shareMode) {
                ShareSelectionBanner(onCancel = viewModel::cancelShare)
            }
            AppSearchField(
                value = query,
                onValueChange = viewModel::setQuery,
                placeholder = stringResource(R.string.contacts_search_hint),
                clearContentDescription = stringResource(R.string.contacts_search_clear),
                modifier = Modifier
                    .fillMaxWidth()
                    // top=0：紧接页眉底缘，三边等距由页眉 edgeMargin 提供。
                    .padding(start = AppSpacing.screenGutter, end = AppSpacing.screenGutter, bottom = 8.dp),
            )

            // rows 已是过滤后列表（VM combine observeAll+milestones+query）：空+空搜索=全空态(图标+CTA)；
            // 空+非空搜索=无结果态(超越 iOS 静默空列表)；否则照常列表。
            when {
                rows.isEmpty() && query.isBlank() -> EmptyContacts(onCreateCharacter)
                rows.isEmpty() -> NoSearchResults(onClearSearch = { viewModel.setQuery("") })
                else -> LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(bottom = bottomContentPadding)) {
                    items(rows, key = { it.character.uuid }) { row ->
                        Column(Modifier.animateItem()) {
                            ContactRow(
                                row = row,
                                nowMillis = nowMillis,
                                // 分享模式下点行 = 把分享内容发给该角色并进会话；否则照常进会话。
                                onOpen = {
                                    if (shareMode) viewModel.shareTo(row.character, onOpenChat)
                                    else viewModel.openChat(row.character, onOpenChat)
                                },
                                // 点头像进资料页（1:1 iOS ContactListView 头像→CharacterProfileView）。
                                onOpenProfile = { onOpenProfile(row.character.uuid) },
                                hasFallback = row.character.uuid in fallbackUuids,
                                onEdit = { onEditCharacter(row.character.uuid) },
                                onDelete = { pendingDelete = row.character },
                                // 长按弹动作面板（#4·取代 ⋮·分享模式下同样弹）。
                                onLongPress = { actionTarget = row.character },
                            )
                            AppListDivider()
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AppDialog(
            onDismissRequest = { pendingDelete = null },
            title = stringResource(R.string.contacts_delete_title),
            body = stringResource(R.string.contacts_delete_message),
            confirmText = stringResource(R.string.contacts_delete_confirm, target.name),
            onConfirm = {
                viewModel.delete(target)
                pendingDelete = null
            },
            confirmTone = AppDialogTone.Danger,
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { pendingDelete = null },
        )
    }

    // #4 长按动作面板：三动作回调先关面板（actionTarget=null）再走现有链（导航 / 删除确认弹窗）。
    actionTarget?.let { target ->
        ContactActionSheet(
            character = target,
            onDismiss = { actionTarget = null },
            onViewProfile = { actionTarget = null; onOpenProfile(target.uuid) },
            onEdit = { actionTarget = null; onEditCharacter(target.uuid) },
            onDelete = { actionTarget = null; pendingDelete = target },
        )
    }
}

/** 13.10a 分享给角色：通用分享落地的选择条——提示点选一个角色把分享内容发过去，右侧可取消退出选择模式。 */
@Composable
private fun ShareSelectionBanner(onCancel: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.contacts_share_banner),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            AppButton(onClick = onCancel, style = AppButtonStyle.Text) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}

/** 全空态（P0-22，对齐 iOS EmptyStateView person.2 + CTA）：图标 + 标题 + 副标题 + 「新建角色」按钮。 */
@Composable
private fun EmptyContacts(onCreateCharacter: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Icon(
                Icons.Filled.PeopleAlt,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
            )
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.contacts_empty_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.contacts_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            AppButton(onClick = onCreateCharacter, style = AppButtonStyle.Primary) { Text(stringResource(R.string.contacts_create)) }
        }
    }
}

/** 无搜索结果态（P0-23，超越 iOS 静默空列表）：图标 + 提示 + 清空搜索。 */
@Composable
private fun NoSearchResults(onClearSearch: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Icon(
                Icons.Filled.SearchOff,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.contacts_no_results),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            AppButton(onClick = onClearSearch, style = AppButtonStyle.Text) { Text(stringResource(R.string.chat_list_search_clear)) }
        }
    }
}

/** 关系称谓徽章：柔玫粉容器（关系色·本行局部 accent，非设计系统 token）；无里程碑→中性灰「初识」。 */
private val RelRose = Color(0xFFD4537E)

@Composable
private fun RelationshipPill(display: String?) {
    val colors = MaterialTheme.colorScheme
    val isFallback = display == null
    val bg = if (isFallback) colors.onSurfaceVariant.copy(alpha = 0.10f) else RelRose.copy(alpha = 0.14f)
    val stroke = if (isFallback) colors.onSurfaceVariant.copy(alpha = 0.30f) else RelRose.copy(alpha = 0.40f)
    val fg = if (isFallback) colors.onSurfaceVariant else colors.onSurface
    Text(
        text = display ?: stringResource(R.string.contacts_relationship_initial),
        style = AppTheme.typography.secondary,
        color = fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(bg, CircleShape)
            .border(0.5.dp, stroke, CircleShape)
            .padding(horizontal = 9.dp, vertical = 2.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactRow(
    row: ContactsViewModel.Row,
    nowMillis: Long,
    onOpen: () -> Unit,
    onOpenProfile: () -> Unit,
    hasFallback: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onLongPress: () -> Unit,
) {
    val character = row.character
    val colors = MaterialTheme.colorScheme
    val haptics = LocalAppHaptics.current
    val streak = StreakManager.getStreakCount(character)
    val occupation = character.occupation.trim()
    // #5 最近纪事文案（VM 已选出窗内事件·此处只格式化）：第二行三级降级的最高优先级。
    val recentEventText: String? = when (val ev = row.recentEvent) {
        null -> null
        is RecentEvent.Milestone -> stringResource(
            R.string.contacts_recent_event_milestone,
            DateFormatters.relativeDay(ev.atMillis, nowMillis), ev.name,
        )
        is RecentEvent.Meeting -> when {
            ev.activity.isNotEmpty() -> stringResource(
                R.string.contacts_recent_event_meeting,
                DateFormatters.relativeDay(ev.atMillis, nowMillis), ev.activity,
            )
            ev.location.isNotEmpty() -> stringResource(
                R.string.contacts_recent_event_meeting_location,
                DateFormatters.relativeDay(ev.atMillis, nowMillis), ev.location,
            )
            else -> stringResource(
                R.string.contacts_recent_event_meeting_plain,
                DateFormatters.relativeDay(ev.atMillis, nowMillis),
            )
        }
    }

    // P1-35（1:1 iOS .accessibilityElement(children:.combine)）：整行合并为单一 TalkBack 停，
    // cd=「{名}(，{关系称谓})(，{纪事/职业})(，连续 N 天)(，有见面摘要待重新生成)」；红点/头像热区纯视觉、
    // 动作经 customActions（查看资料/编辑/删除）。行点击=进会话（onClickLabel）；触摸用户长按=弹动作面板（#4）。
    val a11yLabel = buildString {
        append(character.name)
        row.relationshipDisplay?.let { append("，").append(it) }
        // 第二行拼读：纪事文案优先，无则职业（空职业不读）——与视觉第二行同优先级（神秘占位不拼读，同现状）。
        (recentEventText ?: occupation.takeIf { it.isNotEmpty() })?.let { append("，").append(it) }
        if (streak > 0) append("，连续 ").append(streak).append(" 天")
        if (hasFallback) append("，").append(stringResource(R.string.a11y_contact_fallback_pending))
    }
    val actionProfile = stringResource(R.string.a11y_contact_open_profile)
    val actionEdit = stringResource(R.string.action_edit)
    val actionDelete = stringResource(R.string.action_delete)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 点行进会话；长按弹动作面板（#4·medium 触觉·对齐 SwipeActionsRow:150 契约先例）。
            .combinedClickable(
                onClickLabel = stringResource(R.string.a11y_contact_open_chat),
                onClick = { onOpen() },
                onLongClick = { haptics.medium(); onLongPress() },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = a11yLabel
                customActions = listOf(
                    CustomAccessibilityAction(actionProfile) { onOpenProfile(); true },
                    CustomAccessibilityAction(actionEdit) { onEdit(); true },
                    CustomAccessibilityAction(actionDelete) { onDelete(); true },
                )
            }
            .background(colors.surface)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 头像 52dp（对齐聊天列表）+ 见面兜底红点；点头像进资料页（对 TalkBack 隐藏，动作在行级 customActions）。
        // 复核修（链序）：clearAndSetSemantics 必须在 clickable **之前**（清空节点要最后生效），否则留下无标签可点焦点。
        Box(
            modifier = Modifier
                .clearAndSetSemantics {}
                .clickable(onClick = onOpenProfile),
            contentAlignment = Alignment.Center,
        ) {
            CharacterAvatar(character.name, character.avatarPath, 52.dp)
            if (hasFallback) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(colors.surface)
                        .padding(1.5.dp)
                        .clip(CircleShape)
                        .background(colors.error),
                )
            }
        }
        // 中段两行：①角色名 + 关系称谓 pill ②职业（空→浅灰占位「TA的职业很神秘」）。
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    character.name,
                    style = AppTheme.typography.listName,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                RelationshipPill(display = row.relationshipDisplay)
            }
            // 第二行三级降级（#5）：14 天内纪事 → 职业 → 神秘占位。纪事/职业同色（onSurfaceVariant 全透明度），
            // 仅神秘占位 0.6f 淡化——后两级分支样式逐字保留现状。
            when {
                recentEventText != null -> Text(
                    recentEventText,
                    style = AppTheme.typography.listPreview,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                occupation.isNotEmpty() -> Text(
                    occupation,
                    style = AppTheme.typography.listPreview,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                else -> Text(
                    stringResource(R.string.contacts_occupation_mystery),
                    style = AppTheme.typography.listPreview,
                    color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 右侧 trailing：连续火花（放大·跨行垂直居中·断火花隐藏）。编辑/删除已改长按面板（#4·取代 ⋮）。
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (streak > 0) {
                Icon(
                    Icons.Filled.LocalFireDepartment,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text("$streak", style = AppTheme.typography.bodyEmphasis, color = colors.onSurfaceVariant)
            }
        }
    }
}
