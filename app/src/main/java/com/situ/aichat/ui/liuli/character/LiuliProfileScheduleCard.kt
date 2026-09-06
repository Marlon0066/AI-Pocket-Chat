package com.situ.aichat.ui.liuli.character

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.character.ScheduleCardState
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.liuliTouchHeight
import com.situ.aichat.ui.schedule.ScheduleEventRow

/** 卡内图标 / 转圈行图标尺寸（1:1 暖陶）。 */
private val STATE_ICON = 16.dp
private val CHEVRON = 18.dp
/** 「日程正在整理中…」的点号节拍（1:1 暖陶 1800ms 三步）。 */
private const val DOT_CYCLE_MS = 1800
private const val LOADING_ICON_ALPHA = 0.6f

/**
 * 今日行程卡三态（琉璃·搬暖陶 `ScheduleTimelineCard`）。三态、文案、节拍、a11y 逐字继承；
 * 只换外壳（[LiuliGroup]）与字号色号（图纸 §4.4 映射表）。
 *
 * 时间轴行本身**借用**暖陶 [ScheduleEventRow]（图纸 §2.1 没给它文件与预算，port 它等于把 `ui/schedule`
 * 整支拉进本卷；它读 `MaterialTheme.colorScheme`，琉璃肤下自动拿到 `LiuliLightColors` / `LiuliDarkColors`
 * ——不会串成暖陶色·§11 D-14）。
 */
@Composable
internal fun LiuliProfileScheduleCard(
    state: ScheduleCardState,
    onRetry: () -> Unit,
    onOpenFullDay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is ScheduleCardState.Content -> ContentCard(state, onOpenFullDay, modifier)
        ScheduleCardState.Loading -> StateCard(modifier) { LoadingRow() }
        ScheduleCardState.Failed -> StateCard(modifier) { FailedRow(onRetry) }
        ScheduleCardState.Hidden -> Unit // Screen 已经不发出本卡；此分支仅防御。
    }
}

/** 卡壳：组标题 = 「今日行程」（可带行尾天气），组体一整块。 */
@Composable
private fun StateCard(
    modifier: Modifier = Modifier,
    weatherLabel: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val colors = AppTheme.colors
    LiuliGroup(modifier = modifier, header = stringResource(R.string.schedule_card_title)) {
        LiuliRowBase(
            divider = false,
            minHeight = 0.dp,
            verticalPadding = LiuliPageGeometry.groupPadH,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (weatherLabel != null) {
                    Text(
                        weatherLabel,
                        style = AppTypography.secondary,
                        color = colors.text.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                content()
            }
        }
    }
}

@Composable
private fun ContentCard(state: ScheduleCardState.Content, onOpenFullDay: () -> Unit, modifier: Modifier) {
    val colors = AppTheme.colors
    StateCard(modifier = modifier, weatherLabel = state.weatherLabel) {
        Column {
            state.rows.forEachIndexed { index, row ->
                ScheduleEventRow(event = row.event, timeState = row.timeState, isLast = index == state.rows.lastIndex)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .liuliTouchHeight()
                .clickable(role = Role.Button, onClick = onOpenFullDay),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.schedule_card_view_full_day),
                style = AppTypography.listPreview,
                color = colors.accent.text,
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(CHEVRON),
                tint = colors.accent.text,
            )
        }
    }
}

/** 加载行：动态省略号「.」→「..」→「...」（RM 时固定三点·1:1 暖陶的 1:1 iOS 门控）。 */
@Composable
private fun LoadingRow() {
    val colors = AppTheme.colors
    val loadingA11y = stringResource(R.string.schedule_card_loading)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clearAndSetSemantics { contentDescription = loadingA11y },
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(STATE_ICON),
            tint = colors.text.secondary.copy(alpha = LOADING_ICON_ALPHA),
        )
        Spacer(Modifier.width(8.dp))
        val dotCount = if (rememberReduceMotion()) {
            3
        } else {
            val transition = rememberInfiniteTransition(label = "liuliScheduleLoadingDots")
            val phase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 3f,
                animationSpec = infiniteRepeatable(tween(DOT_CYCLE_MS, easing = LinearEasing), RepeatMode.Restart),
                label = "liuliScheduleLoadingDotPhase",
            )
            phase.toInt().coerceIn(0, 2) + 1
        }
        Text(
            stringResource(R.string.schedule_card_loading) + ".".repeat(dotCount),
            style = AppTypography.listPreview,
            color = colors.text.secondary,
        )
    }
}

/** 失败行：错误图标 + 文案 + 「重试」文字钮。 */
@Composable
private fun FailedRow(onRetry: () -> Unit) {
    val colors = AppTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(STATE_ICON),
            tint = colors.accent.text,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.schedule_card_failed),
            style = AppTypography.listPreview,
            color = colors.text.secondary,
        )
        Spacer(Modifier.weight(1f))
        LiuliButton(onClick = onRetry, style = LiuliButtonStyle.Text) {
            Text(stringResource(R.string.schedule_card_retry), fontWeight = FontWeight.W600)
        }
    }
}
