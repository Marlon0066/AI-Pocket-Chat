package com.situ.aichat.ui.liuli.page

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity

/**
 * 「大标题带收起了没」的三个判据（卷三 §4.2 的两个 + 卷四 A-8 的头图版）。
 *
 * 卷四 A-2 把前两个从 `ui/liuli/home` **只搬不改**搬到本文件并公有化：主页四 Tab 与二级屏共用同一套判据。
 * 阈值改读 [LiuliPageGeometry]（与 `LiuliHomeGeometry` 同值·由 `LiuliPageGeometryTest` 钉住），
 * 行为与搬迁前逐值相同。
 */

/**
 * 列表页的大标题收起判据：大标题带是列表的 item 0，它整条滚出屏顶（或已不是首个可见项）就切成玻璃顶栏里的小标题。
 * 与底栏缩丸（看**方向**累计）是两个独立信号。
 *
 * 二级屏同用（A-3）：列表 `contentPadding.top = 状态栏 + 导航行`，滚过 42 恰好把标题带整条送进玻璃顶栏之下。
 */
@Composable
fun rememberLargeTitleCollapsed(listState: LazyListState): Boolean {
    val titleHeightPx = with(LocalDensity.current) {
        (LiuliPageGeometry.titleTop + LiuliPageGeometry.titleHeight).roundToPx()
    }
    val collapsed by remember(listState, titleHeightPx) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset >= titleHeightPx
        }
    }
    return collapsed
}

/**
 * 卡片流页的大标题收起判据：滚过一整条标题带就收。与列表页的 [rememberLargeTitleCollapsed] 是同一条规格的两种载体。
 */
@Composable
fun rememberScrollCollapsed(scrollState: ScrollState): Boolean {
    val titleHeightPx = with(LocalDensity.current) {
        (LiuliPageGeometry.titleTop + LiuliPageGeometry.titleHeight).roundToPx()
    }
    // 读 `scrollState.value` 必须在 derivedStateOf **里面**（key 少于 lambda 所读 = 捕获过期面·PITFALLS §1d）。
    val collapsed by remember(scrollState, titleHeightPx) { derivedStateOf { scrollState.value >= titleHeightPx } }
    return collapsed
}

/**
 * 详情页（T3）的头图收起判据（A-8）：头图是 item 0，它滚到只剩「收起顶栏那么高」时切成玻璃顶栏 + 小标题。
 *
 * @param heroPx 头图实高（px）——[LiuliPageGeometry.hero] 换算后传入。
 * @param barPx 收起顶栏总高（px）= 状态栏 + 收起顶栏；名义值见 [LiuliPageGeometry.heroCollapseTail]。
 */
@Composable
fun rememberHeroCollapsed(listState: LazyListState, heroPx: Int, barPx: Int): Boolean {
    val collapsed by remember(listState, heroPx, barPx) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset >= heroPx - barPx
        }
    }
    return collapsed
}
