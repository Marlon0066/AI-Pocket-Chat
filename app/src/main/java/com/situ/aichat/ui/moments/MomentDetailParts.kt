package com.situ.aichat.ui.moments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MomentCommentEntity
import com.situ.aichat.data.local.entity.MomentPostWithRelations
import com.situ.aichat.data.model.MomentAuthorType
import com.situ.aichat.data.model.imagePaths
import com.situ.aichat.moments.MomentCommentTreeBuilder
import com.situ.aichat.moments.MomentMentionRules
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppMomentIcons
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.util.DateFormatters

/** 回复目标（原 MomentDetailScreen :70 `private data class ReplyTarget` 改名公开给两张脸）。 */
internal data class MomentReplyTarget(val commentUuid: String, val name: String)

/** 详情页评论草稿（原 :86–87 两个 remember + :117–121 发送清空·两张脸共用）。 */
@Stable
internal class MomentCommentComposerState {
    var text by mutableStateOf("")
    var replyTarget by mutableStateOf<MomentReplyTarget?>(null)
    val canSend: Boolean get() = text.isNotBlank()

    /** 把草稿交给 [submit]（= `viewModel::submitComment`）后清空文字与回复目标。 */
    fun send(submit: (text: String, replyToCommentUuid: String?, replyToName: String?) -> Unit) {
        submit(text, replyTarget?.commentUuid, replyTarget?.name)
        text = ""
        replyTarget = null
    }
}

@Composable
internal fun rememberMomentCommentComposerState(): MomentCommentComposerState = remember { MomentCommentComposerState() }

/** 详情评论一行要画的（纯函数·T1·原 :136–137 与 :162–175 的 items 体逐式搬出）。 */
internal data class MomentCommentRowModel(
    val comment: MomentCommentEntity,
    val level: Int,
    val authorName: String,
    val authorAvatarPath: String?,
    val canDelete: Boolean,
)

internal fun momentCommentRows(
    post: MomentPostWithRelations,
    characters: Map<String, CharacterEntity>,
    userAvatarPath: String?,
    meLabel: String,
    aiLabel: String,
): List<MomentCommentRowModel> {
    val postByUser = MomentAuthorType.fromRaw(post.post.authorTypeRaw) == MomentAuthorType.USER
    return MomentCommentTreeBuilder.flatten(post.comments).map { node ->
        val c = node.comment
        val isUser = MomentAuthorType.fromRaw(c.authorTypeRaw) == MomentAuthorType.USER
        MomentCommentRowModel(
            comment = c,
            level = node.level,
            authorName = momentAuthorName(c.authorTypeRaw, c.characterUuid, characters, meLabel, aiLabel),
            authorAvatarPath = if (isUser) userAvatarPath else c.characterUuid?.let { characters[it]?.avatarPath },
            canDelete = isUser || postByUser,
        )
    }
}

/** 评论缩进（原 :293 逐字·纯函数·T1）：每级 28、最多两级。 */
internal fun momentCommentIndent(level: Int): Dp = (minOf(level, 2) * 28).dp

/** 新评论到了才滚到底（原 :138–147·含原注释）。 */
@Composable
internal fun MomentCommentsAutoScrollEffect(listState: LazyListState, commentCount: Int, hasComments: Boolean) {
    // moments-ui-2：新评论到达后自动滚到底部（1:1 iOS MomentDetailView 评论追加滚动）。
    // 过渡丝滑化·C：仅在评论数「增长」时才滚（=iOS 追加语义）；打开帖子不再因首帧 size 0→N 误滚到底，
    // 让用户落在帖子正文顶部、而非被动滚到最后一条评论。
    var lastCommentCount by remember { mutableStateOf(commentCount) }
    LaunchedEffect(commentCount) {
        if (commentCount > lastCommentCount && hasComments) {
            listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        }
        lastCommentCount = commentCount
    }
}

/** 评论标题（原 CommentHeader :272 的文案式）：有评论 = 「评论 (N)」，否则「评论」。 */
@Composable
internal fun momentCommentHeaderText(count: Int): String =
    if (count > 0) stringResource(R.string.moment_detail_comments_count, count) else stringResource(R.string.moment_comments_label)

@Composable
internal fun MomentPostContentSection(
    post: MomentPostWithRelations,
    characterDict: Map<String, CharacterEntity>,
    userName: String,
    userAvatarPath: String?,
    relStrings: DateFormatters.RelativeTimeStrings,
    nowMillis: Long,
    meLabel: String,
    aiLabel: String,
    modifier: Modifier = Modifier,
    surface: Modifier = Modifier.appCardSurface(),
    avatarFrame: @Composable (avatar: @Composable () -> Unit) -> Unit = { it() },
) {
    val entity = post.post
    val isUserAuthor = MomentAuthorType.fromRaw(entity.authorTypeRaw) == MomentAuthorType.USER
    val authorName = momentAuthorName(entity.authorTypeRaw, entity.characterUuid, characterDict, meLabel, aiLabel)
    val authorAvatarPath = if (isUserAuthor) userAvatarPath else entity.characterUuid?.let { characterDict[it]?.avatarPath }
    Column(
        // 与信息流动态卡同一张皮（契约 §2.4：皮肤规格单源 §2.1·此前为内联复制的旧样式=样式双源，就此并轨）。
        modifier = modifier
            .fillMaxWidth()
            .then(surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            avatarFrame {
                CharacterAvatar(name = if (isUserAuthor) userName else authorName, avatarPath = authorAvatarPath, size = 44.dp)
            }
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
        val mentionNames = remember(entity.mentionedCharacterUuidsJson, characterDict) { MomentMentionRules.displayNames(entity, characterDict) }
        if (mentionNames.isNotEmpty()) MomentMentionLine(mentionNames)
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
internal fun MomentLikesSection(
    post: MomentPostWithRelations,
    characterDict: Map<String, CharacterEntity>,
    meLabel: String,
    aiLabel: String,
    modifier: Modifier = Modifier,
    surface: Modifier = Modifier.clip(AppShapes.small).background(AppTheme.colors.surface.sunken),
) {
    val names = post.likes.joinToString(", ") {
        momentAuthorName(it.authorTypeRaw, it.characterUuid, characterDict, meLabel, aiLabel)
    }
    // 点赞名单条（契约 §2.4）：sunken 圆角 8 内衬（与评论小笺同族）+ 深陶填充小心。
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(AppMomentIcons.HeartFilled, contentDescription = null, tint = AppTheme.colors.accent.text, modifier = Modifier.size(14.dp))
        Text(names, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.text.secondary)
    }
}

/** 评论一行的内容（头像 26 / 缝 8 / 名 + 回复谁 + 内容 + 时间·原 MomentCommentRow :302–314 三子项逐字）。 */
@Composable
internal fun RowScope.MomentCommentRowContent(
    comment: MomentCommentEntity,
    authorName: String,
    authorAvatarPath: String?,
    timeText: String,
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

/** 回复 chip（原 CommentInputBar :364–379 的 `Row` 逐字）：「回复 @X」+ ✕，点它取消回复。 */
@Composable
internal fun MomentReplyChip(replyName: String, onCancelReply: () -> Unit) {
    val colors = AppTheme.colors
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
        Icon(AppActionIcons.Close, contentDescription = stringResource(R.string.action_cancel), modifier = Modifier.size(14.dp), tint = colors.accent.onContainer)
    }
}
