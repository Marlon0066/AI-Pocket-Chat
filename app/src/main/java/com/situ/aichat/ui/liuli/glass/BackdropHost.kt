package com.situ.aichat.ui.liuli.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

/**
 * 琉璃玻璃基建 · 背景宿主（契约 FABLE5_THEME_LIULI_PROPOSAL.md §8 · L0 试验）。
 *
 * 把 [BackdropHost] 的 `content` 每帧录进一个 [GraphicsLayer] 并照常画出；同宿主里的玻璃片（`overlay` 里用
 * [liuliGlass]）经 [LocalBackdrop] 拿到这个 layer 与宿主在根坐标系的原点，在自己的位置上切一片模糊回来——
 * 这就是「实时模糊身后内容」，全程官方 API（铁律 #1 零第三方）。
 *
 * **玻璃片必须放在 `overlay`（content 的兄弟）里**：放进 content 会让 layer 录到自己 → 递归。
 *
 * 防重画死循环：content 与玻璃片各自套 `graphicsLayer()` 成独立渲染层——玻璃片重画不会连带重跑 content 的
 * 录制；content 画完 `tick++`，玻璃片读 `tick` 触发自己重画；玻璃片自身不写任何状态。
 */
@Stable
class BackdropState internal constructor(internal val layer: GraphicsLayer) {
    /** 宿主左上角在根坐标系的位置（玻璃片用 `自身根坐标 - hostOrigin` 求切片偏移）。 */
    internal var hostOrigin by mutableStateOf(Offset.Zero)

    /** content 每画一帧 +1；玻璃片在 draw 里读它 → content 有新帧时玻璃片重画。 */
    internal var tick by mutableIntStateOf(0)

    /**
     * 外部显式失效（例：滚动状态变化但 content 自己的层没重录时）。L0 试验台用 `snapshotFlow` 挂滚动偏移调它；
     * 正式接线是否需要以试验结果为准。
     */
    fun invalidate() {
        tick++
    }
}

/** 玻璃片从这里拿宿主；为 null 表示不在任何 [BackdropHost] 里（玻璃退化为纯染色）。 */
val LocalBackdrop = staticCompositionLocalOf<BackdropState?> { null }

/**
 * 建一个 [BackdropState]。[BackdropHost] 默认自己建一个；**宿主之外**（例如 content 里的 `LaunchedEffect`
 * 想调 [BackdropState.invalidate]）需要拿到同一个实例时，在宿主的上一层调本函数、再传给 `state` 形参。
 */
@Composable
fun rememberBackdropState(): BackdropState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { BackdropState(layer) }
}

@Composable
fun BackdropHost(
    modifier: Modifier = Modifier,
    state: BackdropState = rememberBackdropState(),
    content: @Composable BoxScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier.onGloballyPositioned { coords ->
            state.hostOrigin = coords.positionInRoot()
        },
    ) {
        Box(
            Modifier
                .matchParentSize()
                // 独立渲染层：兄弟玻璃片失效时不重跑本层的录制（否则 tick 互相触发成环）。
                .graphicsLayer()
                .drawWithContent {
                    state.layer.record { this@drawWithContent.drawContent() }
                    drawLayer(state.layer)
                    // 写在画完之后；本 lambda 不读 tick，不成环。
                    state.tick++
                },
        ) {
            content()
        }
        CompositionLocalProvider(LocalBackdrop provides state) {
            overlay()
        }
    }
}
