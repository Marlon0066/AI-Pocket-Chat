package com.situ.aichat.ui.ourdays

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.appCardSurface

/**
 * 选中日卡（卷三图纸 §4.4-B·提案 D-4）：月视图选中卡 / 周视图共用正文与 chips；全部模式按角色分段 +「你的日记」行。
 * 文案经资源；chip 文字由 [chipText] 按种类映射（纯核只给数）。
 */
@Composable
internal fun OurDaysDayCard(
    card: DayCardModel,
    characterUuid: String?,
    allMode: Boolean,
    characterName: String?,
    onOpenDay: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val weekday = OurDaysFormat.date(card.date, stringResource(R.string.our_days_fmt_weekday))
    val openLabel = stringResource(R.string.our_days_open_day)
    Column(modifier.fillMaxWidth().appCardSurface().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${card.date.dayOfMonth}", style = AppTypography.titleMedium.copy(fontFeatureSettings = "tnum"), color = colors.text.primary)
            Text(
                (if (card.isToday) stringResource(R.string.our_days_today) + " · " else "") + weekday + (card.decor?.subtitle?.let { " · $it" } ?: ""),
                style = AppTypography.secondary.copy(fontSize = 12.5.sp),
                color = colors.text.secondary,
            )
            Spacer(Modifier.weight(1f))
            if (!allMode && !card.isFuture && characterUuid != null) {
                Text(
                    openLabel,
                    style = AppTypography.caption.copy(fontSize = 12.sp),
                    color = colors.accent.text,
                    modifier = Modifier.clickable(onClickLabel = openLabel) { onOpenDay(characterUuid, card.key) }.padding(4.dp),
                )
            }
        }
        if (allMode) {
            OurDaysAllSegments(card, onOpenDay)
        } else {
            Spacer(Modifier.height(8.dp))
            OurDayCardBody(card, characterName, AppTypography.kaiQuote.copy(fontSize = 14.sp, lineHeight = 25.sp))
            if (card.chips.isNotEmpty()) OurDayCardChips(card.chips, Modifier.padding(top = 10.dp))
        }
    }
}

/** 正文按状态（§4.4-B）：NORMAL / HIDDEN 手记两行楷体；TODAY / EMPTY / FAILED / DELETED 各一句。 */
@Composable
internal fun OurDayCardBody(card: DayCardModel, characterName: String?, noteStyle: TextStyle) {
    val colors = AppTheme.colors
    when (card.status) {
        CardStatus.NORMAL, CardStatus.HIDDEN_NORMAL ->
            Text(card.note, style = noteStyle, color = colors.text.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        CardStatus.TODAY -> Text(stringResource(R.string.our_days_note_today), style = AppTypography.secondary, color = colors.text.tertiary)
        CardStatus.EMPTY -> Text(
            if (card.scheduleLine.isNotBlank() && characterName != null) {
                stringResource(R.string.our_days_no_record_schedule, characterName, card.scheduleLine)
            } else {
                stringResource(R.string.our_days_no_record)
            },
            style = AppTypography.secondary, color = colors.text.tertiary,
        )
        CardStatus.FAILED -> Text(stringResource(R.string.our_days_note_failed), style = AppTypography.secondary, color = colors.text.tertiary)
        CardStatus.DELETED -> Text(stringResource(R.string.our_days_note_deleted), style = AppTypography.secondary, color = colors.text.tertiary)
    }
}

/** 全部模式：每段 = 识别圈头像 + 名 + chips 摘要一行 + 手记两行（整段可点进 TA 的日页）；末尾「你的日记」行。 */
@Composable
internal fun OurDaysAllSegments(card: DayCardModel, onOpenDay: (String, String) -> Unit) {
    val colors = AppTheme.colors
    val segmentStyle = AppTypography.secondary.copy(fontSize = 12.5.sp)
    if (card.status == CardStatus.TODAY && card.segments.isEmpty()) {
        Text(stringResource(R.string.our_days_note_today), style = AppTypography.secondary, color = colors.text.tertiary, modifier = Modifier.padding(top = 8.dp))
    }
    if (card.status == CardStatus.EMPTY) {
        Text(stringResource(R.string.our_days_no_record), style = AppTypography.secondary, color = colors.text.tertiary, modifier = Modifier.padding(top = 8.dp))
    }
    card.segments.forEach { seg ->
        val chipsLine = seg.card.chips.map { chipText(it) }.joinToString(" · ")
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .clickable { onOpenDay(seg.characterUuid, card.key) },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.border(1.5.dp, colors.ourDays.identity[seg.identityIndex], CircleShape).padding(1.5.dp)) {
                    CharacterAvatar(name = seg.name, avatarPath = seg.avatarPath, size = 24.dp)
                }
                Text(seg.name, style = segmentStyle, color = colors.text.secondary)
                if (chipsLine.isNotEmpty()) {
                    Text(" · $chipsLine", style = segmentStyle, color = colors.text.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(4.dp))
            OurDayCardBody(seg.card, seg.name, AppTypography.kaiQuote.copy(fontSize = 14.sp, lineHeight = 25.sp))
        }
    }
    card.userDiary?.let { diary ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(colors.surface.sunken), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.our_days_me_glyph), style = AppTypography.caption, color = colors.text.secondary)
            }
            Text(stringResource(R.string.our_days_your_diary), style = segmentStyle, color = colors.text.secondary)
            Text(
                " · " + (diary.moodEmoji ?: "") + " 「" + diary.firstLine + "」",
                style = segmentStyle, color = colors.text.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** chips 行（§4.4-B）：sunken 药丸 + 6dp 家族色点 + 11.5sp 文字。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OurDayCardChips(chips: List<Chip>, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { chip ->
            Row(
                modifier = Modifier.clip(AppShapes.full).background(colors.surface.sunken).padding(horizontal = 9.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(chipFamilyColor(chip.kind.family)))
                Text(chipText(chip), style = AppTypography.caption.copy(fontSize = 11.5.sp), color = colors.text.secondary)
            }
        }
    }
}

