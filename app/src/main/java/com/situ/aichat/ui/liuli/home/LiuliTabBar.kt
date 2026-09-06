package com.situ.aichat.ui.liuli.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppBottomNavItem
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/** 小丸的 `onClickLabel`（图纸 §9 ① 唯一字面量·不是用户可见文案，只作读屏提示）。 */
private const val EXPAND_BAR_LABEL = "展开底栏"

/** 徽章丸的两个尺码（§3.2「底栏」：16 高 · 字 10/600）——丸本体是 [LiuliCountPill]（与未读丸同一份）。 */
private val BADGE_SIDE_PADDING = 4.dp
private val BADGE_FONT_SIZE = 10.sp

/** 透镜丸的 testTag（几何断言用·无语义）。 */
internal const val LIULI_TAB_PILL_TAG = "liuli_tab_pill"

/** 液滴拉伸：滑行起步瞬间横向 +18% / 纵向 −6%，随 `calmSpring` 回到 1（用户 09-06 拍板「手感液滴」·RM 不拉伸）。 */
private const val STRETCH_X = 0.18f
private const val STRETCH_Y = 0.06f

/**
 * 琉璃玻璃底栏（图纸 2026-09-06 卷三 §4.1 · 契约 §6 Q-H2 甲 / E 表）。
 *
 * 两态一枚玻璃片：**展开** = 66 高铺满的胶囊（四槽等分 · 当前槽下压一枚 72×46 的玻璃透镜丸·[liuliTabLens]）；**缩起** =
 * 44 高只剩当前 Tab 的小丸（图标 + 名字），点它就展开、**不导航**；两态间 `animateContentSize` 变形。
 * 缩 / 展的信号来自 [LiuliHomeChrome]（滚动方向累计·nested-scroll），本件只读它、并在切 Tab 时调 `expand()`。
 * 数据形状借暖陶 [AppBottomNavItem]（纯数据类·一个大脑喂两张脸），长相与动效全自画（M3 的
 * `NavigationBar / Badge / BadgedBox` 一个不碰·§9 ⑤）。
 */
