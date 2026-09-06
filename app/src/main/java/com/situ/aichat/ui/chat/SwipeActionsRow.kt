package com.situ.aichat.ui.chat

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.situ.aichat.ui.designsystem.AppTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.LocalAppHaptics
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 一个滑动操作按钮（[SwipeActionsRow] 用）。 */
data class SwipeAction(
    val label: String,
    val icon: ImageVector,
    val containerColor: Color,
    val contentColor: Color,
    val onClick: () -> Unit,
)

private val ACTION_WIDTH = 76.dp

/**
 * 列表行「滑动露出操作按钮」（13.5 chat-ui-11，安卓地道复刻 iOS `swipeActions` 的多按钮露出语义）。
 *
 * Material3 的 `SwipeToDismissBox` 每个方向只能挂一个动作，无法 1:1 还原 iOS「行尾露出归档+删除两个按钮」，
 * 故自实现露出式滑动（沿用本仓 [SwipeToReplyBox] 的 `detectHorizontalDragGestures` 习惯写法）：
 * - **左滑**（内容左移）露出 [trailingActions]（行尾，安全侧——远离系统预测式返回的左缘手势）。
 * - **右滑**（内容右移）露出 [leadingActions]（行首）。
 *
 * 松手按「露出过半」吸附为全开/回弹关闭；点按钮触发其动作并自动关闭；行未展开时点内容=进会话([onRowClick])，
 * 已展开时点内容=先关闭。`detectHorizontalDragGestures` 仅在水平 slop 越过后认领指针，纵向滚动自然胜出。
 *
 * 位移用同步的 `mutableFloatStateOf`（拖动回调里直接写，无协程），仅「吸附/回弹」走单飞 [settleJob] 协程，
 * 新拖动开始先 cancel 它——避免「每帧 launch snapTo 与收尾 animateTo 竞态、行卡半开态」（对抗复核 H1）。
 *
 * 注：iOS swipeActions 的 leading/trailing 与本实现对齐——trailing(行尾)=归档/删除、leading(行首)=置顶。
 * （swipe 触感与吸附阈值留真机批末期微调。）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeActionsRow(
    onRowClick: () -> Unit,
    leadingActions: List<SwipeAction>,
    trailingActions: List<SwipeAction>,
    modifier: Modifier = Modifier,
    onRowLongClick: (() -> Unit)? = null,
    /**
     * 动作面的长相（琉璃卷四 A-12·**加法零回归**：null = 原样走本文件的 [ActionButton]，暖陶调用方不传、
     * 渲染逐字节不变）。琉璃那张脸的动作面是「纸底 + 中央玻璃圆钮」，与暖陶的整块实色面不是一回事，
     * 但**手势 / 吸附 / 触觉 / a11y customActions 机制完全共用**——所以只换这一枚面，不复制机制。
     */
    actionFace: (@Composable (action: SwipeAction, modifier: Modifier, onClick: () -> Unit) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // C3-haptics（契约 §2）：分级语义经 LocalAppHaptics——长按弹面板=medium、滑动吸附跨阈=selection。
    val haptics = LocalAppHaptics.current
    var offsetX by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val actionWidthPx = with(density) { ACTION_WIDTH.toPx() }
    val leadingWidthPx = leadingActions.size * actionWidthPx
    val trailingWidthPx = trailingActions.size * actionWidthPx
    val springSpec = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)

    // 滑动吸附跨阈触觉：预测「此刻松手会吸附到哪」（-1=行尾全开 / 0=关 / 1=行首全开），预测翻转的瞬间打 selection
    // ——拖动中跨过吸附分界线就有「咔哒」，与松手后的吸附动画对齐。
    fun snapBucket(x: Float): Int = when {
        trailingWidthPx > 0f && x <= -trailingWidthPx / 2f -> -1
        leadingWidthPx > 0f && x >= leadingWidthPx / 2f -> 1
        else -> 0
    }
    var snapPreview by remember { mutableStateOf(0) }

    fun settleTo(target: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(offsetX, target, animationSpec = springSpec) { value, _ -> offsetX = value }
        }
    }
    fun close() = settleTo(0f)
    fun act(action: SwipeAction) {
        action.onClick()
        close()
    }

    Box(modifier.fillMaxWidth()) {
        // 背景操作按钮层（被内容遮住，内容滑开后从对应一侧露出）。
        Row(Modifier.matchParentSize()) {
            // 行首（右滑露出）：置顶等。
            leadingActions.forEach { action ->
                val faceModifier = Modifier.width(ACTION_WIDTH).fillMaxHeight()
                if (actionFace != null) {
                    actionFace(action, faceModifier) { act(action) }
                } else {
                    ActionButton(action, faceModifier) { act(action) }
                }
            }
            Box(Modifier.weight(1f))
            // 行尾（左滑露出）：归档 / 删除。
            trailingActions.forEach { action ->
                val faceModifier = Modifier.width(ACTION_WIDTH).fillMaxHeight()
                if (actionFace != null) {
                    actionFace(action, faceModifier) { act(action) }
                } else {
                    ActionButton(action, faceModifier) { act(action) }
                }
            }
        }

        // 内容层：不透明背景遮住按钮，随 offsetX 横移。
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .background(AppTheme.colors.surface.base) // 审计 T2：经桥同值换 token
                // 无障碍（14.7e）：滑动露出的归档/置顶/删除按钮被内容层遮挡、仅手势可达 → TalkBack 用户根本够不着。
                // 把每个 leading/trailing 动作挂成 CustomAccessibilityAction（动作菜单可达），并 mergeDescendants
                // 让整行成一个焦点停（行内名字/时间/预览/未读数拼读一次），既补齐滑动动作、又顺带合并行语义。
                .semantics(mergeDescendants = true) {
                    customActions = (leadingActions + trailingActions).map { action ->
                        CustomAccessibilityAction(action.label) { action.onClick(); true }
                    }
                }
                .combinedClickable(
                    onClick = { if (offsetX != 0f) close() else onRowClick() },
                    // 露出态长按忽略（先让用户感知到行已滑开）；闭合态长按 = 弹快捷回复面板（B5）+ 中等触觉。
                    onLongClick = onRowLongClick?.let {
                        {
                            if (offsetX == 0f) {
                                haptics.medium() // 长按弹快捷回复面板=medium（契约 §2）
                                it()
                            }
                        }
                    },
                )
                .pointerInput(leadingWidthPx, trailingWidthPx) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            settleJob?.cancel()
                            snapPreview = snapBucket(offsetX) // 以当前停驻态为基线，已开行重拖不误触
                        },
                        onDragEnd = {
                            val target = when (snapBucket(offsetX)) {
                                -1 -> -trailingWidthPx
                                1 -> leadingWidthPx
                                else -> 0f
                            }
                            settleTo(target)
                        },
                        onDragCancel = { close() },
                    ) { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount).coerceIn(-trailingWidthPx, leadingWidthPx)
                        val bucket = snapBucket(offsetX)
                        if (bucket != snapPreview) {
                            snapPreview = bucket
                            haptics.selection() // 吸附跨阈「咔哒」（契约 §2）
                        }
                    }
                },
        ) {
            content()
        }
    }
}

@Composable
private fun ActionButton(action: SwipeAction, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .background(action.containerColor)
            .clickable(onClick = onClick)
            .padding(4.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(action.icon, contentDescription = action.label, tint = action.contentColor)
        Text(
            action.label,
            color = action.contentColor,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}
