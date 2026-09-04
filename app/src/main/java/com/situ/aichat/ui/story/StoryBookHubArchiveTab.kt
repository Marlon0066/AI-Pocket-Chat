package com.situ.aichat.ui.story

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.story.StoryArcPlanning
import com.situ.aichat.story.StoryEditableField
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.AppLoadingRing
import com.situ.aichat.ui.designsystem.AppLoadingRingSize
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.appCardSurface

/** 档案卡的预览行数（图纸 §9-② 锁定值）。 */
private const val PREVIEW_MAX_LINES = 3

/**
 * 书页「档案」Tab（故事二期卷二·mockup 屏 1·提案 §8）：这本书叙事状态的**八节一处收齐**，
 * 取代旧设定屏「故事记忆」四行 + 独立圣经编辑屏两处分裂面（审计 A2）。
 *
 * 每节 = 一张卡（标题 + 维护者标签 + 3 行预览），点整卡进统一编辑页；② 下一章节拍是**只读**的
 * （编辑收口在卷三导演台，两处编辑会打架 · 图纸 J2②），点开只给一个只读全文弹窗。
 */
internal fun LazyListScope.storyHubArchiveItems(
    story: StoryEntity,
    onOpenField: (StoryEditableField) -> Unit,
    onOpenBeats: () -> Unit,
    regenerating: Boolean,
    onRegenerateOutline: () -> Unit,
) {
    item(key = "archive_notestrip") { ArchiveNoteStrip() }
    item(key = "archive_outline") { OutlineAndArcCard(story, onOpenField, regenerating, onRegenerateOutline) }
    item(key = "archive_beats") { BeatsCard(story, onOpenBeats) }
    item(key = "archive_intimacy") {
        ArchiveCard(
            story, StoryEditableField.INTIMACY, R.string.story_hub_tag_ai_appended,
            R.string.story_hub_empty_intimacy, { onOpenField(StoryEditableField.INTIMACY) }, isNew = true,
        )
    }
    item(key = "archive_scene_ledger") {
        ArchiveCard(
            story, StoryEditableField.SCENE_LEDGER, R.string.story_hub_tag_ai_appended,
            R.string.story_hub_empty_scene_ledger, { onOpenField(StoryEditableField.SCENE_LEDGER) }, isNew = true,
        )
    }
    item(key = "archive_scene_state") {
        ArchiveCard(
            story, StoryEditableField.SCENE_STATE, R.string.story_hub_tag_ai_maintained,
            R.string.story_hub_empty_scene_state, { onOpenField(StoryEditableField.SCENE_STATE) }, isNew = true,
        )
    }
    item(key = "archive_states") {
        ArchiveCard(
            story, StoryEditableField.CHARACTER_STATES, R.string.story_hub_tag_ai_maintained,
            R.string.story_settings_states_empty, { onOpenField(StoryEditableField.CHARACTER_STATES) },
        )
    }
    item(key = "archive_threads") {
        ArchiveCard(
            story, StoryEditableField.OPEN_THREADS, R.string.story_hub_tag_ai_maintained,
            R.string.story_settings_threads_empty, { onOpenField(StoryEditableField.OPEN_THREADS) },
        )
    }
    item(key = "archive_summary_bible") { SummaryAndBibleCard(story, onOpenField) }
}

