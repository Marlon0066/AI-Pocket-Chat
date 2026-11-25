package com.situ.aichat.ui.liuli.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.MonthlyReviewEntity
import com.situ.aichat.prompt.diary.DiaryGuideAnswers
import com.situ.aichat.prompt.diary.MonthlyReviewService
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.diary.DiaryInsights
import com.situ.aichat.ui.diary.DiaryLetterTopRule
import com.situ.aichat.ui.diary.DiaryMoodHeatmap30
import com.situ.aichat.ui.diary.MoodDistribution
import com.situ.aichat.ui.diary.diaryReviewTitle
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliField
import com.situ.aichat.ui.liuli.designsystem.LiuliSheetShell
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import com.situ.aichat.ui.liuli.page.LiuliStatCard

// 琉璃日记的三枚弹层（琉璃 2.0 卷六·一 §4.2）：统计 / 月度回顾 / 三问引导，壳 = [LiuliSheetShell]，内容与暖陶同字同序。

/** 回顾与统计：三格统计卡 + 近 30 天心情色块热力格 + 心情分布（没发布过则一句提示）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiuliDiaryStatsSheet(stats: DiaryInsights.Stats, onDismiss: () -> Unit) {
    LiuliSheetShell(onDismissRequest = onDismiss, title = stringResource(R.string.diary_menu_insights)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            LiuliStatCard(
                listOf(
                    stringResource(R.string.diary_stats_streak) to stats.streakDays.toString(),
                    stringResource(R.string.diary_stats_count) to stats.publishedCount.toString(),
                    stringResource(R.string.diary_stats_chars) to stats.totalChars.toString(),
                ),
            )
            if (stats.recentDayMoods.any { it != null }) {
                DiaryMoodHeatmap30(stats.recentDayMoods)
            }
            if (stats.moodCounts.isNotEmpty()) {
                MoodDistribution(stats.moodCounts)
            } else if (stats.publishedCount == 0) {
                Text(stringResource(R.string.diary_stats_none), style = AppTypography.secondary, color = LiuliTheme.onGlass.secondary)
            }
            Box(Modifier.height(16.dp))
        }
    }
}

/** 月度回顾：双线信笺抬头 + 楷体信文 + 随文心情分布快照（长信可滚）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiuliDiaryReviewSheet(review: MonthlyReviewEntity, onDismiss: () -> Unit) {
    LiuliSheetShell(onDismissRequest = onDismiss, title = diaryReviewTitle(review)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DiaryLetterTopRule()
            Text(review.content, style = AppTypography.kaiBody, color = LiuliTheme.onGlass.primary)
            MonthlyReviewService.decodeMoodCounts(review.moodCountsJson).takeIf { it.isNotEmpty() }?.let { MoodDistribution(it) }
            Box(Modifier.height(16.dp))
        }
    }
}

/** 三问引导（「让 TA 帮你起个头」）：事 / 感觉 / 未说出口，都可留空；「帮我写一段」主色钮。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiuliThreeQuestionGuideSheet(onDismiss: () -> Unit, onGenerate: (DiaryGuideAnswers) -> Unit) {
    var event by rememberSaveable { mutableStateOf("") }
    var feeling by rememberSaveable { mutableStateOf("") }
    var unsaid by rememberSaveable { mutableStateOf("") }
    val onGlass = LiuliTheme.onGlass
    LiuliSheetShell(onDismissRequest = onDismiss, title = stringResource(R.string.diary_guide_title)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.diary_guide_subtitle), style = AppTypography.kaiQuote, color = onGlass.secondary)
            LiuliGuideField(stringResource(R.string.diary_guide_q_event), event, stringResource(R.string.diary_guide_hint_event)) { event = it }
            LiuliGuideField(stringResource(R.string.diary_guide_q_feeling), feeling, stringResource(R.string.diary_guide_hint_optional)) { feeling = it }
            LiuliGuideField(stringResource(R.string.diary_guide_q_unsaid), unsaid, stringResource(R.string.diary_guide_hint_optional)) { unsaid = it }
            LiuliButton(
                onClick = { onGenerate(DiaryGuideAnswers(event, feeling, unsaid)) },
                style = LiuliButtonStyle.Prominent,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.diary_guide_generate)) }
            Text(
                stringResource(R.string.diary_guide_footer),
                style = AppTypography.caption,
                color = onGlass.secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 一个引导问句（楷体·主色字）+ 琉璃多行输入框。 */
@Composable
private fun LiuliGuideField(question: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(question, style = AppTypography.kaiQuote, color = AppTheme.colors.accent.text)
        LiuliField(value = value, onValueChange = onChange, placeholder = placeholder, singleLine = false, modifier = Modifier.fillMaxWidth())
    }
}
