package com.situ.aichat.ui.liuli.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

/**
 * 「门控」背景宿主（图纸 2026-09-06 卷三 A-2 / §2.1）：结构同 [BackdropHost]，多一个 [active] 开关。
 *
 * 为什么要它：主页宿主套在**整个 NavHost** 外面（底栏是它的 overlay），但底栏只在四个 Tab 路由上显示——
 * 详情页 / 聊天屏在场时录一层 `GraphicsLayer` 是白付的开销（L0 实测每层 ≈ 1ms）。[active] = false 时
 * 直接 `drawContent()`，一层都不录。
 *
 * 与 [BackdropHost] 的关系：**不改它、只在同包复制一份**（`BackdropState.layer / hostOrigin / tick` 是
 * internal，只有同包碰得到）。玻璃片仍必须放 `overlay`（放进 `content` 会让 layer 录到自己 → 递归）。
 */
@Composable
fun LiuliGatedBackdropHost(
    modifier: Modifier = Modifier,
    active: Boolean,
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
                    if (active) {
                        state.layer.record { this@drawWithContent.drawContent() }
                        drawLayer(state.layer)
                        // 写在画完之后；本 lambda 不读 tick，不成环。
                        state.tick++
                    } else {
                        drawContent()
                    }
                },
        ) {
            content()
        }
        CompositionLocalProvider(LocalBackdrop provides state) {
            overlay()
        }
    }
}
