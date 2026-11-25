package com.situ.aichat.ui.story

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppActionIcons

/**
 * 章末「本章操作行 + 三档快评行」（故事二期卷三·图纸 §4.1–§4.3·mockup 屏 3）。
 *
 * 章末纵向次序（D-14 锁定）：正文 → 建议完结卡 → **① 本章操作行 → ② 快评行** → 选择区 → 推进区。
 * 本文件只管这两条新行，选择区 / 推进区 / 建议卡 / `[chapter_end]` 渲染块一个像素都不碰；
 * 不并进 [StoryContinueZone] 是因为那个文件已 498 行压 UI 软上限 500（图纸 J1）。
 *
 * 配色全部走阅读器纸面单源（[StoryReaderLayout]），
 * 不硬编码任何 hex；玻璃浮层参数逐值照 [StoryReaderMenu]（同族配方，不新造）。
 */

/** ⋯ 触发钮的视觉直径 / 其内图标 / 浮层宽度（§9-② 锁定值·浮层比 ⋮ 菜单的 240 窄一档：只有三条动作行）。 */
internal val ACTIONS_TRIGGER_SIZE = 26.dp
internal val ACTIONS_TRIGGER_ICON = 14.dp
internal val ACTIONS_MENU_WIDTH = 200.dp

/** 快评胶囊高度 / 胶囊间距 / 标签字号（§9-② 锁定值）。 */
private val RATING_PILL_HEIGHT = 36.dp
private val RATING_PILL_GAP = 10.dp
private val RATING_LABEL_SP = 13.sp

/**
 * 章末两条新行的列表项接线：显示条件全交 [StoryReaderEndgameLogic] 的两个纯谓词
 * （`showChapterActions` / `showChapterRating`·§3.3），本函数只做「出不出」与参数透传。
 * 插在 `storyEndingSuggestItem` 之后、`item(key = "choice")` 之前——§4.1 的纵向次序即由调用位置决定。
 *
 * @param actionsExpanded 浮层展开态**托管在屏侧**（§3.4）——列表项滚出可视区会被回收，state 留在这里会丢。
 * @param canEditSummary 现状恒 true（历史章也能改小结）；留成参数是为了让 §3.3 的谓词有完整输入面。
 * @param onRate 传 1/2/3 落评、传 null 取消（再点同档由本文件判定后调用）。
 */
internal fun LazyListScope.storyChapterEndZoneItems(
    chapter: StoryChapterEntity?,
    isLatestChapter: Boolean,
    isDark: Boolean,
    canRewrite: Boolean,
    canViewPreviousDraft: Boolean,
    canEditSummary: Boolean,
    actionsExpanded: Boolean,
    onActionsExpandedChange: (Boolean) -> Unit,
    onRewrite: () -> Unit,
    onViewPreviousDraft: () -> Unit,
    onEditChapterSummary: () -> Unit,
    onRate: (Int?) -> Unit,
) {
    if (StoryReaderEndgameLogic.showChapterActions(canRewrite, canViewPreviousDraft, canEditSummary)) {
        item(key = "chapterActions") {
            ChapterActionsRow(
                isDark = isDark,
                expanded = actionsExpanded,
                onExpandedChange = onActionsExpandedChange,
                canRewrite = canRewrite,
                canViewPreviousDraft = canViewPreviousDraft,
                canEditSummary = canEditSummary,
                onRewrite = onRewrite,
                onViewPreviousDraft = onViewPreviousDraft,
                onEditChapterSummary = onEditChapterSummary,
            )
        }
    }
    if (StoryReaderEndgameLogic.showChapterRating(isLatestChapter, chapterExists = chapter != null)) {
        item(key = "chapterRating") {
            ChapterRatingRow(rating = chapter?.userRating, isDark = isDark, onRate = onRate)
        }
    }
}

/**
 * ① 本章操作行（§4.2·mockup 屏 3 上部）：一条发丝线 + 右端 ⋯ 触发钮，点开是三条「刚读完这章」的动作。
 * **有意做成独立列表项**（J4）：正文里的 `[chapter_end]` 装饰是渲染层的块，改它会污染渲染层与卷一的
 * 标签解析面；这条行浮在它下面，两者互不相干。
 */
