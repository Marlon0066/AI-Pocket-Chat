package com.situ.aichat.ui.designsystem

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion

/**
 * 全局开关 = **陶土轨 + 恒白瓷拇指**（六件套草图 2026-07-17 过审·拍板⑥「开关拇指恒白瓷」）。
 *
 * 2026-09-04 由「M3 [androidx.compose.material3.Switch] 的触觉包壳」改为自绘。**为什么必须自绘**：M3 的
 * `SwitchColors` 只吃纯色，做不出「陶土**渐变**轨」；而且 M3 的关态拇指恒比开态小一圈，拍板⑥要求
 * 两态恒 22dp。**换脸不换手感**——签名、触达、`onCheckedChange == null` 的纯显示态语义、开
 * [com.situ.aichat.ui.components.AppHaptics.light]（脆）/ 关 [com.situ.aichat.ui.components.AppHaptics.soft]（柔）
 * 的触觉分支，一字未动；只删掉了 `colors` 形参（全库唯一传它的站点正是在手工模拟白瓷效果，删掉即得更好结果）。
 *
 * 造型（1:1 取自对版稿）：轨 44×26dp 全圆角；开态 = [AppColors.accent] gradientStart→gradientEnd 横向渐变，
 * 关态 = [AppColors.surface] sunken 填充 + 0.5dp 发丝内描边；拇指 22dp 正圆、距轨边 2dp、走
 * [Modifier.porcelainThumb]（与分段控件的白瓷药丸同源；正圆时深色档没有月光沿属几何退化，见该函数 KDoc）。
 *
 * 动效：滑行走 [AppMotion.calmSpring]（位移轴），轨色淡入走 [AppMotion.effectMediumSpring]（效果轴）——
 * **两条曲线不许互换**。[rememberReduceMotion] 为真时两者都 `snap()` 直落，终态视觉相同。
 *
 * 交互：整件 `toggleable(role = Role.Switch)`，`indication = null`（轨上不铺 ripple——反馈由拇指与触觉承担），
 * [minimumInteractiveComponentSize] 保证触达 ≥48dp。**不做滑动手势**（对版稿即点击态设计）。
 *
 * @param onCheckedChange null = 纯显示态（如整行 `toggleable` 已接管点击的
 *   [com.situ.aichat.ui.components.SettingsSwitchRow]）：整件不可点，**直接透传不包装**。
 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val reduceMotion = rememberReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 2.dp, // 20 = 轨 44 − 拇指 22 − 边距 2
        animationSpec = if (reduceMotion) snap() else AppMotion.calmSpring(),
        label = "switchThumb",
    )
    val trackOn by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else AppMotion.effectMediumSpring(),
        label = "switchTrack",
    )

    val sunken = colors.surface.sunken
    val hairline = if (colors.isDark) {
        colors.surface.stroke
    } else {
        colors.text.primary.copy(alpha = AppElevation.HAIRLINE_ALPHA)
    }
    val gradient = Brush.horizontalGradient(listOf(colors.accent.gradientStart, colors.accent.gradientEnd))

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .then(
                // onCheckedChange == null = 纯显示态：**整个 toggleable 都不挂**（与被替换的 M3 Switch 逐字同构
                // ——它也是 `if (onCheckedChange != null) Modifier.toggleable(...) else Modifier`）。
                // 挂一个 enabled = false 的 toggleable 会在读屏树里多出一个可切换节点，正是
                // SettingsSwitchRow 的 KDoc 明令要消掉的「TalkBack 双焦点」。
                if (onCheckedChange != null) {
                    Modifier.toggleable(
                        value = checked,
                        enabled = enabled,
                        role = Role.Switch,
                        interactionSource = interaction,
                        indication = null,
                        onValueChange = { value ->
                            if (value) haptics.light() else haptics.soft()
                            onCheckedChange(value)
                        },
                    )
                } else {
                    Modifier
                },
            )
            .alpha(if (enabled) 1f else 0.38f)
            .width(44.dp)
            .height(26.dp)
            .drawBehind {
                val radius = size.height / 2f
                val corner = CornerRadius(radius, radius)
                drawRoundRect(color = sunken, cornerRadius = corner)
                if (trackOn > 0f) {
                    drawRoundRect(brush = gradient, cornerRadius = corner, alpha = trackOn)
                }
                // 发丝只属关态（对版稿的 inset ring 只挂 .off）——开态淡出，别在渐变上留一圈灰边。
                if (trackOn < 1f) {
                    val stroke = AppElevation.hairlineWidth.toPx()
                    drawRoundRect(
                        color = hairline,
                        topLeft = Offset(stroke / 2f, stroke / 2f),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(radius - stroke / 2f, radius - stroke / 2f),
                        style = Stroke(width = stroke),
                        alpha = 1f - trackOn,
                    )
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(start = thumbOffset)
                .size(22.dp)
                .porcelainThumb(pressed = pressed, raised = enabled),
        )
    }
}
