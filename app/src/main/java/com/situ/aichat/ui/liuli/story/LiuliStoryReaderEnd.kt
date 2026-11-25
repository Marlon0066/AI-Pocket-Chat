package com.situ.aichat.ui.liuli.story

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.Palette
import com.situ.aichat.ui.liuli.designsystem.LiuliMaterials
import com.situ.aichat.ui.liuli.designsystem.LiuliMenuEntry
import com.situ.aichat.ui.liuli.designsystem.LiuliPopupMenu
import com.situ.aichat.ui.liuli.designsystem.liuliPaintedLens
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.liuliFootprint
import com.situ.aichat.ui.liuli.page.liuliTouchHeight
import com.situ.aichat.ui.story.ACTIONS_MENU_WIDTH
import com.situ.aichat.ui.story.ACTIONS_TRIGGER_ICON
import com.situ.aichat.ui.story.ACTIONS_TRIGGER_SIZE
import com.situ.aichat.ui.story.ReaderDialog
import com.situ.aichat.ui.story.StoryChoiceHeader
import com.situ.aichat.ui.story.StoryEndingSuggestCard
import com.situ.aichat.ui.story.StoryFinaleProgress
import com.situ.aichat.ui.story.StoryReaderEndgameLogic
import com.situ.aichat.ui.story.StoryReaderHaptics
import com.situ.aichat.ui.story.StoryReaderLayout
import com.situ.aichat.ui.story.StoryReaderNav
import com.situ.aichat.ui.story.StoryReaderSheetsState
import com.situ.aichat.ui.story.rememberStoryChoiceOptions
import com.situ.aichat.ui.story.rememberStoryChoicePopScale
import com.situ.aichat.ui.story.storyChoiceChapter
import com.situ.aichat.ui.story.storyChoiceDimmed
import com.situ.aichat.ui.story.storyChoiceFeedbackText
import com.situ.aichat.ui.story.storyChoiceLetter
import com.situ.aichat.ui.story.storyChoicePromptText
import com.situ.aichat.ui.story.storyContinueZoneState
import com.situ.aichat.ui.story.storyRatingNext
import com.situ.aichat.ui.story.storyRatingPromptRes
import com.situ.aichat.ui.story.storyRatingTiers
import com.situ.aichat.ui.story.storyShowsContinueZone
import com.situ.aichat.ui.story.storyShowsEndingSuggest

// 琉璃阅读器章末内容层（琉璃 2.0 卷六·三·下甲 §4.5）：次序与 key 同暖陶（D-14）；判据 / 取值 / 动效一律调共用件。
// 卡片材质 = 画出来的玻璃（内容层放不了真玻璃）；字色走纸面层，强调色走 accent（琉璃皮下 = 紫）。

/** 写作中上锁 / 选中后其余项变淡（= 暖陶选项淡出值）。 */
internal const val LOCKED_ALPHA = 0.45f
private const val CHOICE_SELECTED_RIM_ALPHA = 0.55f
private const val CHOICE_FREE_DASH_ALPHA = 0.5f
/** 快评轨（三格 × 72·轨 / 内边 / 透镜 = `LiuliSegmented` 纸面态同值）。 */
private val RATING_TRACK_WIDTH = 216.dp
private val RATING_TRACK_SHAPE = RoundedCornerShape(14.dp)
private val RATING_TRACK_INSET = 3.dp
private val RATING_LENS_SHAPE = RoundedCornerShape(11.dp)
private val ACTIONS_MENU_GAP = 8.dp

