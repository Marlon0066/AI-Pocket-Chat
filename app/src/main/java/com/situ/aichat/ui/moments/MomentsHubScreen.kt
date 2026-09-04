package com.situ.aichat.ui.moments

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.model.MomentAuthorType
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppFeatureIcons
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.ourdays.OurDaysStrip
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.EmotionTileAlpha
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.ui.designsystem.grainSurface

/**
 * 动态页内容（动态 Tab·W11 契约 §16 方案①）：hero 位 = **世界卡**（活星球窗景·信息条活文案·整卡点进世界）；
 * 原「圈子」深陶 hero 降级为**动态条**（横幅·点进朋友圈·未读徽标保留）；日记 / 故事两网格卡零碰。
 * W9a 临时「世界」入口行与宠物宽卡已撤（宠物入世界·状态进世界卡信息条·决策 34②）。配色：世界卡恒暗自包含，
 * 动态条 / 网格卡 / 页面走 [AppTheme] token。
 *
 * **卡片外衣 = 「我」页 v2 同款**（2026-07-12 用户拍板「样式复刻」·MOMENTS_HUB 契约 §11）：动态条与网格卡
 * 走 [appCardSurface]（双层软影 + 发丝描边 + 呼吸白 / 深色无影+发丝线——本页全 rest 档，月光沿属 raised 专属不出现），
 * 页底 [grainSurface] 纸感微噪，
 * gutter 20 / 卡内 16 上 4dp 网格；动态条圆角 18 字面量并轨 [AppShapes.medium]。**只换外衣**：布局结构 /
 * 卡片内容 / 路由 / 世界卡（恒暗窗景·圆角 22 = W11 §4.1 锁值·「窗外风景」不上纸影）一概不动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentsHubScreen(
    onOpenFeed: () -> Unit,
    onOpenDiary: () -> Unit,
    onOpenStory: () -> Unit,
    onOpenOurDays: () -> Unit,
    onOpenWorld: () -> Unit,
    bottomContentPadding: Dp = 0.dp,
    onOpenPet: (String) -> Unit = {}, // W12.5（§4.4）：世界卡信息条宠物段 → petDetail
    viewModel: MomentsHubViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val worldCard by viewModel.worldCard.collectAsStateWithLifecycle()
    val apiMissing by viewModel.apiMissing.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshApiMissing() }

    val scrollState = rememberScrollState()
    Scaffold(
        topBar = { AppTopBar(title = stringResource(R.string.moment_hub_title), lifted = scrollState.value > 0) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(AppTheme.colors.surface.base)
                .grainSurface()
                .verticalScroll(scrollState)
                .contentMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp), // v2 军规：屏 gutter 恒 20
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (apiMissing) ApiMissingBanner()

            WorldHeroCard(worldCard = worldCard, onOpenWorld = onOpenWorld, onOpenPet = onOpenPet)

            CircleStrip(state = state, onClick = onOpenFeed)

            OurDaysStrip(onClick = onOpenOurDays)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DiaryCard(state = state, onClick = onOpenDiary, modifier = Modifier.weight(1f))
                StoryCard(state = state, onClick = onOpenStory, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(bottomContentPadding))
        }
    }
}

@Composable
private fun ApiMissingBanner() {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.medium)
            .background(colors.status.warningContainer)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.status.onWarning, modifier = Modifier.size(16.dp))
        Text(
            stringResource(R.string.moment_api_missing),
            style = MaterialTheme.typography.bodySmall,
            color = colors.status.onWarning,
        )
    }
}

/**
 * 动态条（§4.5·朋友圈降级横幅）：行1「圈子」标题 + 副标 + 未读徽标 + chevron；头像排（前5·26dp·叠放-6dp·
 * 描边容器底色）；最新一帖预览（作者名 accent + 正文 secondary）。整条点进朋友圈。未读徽标呈现原样搬自旧 Hero
 * （功能保全·D-5）。颜色走 token（D-4）。
 */
@Composable
private fun CircleStrip(state: MomentsHubState, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 我页 v2 同款外衣（契约 §11）；圆角 18 字面量并轨 16（appCardSurface 默认·裁剪单源在内）。
            .appCardSurface()
            .clickableScale { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.moment_nav_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.moment_hub_circle_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = colors.text.secondary,
            )
            Spacer(Modifier.weight(1f))
            if (state.unreadCount > 0) {
                // W11 R1 🟡-2（D-6 拍板）：未读徽标 token 化——与本文件 GridCard 未读角标同族
                // （status.errorContainer 底 / status.onError 字·形状同），位置尺寸不变。
                Text(
                    "${state.unreadCount}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.status.onError,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.status.errorContainer)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.text.tertiary,
            )
        }

        if (state.heroAvatars.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 9.dp),
                horizontalArrangement = Arrangement.spacedBy((-6).dp),
            ) {
                // heroAvatars 由 VM 封顶 5（HERO_AVATAR_MAX·W11 R1 🔵-1）→ take(5) 与之对齐（demo 五头像=规格）。
                state.heroAvatars.take(5).forEach { character ->
                    Box(modifier = Modifier.border(2.dp, colors.surface.raised, CircleShape)) {
                        CharacterAvatar(name = character.name, avatarPath = character.avatarPath, size = 26.dp)
                    }
                }
            }
        }

        CircleStripPreview(state)
    }
}

