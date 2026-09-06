package com.situ.aichat.ui.liuli.page

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/**
 * 页 chrome 三件（大标题带 / 小节标题 / 收起后的玻璃顶栏）。
 *
 * 卷四 A-2 从 `ui/liuli/home/LiuliHomeScaffold.kt` **只搬不改**搬来并公有化，另按 §2.1 给玻璃顶栏加
 * [LiuliCompactTopBar] 的 `leading` / `trailing` / `subBar` 三个槽（主页与本卷四屏一律传 null / subBar
 * 只有资料页传·**行为对既有调用方零变**）。几何改读 [LiuliPageGeometry]（与 `LiuliHomeGeometry` 同值·
 * 由 `LiuliPageGeometryTest` 钉住）。
 */

/** 收起顶栏的进出时长（卷三 §4.2·180ms fade + 6dp slide）。 */
private const val COMPACT_BAR_MS = 180
private val COMPACT_BAR_SLIDE = 6.dp

/**
 * 大标题带（列表的第 0 个 item / 卡片流的第一项）：顶 + 2 起、40 高、左 20，
 * 右侧留 52（钮 40 + 缝 12）给恒在 overlay 的尾随件。
 *
 * 主页里它落在「状态栏底 + 2」；二级页里列表另带 `contentPadding.top = 状态栏 + 导航行`，
 * 于是同一个件落在「导航行底 + 2」（A-3）。
 */
@Composable
fun LiuliLargeTitle(title: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                top = LiuliPageGeometry.titleTop,
                start = LiuliPageGeometry.gutter,
                end = LiuliPageGeometry.gutter + LiuliPageGeometry.titleEndReserve,
            )
            .height(LiuliPageGeometry.titleHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            title,
            style = AppTypography.titleLarge.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.W700),
            color = AppTheme.colors.text.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/** 列表小节标题（「置顶」/「对话」）：12/500 字距 .06em text.tertiary，上 14 下 6。 */
@Composable
fun LiuliSectionHeader(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = LiuliPageGeometry.gutter, end = LiuliPageGeometry.gutter, top = 14.dp, bottom = 6.dp),
    ) {
        Text(
            text,
            style = AppTypography.caption.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.W500,
                letterSpacing = 0.06.em,
            ),
            color = AppTheme.colors.text.tertiary,
            maxLines = 1,
        )
    }
}

/**
 * 收起后的玻璃顶栏：从窗口顶铺到「状态栏 + 44」（契约 §3.2 / §4.7 ⑱），小标题居中于**下 44**。
 * [subBar] 非空时同一片玻璃再往下延 56（8 + 40 玻璃 pill + 8·A-11），覆盖区总高 = 状态栏 + 44 + 56。
 *
 * **不挂任何 pointerInput**（卷三 A-17）：它是纯玻璃条，列表在它下面照常滚。
 * [leading] / [trailing] 是给「返回钮住进顶栏」那类页型留的槽；本卷四屏与主页四 Tab 都不传——它们的
 * 圆钮**恒在 overlay 同一位置两态不跳**（A-3），不随顶栏进出。
 */
@Composable
fun BoxScope.LiuliCompactTopBar(
    title: String,
    visible: Boolean,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    subBar: (@Composable () -> Unit)? = null,
) {
    val dark = LocalIsDarkTheme.current
    val reduceMotion = rememberReduceMotion()
    val slidePx = with(LocalDensity.current) { COMPACT_BAR_SLIDE.roundToPx() }
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.TopCenter),
        enter = if (reduceMotion) {
            fadeIn(tween(0))
        } else {
            fadeIn(tween(COMPACT_BAR_MS)) + slideInVertically(tween(COMPACT_BAR_MS)) { -slidePx }
        },
        exit = if (reduceMotion) {
            fadeOut(tween(0))
        } else {
            fadeOut(tween(COMPACT_BAR_MS)) + slideOutVertically(tween(COMPACT_BAR_MS)) { -slidePx }
        },
    ) {
        Column(Modifier.fillMaxWidth().liuliGlass(RectangleShape, dark = dark)) {
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars))
            Box(
                Modifier.fillMaxWidth().height(LiuliPageGeometry.compactBar),
                contentAlignment = Alignment.Center,
            ) {
                if (leading != null) {
                    Box(
                        Modifier.align(Alignment.CenterStart).padding(start = LiuliPageGeometry.gutter),
                        content = { leading() },
                    )
                }
                Text(
                    title,
                    style = AppTypography.caption.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.W600),
                    color = LiuliTheme.onGlass.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (trailing != null) {
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = LiuliPageGeometry.gutter),
                        horizontalArrangement = Arrangement.spacedBy(LiuliPageGeometry.actionButtonGap),
                        verticalAlignment = Alignment.CenterVertically,
                        content = trailing,
                    )
                }
            }
            if (subBar != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(LiuliPageGeometry.subBar)
                        .padding(
                            horizontal = LiuliPageGeometry.gutter,
                            vertical = (LiuliPageGeometry.subBar - LiuliPageGeometry.stripGlass) / 2,
                        ),
                    content = { subBar() },
                )
            }
        }
    }
}