/** 章末项编排：建议卡 → ⋯ 行 → 快评 → 选择区 → 推进区（次序与 key 同暖陶·D-14 锁定）。 */
internal fun LazyListScope.liuliStoryReaderEndItems(
    chapter: StoryChapterEntity?,
    story: StoryEntity?,
    nav: StoryReaderNav,
    isGenerating: Boolean,
    isDark: Boolean,
    narrativePerson: String,
    userRoleName: String?,
    selectedChoiceText: String?,
    finaleProgress: StoryFinaleProgress?,
    sheets: StoryReaderSheetsState,
    haptics: StoryReaderHaptics,
    callbacks: LiuliReaderCallbacks,
    onKeepWriting: () -> Unit,
) {
    if (storyShowsEndingSuggest(chapter, nav.isLatestChapter, story?.status)) {
        item(key = "endingSuggest") {
            val onGraceful = { haptics.light(); sheets.pendingGraceful.value = true; sheets.dialog.value = ReaderDialog.EndingPicker }
            val onFinish = { haptics.light(); sheets.dialog.value = ReaderDialog.ArchiveConfirm }
            StoryEndingSuggestCard(
                isDark = isDark, onGracefulFinale = onGraceful, onFinish = onFinish, onKeepWriting = onKeepWriting, modifier = READER_ITEM_MODIFIER,
                actions = { LiuliStoryEndingSuggestActions(enabled = !isGenerating, onGracefulFinale = onGraceful, onFinish = onFinish, onKeepWriting = onKeepWriting) },
            )
        }
    }
    // R2：写作中不给改本章小结；章还在加载时也不出（D-11）。重写 / 上一版本就因 !isGenerating 不出。
    val canEditSummary = !isGenerating && chapter != null
    if (StoryReaderEndgameLogic.showChapterActions(nav.canRewrite, nav.canViewPreviousDraft, canEditSummary)) {
        item(key = "chapterActions") {
            LiuliStoryChapterActionsRow(
                isDark,
                expanded = sheets.showChapterActionsMenu,
                onExpandedChange = { sheets.showChapterActionsMenu = it },
                canRewrite = nav.canRewrite,
                canViewPreviousDraft = nav.canViewPreviousDraft,
                canEditSummary = canEditSummary,
                onRewrite = { sheets.dialog.value = ReaderDialog.RewriteConfirm },
                onViewPreviousDraft = { sheets.showPreviousDraft = true },
                onEditChapterSummary = { sheets.dialog.value = ReaderDialog.ChapterSummary },
            )
        }
    }
    // 快评写作中照常可点（只是元数据）。
    if (StoryReaderEndgameLogic.showChapterRating(nav.isLatestChapter, chapterExists = chapter != null)) {
        item(key = "chapterRating") { LiuliStoryRatingRow(chapter?.userRating, isDark, callbacks.onRate) }
    }
    storyChoiceChapter(chapter, story?.status)?.let { ch ->
        item(key = "choice") {
            LiuliStoryChoiceSection(
                ch, isDark, narrativePerson, userRoleName, selectedChoiceText,
                locked = isGenerating,
                onSubmit = callbacks.onSubmitChoice,
                onOpenCustomInput = { sheets.showCustomChoice = true },
                modifier = READER_ITEM_MODIFIER,
            )
        }
    }
    if (storyShowsContinueZone(chapter, nav.isLatestChapter, story?.status)) {
        val zone = storyContinueZoneState(chapter)
        item(key = "continueZone") {
            LiuliStoryContinueZone(
                isDark, sheets.breatheTrigger, finaleProgress, zone,
                draftBeats = story?.pendingChapterBeats,
                draftUserEdited = story?.pendingBeatsUserEdited == true,
                locked = isGenerating,
                onWriteClick = { haptics.light(); sheets.showDirector = true },
                onFlowClick = callbacks.onFlow,
                onFinaleClick = { sheets.dialog.value = ReaderDialog.FinaleMethod },
                onCancelFinaleClick = { sheets.dialog.value = ReaderDialog.FinaleCancelConfirm },
                modifier = READER_ITEM_MODIFIER,
            )
        }
    }
}

/** 选择区：选项卡 / 自由输入换画出来的玻璃 + 紫渐变字母徽章；[locked] = 写作中上锁（R2）。 */
@Composable
internal fun LiuliStoryChoiceSection(
    chapter: StoryChapterEntity,
    isDark: Boolean,
    narrativePerson: String,
    userRoleName: String?,
    selectedChoiceText: String?,
    locked: Boolean,
    onSubmit: (String) -> Unit,
    onOpenCustomInput: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isLocked = chapter.userChoice != null
    val options = rememberStoryChoiceOptions(chapter)
    val popScale = rememberStoryChoicePopScale(selectedChoiceText, isLocked)
    val canPick = !isLocked && !locked
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        StoryChoiceHeader(isDark)
        Text(
            storyChoicePromptText(chapter, narrativePerson, userRoleName),
            color = StoryReaderLayout.textColor(isDark),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Serif,
        )
        options.forEachIndexed { index, option ->
            val selected = selectedChoiceText == option
            // 写作中选项只禁点不变淡（复核 R1 核准 D-12：「本章未答 + 在写」几乎到不了——写下一章要先答题、重写时 VM 已切回上一章）。
            LiuliChoiceCard(
                storyChoiceLetter(index), option, selected, storyChoiceDimmed(selectedChoiceText, option),
                enabled = canPick, isDark = isDark, scale = if (selected) popScale.value else 1f, onClick = { onSubmit(option) },
            )
        }
        LiuliFreeInputCard(enabled = canPick, isDark = isDark, onClick = onOpenCustomInput)
        storyChoiceFeedbackText(chapter, narrativePerson, userRoleName)?.let {
            Text(it, color = StoryReaderLayout.secondaryTextColor(isDark), fontSize = 13.sp)
        }
    }
}