/** 顶部一条说明：把「AI 续记 / 你随时可改 / 改过之后在你的版本上继续」一次讲清，各卡不再重复。 */
@Composable
private fun ArchiveNoteStrip() {
    Text(
        stringResource(R.string.story_hub_notestrip),
        style = AppTheme.typography.caption.copy(fontSize = 11.5.sp),
        color = AppTheme.colors.text.secondary,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppTheme.colors.surface.sunken)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** 卡①副行：当前弧起点与自报章数都拿得到时才出（任一缺省整行省略·图纸 §4.2）。 */
@Composable
private fun arcRangeLine(story: StoryEntity): String? {
    val start = story.currentArcStartChapter ?: return null
    val planned = StoryArcPlanning.parseArcPlannedLength(story.storyOutline) ?: return null
    return stringResource(R.string.story_hub_arc_range, start, planned)
}

@Composable
private fun ArchiveCard(
    story: StoryEntity,
    field: StoryEditableField,
    @StringRes tagRes: Int,
    @StringRes emptyRes: Int,
    onClick: () -> Unit,
    isNew: Boolean = false,
    subline: String? = null,
) {
    val value = field.currentValue(story)
    HubCard(onClick = onClick) {
        CardHeader(stringResource(field.titleRes), tagRes, isNew, chevron = true)
        subline?.let { CardSubline(it) }
        CardPreview(value, emptyRes)
    }
}

/** 节拍卡的正文（纯空白视同没有）；null = 还没有预排 → 不给点开、只留空态文案。 */
internal fun storyHubBeatsText(story: StoryEntity): String? = story.pendingChapterBeats?.takeIf { it.isNotBlank() }

/** 节拍卡的标签：用户在导演台改过 → 「已由你修改」，否则「AI 预排」（卷一已建的列，本卷只显示·E12）。 */
@StringRes
internal fun storyHubBeatsTagRes(story: StoryEntity): Int =
    if (story.pendingBeatsUserEdited) R.string.story_hub_tag_user_edited else R.string.story_hub_tag_ai_planned

/**
 * 卡②「下一章节拍」——**只读**：AI 每章预排一份，改它的家在卷三章末导演台（两处编辑会打架）。
 */
@Composable
private fun BeatsCard(story: StoryEntity, onOpen: () -> Unit) {
    val beats = storyHubBeatsText(story)
    HubCard(onClick = onOpen.takeIf { beats != null }) {
        CardHeader(stringResource(R.string.story_hub_sec_beats), storyHubBeatsTagRes(story), isNew = false, chevron = beats != null)
        CardPreview(beats, R.string.story_hub_empty_beats)
    }
}

/**
 * 卡①：弧线大纲与「当前剧情弧线」同住一张卡（R1 复核 D-9 修复·旧设定屏的弧线编辑口回归），
 * 结构照卡⑧的两行范式：上区点开进大纲编辑页，下行进弧线概述编辑页。
 *
 * 第三区 = 「按最新剧情重排」动作行（图纸 2026-08-05 U-1·样图画面①）：大纲改成不绑章号的导演手记后，
 * 用户随时可以让 AI 照最新剧情重写整份大纲（自动修订已在契约里撤销，改由这个按需付费的手动闸承担）。
 */
@Composable
private fun OutlineAndArcCard(
    story: StoryEntity,
    onOpenField: (StoryEditableField) -> Unit,
    regenerating: Boolean,
    onRegenerateOutline: () -> Unit,
) {
    var confirmRegen by remember { mutableStateOf(false) }
    HubCard(onClick = null) {
        Column(Modifier.fillMaxWidth().clickable { onOpenField(StoryEditableField.OUTLINE) }) {
            CardHeader(
                stringResource(StoryEditableField.OUTLINE.titleRes),
                R.string.story_hub_tag_ai_maintained,
                isNew = false,
                chevron = true,
            )
            arcRangeLine(story)?.let { CardSubline(it) }
            CardPreview(StoryEditableField.OUTLINE.currentValue(story), R.string.story_hub_empty_outline)
        }
        SubEntryRow(StoryEditableField.CURRENT_ARC, story, R.string.story_settings_arc_empty, onOpenField)
        OutlineRegenRow(regenerating) { confirmRegen = true }
    }

    if (confirmRegen) {
        AppDialog(
            onDismissRequest = { confirmRegen = false },
            title = stringResource(R.string.story_outline_regen_title),
            body = stringResource(R.string.story_outline_regen_body),
            // 非破坏性动作（重排可重做）→ 确认钮走 Primary 陶土而非 Danger 深琥珀（样图画面①要点）
            confirmText = stringResource(R.string.story_outline_regen_confirm),
            onConfirm = { confirmRegen = false; onRegenerateOutline() },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { confirmRegen = false },
        )
    }
}

/**
 * 大纲卡第三区的动作行。执行中（[regenerating]）换灰字 + 菊花并禁点——重排是一次真 LLM 调用，
 * 数十秒内不给第二次机会（VM 侧另有 `_regenerating` 短路，双保险）。
 */
@Composable
private fun OutlineRegenRow(regenerating: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    // 发丝线与卡内容同宽（`RowDivider` 自带 14dp 横向内缩，HubCard 已内缩 14dp，套用会双重内缩·施工日志 D-9）
    AppListDivider(modifier = Modifier.padding(top = 10.dp), startInset = 0.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !regenerating, onClick = onClick)
            .padding(top = 10.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (regenerating) {
            AppLoadingRing(size = AppLoadingRingSize.Small)
        } else {
            Icon(
                Icons.Outlined.Refresh,
                contentDescription = null,
                tint = c.accent.text,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(if (regenerating) R.string.story_outline_regen_running else R.string.story_outline_regen_row),
            style = AppTheme.typography.body.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            color = if (regenerating) c.text.tertiary else c.accent.text,
        )
    }
}

/** 卡⑧：前情摘要与故事圣经同住一张卡，两行各进各自的编辑页（原独立圣经编辑屏并入此处）。 */
@Composable
private fun SummaryAndBibleCard(story: StoryEntity, onOpenField: (StoryEditableField) -> Unit) {
    HubCard(onClick = null) {
        CardHeader(
            stringResource(R.string.story_hub_sec_summary_bible),
            R.string.story_hub_tag_compressed,
            isNew = false,
            chevron = false,
        )
        SubEntryRow(StoryEditableField.SUMMARY, story, R.string.story_settings_summary_empty, onOpenField)
        SubEntryRow(StoryEditableField.BIBLE, story, R.string.story_settings_bible_empty, onOpenField)
    }
}

@Composable
private fun SubEntryRow(
    field: StoryEditableField,
    story: StoryEntity,
    @StringRes emptyRes: Int,
    onOpenField: (StoryEditableField) -> Unit,
) {
    val c = AppTheme.colors
    Column(
        Modifier.fillMaxWidth().clickable { onOpenField(field) }.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(field.titleRes),
                style = AppTheme.typography.body.copy(fontSize = 13.sp),
                color = c.text.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.text.tertiary)
        }
        CardPreview(field.currentValue(story), emptyRes)
    }
}

@Composable
private fun HubCard(onClick: (() -> Unit)?, content: @Composable () -> Unit) {
    val base = Modifier.fillMaxWidth().appCardSurface()
    Column(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) { content() }
}

@Composable
private fun CardHeader(title: String, @StringRes tagRes: Int, isNew: Boolean, chevron: Boolean) {
    val c = AppTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = AppTheme.typography.body.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            color = c.text.primary,
            modifier = Modifier.weight(1f),
        )
        if (isNew) HubTagChip(stringResource(R.string.story_hub_tag_new), highlighted = true)
        HubTagChip(stringResource(tagRes), highlighted = false)
        if (chevron) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.text.tertiary)
        }
    }
}

@Composable
private fun CardSubline(text: String) {
    Text(
        text,
        style = AppTheme.typography.caption.copy(fontSize = 10.5.sp),
        color = AppTheme.colors.text.tertiary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun CardPreview(value: String?, @StringRes emptyRes: Int) {
    Text(
        value?.takeIf { it.isNotBlank() } ?: stringResource(emptyRes),
        style = AppTheme.typography.secondary.copy(fontSize = 12.sp),
        color = AppTheme.colors.text.secondary,
        maxLines = PREVIEW_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp),
    )
}
