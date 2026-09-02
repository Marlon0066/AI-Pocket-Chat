package com.situ.aichat.ui.ourdays

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ourdays.OurDayCoordinator.BackfillProgress
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppFeatureIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.appCardSurface

/**
 * 状态族（卷三图纸 §4.8·提案 D-10）：回填横幅 / 补写这个月横幅 / 无 key 横幅（W-11）+ 空日卡 + 角色空态 / 无角色空态。
 * 颜色只经 [AppTheme.colors]；进度条 [AppMotion.calmSpring]·reduceMotion `snap()`。
 */
@Composable
internal fun OurDaysBannerArea(backfill: BackfillProgress?, monthBackfill: Pair<Int, Int>?, apiMissing: Boolean) {
    val showApiMissing = apiMissing && backfill == null
    if (backfill == null && monthBackfill == null && !showApiMissing) return
    Column(
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (backfill != null) {
            OurDaysProgressBanner(
                title = stringResource(R.string.our_days_backfill_banner),
                done = backfill.done, total = backfill.total,
                subtitle = stringResource(R.string.our_days_backfill_sub),
            )
        }
        if (monthBackfill != null) {
            OurDaysProgressBanner(
                title = stringResource(R.string.our_days_month_backfill_banner),
                done = monthBackfill.first, total = monthBackfill.second, subtitle = null,
            )
        }
        if (showApiMissing) OurDaysPlainBanner(stringResource(R.string.our_days_api_missing_banner))
    }
}

/** 同壳横幅（accent.container 底·12dp 圆角·h12 v9）。 */
@Composable
private fun BannerShell(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppTheme.colors.accent.container)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) { content() }
}

@Composable
private fun OurDaysProgressBanner(title: String, done: Int, total: Int, subtitle: String?) {
    val colors = AppTheme.colors
    val reduceMotion = rememberReduceMotion()
    val fraction by animateFloatAsState(
        targetValue = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f,
        animationSpec = if (reduceMotion) snap() else AppMotion.calmSpring(),
        label = "ourDaysBackfill",
    )
    BannerShell {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = AppTypography.caption.copy(fontSize = 12.5.sp), color = colors.accent.onContainer)
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.accent.text.copy(alpha = 0.18f)),
            ) {
                Box(Modifier.fillMaxWidth(fraction = fraction).fillMaxHeight().background(colors.accent.text))
            }
            Text(stringResource(R.string.our_days_backfill_progress, done, total), style = AppTypography.captionNumeric, color = colors.accent.onContainer)
        }
        if (subtitle != null) {
            Text(subtitle, style = AppTypography.caption, color = colors.text.secondary, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun OurDaysPlainBanner(text: String) {
    BannerShell { Text(text, style = AppTypography.caption.copy(fontSize = 12.5.sp), color = AppTheme.colors.accent.onContainer) }
}

/** 角色空态 / 无角色空态（照 `PromiseLedgerEmptyState` 结构·图标 [AppFeatureIcons.Days]）。 */
@Composable
internal fun OurDaysEmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 48.dp, start = 40.dp, end = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(AppFeatureIcons.Days, contentDescription = null, modifier = Modifier.size(44.dp), tint = colors.text.secondary.copy(alpha = 0.45f))
        Spacer(Modifier.height(14.dp))
        Text(title, style = AppTypography.titleSmall, color = colors.text.secondary)
        Spacer(Modifier.height(6.dp))
        Text(body, style = AppTypography.secondary, color = colors.text.secondary.copy(alpha = 0.7f), textAlign = TextAlign.Center)
    }
}

/** 日页空日卡（无行 ∧ 非今天）。 */
@Composable
internal fun OurDaysEmptyDayCard(characterName: String?, scheduleLine: String, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Column(
        modifier = modifier.fillMaxWidth().appCardSurface().padding(horizontal = 22.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.our_days_empty_day_title), style = AppTypography.label, color = colors.text.primary)
        Text(stringResource(R.string.our_days_empty_day_body), style = AppTypography.secondary, color = colors.text.secondary, textAlign = TextAlign.Center)
        if (scheduleLine.isNotBlank() && characterName != null) {
            Text(
                stringResource(R.string.our_days_empty_day_schedule, characterName, scheduleLine),
                style = AppTypography.caption.copy(fontSize = 12.sp),
                color = colors.text.tertiary,
                modifier = Modifier.padding(top = 8.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}
