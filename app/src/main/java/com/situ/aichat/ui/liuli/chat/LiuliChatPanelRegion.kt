package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.situ.aichat.ui.chat.ChatInputPanelState

/**
 * 「+」面板区占位（图纸 2026-09-05 卷二B §2.2 / A-5）：键盘与功能面板**轮流坐**的那块底部区域，高度 =
 * `inputPanel.regionPx(ime − navBar)`——机制（含在布局 lambda 里读 ime、绝不在组合期读）与 PLUS_PANEL 的
 * 「高度实时 = 键盘高度」硬指标零碰。
 *
 * 卷二B 起它只**占位**：面板本体搬到 overlay 层的 [LiuliPlusPanel]（一片玻璃·A-5），这里既不画底也不放格子
 * ——露出来的就是聊天背景本身（卷二A R1 🔵-6「面板区底露缝」随之消失，因为已经没有那块白底可露）。
 *
 * 它住在 [com.situ.aichat.ui.liuli.glass.BackdropHost] 的**内容层**（输入区 / 面板在 overlay 层按同一高度
 * 向上偏移），故这里额外把绘制整体上移一个导航栏 inset：内容层 `Column` 没吃 navBar padding（列表底留白
 * 自带 navBar·图纸 §4.7），不移的话这块占位会比托盘底缘低一条导航栏。
 */
@Composable
internal fun LiuliChatPanelRegion(inputPanel: ChatInputPanelState) {
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val navBarInsets = WindowInsets.navigationBars
    val navBarPx = navBarInsets.getBottom(density)
    Box(
        Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, -navBarPx) }
            // 审计 P4 照抄：高度在布局 lambda 里读 ime——键盘动画帧只重排此区、不重组。
            .layout { measurable, constraints ->
                val h = inputPanel.regionPx(imeInsets.exclude(navBarInsets).getBottom(this))
                val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                layout(placeable.width, h) { placeable.place(0, 0) }
            },
    )
}

/**
 * 「面板开着点空白 = 缩回」（图纸 §4.4 · E9 第三路）——挂在**列表区容器**上的父级手势，**不是**盖在列表上的兄弟层
 * （复核 R1 🔴-1）：兄弟层会独占整块区域的指针（Compose 命中测试到最上层的兄弟即停），结果列表滚不动、回底钮 /
 * 横幅按钮点不到、一次上滑还被当成「点」把面板收了。父级 `pointerInput` 与子树共享指针：拖动被列表的 scrollable
 * 消费 → 这里的 tap 自动作废（列表照滚、面板留着）；点在气泡 / 按钮上 → 子项的 clickable 先消费 down → 这里收不到；
 * 只有点在真正的空白处才收面板。[onTap] 经 `rememberUpdatedState` 读最新值（捕获过期陷阱·PITFALLS）。
 */
internal fun Modifier.liuliDismissPanelOnTap(active: Boolean, onTap: () -> Unit): Modifier = composed {
    val currentOnTap = rememberUpdatedState(onTap)
    pointerInput(active) {
        if (active) detectTapGestures(onTap = { currentOnTap.value() })
    }
}