@Composable
internal fun chipFamilyColor(family: ChipFamily): Color = when (family) {
    ChipFamily.CHAT -> AppTheme.colors.accent.primary
    ChipFamily.MEETING -> AppTheme.colors.ourDays.dotMeeting
    ChipFamily.RELATION -> AppTheme.colors.ourDays.dotRelation
    ChipFamily.LIFE -> AppTheme.colors.ourDays.dotLife
}

@Composable
internal fun chipText(chip: Chip): String = when (chip.kind) {
    ChipKind.CHAT -> stringResource(R.string.our_days_chip_chat, chip.count)
    ChipKind.CALL -> stringResource(R.string.our_days_chip_call, chip.count)
    ChipKind.MEETING -> stringResource(R.string.our_days_chip_meeting)
    ChipKind.PROMISE -> stringResource(R.string.our_days_chip_promise, chip.text)
    ChipKind.PROMISE_FULFILLED -> stringResource(R.string.our_days_chip_promise_fulfilled)
    ChipKind.PROMISE_CANCELLED -> stringResource(R.string.our_days_chip_promise_cancelled)
    ChipKind.MILESTONE -> stringResource(R.string.our_days_chip_milestone)
    ChipKind.GIFT -> stringResource(R.string.our_days_chip_gift)
    ChipKind.RED_PACKET -> stringResource(R.string.our_days_chip_redpacket)
    ChipKind.MOMENTS -> stringResource(R.string.our_days_chip_moments)
    ChipKind.DIARY -> stringResource(R.string.our_days_chip_diary)
}

/** 热度底色（§4.3）：0 ⇒ 透明；1/2/3 ⇒ heat1/2/3。 */
@Composable
internal fun heatColor(level: Int): Color = when (level) {
    1 -> AppTheme.colors.ourDays.heat1
    2 -> AppTheme.colors.ourDays.heat2
    3 -> AppTheme.colors.ourDays.heat3
    else -> Color.Transparent
}

@Composable
internal fun dotColor(family: DotFamily): Color = when (family) {
    DotFamily.MEETING -> AppTheme.colors.ourDays.dotMeeting
    DotFamily.RELATION -> AppTheme.colors.ourDays.dotRelation
    DotFamily.LIFE -> AppTheme.colors.ourDays.dotLife
}