@Composable
private fun ChapterActionsRow(
    isDark: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    canRewrite: Boolean,
    canViewPreviousDraft: Boolean,
    canEditSummary: Boolean,
    onRewrite: () -> Unit,
    onViewPreviousDraft: () -> Unit,
    onEditChapterSummary: () -> Unit,
) {
    val border = StoryReaderLayout.chromeBorderColor(isDark)
    val scrim = StoryReaderLayout.chromeScrimColor(isDark)
    val content = StoryReaderLayout.textColor(isDark)
    val surface = StoryReaderLayout.menuSurfaceColor(isDark)
    val accent = StoryReaderLayout.menuAccentColor(isDark)
    Row(
        modifier = Modifier
            .widthIn(max = StoryReaderLayout.maxContentWidth)
            .fillMaxWidth()
            .padding(horizontal = StoryReaderLayout.horizontalPadding)
            .padding(top = 18.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(border))
        Spacer(Modifier.width(12.dp))
        // 触发钮与浮层同包一个 Box：锚点 = ⋯ 自身（照 StoryReaderMenu 的「锚定修复」——兄弟节点会让浮层跑偏）。
        Box {
            Surface(
                onClick = { onExpandedChange(true) },
                shape = CircleShape,
                color = scrim,
                border = BorderStroke(0.75.dp, border),
                modifier = Modifier.size(ACTIONS_TRIGGER_SIZE),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.MoreHoriz,
                        contentDescription = stringResource(R.string.story_chapter_actions_desc),
                        tint = content,
                        modifier = Modifier.size(ACTIONS_TRIGGER_ICON),
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
                offset = DpOffset(0.dp, 4.dp),
                shape = RoundedCornerShape(20.dp),
                containerColor = surface,
                tonalElevation = 0.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(0.75.dp, border),
                modifier = Modifier.width(ACTIONS_MENU_WIDTH),
            ) {
                if (canRewrite) {
                    MenuActionRow(AppActionIcons.Refresh, stringResource(R.string.story_chapter_action_rewrite), content, accent) {
                        onExpandedChange(false); onRewrite()
                    }
                }
                if (canViewPreviousDraft) {
                    MenuActionRow(Icons.Filled.History, stringResource(R.string.story_chapter_action_prev_draft), content, accent) {
                        onExpandedChange(false); onViewPreviousDraft()
                    }
                }
                if (canEditSummary) {
                    MenuActionRow(Icons.Filled.EditNote, stringResource(R.string.story_chapter_action_summary), content, accent) {
                        onExpandedChange(false); onEditChapterSummary()
                    }
                }
            }
        }
    }
}

/**
 * ② 三档快评行（§4.3·mockup 屏 3 中部）：读完顺手评一下，下一章生成时由卷一的
 * `StoryCraftSections.appendReaderFeedback` 消费（1..3 才注入）。提示行随「评没评过」二选一；
 * 三枚胶囊映射 爽 3 / 还行 2 / 不行 1，再点已选的那一档 = 取消（传 null·J3）。
 * 落库即时、**无反悔窗**——评分随时可改写，不像选择那样一提交就决定下一章走向。
 */
@Composable
private fun ChapterRatingRow(rating: Int?, isDark: Boolean, onRate: (Int?) -> Unit) {
    val secondary = StoryReaderLayout.secondaryTextColor(isDark)
    Column(
        modifier = Modifier
            .widthIn(max = StoryReaderLayout.maxContentWidth)
            .fillMaxWidth()
            .padding(horizontal = StoryReaderLayout.horizontalPadding)
            .padding(top = 10.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(storyRatingPromptRes(rating)),
            color = secondary,
            fontSize = 12.sp,
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(RATING_PILL_GAP, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            storyRatingTiers.forEach { (labelRes, tier) ->
                RatingPill(labelRes, tier = tier, current = rating, isDark = isDark, onRate = onRate)
            }
        }
    }
}

/**
 * 一枚快评胶囊。未选 = 透明底 + 发丝描边；已选 = 陶土实底 + [StoryReaderLayout.onAccentTextColor] 反白字。
 *
 * **选中过渡只走颜色轴**（§4.3）：无缩放无弹跳——胶囊在正文流里，弹一下会把读者的眼睛从字上拽走
 * （提案「墨点晕开」的降级理由见图纸 §11 D-5）。未选态用 `accent.copy(alpha = 0f)` 而不是
 * `Color.Transparent`：渲染同为全透明，但渐变路径不会从「透明的黑」中途拐一趟灰（Color 插值连色相一起走）。
 */
@Composable
private fun RatingPill(
    @StringRes labelRes: Int,
    tier: Int,
    current: Int?,
    isDark: Boolean,
    onRate: (Int?) -> Unit,
) {
    val haptics = LocalAppHaptics.current
    val selected = current == tier
    val accent = StoryReaderLayout.menuAccentColor(isDark)
    val fill = if (selected) accent else accent.copy(alpha = 0f)
    val label = if (selected) StoryReaderLayout.onAccentTextColor(isDark) else StoryReaderLayout.textColor(isDark)
    val border = StoryReaderLayout.chromeBorderColor(isDark)
    val stateDesc = stringResource(if (selected) R.string.a11y_selected else R.string.a11y_not_selected)
    Surface(
        // 再点已选中的那一档 = 取消评分（§3.2）；触达 48dp 由 M3 可点 Surface 兜底（视觉仍 36dp）。
        onClick = { haptics.light(); onRate(storyRatingNext(current, tier)) },
        shape = CircleShape,
        color = fill,
        border = if (selected) null else BorderStroke(1.dp, border),
        modifier = Modifier
            .height(RATING_PILL_HEIGHT)
            .semantics {
                role = Role.RadioButton
                stateDescription = stateDesc
            },
    ) {
        Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(labelRes),
                color = label,
                fontSize = RATING_LABEL_SP,
                fontWeight = if (selected) FontWeight.Medium else null,
            )
        }
    }
}