@Composable
private fun LiuliChoiceCard(
    letter: String,
    option: String,
    selected: Boolean,
    dimmed: Boolean,
    enabled: Boolean,
    isDark: Boolean,
    scale: Float,
    onClick: () -> Unit,
) {
    val shape = AppTheme.shapes.medium // = 暖陶卡形
    val accent = AppTheme.colors.accent
    val a11y = stringResource(R.string.story_choice_option_a11y, option)
    Row(
        Modifier
            .fillMaxWidth()
            .scale(scale)
            .alpha(if (dimmed) LOCKED_ALPHA else 1f)
            .semantics { contentDescription = a11y }
            .liuliPaintedLens(shape, isDark)
            .then(if (selected) Modifier.border(1.5.dp, accent.primary.copy(alpha = CHOICE_SELECTED_RIM_ALPHA), shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(LiuliMaterials.accentBrush), contentAlignment = Alignment.Center) {
            Text(letter, color = Palette.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Text(option, color = StoryReaderLayout.textColor(isDark), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LiuliFreeInputCard(enabled: Boolean, isDark: Boolean, onClick: () -> Unit) {
    val shape = AppTheme.shapes.medium
    val accent = AppTheme.colors.accent
    val a11y = stringResource(R.string.story_choice_free_input_a11y)
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else LOCKED_ALPHA)
            .semantics { contentDescription = a11y }
            .liuliPaintedLens(shape, isDark)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(22.dp).drawBehind {
                drawCircle(
                    accent.primary.copy(alpha = CHOICE_FREE_DASH_ALPHA),
                    radius = size.minDimension / 2 - 0.5.dp.toPx(),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
                )
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppActionIcons.Edit, null, tint = accent.text, modifier = Modifier.size(13.dp))
        }
        Text(stringResource(R.string.story_choice_free_input), color = StoryReaderLayout.secondaryTextColor(isDark), modifier = Modifier.weight(1f))
    }
}

/** 快评：带透镜的三段条（同 `LiuliSegmented` 纸面材质手画·支持「无选中」与「再点取消」·不滑动不弹）。 */
@Composable
internal fun LiuliStoryRatingRow(rating: Int?, isDark: Boolean, onRate: (Int?) -> Unit) {
    val haptics = LocalAppHaptics.current
    Column(READER_ITEM_MODIFIER.padding(top = 10.dp, bottom = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) { // = 暖陶 ChapterRatingRow
        Text(stringResource(storyRatingPromptRes(rating)), color = StoryReaderLayout.secondaryTextColor(isDark), fontSize = 12.sp)
        Row(
            Modifier
                .padding(top = 8.dp)
                .width(RATING_TRACK_WIDTH)
                .height(LiuliPageGeometry.stripPaper)
                .clip(RATING_TRACK_SHAPE)
                .background(LiuliMaterials.segTrack(isDark))
                .padding(RATING_TRACK_INSET)
                .selectableGroup(),
        ) {
            storyRatingTiers.forEach { (labelRes, tier) ->
                val selected = rating == tier
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    if (selected) Box(Modifier.matchParentSize().liuliPaintedLens(RATING_LENS_SHAPE, isDark))
                    Text(
                        stringResource(labelRes),
                        fontSize = 13.sp, // = 暖陶 RATING_LABEL_SP
                        fontWeight = if (selected) FontWeight.W600 else FontWeight.W500,
                        color = if (selected) StoryReaderLayout.textColor(isDark) else StoryReaderLayout.secondaryTextColor(isDark),
                    )
                    // 点击面最上层、不裁：liuliTouchHeight 把触达撑到 48（同 LiuliSegmented）。
                    Box(
                        Modifier.matchParentSize().liuliTouchHeight().selectable(
                            selected = selected, role = Role.RadioButton, interactionSource = null, indication = null,
                            onClick = { haptics.light(); onRate(storyRatingNext(rating, tier)) },
                        ),
                    )
                }
            }
        }
    }
}

/** 章末「⋯」行：发丝 + 画出来的玻璃小圆钮；点开是琉璃玻璃菜单（只有文字行）。 */
@Composable
internal fun LiuliStoryChapterActionsRow(
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
    val actionsDesc = stringResource(R.string.story_chapter_actions_desc)
    val rewrite = stringResource(R.string.story_chapter_action_rewrite)
    val previousDraft = stringResource(R.string.story_chapter_action_prev_draft)
    val summary = stringResource(R.string.story_chapter_action_summary)
    Row(READER_ITEM_MODIFIER.padding(top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) { // = 暖陶
        Box(Modifier.weight(1f).height(1.dp).background(StoryReaderLayout.chromeBorderColor(isDark)))
        Spacer(Modifier.width(12.dp))
        Box {
            Box(Modifier.size(ACTIONS_TRIGGER_SIZE), contentAlignment = Alignment.Center) {
                Box(Modifier.matchParentSize().liuliPaintedLens(CircleShape, isDark))
                Icon(Icons.Filled.MoreHoriz, contentDescription = null, tint = StoryReaderLayout.textColor(isDark), modifier = Modifier.size(ACTIONS_TRIGGER_ICON))
                Box(
                    Modifier.liuliFootprint(ACTIONS_TRIGGER_SIZE).clickable(role = Role.Button) { onExpandedChange(true) }
                        .semantics { contentDescription = actionsDesc },
                )
            }
            LiuliPopupMenu(
                expanded = expanded,
                onDismiss = { onExpandedChange(false) },
                items = buildList {
                    if (canRewrite) add(LiuliMenuEntry(rewrite) { onRewrite() })
                    if (canViewPreviousDraft) add(LiuliMenuEntry(previousDraft) { onViewPreviousDraft() })
                    if (canEditSummary) add(LiuliMenuEntry(summary) { onEditChapterSummary() })
                },
                offset = DpOffset(0.dp, ACTIONS_TRIGGER_SIZE + ACTIONS_MENU_GAP),
                width = ACTIONS_MENU_WIDTH,
            )
        }
    }
}
