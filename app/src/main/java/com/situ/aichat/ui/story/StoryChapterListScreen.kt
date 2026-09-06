package com.situ.aichat.ui.story

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.story.StoryArcPlanning
import com.situ.aichat.story.StoryReadingProgressLogic
import com.situ.aichat.story.StoryStatus
import com.situ.aichat.story.isUnlocked
import com.situ.aichat.story.unlockRemainingMinutes
import kotlinx.coroutines.delay

/**
 * 章节列表 = 时间线（ST7c 换装·契约 §6.3·照 mockup 屏四）。书头卡（程序化封面 + 书名 + 连载状态·已读话数·选择数）+
 * 快捷操作（去做选择 / 继续阅读）+ 章节时间线：左轴四节点（已读实心 / 有选择菱形 / 未读描边 / 追更锁）+ 章号 + 标题（含「新」徽）+
 * teaser 一行 + 「▶ 当时你选了…」选择回显 + 解锁倒计时。空态按生成中/失败/等待显示副标题 + 失败可重试。全量脱 M3 配色 → AppTheme token。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryChapterListScreen(
    onBack: () -> Unit,
    onStoryGone: () -> Unit,
    onOpenChapter: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: StoryChapterListViewModel = hiltViewModel(),
) {
    val story by viewModel.story.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val generatingPhase by viewModel.generatingPhase.collectAsStateWithLifecycle()
    val lastReadChapterId by viewModel.lastReadChapterId.collectAsStateWithLifecycle()
    val advancedFromChapterNumber by viewModel.advancedFromChapterNumber.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshReadingProgress() }

    // 深链兜底（2026-08-04）：解锁通知点开时书可能已被删——提示一声、体面退回，不停在假「生成中」空态。
    val storyMissing by viewModel.storyMissing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(storyMissing) {
        if (storyMissing) {
            Toast.makeText(context, R.string.story_missing_toast, Toast.LENGTH_SHORT).show()
            onStoryGone()
        }
    }

    // 每 60s 刷新一次「现在」，驱动锁态 + 解锁倒计时（= iOS TimelineView .periodic 60s）。
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000)
        }
    }

    val listState = rememberLazyListState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.story_chapter_nav_title),
                onBack = onBack,
                lifted = listState.canScrollBackward,
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.story_menu_settings))
                    }
                },
            )
        },
    ) { padding ->
        if (chapters.isEmpty()) {
            ChapterListEmptyState(
                generatingPhase = generatingPhase,
                status = story?.status,
                onRetry = viewModel::retryGeneration,
                modifier = Modifier.padding(padding),
            )
        } else {
            val pending = StoryReadingProgressLogic.latestPendingChoiceChapter(chapters)
            val resume = StoryReadingProgressLogic.preferredResumeChapter(
                chapters, lastReadChapterId, advancedFromChapterNumber,
            )
            val reversed = chapters.asReversed()
            val latestId = chapters.lastOrNull()?.id
            val lastReadNumber = chapters.firstOrNull { it.id == lastReadChapterId }?.chapterNumber ?: 0
            val choiceCount = chapters.count { it.userChoice != null }
            // 卷三 C4：章号 → 该章之前要插的弧小节头（列表最新在上，故头行挂在每段区间**最大**的那一章上）。
            val arcHeadByChapterId = remember(chapters, story?.arcHistory, story?.currentArcStartChapter, story?.currentArc) {
                arcHeadAnchors(chapters, story)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                // 屏 gutter 恒 20（设计语言 §2.5 军规）
                contentPadding = PaddingValues(horizontal = AppSpacing.screenGutter, vertical = 16.dp),
            ) {
                item(key = "header") { story?.let { BookHead(it, lastReadNumber, choiceCount) } }
                item(key = "quick") { QuickActions(pending, resume, onOpenChapter) }
                itemsIndexed(reversed, key = { _, c -> c.id }) { index, chapter ->
                    // 卷三 C4：弧边界插小节头（吃卷二弧线简史现成数据）。简史空且无进行中弧 → 恒空表 → 与分组前完全一致。
                    arcHeadByChapterId[chapter.id]?.let { ArcSectionHeader(it) }
                    ChapterTimelineRow(
                        chapter = chapter,
                        isLatest = chapter.id == latestId,
                        isLast = index == reversed.lastIndex,
                        lastReadNumber = lastReadNumber,
                        now = now,
                        onClick = { if (chapter.isUnlocked(now)) onOpenChapter(chapter.id) },
                    )
                }
            }
        }
    }
}

// ── 按弧分组小节头（卷三 C4·可选件）──

/**
 * 把弧线简史解析成「章 id → 该章之前要插的小节头」。
 *
 * 列表最新在上，所以每段弧的头行挂在**该弧区间内实际存在的最大章号**那一章上；区间内一章都没有（简史与
 * 实际章号错峰的罕见情形）就不挂——**不做对账修正**，只是不渲染一个底下空无一物的头（图纸 §5 E9）。
 */