@Composable
fun LiuliTabBar(
    items: List<AppBottomNavItem>,
    chrome: LiuliHomeChrome,
    modifier: Modifier = Modifier,
) {
    val dark = LocalIsDarkTheme.current
    val reduceMotion = rememberReduceMotion()
    val selectedIndex = items.indexOfFirst { it.selected }.coerceAtLeast(0)

    // 切 Tab（含从详情页回来落到别的 Tab）→ 立刻展开（E4）。
    LaunchedEffect(selectedIndex) { chrome.expand() }

    val collapsed = chrome.collapsed
    Box(
        modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(
            start = LiuliHomeGeometry.tabBarSide,
            end = LiuliHomeGeometry.tabBarSide,
            bottom = LiuliHomeGeometry.tabBarBottom,
        ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // 玻璃片（含形状外的 8dp 软影）在**最外层**、只包住内层的动画盒：`animateContentSize` 自带 `clipToBounds`，
        // 若把它排在 `liuliGlass` 之前，软影会被裁成矩形、只在胶囊两个圆角外露出灰块（R1 🔴-1）。
        // 外层没有宽高修饰符 → 逐帧跟随内层的动画尺寸，玻璃裁切 / 发丝 / 软影都按当帧尺寸画。
        Box(Modifier.liuliGlass(LiuliShapes.pill, dark = dark)) {
            Box(
                modifier = Modifier
                    .animateContentSize(if (reduceMotion) snap() else AppMotion.calmSpring())
                    .then(if (collapsed) Modifier.wrapContentWidth() else Modifier.fillMaxWidth())
                    .height(if (collapsed) LiuliHomeGeometry.tabMini else LiuliHomeGeometry.tabBar),
            ) {
                if (collapsed) {
                    LiuliTabMini(item = items.getOrNull(selectedIndex), onExpand = chrome::expand)
                } else {
                    LiuliTabRow(items = items, selectedIndex = selectedIndex, reduceMotion = reduceMotion)
                }
            }
        }
    }
}

/** 展开态：四槽等分 + 一枚随选中索引滑行的玻璃透镜丸（丸绘于槽之前 → 在图标 / 字之后）。 */
@Composable
private fun LiuliTabRow(
    items: List<AppBottomNavItem>,
    selectedIndex: Int,
    reduceMotion: Boolean,
) {
    val dark = LocalIsDarkTheme.current
    val haptics = LocalAppHaptics.current
    val animIndex by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = if (reduceMotion) snap() else AppMotion.calmSpring(),
        label = "liuliTabPillSlide",
    )
    // 液滴拉伸：切 Tab 瞬间 snap 到 1、随 calmSpring 松回 0（初次组合不拉·RM 不拉）。
    val stretch = remember { Animatable(0f) }
    var firstIndex by remember { mutableStateOf(true) }
    LaunchedEffect(selectedIndex) {
        if (firstIndex) {
            firstIndex = false
            return@LaunchedEffect
        }
        if (!reduceMotion) {
            stretch.snapTo(1f)
            stretch.animateTo(0f, AppMotion.calmSpring())
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val tabWidth = maxWidth / items.size.coerceAtLeast(1)
        Box(
            modifier = Modifier
                // offset{} 走布局相位，不触发重组（同暖陶滑丸口径）。
                .offset {
                    IntOffset(
                        x = (tabWidth * animIndex + (tabWidth - LiuliHomeGeometry.tabPillWidth) / 2).roundToPx(),
                        y = LiuliHomeGeometry.tabPillTop.roundToPx(),
                    )
                }
                .size(width = LiuliHomeGeometry.tabPillWidth, height = LiuliHomeGeometry.tabPillHeight)
                .graphicsLayer {
                    scaleX = 1f + STRETCH_X * stretch.value
                    scaleY = 1f - STRETCH_Y * stretch.value
                }
                .liuliTabLens(dark = dark)
                .testTag(LIULI_TAB_PILL_TAG),
        )
        Row(Modifier.fillMaxWidth().selectableGroup().padding(vertical = LiuliHomeGeometry.tabBarVPad)) {
            items.forEach { item ->
                LiuliTabSlot(
                    item = item,
                    reduceMotion = reduceMotion,
                    onClick = { haptics.selection(); item.onClick() },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 一个 Tab 槽：槽高 52（≥ 48 触达·丸 46 只是视觉）；图标 24 + 3 + 名字 11/500 整体在槽内居中。 */
@Composable
private fun LiuliTabSlot(
    item: AppBottomNavItem,
    reduceMotion: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = AppTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val contentColor by animateColorAsState(
        // 选中 = 钴蓝 `accent.text`（透镜丸是淡玻璃·主题色只落在图标和字上·用户 09-06「丸甲」）。
        targetValue = if (item.selected) colors.accent.text else LiuliTheme.onGlass.secondary,
        animationSpec = if (reduceMotion) snap() else AppMotion.effectMediumSpring(),
        label = "liuliTabColor",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) 0.96f else 1f,
        animationSpec = AppMotion.calmSpring(),
        label = "liuliTabPress",
    )
    // 选中瞬间图标 snap 到 1.16 再 lively 弹回（初次组合跳过·照暖陶 F3）。
    val iconScale = remember { Animatable(1f) }
    var firstComposition by remember { mutableStateOf(true) }
    LaunchedEffect(item.selected) {
        if (firstComposition) {
            firstComposition = false
            return@LaunchedEffect
        }
        if (item.selected && !reduceMotion) {
            iconScale.snapTo(1.16f)
            iconScale.animateTo(1f, AppMotion.livelySpring())
        } else {
            iconScale.snapTo(1f)
        }
    }
    Column(
        modifier = modifier
            .selectable(
                selected = item.selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .height(LiuliHomeGeometry.tabSlot)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.graphicsLayer { scaleX = iconScale.value; scaleY = iconScale.value },
            contentAlignment = Alignment.Center,
        ) {
            Icon(item.icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(LiuliHomeGeometry.tabIcon))
            LiuliCountPill(
                item.badgeCount, LiuliHomeGeometry.badge, BADGE_SIDE_PADDING, BADGE_FONT_SIZE,
                Modifier.align(Alignment.TopEnd)
                    .offset(x = LiuliHomeGeometry.badgeOffsetX, y = LiuliHomeGeometry.badgeOffsetY),
            )
        }
        Spacer(Modifier.height(LiuliHomeGeometry.tabLabelGap))
        Text(
            item.label,
            style = AppTypography.caption.copy(fontWeight = FontWeight.W500),
            color = contentColor,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** 缩起态：只剩当前 Tab 的小丸；点它 = 展开，**不导航**（E4 / §4.1）。 */
@Composable
private fun LiuliTabMini(item: AppBottomNavItem?, onExpand: () -> Unit) {
    if (item == null) return
    Row(
        modifier = Modifier
            .height(LiuliHomeGeometry.tabMini)
            .clickable(role = Role.Button, onClickLabel = EXPAND_BAR_LABEL, onClick = onExpand)
            .padding(start = LiuliHomeGeometry.tabMiniStart, end = LiuliHomeGeometry.tabMiniEnd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LiuliHomeGeometry.tabMiniGap),
    ) {
        Box {
            Icon(item.icon, contentDescription = null, tint = LiuliTheme.onGlass.primary, modifier = Modifier.size(LiuliHomeGeometry.tabMiniIcon))
            LiuliCountPill(
                item.badgeCount, LiuliHomeGeometry.badge, BADGE_SIDE_PADDING, BADGE_FONT_SIZE,
                Modifier.align(Alignment.TopEnd)
                    .offset(x = LiuliHomeGeometry.badgeOffsetX, y = LiuliHomeGeometry.badgeOffsetY),
            )
        }
        Text(
            item.label,
            style = AppTypography.secondary.copy(fontWeight = FontWeight.W600),
            color = LiuliTheme.onGlass.primary,
            maxLines = 1,
        )
    }
}
