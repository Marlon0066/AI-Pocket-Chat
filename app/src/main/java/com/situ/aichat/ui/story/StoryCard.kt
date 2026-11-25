package com.situ.aichat.ui.story

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.story.StoryGenerationTaskManager
import com.situ.aichat.story.StoryStatus
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.util.DateFormatters

/**
 * 书架故事卡片（ST7a 换装·1:1 iOS `StoryCardView`）。无状态：故事实体 + 生成进度（生成中）+ 上次阅读章号 + 回调由
 * [StoryBookshelfScreen] 注入。程序化封面（[StoryCover]）/ 状态胶囊（token）/ 详情行 / mini 四段进度条 + 阶段词 /
 * 流式预览条 / 快捷操作（重生成·去做选择·继续阅读·阅读最新）。全走 [AppTheme] token，脱 M3 配色。
 *
 * @param generation 该书的活跃生成进度；null = 没在生成（不渲染进度区块）。与灵动岛药丸同信号源。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StoryCard(
    story: StoryEntity,
    generation: StoryGenerationTaskManager.GenerationProgress?,
    lastReadChapterNumber: Int?,
    onOpenStory: () -> Unit,
    onLongPress: () -> Unit,
    onContinueReading: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** 卡面材质（琉璃 2.0 卷六·三「材质外给」：默认 = 暖陶承托，琉璃传半透明卡）。 */
    surface: Modifier = Modifier.appCardSurface(),
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpenStory, onLongClick = onLongPress),
        ) {
            StoryCover(
                coverColorScheme = story.coverColorScheme,
                title = story.title,
                storyId = story.id,
                titleSizeSp = 11f,
                modifier = Modifier.size(width = 82.dp, height = 108.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        story.title,
                        style = AppTheme.typography.listName,
                        color = AppTheme.colors.text.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    StatusBadge(story.status)
                }
                Text(
                    detailLine(story),
                    style = AppTheme.typography.secondary,
                    color = AppTheme.colors.text.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    DateFormatters.dateYMD(story.updatedAt),
                    style = AppTheme.typography.caption,
                    color = AppTheme.colors.text.tertiary,
                )
            }
        }

        if (generation != null) {
            // 与灵动岛药丸、阅读器遮罩同信号源的 mini 四段条（D-4）。
            StoryPhaseBar(
                genPhase = generation.genPhase,
                progress = generation.progress,
                modifier = Modifier.fillMaxWidth(),
                mini = true,
            )
            StreamingPreviewBar(
                phase = generation.phase,
                // 生成刚起步时预览还是空串——空串不渲染正文行，只留阶段词（否则会多出一条空行）。
                text = generation.streamingPreview.takeIf { it.isNotEmpty() },
            )
        }

        QuickAction(
            story = story,
            lastReadChapterNumber = lastReadChapterNumber,
            onContinueReading = onContinueReading,
            onRetry = onRetry,
        )
    }
}

// MARK: - 状态胶囊（token·连载=陶土 / 等你选=琥珀 / 完结=中性 / 失败=红）