internal fun arcHeadAnchors(
    chapters: List<StoryChapterEntity>,
    story: StoryEntity?,
): Map<String, StoryArcPlanning.ArcSection> {
    if (story == null || chapters.isEmpty()) return emptyMap()
    val sections = StoryArcPlanning.arcSections(
        arcHistory = story.arcHistory,
        currentArcStartChapter = story.currentArcStartChapter,
        currentArcTheme = story.currentArc,
        latestChapterNumber = chapters.last().chapterNumber,
    )
    if (sections.isEmpty()) return emptyMap()
    return buildMap {
        sections.forEach { section ->
            val end = section.endInclusive ?: Int.MAX_VALUE
            chapters.lastOrNull { it.chapterNumber in section.start..end }?.let { put(it.id, section) }
        }
    }
}

/** 弧小节头：两侧发丝线 + 中间小字（不可点、无节点轴）。 */
@Composable
internal fun ArcSectionHeader(section: StoryArcPlanning.ArcSection) {
    val c = AppTheme.colors
    val label = when {
        section.ongoing && section.theme != null ->
            stringResource(R.string.story_arc_section_ongoing_format, section.start, section.theme)
        section.ongoing -> stringResource(R.string.story_arc_section_ongoing_plain, section.start)
        else -> stringResource(
            R.string.story_arc_section_format,
            section.start,
            section.endInclusive ?: section.start,
            section.theme.orEmpty(),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f).height(0.5.dp).background(c.surface.stroke))
        Text(
            label.trimEnd(' ', '·'),
            fontSize = 10.5.sp,
            color = c.text.tertiary,
            letterSpacing = 0.63.sp, // 10.5sp × 0.06em
        )
        Box(Modifier.weight(1f).height(0.5.dp).background(c.surface.stroke))
    }
}

/** 书头卡：小封面 + 书名 + 「连载状态 · 已读 N 话 · M 次选择」。 */
@Composable
private fun BookHead(story: StoryEntity, lastReadNumber: Int, choiceCount: Int) {
    val c = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp)
            // 容器三连（clip+background(raised)+border）→ appCardSurface（§4.A9）；封面/文字/徽章零改。
            .appCardSurface()
            .padding(13.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StoryCover(
            coverColorScheme = story.coverColorScheme,
            title = story.title,
            storyId = story.id,
            titleSizeSp = 8.5f,
            modifier = Modifier.size(width = 56.dp, height = 74.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(story.title, style = AppTheme.typography.titleSmall, color = c.text.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(
                    R.string.story_chapter_head_meta,
                    stringResource(storyStatusDisplayNameRes(story.status)),
                    lastReadNumber,
                    choiceCount,
                ),
                style = AppTheme.typography.secondary,
                color = c.text.secondary,
            )
        }
    }
}

@Composable
private fun QuickActions(
    pending: StoryChapterEntity?,
    resume: StoryChapterEntity?,
    onOpenChapter: (String) -> Unit,
) {
    if (pending == null && resume == null) return
    Column(modifier = Modifier.padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (pending != null) {
            AppButton(onClick = { onOpenChapter(pending.id) }, style = AppButtonStyle.Primary, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.story_quick_make_choice, pending.chapterNumber))
            }
        }
        if (resume != null && resume.id != pending?.id) {
            AppButton(onClick = { onOpenChapter(resume.id) }, modifier = Modifier.fillMaxWidth(), style = AppButtonStyle.Tonal) {
                Text(stringResource(R.string.story_quick_continue, resume.chapterNumber))
            }
        }
    }
}

/** 时间线节点四态（契约 §6.3·照 mockup .node）。 */
private enum class NodeKind { DOT, RING, DIAMOND, LOCK }

