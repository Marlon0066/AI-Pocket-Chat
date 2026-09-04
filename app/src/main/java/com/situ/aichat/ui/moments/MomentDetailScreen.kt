package com.situ.aichat.ui.moments

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MomentCommentEntity
import com.situ.aichat.data.local.entity.MomentPostWithRelations
import com.situ.aichat.data.model.MomentAuthorType
import com.situ.aichat.data.model.imagePaths
import com.situ.aichat.moments.MomentCommentTreeBuilder
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDialogTone
import com.situ.aichat.ui.designsystem.AppElevation
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.AppMenu
import com.situ.aichat.ui.designsystem.AppMenuItem
import com.situ.aichat.ui.designsystem.AppMomentIcons
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTextArea
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.ui.designsystem.grainSurface
import com.situ.aichat.util.DateFormatters

private data class ReplyTarget(val commentUuid: String, val name: String)

/**
 * 朋友圈详情（M06 7.2.8，对齐 iOS `MomentDetailView`）：正文区 + 点赞名单 + 展平评论树 + 底部评论输入栏
 * （含回复@目标）。提交评论 → VM 落库 + 排 AI 延迟回复。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MomentDetailScreen(
    onBack: () -> Unit,
    viewModel: MomentDetailViewModel = hiltViewModel(),
) {
    val post by viewModel.post.collectAsStateWithLifecycle()
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()

    var commentText by remember { mutableStateOf("") }
    var replyTarget by remember { mutableStateOf<ReplyTarget?>(null) }

    val meLabel = stringResource(R.string.moment_author_me)
    val aiLabel = stringResource(R.string.moment_author_ai)
    val relStrings = DateFormatters.RelativeTimeStrings(
        justNow = stringResource(R.string.relative_time_just_now),
        minutesAgo = stringResource(R.string.relative_time_minutes_ago),
        hoursAgo = stringResource(R.string.relative_time_hours_ago),
        yesterday = stringResource(R.string.relative_time_yesterday),
    )
    val nowMillis = System.currentTimeMillis()
    val userName = userProfile?.nickname?.ifBlank { null } ?: meLabel
    val userAvatarPath = userProfile?.avatarPath

    // 门楣升起态要读滚动位置，故 listState 从内容 lambda 提到屏级（顶栏在 Scaffold 参数里，看不见 lambda 内的局部量）。
    val listState = rememberLazyListState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.moment_detail_title),
                onBack = onBack,
                lifted = listState.canScrollBackward,
            )
        },
        bottomBar = {
            CommentInputBar(
                text = commentText,
                replyName = replyTarget?.name,
                onTextChange = { commentText = it },
                onCancelReply = { replyTarget = null },
                onSend = {
                    viewModel.submitComment(commentText, replyTarget?.commentUuid, replyTarget?.name)
                    commentText = ""
                    replyTarget = null
                },
                modifier = Modifier.imePadding(),
            )
        },
    ) { padding ->
        val p = post
        if (p == null) {
            Box(
                Modifier.fillMaxSize().padding(padding).background(AppTheme.colors.surface.base).grainSurface(),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.moment_detail_deleted), color = AppTheme.colors.text.secondary)
            }
            return@Scaffold
        }
        val flat = remember(p.comments) { MomentCommentTreeBuilder.flatten(p.comments) }
        val postByUser = MomentAuthorType.fromRaw(p.post.authorTypeRaw) == MomentAuthorType.USER
        // moments-ui-2：新评论到达后自动滚到底部（1:1 iOS MomentDetailView 评论追加滚动）。
        // 过渡丝滑化·C：仅在评论数「增长」时才滚（=iOS 追加语义）；打开帖子不再因首帧 size 0→N 误滚到底，
        // 让用户落在帖子正文顶部、而非被动滚到最后一条评论。
        var lastCommentCount by remember { mutableStateOf(p.comments.size) }
        LaunchedEffect(p.comments.size) {
            if (p.comments.size > lastCommentCount && flat.isNotEmpty()) {
                listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
            }
            lastCommentCount = p.comments.size
        }
        LazyColumn(
            state = listState,
            // 页底 = surface.base + 纸感 grain；gutter 20（契约 §2.4·v2 军规）。
            modifier = Modifier.fillMaxSize().padding(padding).background(AppTheme.colors.surface.base).grainSurface(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "content") {
                PostContentSection(p, characters, userName, userAvatarPath, relStrings, nowMillis, meLabel, aiLabel)
            }
            if (p.likes.isNotEmpty()) {
                item(key = "likes") { LikesSection(p, characters, meLabel, aiLabel) }
            }
            item(key = "comment-header") { CommentHeader(p.comments.size) }
            items(flat, key = { it.comment.uuid }) { node ->
                val c = node.comment
                val isUser = MomentAuthorType.fromRaw(c.authorTypeRaw) == MomentAuthorType.USER
                val name = momentAuthorName(c.authorTypeRaw, c.characterUuid, characters, meLabel, aiLabel)
                MomentCommentRow(
                    comment = c,
                    level = node.level,
                    authorName = name,
                    authorAvatarPath = if (isUser) userAvatarPath else c.characterUuid?.let { characters[it]?.avatarPath },
                    timeText = DateFormatters.relativeTimeString(c.timestamp, nowMillis, relStrings),
                    canDelete = isUser || postByUser,
                    onReply = { replyTarget = ReplyTarget(c.uuid, name) },
                    onDelete = { viewModel.deleteComment(c.uuid) },
                )
            }
        }
    }
}

@Composable
private fun PostContentSection(
    post: MomentPostWithRelations,
    characterDict: Map<String, CharacterEntity>,
    userName: String,
    userAvatarPath: String?,
    relStrings: DateFormatters.RelativeTimeStrings,
    nowMillis: Long,
    meLabel: String,
    aiLabel: String,
) {
    val entity = post.post
    val isUserAuthor = MomentAuthorType.fromRaw(entity.authorTypeRaw) == MomentAuthorType.USER
    val authorName = momentAuthorName(entity.authorTypeRaw, entity.characterUuid, characterDict, meLabel, aiLabel)
    val authorAvatarPath = if (isUserAuthor) userAvatarPath else entity.characterUuid?.let { characterDict[it]?.avatarPath }
    Column(
        // 与信息流动态卡同一张皮（契约 §2.4：皮肤规格单源 §2.1·此前为内联复制的旧样式=样式双源，就此并轨）。
        modifier = Modifier
            .fillMaxWidth()
            .appCardSurface()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CharacterAvatar(name = if (isUserAuthor) userName else authorName, avatarPath = authorAvatarPath, size = 44.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(authorName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.text.primary)
                // moments-ui-4：点按时间在「相对/精确」间切换（1:1 iOS MomentDetailView .onTapGesture{ showPreciseTime.toggle() }）。
                var showPreciseTime by remember { mutableStateOf(false) }
                Text(
                    if (showPreciseTime) {
                        DateFormatters.longDateShortTime(entity.timestamp)
                    } else {
                        DateFormatters.relativeTimeString(entity.timestamp, nowMillis, relStrings)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.colors.text.secondary,
                    modifier = Modifier.clickable { showPreciseTime = !showPreciseTime },
                )
            }
        }
        if (entity.content.isNotEmpty()) {
            Text(entity.content, style = MaterialTheme.typography.bodyLarge, color = AppTheme.colors.text.primary)
        }
        val images = entity.imagePaths
        if (images.isNotEmpty()) MomentImageGrid(imagePaths = images)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CountChip(AppMomentIcons.HeartFilled, post.likes.size)
            CountChip(AppMomentIcons.CommentBubble, post.comments.size)
        }
    }
}

@Composable
private fun CountChip(icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int) {
    // 契约 §2.4：自绘图标族 + 深陶不再半透明（accent.text 直落）；计数 secondary。
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = AppTheme.colors.accent.text, modifier = Modifier.size(16.dp))
        Text("$count", style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.text.secondary)
    }
}

@Composable
private fun LikesSection(
    post: MomentPostWithRelations,
    characterDict: Map<String, CharacterEntity>,
    meLabel: String,
    aiLabel: String,
) {
    val names = post.likes.joinToString(", ") {
        momentAuthorName(it.authorTypeRaw, it.characterUuid, characterDict, meLabel, aiLabel)
    }
    // 点赞名单条（契约 §2.4）：sunken 圆角 8 内衬（与评论小笺同族）+ 深陶填充小心。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.small)
            .background(AppTheme.colors.surface.sunken)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(AppMomentIcons.HeartFilled, contentDescription = null, tint = AppTheme.colors.accent.text, modifier = Modifier.size(14.dp))
        Text(names, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.text.secondary)
    }
}

@Composable
private fun CommentHeader(count: Int) {
    Text(
        if (count > 0) stringResource(R.string.moment_detail_comments_count, count) else stringResource(R.string.moment_comments_label),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = AppTheme.colors.text.primary,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MomentCommentRow(
    comment: MomentCommentEntity,
    level: Int,
    authorName: String,
    authorAvatarPath: String?,
    timeText: String,
    canDelete: Boolean,
    onReply: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val indent = (minOf(level, 2) * 28).dp
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = indent, top = 4.dp, bottom = 4.dp)
                .combinedClickable(onClick = onReply, onLongClick = { menuExpanded = true }),
            verticalAlignment = Alignment.Top,
        ) {
            CharacterAvatar(name = authorName, avatarPath = authorAvatarPath, size = 26.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(authorName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.text.primary)
                comment.replyToName?.let { replyTo ->
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.moment_comment_reply), style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.text.secondary)
                        Text("@$replyTo", style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.accent.text)
                    }
                }
                Text(comment.content, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.text.primary)
                Text(timeText, style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.text.secondary)
            }
        }
        AppMenu(expanded = menuExpanded, onDismiss = { menuExpanded = false }) {
            AppMenuItem(
                text = stringResource(R.string.moment_comment_reply),
                onClick = { menuExpanded = false; onReply() },
            )
            if (canDelete) {
                AppMenuItem(
                    text = stringResource(R.string.moment_comment_delete),
                    onClick = { menuExpanded = false; showDeleteConfirm = true },
                    danger = true,
                )
            }
        }
    }
    if (showDeleteConfirm) {
        AppDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = stringResource(R.string.moment_comment_delete_title),
            body = stringResource(R.string.moment_comment_delete_message),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = { showDeleteConfirm = false; onDelete() },
            confirmTone = AppDialogTone.Danger,
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

@Composable
private fun CommentInputBar(
    text: String,
    replyName: String?,
    onTextChange: (String) -> Unit,
    onCancelReply: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 契约 §2.4：raised 平底 + 顶边发丝线（v2 海拔口径：分层靠明度+发丝，不靠 M3 tonal）。
    val colors = AppTheme.colors
    Surface(modifier = modifier, color = colors.surface.raised) {
        Column {
            AppListDivider(startInset = 0.dp)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (replyName != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(colors.accent.container)
                            .clickable(onClick = onCancelReply)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            stringResource(R.string.moment_detail_replying_to, replyName),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.accent.onContainer,
                        )
                        Spacer(Modifier.width(2.dp))
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel), modifier = Modifier.size(14.dp), tint = colors.accent.onContainer)
                    }
                }
                AppTextArea(
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = stringResource(if (replyName != null) R.string.moment_detail_reply_hint else R.string.moment_detail_comment_hint),
                    minHeight = 52.dp, // 评论栏单行起步，随内容长到 maxLines=4
                    maxLines = 4,
                    modifier = Modifier.weight(1f),
                )
                // 发送钮（契约 §2.4）：36dp 浅陶双 stop 圆钮 + 深墨纸飞机（「浅底深字」与用户气泡同口径·
                // 对比度走既有 onAccent×gradient 断言）；禁用 = sunken 底 + tertiary 图标。点击域 48dp（a11y 红线）。
                val canSend = text.isNotBlank()
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(enabled = canSend, onClick = onSend),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (canSend) {
                                    Brush.linearGradient(listOf(colors.accent.gradientStart, colors.accent.gradientEnd))
                                } else {
                                    SolidColor(colors.surface.sunken)
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            AppMomentIcons.PaperPlane,
                            contentDescription = stringResource(R.string.moment_detail_send),
                            tint = if (canSend) colors.text.onAccent else colors.text.tertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}
