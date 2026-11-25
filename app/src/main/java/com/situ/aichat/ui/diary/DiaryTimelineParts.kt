package com.situ.aichat.ui.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.DiaryEntryWithComments
import com.situ.aichat.data.local.entity.MonthlyReviewEntity
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.appCardSurface
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** 时间线月分组（R1 拆自 DiaryListScreen·只搬不改 + R5 加 monthStartMillis 供回顾 chip）。 */
internal data class DiaryMonthSection(
    val key: String,
    val title: String,
    val year: String,
    val monthStartMillis: Long,
    val entries: List<DiaryEntryWithComments>,
)

@Composable
internal fun rememberDiaryMonthSections(items: List<DiaryEntryWithComments>): List<DiaryMonthSection> {
    val monthPattern = stringResource(R.string.diary_fmt_month_section)
    val yearPattern = stringResource(R.string.diary_fmt_month_section_year)
    return remember(items, monthPattern, yearPattern) {
        val zone = ZoneId.systemDefault()
        val map = linkedMapOf<YearMonth, MutableList<DiaryEntryWithComments>>()
        for (e in items) {
            val ym = YearMonth.from(Instant.ofEpochMilli(e.entry.timestamp).atZone(zone))
            map.getOrPut(ym) { mutableListOf() }.add(e)
        }
        map.map { (ym, list) ->
            val firstMillis = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            DiaryMonthSection(
                key = ym.toString(),
                title = formatDiaryDate(firstMillis, monthPattern, zone),
                year = formatDiaryDate(firstMillis, yearPattern, zone),
                monthStartMillis = firstMillis,
                entries = list,
            )
        }
    }
}

/**
 * 月分节头（R1 大字 + 票据虚线）+ R5 月度回顾 chip 四态：已有 → 「月度回顾」开面板；生成中；失败重试；
 * 已完月无回顾 → 「生成回顾」。当前未完月不出 chip（月未过完不小结）。
 */
@Composable
internal fun MonthHeader(
    section: DiaryMonthSection,
    review: MonthlyReviewEntity?,
    isPastMonth: Boolean,
    isGenerating: Boolean,
    isFailed: Boolean,
    onOpenReview: (MonthlyReviewEntity) -> Unit,
    onGenerateReview: (Long) -> Unit,
) {
    val colors = AppTheme.colors
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                section.title,
                style = AppTheme.typography.titleMedium,
                color = colors.text.primary,
                modifier = Modifier.alignByBaseline().semantics { heading() },
            )
            Spacer(Modifier.size(8.dp))
            Text(
                section.year,
                style = AppTheme.typography.caption,
                color = colors.text.secondary,
                modifier = Modifier.alignByBaseline(),
            )
            Spacer(Modifier.weight(1f))
            when {
                review != null -> ReviewChip(
                    label = stringResource(R.string.diary_review_chip),
                    labelColor = colors.accent.onContainer,
                    background = colors.accent.container,
                ) { onOpenReview(review) }
                isGenerating -> Text(
                    stringResource(R.string.diary_review_generating),
                    style = AppTheme.typography.caption,
                    color = colors.text.secondary,
                )
                isFailed -> ReviewChip(
                    label = stringResource(R.string.diary_review_retry),
                    labelColor = colors.status.onError,
                    background = colors.status.errorContainer,
                ) { onGenerateReview(section.monthStartMillis) }
                isPastMonth -> ReviewChip(
                    label = stringResource(R.string.diary_review_generate),
                    labelColor = colors.text.secondary,
                    background = colors.surface.sunken,
                ) { onGenerateReview(section.monthStartMillis) }
            }
        }
        Spacer(Modifier.height(8.dp))
        DiaryDottedDivider()
    }
}

@Composable
private fun ReviewChip(
    label: String,
    labelColor: androidx.compose.ui.graphics.Color,
    background: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = AppTheme.typography.caption,
        color = labelColor,
        modifier = Modifier
            .clip(AppTheme.shapes.full)
            .background(background)
            .clickable(onClickLabel = label) { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** 那年今天时光书签卡（方向 C·纯本地）：左侧主色书签竖带 + 年份 + 摘录两行，点击进详情。 */
@Composable
internal fun OnThisDayCard(
    hit: DiaryEntryWithComments,
    onOpenEntry: (String) -> Unit,
    surface: Modifier = Modifier.appCardSurface(cornerRadius = 16.dp),
) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenGutter, vertical = 5.dp)
            .then(surface)
            .clickable(onClickLabel = stringResource(R.string.a11y_diary_open)) { onOpenEntry(hit.entry.uuid) }
            .height(IntrinsicSize.Min)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.5.dp)
                .fillMaxHeight()
                .clip(AppTheme.shapes.full)
                .background(colors.accent.primary.copy(alpha = 0.78f)),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.diary_on_this_day),
                    style = AppTheme.typography.caption,
                    color = colors.accent.text,
                )
                Text(
                    formatDiaryDate(hit.entry.timestamp, stringResource(R.string.diary_fmt_month_section_year)),
                    style = AppTheme.typography.captionNumeric,
                    color = colors.text.secondary,
                )
            }
            Text(
                hit.entry.content,
                style = AppTheme.typography.listPreview,
                color = colors.text.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
