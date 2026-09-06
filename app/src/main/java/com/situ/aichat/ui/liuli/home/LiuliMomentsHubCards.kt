package com.situ.aichat.ui.liuli.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.model.MomentAuthorType
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppFeatureIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.moments.MomentsHubState
import com.situ.aichat.ui.moments.StoryHubStatus
import com.situ.aichat.ui.moments.storyHubStatus

/** 卡内落值（§3.2「卡片」/ §4.5）：提示条 14 圆角 10/14 · 头像排 26 叠 −6 描边 2 · 预览正文取 30 字。 */
private val BANNER_SHAPE = RoundedCornerShape(14.dp)
private val WARN_ICON = 16.dp
private val STRIP_AVATAR = 26.dp
private val STRIP_AVATAR_OVERLAP = (-6).dp
private val STRIP_AVATAR_RING = 2.dp
private const val PREVIEW_CHARS = 30

/** API 未配置提示条（§3.2「提示条」）：14 圆角 · 10/14 内距 · warningContainer 底 + onWarning 字 13 + Warning 16。 */
@Composable
fun LiuliApiMissingBanner(modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(BANNER_SHAPE)
            .background(colors.status.warningContainer)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.status.onWarning, modifier = Modifier.size(WARN_ICON))
        Text(stringResource(R.string.moment_api_missing), style = AppTypography.secondary, color = colors.status.onWarning)
    }
}

/**
 * 圈子条（§4.5）：行1「圈子」+ 副标 + 未读丸 + chevron；头像排 26 叠 −6；预览两条「作者：正文 30 字」。
 * 未读丸与列表未读丸同一具（[LiuliUnreadPill]·钴蓝而非暖陶的 errorContainer——琉璃这张脸的强调色统一）。
 */
@Composable
fun LiuliCircleStrip(state: MomentsHubState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val title = stringResource(R.string.moment_nav_title)
    LiuliHubCard(onClick = onClick, onClickLabel = title, modifier = modifier) {
        LiuliStripHeader(title = title, subText = stringResource(R.string.moment_hub_circle_subtitle)) {
            LiuliUnreadPill(state.unreadCount)
            if (state.unreadCount > 0) Spacer(Modifier.width(6.dp))
        }
        if (state.heroAvatars.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(STRIP_AVATAR_OVERLAP),
            ) {
                state.heroAvatars.take(5).forEach { character ->
                    Box(Modifier.border(STRIP_AVATAR_RING, colors.surface.raised, CircleShape)) {
                        CharacterAvatar(name = character.name, avatarPath = character.avatarPath, size = STRIP_AVATAR)
                    }
                }
            }
        }
        LiuliCirclePreview(state)
    }
}

/** 圈子条预览（§4.5）：最新两帖「{作者}：{正文 30 字}」，作者 accent.text W600；无帖 → 空态一句。 */
@Composable
private fun LiuliCirclePreview(state: MomentsHubState) {
    val colors = AppTheme.colors
    val posts = state.previewPosts.take(2)
    if (posts.isEmpty()) {
        Text(
            stringResource(R.string.moment_hub_empty_preview), style = AppTypography.secondary,
            color = colors.text.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        return
    }
    val meLabel = stringResource(R.string.moment_author_me)
    val aiLabel = stringResource(R.string.moment_author_ai)
    posts.forEachIndexed { index, post ->
        val author = previewAuthor(post, state.charactersByUuid, meLabel, aiLabel)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = colors.accent.text, fontWeight = FontWeight.SemiBold)) { append("$author：") }
                withStyle(SpanStyle(color = colors.text.secondary)) { append(post.content.take(PREVIEW_CHARS).replace("\n", " ")) }
            },
            style = AppTypography.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = if (index == 0) 8.dp else 4.dp),
        )
    }
}

/** 方卡（日记 / 故事）：`heightIn(min 148)` · IconTile + 徽标同行 · 标题 · 正文两行省略。 */
@Composable
fun LiuliGridCard(
    icon: ImageVector,
    tileTint: Color,
    tileInk: Color,
    title: String,
    body: String,
    badgeText: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    LiuliHubCard(
        onClick = onClick,
        onClickLabel = title,
        modifier = modifier.heightIn(min = LiuliHomeGeometry.gridCardMinHeight),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiuliIconTile(icon, tileTint, tileInk)
            if (badgeText != null) {
                Spacer(Modifier.weight(1f))
                Text(
                    badgeText, color = colors.accent.onPrimary,
                    style = AppTypography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.W600),
                    modifier = Modifier.clip(LiuliShapes.pill).background(colors.accent.primary).padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = AppTypography.titleSmall, color = colors.text.primary)
        Spacer(Modifier.height(4.dp))
        Text(body, style = AppTypography.secondary, color = colors.text.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 日记方卡：`status.successContainer` 图标块（A-12）+ 最新一篇预览 + 未读评论徽标。 */
@Composable
fun LiuliDiaryCard(state: MomentsHubState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    LiuliGridCard(
        icon = AppFeatureIcons.Diary,
        tileTint = colors.status.successContainer,
        tileInk = colors.status.onSuccess,
        title = stringResource(R.string.moment_hub_diary),
        body = liuliDiaryPreviewText(state),
        badgeText = if (state.diaryUnreadCount > 0) stringResource(R.string.diary_unread_badge, state.diaryUnreadCount) else null,
        onClick = onClick,
        modifier = modifier,
    )
}

/** 故事方卡：`accent.container` 图标块（A-12）+ 连载进度三态。 */
@Composable
fun LiuliStoryCard(state: MomentsHubState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    LiuliGridCard(
        icon = AppFeatureIcons.Story,
        tileTint = colors.accent.container,
        tileInk = colors.accent.onContainer,
        title = stringResource(R.string.moment_hub_story),
        body = liuliStoryPreviewText(state),
        badgeText = null,
        onClick = onClick,
        modifier = modifier,
    )
}

/** 故事卡 body：连载进度三态（判据借 `storyHubStatus` 纯函数·只搬文案装配）。 */
@Composable
internal fun liuliStoryPreviewText(state: MomentsHubState): String = when (val s = storyHubStatus(state.latestStory)) {
    is StoryHubStatus.Chapter -> stringResource(R.string.story_hub_chapter, s.number, s.title)
    StoryHubStatus.NoChapter -> stringResource(R.string.story_hub_no_chapter)
    StoryHubStatus.None -> stringResource(R.string.moment_hub_story_desc)
}

/** 日记卡 body：最新一篇非草稿日记的「心情 emoji + 正文前 30」；无则默认描述（照暖陶 F6 逐字）。 */
@Composable
internal fun liuliDiaryPreviewText(state: MomentsHubState): String {
    val entry = state.latestDiary ?: return stringResource(R.string.moment_hub_diary_desc)
    val body = entry.content.take(PREVIEW_CHARS).replace("\n", " ")
    if (body.isBlank()) return stringResource(R.string.moment_hub_diary_desc)
    val emoji = entry.moodEmoji
    return if (!emoji.isNullOrEmpty()) "$emoji $body" else body
}

/** 预览作者名（本地化「我」/ 角色名 / 「AI」回落·照暖陶 F6 逐字）。 */
private fun previewAuthor(
    post: MomentPostEntity,
    charactersByUuid: Map<String, CharacterEntity>,
    meLabel: String,
    aiLabel: String,
): String = when (MomentAuthorType.fromRaw(post.authorTypeRaw)) {
    MomentAuthorType.USER -> meLabel
    MomentAuthorType.CHARACTER -> post.characterUuid?.let { charactersByUuid[it]?.name } ?: aiLabel
}
