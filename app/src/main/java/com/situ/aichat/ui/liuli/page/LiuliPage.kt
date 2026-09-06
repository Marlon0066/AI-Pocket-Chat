package com.situ.aichat.ui.liuli.page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBarIcons
import com.situ.aichat.ui.liuli.designsystem.LiuliCircleButton
import com.situ.aichat.ui.liuli.glass.BackdropHost
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.ui.platform.testTag

/**
 * 琉璃**二级屏**页壳（图纸 2026-09-06 卷四 §4.2 · A-3）。
 *
 * 与主页壳 [com.situ.aichat.ui.liuli.home.LiuliHomeScaffold] 同一副底座（`BackdropHost` + 内容层自画纸面 +
 * overlay 玻璃顶栏），差别只在**多一行导航行**：
 *
 * ```
 * 静止态：状态栏 │ 44 导航行（返回圆钮 左 20 · 尾随动作 右 20 · 纸面无玻璃）│ 大标题带（+2 · 40 高）│ 内容
 * 收起态：88 玻璃顶栏（状态栏 + 44）+ 居中小标题（+ subBar 56）；返回 / 尾随圆钮**恒在同一位置两态不跳**
 * ```
 *
 * **顶内距分两层给**（本页壳 + 调用方）：本页壳给非 [hero] 页加 `statusBarsPadding()`（同主页四 Tab 的做法
 * ——内容必须能滚到窗口顶、从玻璃底下透出来才有模糊可看），调用方的 `LazyColumn` 再加
 * `contentPadding.top = LiuliPageGeometry.navRow`；两层合起来 = [LiuliPageGeometry.contentTopInset]。
 * [hero] = true 时两层都不加，头图直接穿到窗口顶。
 *
 * 收起判据由调用方给（列表页 [rememberLargeTitleCollapsed] · 详情页 [rememberHeroCollapsed]）。
 */
@Composable
fun LiuliPage(
    title: String,
    onBack: () -> Unit,
    collapsed: Boolean,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
    subBar: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    /**
     * 悬浮主行动钮槽（卷五 A-4 ⑦·**加法零回归**：不传 = 与增补前逐字节同渲染）。住 overlay 的右下角，
     * 版位 [LiuliPageGeometry.fab] · 右缘 [LiuliPageGeometry.gutter] · 距导航栏 [LiuliPageGeometry.fabBottom]；
     * 排在 [bottomBar] **之前**，同时给时保存栏压在钮上（两者同屏共存不是已过审长相，只为顺序确定）。
     */
    fab: (@Composable () -> Unit)? = null,
    hero: Boolean = false,
    /** 返回钮可用（卷五复核 R1 A-3 补·默认 true）：导入进行中这类「不许退」的页传 false，钮淡出且不吃点击。 */
    backEnabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = AppTheme.colors
    BackdropHost(
        modifier = modifier.fillMaxSize(),
        content = {
            // 纸面**画在内容层里**（不在宿主 modifier 上）：宿主只录 content，纸面若只铺在宿主外面，overlay 里的
            // 玻璃切到的是透明底 → 合成后半透明 → 它自己的海拔影从片内透出来（PITFALLS §1d·2026-09-06 圆钮甲装机）。
            Box(Modifier.matchParentSize().background(colors.surface.base))
            if (hero) {
                content()
            } else {
                Box(Modifier.matchParentSize().statusBarsPadding(), content = content)
            }
        },
        overlay = {
            // 导航行纸面带（非 hero 页·未收起时）：大标题带滚进导航行那 42dp 里时被它遮住，不会从返回圆钮
            // 底下露出半截字（卷五复核 R1：内容只比一屏高十几 dp 的页滚到底就停在这一段·日记设置页实拍）。
            // 收起那一刻带子撤走、玻璃顶栏接手，玻璃底下照常有内容可糊。
            if (!hero && !collapsed) {
                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(colors.surface.base).testTag(LIULI_NAV_BAND_TAG)) {
                    Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars))
                    Box(Modifier.fillMaxWidth().height(LiuliPageGeometry.navRow))
                }
            }
            LiuliCompactTopBar(title = title, visible = collapsed, subBar = subBar)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = LiuliPageGeometry.titleTop, start = LiuliPageGeometry.gutter)
                    // 版位 = 视觉 40；圆钮自带的 48dp 触达框超出约束时居中外溢（PITFALLS §1d）。
                    .size(LiuliPageGeometry.backButton),
                contentAlignment = Alignment.Center,
            ) {
                LiuliCircleButton(
                    onClick = onBack,
                    contentDescription = stringResource(R.string.action_back),
                    size = LiuliPageGeometry.backButton,
                    enabled = backEnabled,
                ) {
                    Icon(AppTopBarIcons.Back, contentDescription = null, modifier = Modifier.size(LiuliPageGeometry.chromeIcon))
                }
            }
            if (actions != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = LiuliPageGeometry.titleTop, end = LiuliPageGeometry.gutter)
                        .height(LiuliPageGeometry.backButton),
                    horizontalArrangement = Arrangement.spacedBy(LiuliPageGeometry.actionButtonGap),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
            if (fab != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = LiuliPageGeometry.gutter, bottom = LiuliPageGeometry.fabBottom)
                        // 版位 = 视觉 56；钮自带的 48 触达框小于版位，不外溢。
                        .size(LiuliPageGeometry.fab),
                    contentAlignment = Alignment.Center,
                    content = { fab() },
                )
            }
            if (bottomBar != null) {
                Box(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    content = { bottomBar() },
                )
            }
        },
    )
}

/**
 * 尾随槽（[LiuliPage.actions]）里的标准圆钮：视觉 40 · 图标 20 · **版位恰 40**。
 * 直接放 [LiuliCircleButton] 会被它的 `minimumInteractiveComponentSize` 撑成 48 宽 → 右距变 24（复核 R1 🟡-3）；
 * 外套一枚 40 盒让 48 触达框居中外溢（与返回钮同法）。
 */
@Composable
fun LiuliPageCircleAction(
    onClick: () -> Unit,
    contentDescription: String,
    icon: ImageVector,
    enabled: Boolean = true,
) {
    Box(Modifier.size(LiuliPageGeometry.backButton), contentAlignment = Alignment.Center) {
        LiuliCircleButton(
            onClick = onClick,
            contentDescription = contentDescription,
            size = LiuliPageGeometry.backButton,
            enabled = enabled,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(LiuliPageGeometry.chromeIcon))
        }
    }
}

/** 导航行纸面带的测试标记（生产期零影响）。 */
const val LIULI_NAV_BAND_TAG = "liuliNavBand"