@Composable
private fun ChapterTimelineRow(
    chapter: StoryChapterEntity,
    isLatest: Boolean,
    isLast: Boolean,
    lastReadNumber: Int,
    now: Long,
    onClick: () -> Unit,
) {
    val c = AppTheme.colors
    val unlocked = chapter.isUnlocked(now)
    val isRead = unlocked && chapter.chapterNumber <= lastReadNumber
    val kind = when {
        !unlocked -> NodeKind.LOCK
        chapter.hasChoice -> NodeKind.DIAMOND
        isRead -> NodeKind.DOT
        else -> NodeKind.RING
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (unlocked) it.clickable(onClick = onClick) else it }
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 左轴：节点 + 连接线（线按整行高度自适应填充）
        Column(modifier = Modifier.width(23.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 2.dp)) { TimelineNode(kind) }
            if (!isLast) {
                Box(Modifier.padding(top = 2.dp).width(1.5.dp).weight(1f).background(c.surface.stroke))
            }
        }
        // 右侧章节文字
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.story_chapter_ep, chapter.chapterNumber),
                style = AppTheme.typography.caption,
                color = c.text.tertiary,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    chapter.title,
                    style = AppTheme.typography.label,
                    color = if (unlocked) c.text.primary else c.text.secondary,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isLatest) NewBadge()
                if (!unlocked) Icon(Icons.Filled.Lock, contentDescription = null, tint = c.accent.text, modifier = Modifier.size(13.dp))
            }
            chapter.teaser?.takeIf { it.isNotEmpty() }?.let { teaser ->
                Text(teaser, style = AppTheme.typography.secondary, color = c.text.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            chapter.userChoice?.takeIf { it.isNotEmpty() }?.let { choice -> ChoiceEcho(choice) }
            if (!unlocked && chapter.unlockAt != null) UnlockCountdownText(chapter.unlockAt, now)
        }
    }
}

@Composable
private fun TimelineNode(kind: NodeKind) {
    val c = AppTheme.colors
    Box(Modifier.size(23.dp), contentAlignment = Alignment.Center) {
        when (kind) {
            NodeKind.DOT -> Box(Modifier.size(11.dp).clip(CircleShape).background(c.accent.primary))
            NodeKind.RING -> Box(Modifier.size(11.dp).clip(CircleShape).background(c.surface.base).border(2.dp, c.text.tertiary, CircleShape))
            NodeKind.DIAMOND -> Box(Modifier.size(11.dp).rotate(45f).clip(RoundedCornerShape(2.dp)).background(c.accent.text))
            NodeKind.LOCK -> Box(
                Modifier.size(21.dp).clip(CircleShape).background(c.surface.sunken),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Lock, contentDescription = null, tint = c.text.secondary, modifier = Modifier.size(11.dp)) }
        }
    }
}

@Composable
private fun NewBadge() {
    val c = AppTheme.colors
    Text(
        stringResource(R.string.story_chapter_new_badge),
        style = AppTheme.typography.caption,
        color = c.accent.text,
        modifier = Modifier
            .clip(AppTheme.shapes.full)
            .background(c.accent.container)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun ChoiceEcho(choice: String) {
    val c = AppTheme.colors
    Text(
        stringResource(R.string.story_chapter_choice_echo, choice),
        style = AppTheme.typography.caption,
        color = c.accent.text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(top = 3.dp)
            .clip(AppTheme.shapes.full)
            .background(c.accent.container)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun UnlockCountdownText(unlockAt: Long, now: Long) {
    val c = AppTheme.colors
    val text = if (now >= unlockAt) {
        stringResource(R.string.story_unlocked)
    } else {
        val mins = unlockRemainingMinutes(unlockAt, now)
        val remaining = if (mins >= 60) {
            stringResource(R.string.story_remaining_hm, mins / 60, mins % 60)
        } else {
            stringResource(R.string.story_remaining_m, mins)
        }
        stringResource(R.string.story_unlock_in, remaining)
    }
    Text(text, style = AppTheme.typography.secondary, color = c.text.secondary, modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun ChapterListEmptyState(
    generatingPhase: String?,
    status: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = null,
            tint = c.text.tertiary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.story_empty_chapter_title), style = AppTheme.typography.titleSmall, color = c.text.primary)
        Spacer(Modifier.height(6.dp))
        // 生成中直接显示真实阶段词（不走资源：故事模块阶段文案硬编码惯例，且它由 StoryProgressModel 单源生成）。
        val subtitle = generatingPhase ?: stringResource(
            if (status == StoryStatus.GENERATION_FAILED) R.string.story_empty_failed else R.string.story_empty_waiting,
        )
        Text(
            subtitle,
            style = AppTheme.typography.secondary,
            color = c.text.secondary,
            textAlign = TextAlign.Center,
        )
        if (status == StoryStatus.GENERATION_FAILED) {
            Spacer(Modifier.height(16.dp))
            AppButton(onClick = onRetry, style = AppButtonStyle.Primary) { Text(stringResource(R.string.story_quick_regenerate)) }
        }
    }
}
