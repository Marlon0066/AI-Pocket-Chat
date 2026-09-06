package com.situ.aichat.ui.liuli.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.chat.ChatRenderItem
import com.situ.aichat.ui.chat.FloatingDateLabel
import com.situ.aichat.ui.chat.FloatingDateVisibility
import com.situ.aichat.ui.chat.floatingDateLabel
import com.situ.aichat.ui.chat.floatingDateSuppressed
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 琉璃浮动日期胶囊（图纸 2026-09-05 卷二A §4.6）：**状态机与触发条件直接复用**暖陶
 * [FloatingDateVisibility] / [floatingDateLabel] / [floatingDateSuppressed]（契约 TELEGRAM_MOTION §2 的
 * 节奏一字不动：只认用户拖动 / 停滚 500ms 驻留 / 淡入淡出 150ms / 最早项可见或顶部横幅在场即熄），
 * 只把皮换成玻璃 pill。纯显示：不消费指针、对读屏隐形。
 */
@Composable
internal fun BoxScope.LiuliDatePill(
    listState: LazyListState,
    listItems: List<ChatRenderItem>,
    topBannerVisible: Boolean,
    /** 胶囊上缘距列表区顶（= 世界胶囊底 + 8dp，无胶囊则顶栏底 + 8dp·[LiuliChatGeometry.datePillOffset]）。 */
    topOffset: Dp,
) {
    val scope = rememberCoroutineScope()
    val machine = remember(scope) { FloatingDateVisibility(scope) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> machine.onDragStart()
                is DragInteraction.Stop, is DragInteraction.Cancel -> machine.onDragEnd()
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { machine.onScrollingChanged(it) }
    }
    val suppressed by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val topIndex = layout.visibleItemsInfo.lastOrNull()?.index ?: (layout.totalItemsCount - 1)
            floatingDateSuppressed(topIndex, layout.totalItemsCount)
        }
    }
    LaunchedEffect(suppressed, topBannerVisible) { machine.onSuppressedChanged(suppressed || topBannerVisible) }

    val alpha by animateFloatAsState(
        targetValue = if (machine.visible) 1f else 0f,
        animationSpec = tween(FADE_MS, easing = AppMotion.EaseInOut),
        label = "liuliDatePillAlpha",
    )
    if (alpha <= 0.01f) return

    val zone = remember { ZoneId.systemDefault() }
    val anchorDate by remember(listItems, listState) {
        derivedStateOf {
            val topIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            val ts = when (val item = topIndex?.let(listItems::getOrNull)) {
                is ChatRenderItem.Message -> item.entity.timestamp
                null -> null
            }
            ts?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        }
    }
    val date = anchorDate ?: return
    val text = when (remember(date) { floatingDateLabel(date, LocalDate.now(zone)) }) {
        FloatingDateLabel.TODAY -> stringResource(R.string.schedule_day_today)
        FloatingDateLabel.YESTERDAY -> stringResource(R.string.schedule_day_yesterday)
        FloatingDateLabel.SAME_YEAR -> remember(date) { date.format(MonthDayCn) }
        FloatingDateLabel.OTHER_YEAR -> remember(date) { date.format(YearMonthDayCn) }
    }
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .padding(top = topOffset)
            .graphicsLayer { this.alpha = alpha }
            .clearAndSetSemantics {},
    ) {
        Box(
            Modifier
                .height(LiuliChatGeometry.worldPillHeight)
                .liuliGlass(LiuliShapes.pill, dark = LocalIsDarkTheme.current)
                .padding(horizontal = PillHorizontal),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = AppTypography.caption, color = LiuliTheme.onGlass.primary)
        }
    }
}

/** 淡入 / 淡出时长（Telegram 150ms·契约 TELEGRAM_MOTION §2 不动）。 */
private const val FADE_MS = 150

/** 胶囊横内边距（图纸 §4.6 锁 11dp）。 */
private val PillHorizontal = 11.dp

/** 中文日期格式（与暖陶胶囊同源）。 */
private val MonthDayCn = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
private val YearMonthDayCn = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)
