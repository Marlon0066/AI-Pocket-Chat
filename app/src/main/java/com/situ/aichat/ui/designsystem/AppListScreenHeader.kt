package com.situ.aichat.ui.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Fable-5 一级列表页页眉（聊天 / 联系人）：大标题（左·M3 `titleLarge`·与原 `TopAppBar` 同字阶同色）+ 右上角
 * 「新建」圆钮（[AppTopBarAction]）。取代这两屏原本的 M3 `TopAppBar`——把「新建」从 actions 槽「居中于
 * 64dp 栏内」改成**三边视觉等距**（用户拍板 2026-06-19）：加号视觉圆距上方状态栏、距屏幕右缘、距下方搜索框
 * 三段净距都 = [edgeMargin]；[edgeMargin] = [AppSpacing.screenGutter]，故加号视觉右缘恰落在 **20dp 的屏
 * gutter 线**上（设计语言 §2.5 军规）；左侧大标题的字形左缘同样落在这条线上。
 *
 * **自适应**：状态栏 inset 由外层 [androidx.compose.material3.Scaffold] 的 contentWindowInsets 兜（本页眉
 * 不再 statusBarsPadding），故 [edgeMargin] 就是「状态栏底 → 加号」的真实净距，随机型状态栏高度恒定。
 *
 * **几何**：band 高 = [edgeMargin] × 2 + 视觉直径 40 → 加号视觉垂直居中、与大标题共一中线。加号 48dp 最小
 * 触达比 40dp 视觉每边外溢 [touchHalo]=4dp，故按布局框定位时 padding 取 [edgeMargin] − [touchHalo] 让
 * 「视觉」恰落在 [edgeMargin]。页眉底缘 = 加号视觉底 + [edgeMargin]，因此紧随其后的搜索框 top padding 须为 0。
 */
@Composable
fun AppListScreenHeader(
    title: String,
    actionIcon: ImageVector,
    actionContentDescription: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 屏 gutter 恒 20（设计语言 §2.5 军规）——本组件「三边等距」的那个距离就是它。
    val edgeMargin = AppSpacing.screenGutter
    val actionVisual = 40.dp
    val touchHalo = 4.dp // (48dp 最小触达 − 40dp 视觉) / 2
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(edgeMargin * 2 + actionVisual),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.CenterStart)
                // start = gutter：裸文字无内部补偿，字形左缘直接落在 20dp 屏 gutter 线上（§2.5 换算表），
                // 与站点侧搜索框左缘同栅格；end 预留加号区，长标题省略不压到加号。
                .padding(start = edgeMargin, end = edgeMargin * 2 + actionVisual)
                .semantics { heading() },
        )
        AppTopBarAction(
            icon = actionIcon,
            contentDescription = actionContentDescription,
            onClick = onAction,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = edgeMargin - touchHalo, end = edgeMargin - touchHalo),
        )
    }
}
