package com.situ.aichat.ui.story

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.story.StoryEndingType
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppTheme

/**
 * 书架分区件（ST7a·契约 §6.1·照 mockup 屏一）：「开新故事」虚线陶土入场卡 + 「档案」已完结横排分组。
 * 纯展示，回调注入；不新增归档状态（档案 = status==completed 的展示层分组）。
 */

/** 「开新故事」入场卡：虚线陶土边 + 软陶渐变底 + 「+」药丸 + 标题/副文案 + 陶土 chevron。 */
@Composable
fun NewStoryCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppTheme.shapes.medium)
            .background(Brush.linearGradient(listOf(c.accent.gradientStart.copy(alpha = 0.14f), c.accent.gradientEnd.copy(alpha = 0.07f))))
            .drawBehind {
                val r = 16.dp.toPx()
                drawRoundRect(
                    color = c.accent.text.copy(alpha = 0.35f),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                    cornerRadius = CornerRadius(r, r),
                )
            }
            .clickableScale(onClick = onClick)
            .padding(15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(AppTheme.shapes.medium)
                .background(Brush.linearGradient(listOf(c.accent.gradientStart, c.accent.gradientEnd))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = c.text.onAccent, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.story_new_story_title), style = AppTheme.typography.label, color = c.text.primary)
            Text(stringResource(R.string.story_new_story_subtitle), style = AppTheme.typography.caption, color = c.text.secondary)
        }
        Icon(AppActionIcons.ChevronRight, contentDescription = null, tint = c.accent.text, modifier = Modifier.size(18.dp))
    }
}

/** 档案横排预览封顶（超此数在区头出「全部 ›」进全览网格·ST8 §5④）。 */
private const val ARCHIVE_PREVIEW_LIMIT = 6

/**
 * 「档案」分区：区头（档案 + 已完结 N + 「全部 ›」）+ 已完结故事横排紧凑卡（只横排最近几本·超 [ARCHIVE_PREVIEW_LIMIT] 出全览入口）。
 * [onOpen]→结局档案卡（ST8·非阅读器）；[onViewAll]→全览网格。
 *
 * 长按删除（2026-08-04 卷）：菜单展开态经 [menuStoryId] 由屏幕层持有（与在读卡菜单共用同一状态 →
 * scrim 压暗自动覆盖），本件保持纯展示；[onCardLongPress]/[onMenuDismiss]/[onDeleteRequest] 回调注入。
 */
@Composable
fun StoryArchiveSection(
    archived: List<StoryEntity>,
    onOpen: (String) -> Unit,
    onViewAll: () -> Unit,
    menuStoryId: String?,
    onCardLongPress: (String) -> Unit,
    onMenuDismiss: () -> Unit,
    onDeleteRequest: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** 档案小卡的长按菜单（琉璃 2.0 卷六·三「槽外给」：默认 = 暖陶玻璃菜单，琉璃传 LiuliPopupMenu）。 */
    menu: @Composable (story: StoryEntity) -> Unit = { story ->
        StoryArchivedCardMenu(
            expanded = menuStoryId == story.id,
            onDismiss = onMenuDismiss,
            onDelete = { onDeleteRequest(story.id) },
        )
    },
) {
    val c = AppTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.story_archive_header), style = AppTheme.typography.label, color = c.text.primary)
            Text(stringResource(R.string.story_archive_count, archived.size), style = AppTheme.typography.caption, color = c.text.tertiary)
            Spacer(Modifier.weight(1f))
            if (archived.size > ARCHIVE_PREVIEW_LIMIT) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(99.dp)).clickableScale(onClick = onViewAll),
                ) {
                    Text(stringResource(R.string.story_archive_view_all), style = AppTheme.typography.caption, color = c.accent.text)
                    Icon(AppActionIcons.ChevronRight, contentDescription = null, tint = c.accent.text, modifier = Modifier.size(15.dp))
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            items(archived.take(ARCHIVE_PREVIEW_LIMIT), key = { it.id }) { story ->
                // 包 Box 当菜单锚（ST10-1 锚定修复同款思路）：菜单贴本卡展开，不飘到分区边缘。
                Box {
                    ArchiveCard(story, onClick = { onOpen(story.id) }, onLongPress = { onCardLongPress(story.id) })
                    menu(story)
                }
            }
        }
    }
}

@Composable
private fun ArchiveCard(story: StoryEntity, onClick: () -> Unit, onLongPress: () -> Unit) {
    val c = AppTheme.colors
    Column(
        Modifier.width(78.dp).clickableScale(onClick = onClick, onLongClick = onLongPress),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        StoryCover(
            coverColorScheme = story.coverColorScheme,
            title = story.title,
            storyId = story.id,
            titleSizeSp = 10f,
            modifier = Modifier.size(width = 78.dp, height = 104.dp),
        )
        Text(
            story.title,
            style = AppTheme.typography.caption,
            color = c.text.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            archiveDetail(story),
            style = AppTheme.typography.caption,
            color = c.text.tertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 档案卡副行：「N 话 · 结局类型」。结局取 [StoryEntity.finalEndingType]（ST8 完结快照·持久列），
 * null = 自然完结 / 满章 / 手动归档 → 「全书完」——旧实现误读一次性的 requestedEndingType
 * （完结即清空，恒 null → 全部错显「开放式」），ST10-4 装机抽查中发现修正,与档案详情 ArchiveChips 对齐。
 */
@Composable
private fun archiveDetail(story: StoryEntity): String {
    val chapters = story.cachedLatestChapterNumber ?: story.cachedChapterCount
    val ending = stringResource(
        when (story.finalEndingType) {
            StoryEndingType.CUSTOM -> R.string.story_ending_short_custom
            StoryEndingType.AI -> R.string.story_ending_short_ai
            StoryEndingType.OPEN -> R.string.story_ending_short_open
            else -> R.string.story_ending_short_natural
        },
    )
    return stringResource(R.string.story_archive_item, chapters, ending)
}
