package com.situ.aichat.ui.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion

/** 底部导航一项。[icon]=自绘 [AppNavIcons]；[badgeCount]>0 显角标；[selected] 由调用方按当前路由判定。 */
data class AppBottomNavItem(
    val icon: ImageVector,
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
    val badgeCount: Int = 0,
)

private val PILL_WIDTH = 58.dp
private val PILL_HEIGHT = 34.dp
private val BAR_VPAD = 8.dp

/**
 * 悬浮底栏在导航栏 inset 之上占的高度（≈ 外距 8 + 胶囊 ~69 + 余量）——A0 叠加层变体下，Tab 页据此留出底部空间，
 * 避免内容被浮于其上的底栏遮挡（过渡丝滑化·A0）。略大于实测高度以保证清空；真机走查后可微调。
 */
val AppBottomNavHeight: Dp = 88.dp

/**
 * Fable-5 底部导航 = 悬浮胶囊栏（设计语言 §3·B 方案用户过审 2026-06-19·动画升级过审 2026-06-19）。
 *
 * 脱离底缘的胶囊（[AppShapes.full]·[AppColors.surface] raised 暖白纸 + 0.5dp [AppColors.surface] stroke 发丝边·
 * **靠明度分层浮起不用投影**[设计语言 §0]·[navigationBarsPadding] 自处理系统手势条 inset）。
 *
 * **四层动效（全 [rememberReduceMotion] 门控）**：
 * 1. **滑动药丸**——单个陶土药丸（[AppColors.accent] primary 16% tint）随选中 tab 索引插值横向滑行，
 *    [AppMotion.calmSpring]（ζ1.0 无过冲·守效果轴）；等宽 tab → 位置确定，无测量竞态。
 * 2. **图标轻弹**——选中瞬间图标 snap 到 1.16 再 [AppMotion.livelySpring]（ζ0.78·设计语言指定的「微反馈·
 *    icon morph」档）弹回 1.0；初次组合跳过（不在启动时弹）。
 * 3. **按压回弹**——按下整 tab 缩到 0.93、松开 [AppMotion.calmSpring] 弹回（复刻 [Modifier.clickableScale]
 *    手感·但挂在 [selectable] 上保 `Role.Tab` 语义）。
 * 4. **触感**——每次点击 [com.situ.aichat.ui.components.AppHaptics.selection]（EFFECT_TICK 轻 tick）。
 *
 * a11y：[selectableGroup] + 每项 `Role.Tab` + selected 语义（TalkBack 读「<label>·标签页·已选中」）；图标
 * `contentDescription=null` 装饰、名字走 label 文本；48dp 最小触达。
 *
 * [opacity]=栏背景不透明度（过渡丝滑化·A1）：默认 1f 实色；外观设置可调至 0.5，内容隐隐透到胶囊栏后
 * （发丝边/药丸/角标/动效不变）。
 */
@Composable
fun AppBottomNav(items: List<AppBottomNavItem>, modifier: Modifier = Modifier, opacity: Float = 1f) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val reduceMotion = rememberReduceMotion()
    val selectedIndex = items.indexOfFirst { it.selected }.coerceAtLeast(0)
    val animIndex by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = if (reduceMotion) snap() else AppMotion.calmSpring(),
        label = "navPillSlide",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            // 屏 gutter 恒 20（设计语言 §2.5 军规）：胶囊是有底色的容器，无补偿故直接给 20（胶囊因此窄 8dp）。
            .padding(horizontal = AppSpacing.screenGutter, vertical = 8.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .clip(AppShapes.full)
                .background(colors.surface.raised.copy(alpha = opacity))
                .border(width = 0.5.dp, color = colors.surface.stroke, shape = AppShapes.full),
        ) {
            val tabWidth = maxWidth / items.size.coerceAtLeast(1)
            // 滑动药丸（绘于 Row 之后→在图标后面；offset{} 走布局相位，不触发重组，60fps 平滑）
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (tabWidth * animIndex + (tabWidth - PILL_WIDTH) / 2).roundToPx(),
                            y = BAR_VPAD.roundToPx(),
                        )
                    }
                    .size(width = PILL_WIDTH, height = PILL_HEIGHT)
                    .clip(AppShapes.medium)
                    .background(colors.accent.primary.copy(alpha = 0.16f)),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .padding(vertical = BAR_VPAD),
                verticalAlignment = Alignment.Top,
            ) {
                items.forEach { item ->
                    AppBottomNavTab(
                        item = item,
                        reduceMotion = reduceMotion,
                        onClick = { haptics.selection(); item.onClick() },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AppBottomNavTab(
    item: AppBottomNavItem,
    reduceMotion: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = AppTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val contentColor by animateColorAsState(
        targetValue = if (item.selected) colors.accent.text else colors.text.secondary,
        animationSpec = if (reduceMotion) snap() else AppMotion.effectMediumSpring(),
        label = "navTabColor",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) 0.93f else 1f,
        animationSpec = AppMotion.calmSpring(),
        label = "navTabPress",
    )
    // 图标轻弹：选中→snap 到峰值再 lively 弹回（初次组合跳过，避免启动即弹）。
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
            .heightIn(min = 48.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Box(
            modifier = Modifier
                .height(PILL_HEIGHT)
                .graphicsLayer { scaleX = iconScale.value; scaleY = iconScale.value },
            contentAlignment = Alignment.Center,
        ) {
            BadgedBox(
                badge = {
                    if (item.badgeCount > 0) {
                        Badge { Text(if (item.badgeCount > 99) "99+" else item.badgeCount.toString()) }
                    }
                },
            ) {
                Icon(item.icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(item.label, style = AppTypography.caption, color = contentColor, maxLines = 1)
    }
}
