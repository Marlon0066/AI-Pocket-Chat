package com.situ.aichat.ui.character

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.situ.aichat.R
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.ui.schedule.ScheduleEventRow
import com.situ.aichat.ui.schedule.TimeState

/** 资料页日程卡的一条已选事件（事件 + 其时态）。 */
data class ScheduleRow(val event: ScheduleEventEntity, val timeState: TimeState)

/**
 * 资料页日程卡的 UI 状态（1:1 iOS `ScheduleTimelineCard` 的 5 分支，[Hidden] 由 Screen 决定不渲染）。
 * - [Content]：有正式日程且有已开始事件 → 时间线卡。
 * - [Hidden]：有正式日程但无已开始事件 → 整卡（含间距）不渲染。
 * - [Loading]：无正式日程（生成中 / 排队中 / 首次加载）。
 * - [Failed]：生成失败 → 失败卡 + 重试。
 */
sealed interface ScheduleCardState {
    data object Hidden : ScheduleCardState
    data object Loading : ScheduleCardState
    data object Failed : ScheduleCardState
    data class Content(val rows: List<ScheduleRow>, val weatherLabel: String?) : ScheduleCardState
}

/**
 * 资料页【今日行程】卡（P14.2a）。1:1 iOS `ScheduleTimelineCard`：标题 + 紧凑天气标签（无 key 降级不显）+
 * 仅已开始事件（最多 3 件，[ScheduleCardState.Content] 已由 VM 算好）+「查看全天行程」入口；
 * 加载 / 失败重试多态。卡序在资料页钱包卡与见面回忆卡之间。
 */
@Composable
fun ScheduleTimelineCard(
    state: ScheduleCardState,
    onRetry: () -> Unit,
    onOpenFullDay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is ScheduleCardState.Content -> ContentCard(state, onOpenFullDay, modifier)
        ScheduleCardState.Loading -> LoadingCard(modifier)
        ScheduleCardState.Failed -> FailedCard(onRetry, modifier)
        ScheduleCardState.Hidden -> Unit // Screen 已经不发出本卡；此分支仅防御。
    }
}

@Composable
private fun CardHeader(trailing: @Composable (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            AppActionIcons.Calendar,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.schedule_card_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() }, // P1-18：卡标题进「按标题导航」
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

@Composable
private fun ContentCard(
    state: ScheduleCardState.Content,
    onOpenFullDay: () -> Unit,
    modifier: Modifier,
) {
    ProfileCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardHeader(trailing = {
                val label = state.weatherLabel
                if (label != null) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            })

            Column {
                state.rows.forEachIndexed { index, row ->
                    ScheduleEventRow(
                        event = row.event,
                        timeState = row.timeState,
                        isLast = index == state.rows.lastIndex,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onOpenFullDay) // P1-18：报「按钮」
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.schedule_card_view_full_day),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    AppActionIcons.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun LoadingCard(modifier: Modifier) {
    ProfileCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardHeader()
            // P1-18：加载行压停为静态稳定文案（不带动态点号——对齐 iOS ScheduleTimelineCard.swift:191
            // reduceMotion 静态文本意图，防 TalkBack 每 600ms 内容变更让获焦节点重播）。
            val loadingA11y = stringResource(R.string.schedule_card_loading)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clearAndSetSemantics { contentDescription = loadingA11y },
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
                Spacer(Modifier.width(8.dp))
                // 动态省略号「日程正在整理中.」→「..」→「...」循环（1:1 iOS 0.6s 等长节拍）：
                // phase 0→3 线性，floor 后每 600ms 整步切 1→2→3。
                // P1-23：RM 静止=固定三点（=iOS ScheduleTimelineCard.swift:191 静态分支
                // `Text("日程正在整理中...")` 逐字）——1:1 门控（iOS 真读了 RM）。
                val dotCount = if (rememberReduceMotion()) {
                    3
                } else {
                    val transition = rememberInfiniteTransition(label = "scheduleLoadingDots")
                    val phase by transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 3f,
                        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
                        label = "scheduleLoadingDotPhase",
                    )
                    phase.toInt().coerceIn(0, 2) + 1
                }
                Text(
                    stringResource(R.string.schedule_card_loading) + ".".repeat(dotCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FailedCard(onRetry: () -> Unit, modifier: Modifier) {
    ProfileCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardHeader()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.schedule_card_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                AppButton(onClick = onRetry, style = AppButtonStyle.Text) {
                    Text(stringResource(R.string.schedule_card_retry), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
