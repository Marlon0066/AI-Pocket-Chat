package com.situ.aichat.ui.story

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import com.situ.aichat.ui.designsystem.AppTopBarAction
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.story.StoryArcPlanning
import com.situ.aichat.story.isUnlocked

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

    StoryChapterListEffects(viewModel, onStoryGone)

    val now by rememberStoryMinuteClock()

    val listState = rememberLazyListState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.story_chapter_nav_title),
                onBack = onBack,
                lifted = listState.canScrollBackward,
                actions = {
                    AppTopBarAction(
                        icon = Icons.Filled.Tune,
                        contentDescription = stringResource(R.string.story_menu_settings),
                        onClick = onOpenSettings,
                    )
                },
            )
        },
    ) { padding ->
        if (chapters.isEmpty()) {
            StoryChapterListEmptyState(generatingPhase, story?.status, Modifier.padding(padding)) {
                AppButton(onClick = viewModel::retryGeneration, style = AppButtonStyle.Primary) { Text(stringResource(R.string.story_quick_regenerate)) }
            }
        } else {
            val summary = storyChapterListSummary(chapters, lastReadChapterId, advancedFromChapterNumber)
            val arcHeadByChapterId = rememberArcHeadAnchors(chapters, story)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                // 屏 gutter 恒 20（设计语言 §2.5 军规）
                contentPadding = PaddingValues(horizontal = AppSpacing.screenGutter, vertical = 16.dp),
            ) {
                item(key = "header") { story?.let { StoryChapterBookHead(it, summary.lastReadNumber, summary.choiceCount) } }
                item(key = "quick") { QuickActions(summary.pending, summary.resume, onOpenChapter) }
                itemsIndexed(summary.newestFirst, key = { _, c -> c.id }) { index, chapter ->
                    // 卷三 C4：弧边界插小节头（吃卷二弧线简史现成数据）。简史空且无进行中弧 → 恒空表 → 与分组前完全一致。
                    arcHeadByChapterId[chapter.id]?.let { ArcSectionHeader(it) }
                    ChapterTimelineRow(
                        chapter = chapter,
                        isLatest = chapter.id == summary.latestId,
                        isLast = index == summary.newestFirst.lastIndex,
                        lastReadNumber = summary.lastReadNumber,
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

@Composable
private fun QuickActions(
    pending: StoryChapterEntity?,
    resume: StoryChapterEntity?,
    onOpenChapter: (String) -> Unit,
) {
    val cont = storyChapterContinueTarget(pending, resume)
    if (pending == null && cont == null) return
    Column(modifier = Modifier.padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (pending != null) {
            AppButton(onClick = { onOpenChapter(pending.id) }, style = AppButtonStyle.Primary, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.story_quick_make_choice, pending.chapterNumber))
            }
        }
        if (cont != null) {
            AppButton(onClick = { onOpenChapter(cont.id) }, modifier = Modifier.fillMaxWidth(), style = AppButtonStyle.Tonal) {
                Text(stringResource(R.string.story_quick_continue, cont.chapterNumber))
            }
        }
    }
}

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
    val kind = storyChapterNodeKind(chapter, now, lastReadNumber)
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
        StoryChapterRowText(chapter, isLatest, unlocked, now, Modifier.fillMaxWidth().padding(bottom = 22.dp))
    }
}