/** 动态条预览行（§4.5）：「{作者名}：{正文首行}」作者 accent、正文 secondary·单行省略·无帖 → 空态。 */
@Composable
private fun CircleStripPreview(state: MomentsHubState) {
    val colors = AppTheme.colors
    val post = state.previewPosts.firstOrNull()
    if (post == null) {
        Text(
            stringResource(R.string.moment_hub_empty_preview),
            style = MaterialTheme.typography.bodySmall,
            color = colors.text.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        return
    }
    val meLabel = stringResource(R.string.moment_author_me)
    val aiLabel = stringResource(R.string.moment_author_ai)
    val author = previewAuthor(post, state.charactersByUuid, meLabel, aiLabel)
    val body = post.content.take(30).replace("\n", " ")
    val annotated = buildAnnotatedString {
        withStyle(SpanStyle(color = colors.accent.primary, fontWeight = FontWeight.SemiBold)) { append("$author：") }
        withStyle(SpanStyle(color = colors.text.secondary)) { append(body) }
    }
    Text(
        annotated,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** 日记网格卡：灰绿 IconTile + 最新一篇日记预览 + 未读评论角标。 */
@Composable
private fun RowScope.DiaryCard(state: MomentsHubState, onClick: () -> Unit, modifier: Modifier) {
    val colors = AppTheme.colors
    GridCard(
        modifier = modifier,
        icon = AppFeatureIcons.Diary,
        tileColor = colors.emotion.calm.copy(alpha = EmotionTileAlpha),
        iconColor = colors.emotion.calmInk,
        title = stringResource(R.string.moment_hub_diary),
        body = diaryPreviewText(state),
        badgeText = if (state.diaryUnreadCount > 0) {
            stringResource(R.string.diary_unread_badge, state.diaryUnreadCount)
        } else {
            null
        },
        onClick = onClick,
    )
}

/** 故事网格卡：雾蓝 IconTile + 连载进度（去 genre·契约 §3）。 */
@Composable
private fun RowScope.StoryCard(state: MomentsHubState, onClick: () -> Unit, modifier: Modifier) {
    val colors = AppTheme.colors
    GridCard(
        modifier = modifier,
        icon = AppFeatureIcons.Story,
        tileColor = colors.emotion.sad.copy(alpha = EmotionTileAlpha),
        iconColor = colors.emotion.sadInk,
        title = stringResource(R.string.moment_hub_story),
        body = storyPreviewText(state),
        badgeText = null,
        onClick = onClick,
    )
}

/** 网格卡通用外壳：图标块（+可选角标）+ 标题 + 两行实时 body。整卡按压缩放。 */
@Composable
private fun GridCard(
    modifier: Modifier,
    icon: ImageVector,
    tileColor: Color,
    iconColor: Color,
    title: String,
    body: String,
    badgeText: String?,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    Column(
        modifier = modifier
            .heightIn(min = 148.dp)
            .appCardSurface() // 我页 v2 同款外衣（契约 §11·裁剪单源在内）
            .clickableScale { onClick() }
            .padding(16.dp), // v2 卡内 16（原 14 孤值退场）
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, tileColor, iconColor)
            if (badgeText != null) {
                Spacer(Modifier.weight(1f))
                Text(
                    badgeText,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp),
                    color = colors.status.onError,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.status.errorContainer)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.text.primary)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = colors.text.secondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 40dp 圆角方块图标：柔和家族色底（emotion.X@alpha）+ 同族功能深档图标（浅底深字守 WCAG·设计语言 §1.4）。 */
@Composable
private fun IconTile(icon: ImageVector, tileColor: Color, iconColor: Color) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tileColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(22.dp))
    }
}

/** 故事卡 body：连载进度（第N章·标题 / 尚未生成 / 无故事兜底）。 */
@Composable
private fun storyPreviewText(state: MomentsHubState): String = when (val s = storyHubStatus(state.latestStory)) {
    is StoryHubStatus.Chapter -> stringResource(R.string.story_hub_chapter, s.number, s.title)
    StoryHubStatus.NoChapter -> stringResource(R.string.story_hub_no_chapter)
    StoryHubStatus.None -> stringResource(R.string.moment_hub_story_desc)
}

/** 动态条预览作者名（本地化「我」/角色名/「AI」回落·取法同旧 Hero 预览·§4.5）。 */
private fun previewAuthor(
    post: MomentPostEntity,
    charactersByUuid: Map<String, CharacterEntity>,
    meLabel: String,
    aiLabel: String,
): String = when (MomentAuthorType.fromRaw(post.authorTypeRaw)) {
    MomentAuthorType.USER -> meLabel
    MomentAuthorType.CHARACTER -> post.characterUuid?.let { charactersByUuid[it]?.name } ?: aiLabel
}

/** 日记卡 body：最新一篇非草稿日记的「心情 emoji + 正文前30」；无则默认描述。 */
@Composable
private fun diaryPreviewText(state: MomentsHubState): String {
    val entry = state.latestDiary ?: return stringResource(R.string.moment_hub_diary_desc)
    val body = entry.content.take(30).replace("\n", " ")
    if (body.isBlank()) return stringResource(R.string.moment_hub_diary_desc)
    val emoji = entry.moodEmoji
    return if (!emoji.isNullOrEmpty()) "$emoji $body" else body
}
