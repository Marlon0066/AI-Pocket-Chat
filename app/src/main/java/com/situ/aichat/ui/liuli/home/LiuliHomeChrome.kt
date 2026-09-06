package com.situ.aichat.ui.liuli.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity

/**
 * 底栏「缩丸 / 展开」的滚动信号（图纸 2026-09-06 卷三 A-3 · §4.1）。
 *
 * **只用 nested-scroll，绝不在列表上盖兄弟拦截层**（PITFALLS §1d：重叠兄弟的最上层独占指针 → 列表一个事件都
 * 收不到）。宿主 [LiuliHomeHost] 把 [connection] 挂在整个主页 Box 上，四屏的 `LazyColumn` / `verticalScroll`
 * 的滚动量沿 nested-scroll 树自然上报到这里。
 *
 * 判据 = **方向累计**（与「大标题收起」的位置判据是两个独立信号）：同向累计到 24dp 翻一次态，方向一反转就清零
 * 累计（E2 overscroll 回弹 / E3 小幅来回都不该闪切）。
 */
@Stable
class LiuliHomeChrome internal constructor(private val thresholdPx: Float) {

    /** true = 底栏缩成小丸（顶栏大标题的收起是每屏自己按位置判，不看这里）。 */
    var collapsed by mutableStateOf(false)
        private set

    private var accumulatedPx = 0f

    /** 切 Tab / 点小丸 / 宿主重建时展开（不导航）。 */
    fun expand() {
        accumulatedPx = 0f
        collapsed = false
    }

    /**
     * 挂在主页宿主 Box 上；只读滚动量、不消费（恒返回 [Offset.Zero]）。
     *
     * **只累计 [consumed]（列表真的滚过的量），不算 [available]**：列表铺不满一屏 / 已到底时，手指再拖
     * 列表一格不动（consumed = 0）但 available 照样上报——若把它也算进去，两行的聊天列表滑一下空白底栏就缩
     * 成小丸（E1 违反·R1 🔴-3 装机实证）。overscroll 回弹同理（E2）。
     */
    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // 不对称补丁（独立复核 🟡-1）：列表已在顶、手指还往下拽（consumed = 0 且 available > 0）→ 直接展开。
            // 否则「缩起后列表被程序性缩短 / 小步回顶」会落到「在顶 + 缩起」再也滚不出来（只剩点小丸 / 切 Tab 脱困）。
            // 向上的 available（到底 / 铺不满一屏）仍忽略——E1 不回退。
            if (consumed.y == 0f && available.y > 0f) {
                expand()
                return Offset.Zero
            }
            val (nextAccumulated, nextCollapsed) =
                liuliCollapseStep(accumulatedPx, consumed.y, thresholdPx, collapsed)
            accumulatedPx = nextAccumulated
            collapsed = nextCollapsed
            return Offset.Zero
        }
    }
}

/**
 * 缩丸状态机的纯函数（便于 T1 独立反推·`LiuliHomeChromeTest`）。
 *
 * [deltaPx] 用 Compose 的滚动符号：**向下滚列表（看后面的内容）为负**，向上滚为正。
 * 返回 `新累计 to 新折叠态`；翻转时累计归零（同向再滚不重复触发，反向要重新攒够一个阈值）。
 */
internal fun liuliCollapseStep(
    accumulatedPx: Float,
    deltaPx: Float,
    thresholdPx: Float,
    collapsed: Boolean,
): Pair<Float, Boolean> {
    if (deltaPx == 0f) return accumulatedPx to collapsed
    // 方向反转即清零：只从这一下的方向重新攒。
    val base = if (accumulatedPx != 0f && (accumulatedPx > 0f) != (deltaPx > 0f)) 0f else accumulatedPx
    val next = base + deltaPx
    return when {
        next <= -thresholdPx -> 0f to true
        next >= thresholdPx -> 0f to false
        else -> next to collapsed
    }
}

@Composable
fun rememberLiuliHomeChrome(): LiuliHomeChrome {
    val thresholdPx = with(LocalDensity.current) { LiuliHomeGeometry.collapseThreshold.toPx() }
    return remember(thresholdPx) { LiuliHomeChrome(thresholdPx) }
}
