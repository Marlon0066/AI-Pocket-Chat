package com.situ.aichat.ui.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.MonthlyReviewEntity
import com.situ.aichat.prompt.diary.MonthlyReviewService
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppTheme

/**
 * 回顾与统计面板（方向 C 时光档案）：连续记录（主色高光格）+ 已发布篇数 / 总字数 + 近 30 天心情色块热力格
 * [DiaryMoodHeatmap30] + 分段式情绪气候光谱条 [MoodDistribution]。纯展示——计算在 [DiaryInsights]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiaryStatsSheet(stats: DiaryInsights.Stats, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    AppSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                stringResource(R.string.diary_menu_insights),
                style = AppTheme.typography.titleSmall,
                color = colors.text.primary,
                modifier = Modifier.semantics { heading() },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCell(
                    value = stats.streakDays.toString(),
                    label = stringResource(R.string.diary_stats_streak),
                    highlighted = stats.streakDays > 0,
                    modifier = Modifier.weight(1f),
                )
                StatCell(
                    value = stats.publishedCount.toString(),
                    label = stringResource(R.string.diary_stats_count),
                    modifier = Modifier.weight(1f),
                )
                StatCell(
                    value = stats.totalChars.toString(),
                    label = stringResource(R.string.diary_stats_chars),
                    modifier = Modifier.weight(1f),
                )
            }
            if (stats.recentDayMoods.any { it != null }) {
                DiaryMoodHeatmap30(stats.recentDayMoods)
            }
            if (stats.moodCounts.isNotEmpty()) {
                MoodDistribution(stats.moodCounts)
            } else if (stats.publishedCount == 0) {
                Text(
                    stringResource(R.string.diary_stats_none),
                    style = AppTheme.typography.secondary,
                    color = colors.text.secondary,
                )
            }
            Box(Modifier.height(16.dp))
        }
    }
}

/** 心情分布（分段式情绪气候光谱条 + emoji 计数冗余·统计面板与月度回顾面板共用）。 */
@Composable
internal fun MoodDistribution(moodCounts: List<Pair<String, Int>>) {
    val colors = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.diary_stats_moods),
            style = AppTheme.typography.caption,
            color = colors.text.secondary,
        )
        val toneCounts = DiaryMoodTone.entries.mapNotNull { tone ->
            val count = moodCounts.filter { diaryMoodTone(it.first) == tone }.sumOf { it.second }
            if (count > 0) tone to count else null
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(9.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            toneCounts.forEach { (tone, count) ->
                Box(
                    Modifier
                        .weight(count.toFloat())
                        .fillMaxSize()
                        .clip(AppTheme.shapes.full)
                        .background(colors.emotion.toneColor(tone)),
                )
            }
        }
        Text(
            moodCounts.take(5).joinToString(" · ") { "${it.first} ${it.second}" },
            style = AppTheme.typography.captionNumeric,
            color = colors.text.secondary,
        )
    }
}

/**
 * 月度回顾面板（R5）：「yyyy年M月 · 月度回顾」标题 + 楷体信文 + 随文心情分布快照。
 * 内容可滚（长信不顶破面板）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiaryReviewSheet(review: MonthlyReviewEntity, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    AppSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                diaryReviewTitle(review),
                style = AppTheme.typography.titleSmall,
                color = colors.text.primary,
                modifier = Modifier.semantics { heading() },
            )
            DiaryLetterTopRule()
            Text(review.content, style = AppTheme.typography.kaiBody, color = colors.text.primary)
            val moods = MonthlyReviewService.decodeMoodCounts(review.moodCountsJson)
            if (moods.isNotEmpty()) MoodDistribution(moods)
            Box(Modifier.height(16.dp))
        }
    }
}

/** 月度回顾标题「yyyy年M月 · 月度回顾」（两张脸共用）。 */
@Composable
internal fun diaryReviewTitle(review: MonthlyReviewEntity): String =
    stringResource(R.string.diary_review_title, formatDiaryDate(review.monthStartMillis, stringResource(R.string.diary_fmt_month_title)))

@Composable
private fun StatCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    val colors = AppTheme.colors
    Column(
        modifier = modifier
            .clip(AppTheme.shapes.medium)
            .background(if (highlighted) colors.accent.container else colors.surface.sunken)
            .padding(vertical = 15.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            value,
            style = AppTheme.typography.titleMedium.copy(
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
            ),
            color = if (highlighted) colors.accent.onContainer else colors.text.primary,
        )
        Text(
            label,
            style = AppTheme.typography.caption,
            color = if (highlighted) colors.accent.onContainer.copy(alpha = 0.85f) else colors.text.secondary,
        )
    }
}
