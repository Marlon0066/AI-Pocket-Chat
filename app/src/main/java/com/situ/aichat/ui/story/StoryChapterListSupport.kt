package com.situ.aichat.ui.story

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.story.StoryArcPlanning
import com.situ.aichat.story.StoryReadingProgressLogic
import com.situ.aichat.story.StoryStatus
import com.situ.aichat.story.isUnlocked
import com.situ.aichat.story.unlockRemainingMinutes
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.appCardSurface
import kotlinx.coroutines.delay

/** 章节页两件进页事（原 StoryChapterListScreen :87–98 逐字·含深链兜底原注释·两张脸共用）。 */
@Composable
internal fun StoryChapterListEffects(viewModel: StoryChapterListViewModel, onStoryGone: () -> Unit) {
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
}

/** 每 60s 刷新一次「现在」（原 :99–105 逐字·含原注释）：驱动锁态 + 解锁倒计时。调用处 `val now by rememberStoryMinuteClock()`。 */
@Composable
internal fun rememberStoryMinuteClock(): State<Long> =
    // 每 60s 刷新一次「现在」，驱动锁态 + 解锁倒计时（= iOS TimelineView .periodic 60s）。
    produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000)
        }
    }

/** 章节页六个派生量（原 :130–137 逐式·纯·T1）。 */
internal data class StoryChapterListSummary(
    val pending: StoryChapterEntity?,
    val resume: StoryChapterEntity?,
    val newestFirst: List<StoryChapterEntity>,
    val latestId: String?,
    val lastReadNumber: Int,
    val choiceCount: Int,
)

internal fun storyChapterListSummary(
    chapters: List<StoryChapterEntity>,
    lastReadChapterId: String?,
    advancedFromChapterNumber: Int?,
): StoryChapterListSummary = StoryChapterListSummary(
    pending = StoryReadingProgressLogic.latestPendingChoiceChapter(chapters),
    resume = StoryReadingProgressLogic.preferredResumeChapter(chapters, lastReadChapterId, advancedFromChapterNumber),
    newestFirst = chapters.asReversed(),
    latestId = chapters.lastOrNull()?.id,
    lastReadNumber = chapters.firstOrNull { it.id == lastReadChapterId }?.chapterNumber ?: 0,
    choiceCount = chapters.count { it.userChoice != null },
)

/** 弧小节头挂点（原 :138–141 的 remember 连 key 逐字·含卷三 C4 原注释）。 */
@Composable
internal fun rememberArcHeadAnchors(chapters: List<StoryChapterEntity>, story: StoryEntity?): Map<String, StoryArcPlanning.ArcSection> =
    // 卷三 C4：章号 → 该章之前要插的弧小节头（列表最新在上，故头行挂在每段区间**最大**的那一章上）。
    remember(chapters, story?.arcHistory, story?.currentArcStartChapter, story?.currentArc) { arcHeadAnchors(chapters, story) }

/** 「继续阅读」钮的目标（原 QuickActions 判据·纯·T1）：与「去做选择」是同一章时不重复给。 */
internal fun storyChapterContinueTarget(pending: StoryChapterEntity?, resume: StoryChapterEntity?): StoryChapterEntity? =
    resume?.takeIf { it.id != pending?.id }

/** 时间线节点四态（契约 §6.3·照 mockup .node）——原 private `NodeKind` 改名公开（琉璃 2.0 卷六·三）。 */
internal enum class StoryChapterNodeKind { DOT, RING, DIAMOND, LOCK }

/** 节点判据（原 ChapterTimelineRow :298–305 三式·纯·T1）：锁 > 有选择 > 已读 > 未读。 */
internal fun storyChapterNodeKind(chapter: StoryChapterEntity, now: Long, lastReadNumber: Int): StoryChapterNodeKind {
    val unlocked = chapter.isUnlocked(now)
    val isRead = unlocked && chapter.chapterNumber <= lastReadNumber
    return when {
        !unlocked -> StoryChapterNodeKind.LOCK
        chapter.hasChoice -> StoryChapterNodeKind.DIAMOND
        isRead -> StoryChapterNodeKind.DOT
        else -> StoryChapterNodeKind.RING
    }
}

/** 书头卡：小封面 + 书名 + 「连载状态 · 已读 N 话 · M 次选择」。 */
@Composable
internal fun StoryChapterBookHead(
    story: StoryEntity,
    lastReadNumber: Int,
    choiceCount: Int,
    modifier: Modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
    surface: Modifier = Modifier.appCardSurface(),
) {
    val c = AppTheme.colors
    Row(
        modifier = modifier
            // 容器三连（clip+background(raised)+border）→ appCardSurface（§4.A9）；封面/文字/徽章零改。
            .then(surface)
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

/** 时间线一行的右侧章节文字（原 ChapterTimelineRow 右列逐字·卡 / 底距由调用方的 [modifier] 给）。 */
@Composable
internal fun StoryChapterRowText(chapter: StoryChapterEntity, isLatest: Boolean, unlocked: Boolean, now: Long, modifier: Modifier) {
    val c = AppTheme.colors
    Column(
        modifier = modifier,
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
            if (!unlocked) Icon(AppActionIcons.Lock, contentDescription = null, tint = c.accent.text, modifier = Modifier.size(13.dp))
        }
        chapter.teaser?.takeIf { it.isNotEmpty() }?.let { teaser ->
            Text(teaser, style = AppTheme.typography.secondary, color = c.text.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        chapter.userChoice?.takeIf { it.isNotEmpty() }?.let { choice -> ChoiceEcho(choice) }
        if (!unlocked && chapter.unlockAt != null) UnlockCountdownText(chapter.unlockAt, now)
    }
}

@Composable
internal fun TimelineNode(kind: StoryChapterNodeKind) {
    val c = AppTheme.colors
    Box(Modifier.size(23.dp), contentAlignment = Alignment.Center) {
        when (kind) {
            StoryChapterNodeKind.DOT -> Box(Modifier.size(11.dp).clip(CircleShape).background(c.accent.primary))
            StoryChapterNodeKind.RING -> Box(Modifier.size(11.dp).clip(CircleShape).background(c.surface.base).border(2.dp, c.text.tertiary, CircleShape))
            StoryChapterNodeKind.DIAMOND -> Box(Modifier.size(11.dp).rotate(45f).clip(RoundedCornerShape(2.dp)).background(c.accent.text))
            StoryChapterNodeKind.LOCK -> Box(
                Modifier.size(21.dp).clip(CircleShape).background(c.surface.sunken),
                contentAlignment = Alignment.Center,
            ) { Icon(AppActionIcons.Lock, contentDescription = null, tint = c.text.secondary, modifier = Modifier.size(11.dp)) }
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
internal fun StoryChapterListEmptyState(
    generatingPhase: String?,
    status: String?,
    modifier: Modifier = Modifier,
    retryAction: @Composable () -> Unit,
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
            retryAction()
        }
    }
}