@Composable
private fun StatusBadge(status: String) {
    val badge = storyStatusBadgeColors(status)
    Surface(shape = AppTheme.shapes.full, color = badge.container) {
        Text(
            stringResource(storyStatusDisplayNameRes(status)),
            style = AppTheme.typography.caption,
            color = badge.content,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}

// MARK: - 详情行（genre · 第N章 · 标题 / 尚未生成 / 计划 N 章）

@Composable
private fun detailLine(story: StoryEntity): String {
    val latestNumber = story.cachedLatestChapterNumber
    val latestTitle = story.cachedLatestChapterTitle
    return when {
        latestNumber != null && !latestTitle.isNullOrEmpty() ->
            stringResource(R.string.story_detail_chapter, story.genre, latestNumber, latestTitle)
        story.maxChapters == null -> stringResource(R.string.story_detail_no_chapter, story.genre)
        else -> stringResource(R.string.story_detail_planned, story.genre, story.maxChapters)
    }
}

// MARK: - 快捷操作（重生成 / 生成中 / 去做选择 / 继续阅读 / 阅读最新）

@Composable
private fun QuickAction(
    story: StoryEntity,
    lastReadChapterNumber: Int?,
    onContinueReading: () -> Unit,
    onRetry: () -> Unit,
) {
    val kind = StoryCardLogic.quickAction(
        status = story.status,
        hasPendingChoice = story.cachedHasPendingChoice,
        latestChapterNumber = story.cachedLatestChapterNumber,
        lastReadChapterNumber = lastReadChapterNumber,
    ) ?: return
    val c = AppTheme.colors
    val (content, container) = when (kind) {
        StoryQuickAction.REGENERATE -> c.status.onError to c.status.errorContainer
        StoryQuickAction.MAKE_CHOICE -> c.status.onWarning to c.status.warningContainer
        StoryQuickAction.GENERATING -> c.text.secondary to c.surface.sunken
        else -> c.accent.text to c.accent.container
    }
    val icon: ImageVector = when (kind) {
        StoryQuickAction.REGENERATE -> AppActionIcons.Refresh
        StoryQuickAction.GENERATING -> Icons.Filled.MoreHoriz
        StoryQuickAction.MAKE_CHOICE -> Icons.Outlined.Forum
        else -> Icons.AutoMirrored.Filled.MenuBook
    }
    val title = when (kind) {
        StoryQuickAction.REGENERATE -> stringResource(R.string.story_quick_regenerate)
        StoryQuickAction.GENERATING -> stringResource(R.string.story_quick_generating)
        StoryQuickAction.MAKE_CHOICE -> stringResource(R.string.story_quick_make_choice, story.cachedLatestChapterNumber ?: 0)
        StoryQuickAction.CONTINUE_READING -> stringResource(R.string.story_quick_continue, lastReadChapterNumber ?: 0)
        StoryQuickAction.READ_LATEST -> stringResource(R.string.story_quick_read_latest)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            onClick = { if (kind == StoryQuickAction.REGENERATE) onRetry() else onContinueReading() },
            enabled = kind != StoryQuickAction.GENERATING,
            shape = AppTheme.shapes.medium,
            color = container,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = AppTheme.typography.label, color = content, modifier = Modifier.weight(1f))
                Icon(AppActionIcons.ChevronRight, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
            }
        }
        // 失败卡结局意图提示行（ST11 §4.4）：失败后意图不再被清（拍板①），告诉用户「重试仍会写结局」，
        // 免得以为系统忘了。只在「失败态 + 确有残留结局意图」时出现。
        if (story.status == StoryStatus.GENERATION_FAILED && story.requestedEndingType != null) {
            Spacer(Modifier.height(7.dp))
            val hint = stringResource(R.string.story_retry_ending_hint)
            Text(
                text = buildAnnotatedString {
                    // 「◆ 」= 纯文字装饰符（§4.4 明写不引图标），金色；正文 tertiary。
                    withStyle(SpanStyle(color = c.economy.gold)) { append("◆ ") }
                    append(hint)
                },
                style = AppTheme.typography.caption.copy(fontSize = 11.5.sp),
                color = c.text.tertiary,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

// MARK: - 流式创作预览条（阶段词 + 正文预览）

/**
 * @param phase 当前阶段词——取代原先恒定的「正在创作」静态标签（灵动岛卷一 §4.3）。
 * @param text 流式预览正文；null = 还没清出正文（只显示阶段词）。
 */
@Composable
private fun StreamingPreviewBar(phase: String, text: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppTheme.colors.surface.sunken, AppTheme.shapes.medium)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier.size(6.dp).background(AppTheme.colors.accent.primary, AppTheme.shapes.full))
            Text(
                phase,
                style = AppTheme.typography.caption,
                color = AppTheme.colors.text.secondary,
            )
        }
        // 末 2 个非空行（= iOS lastLines count:2 + truncationMode .head）。
        if (text != null) {
            Text(
                lastNonEmptyLines(text, 2),
                style = AppTheme.typography.secondary,
                color = AppTheme.colors.text.secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun lastNonEmptyLines(text: String, count: Int): String =
    text.split('\n').filter { it.isNotBlank() }.takeLast(count).joinToString("\n")
