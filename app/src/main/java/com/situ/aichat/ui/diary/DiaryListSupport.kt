package com.situ.aichat.ui.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import com.situ.aichat.ui.designsystem.AppActionIcons
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.DiaryEntryWithComments
import com.situ.aichat.ui.designsystem.AppTheme
import java.time.YearMonth
import java.time.ZoneId

/** 日记本进出页的两件事（琉璃 2.0 卷六·一 §3.1：自 [DiaryListScreen] 只搬不改，两张脸共用）。 */
@Composable
internal fun DiaryListLifecycleEffects(viewModel: DiaryViewModel) {
    LaunchedEffect(Unit) { viewModel.refreshApiMissing() }
    // diary-1：进/出日记列表都标记已读，清枢纽日记卡未读角标。
    DisposableEffect(Unit) {
        viewModel.markDiaryAsRead()
        onDispose { viewModel.markDiaryAsRead() }
    }
}

/** U4 作者筛选落到条目上（纯函数·T1）。 */
internal fun filterDiaryEntries(entries: List<DiaryEntryWithComments>, filter: DiaryEntryFilter): List<DiaryEntryWithComments> =
    entries.filter { filter.matches(it.entry) }

/** 某月一号零点（给定时区）的毫秒（纯函数·T1）。 */
internal fun diaryMonthStartMillis(month: YearMonth, zone: ZoneId): Long =
    month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

/** 当前未完月的起点（时间线据此判「月过完才出回顾 chip」）。 */
internal fun diaryCurrentMonthStartMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
    diaryMonthStartMillis(YearMonth.now(zone), zone)

@Composable
internal fun ApiMissingBanner() {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(AppTheme.shapes.small)
            .background(colors.status.errorContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(AppActionIcons.Warning, contentDescription = null, tint = colors.status.onError, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.diary_api_missing), style = AppTheme.typography.secondary, color = colors.status.onError)
    }
}

@Composable
internal fun DiaryEmptyState(action: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("📖", style = AppTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics {}) // 装饰压停
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.diary_empty_title), style = AppTheme.typography.titleSmall, color = AppTheme.colors.text.primary)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.diary_empty_desc),
            style = AppTheme.typography.secondary,
            color = AppTheme.colors.text.secondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        action()
    }
}

/** U4 F5：某筛选无内容的空态（尊重语气·不塞按钮）。「全部」空态由外层 [DiaryEmptyState] 接管，此处只 MINE/THEIRS。 */
@Composable
internal fun DiaryFilterEmptyState(filter: DiaryEntryFilter) {
    val msg = if (filter == DiaryEntryFilter.THEIRS) {
        R.string.diary_filter_empty_theirs
    } else {
        R.string.diary_filter_empty_mine
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(msg),
            style = AppTheme.typography.secondary,
            color = AppTheme.colors.text.secondary,
            textAlign = TextAlign.Center,
        )
    }
}
