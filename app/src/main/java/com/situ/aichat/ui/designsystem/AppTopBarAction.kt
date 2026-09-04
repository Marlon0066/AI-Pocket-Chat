package com.situ.aichat.ui.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion

/**
 * Fable-5 顶栏动作按钮 = 一枚**白瓷圆钮**（用户过审 2026-06-19 迁到顶栏右上角 / 2026-09-04 换白瓷釉面）。
 *
 * 质感与白瓷药丸（[Modifier.porcelainThumb]）**同族同源**：釉面纵向渐变（[AppColors.surface] glaze →
 * glazeShade）+ 暖边（浅档 [AppColors.accent] primary 32% / 深档 [AppColors.text] primary 9%）+ 接触影，
 * 浅档另有一层外扩软影。40dp 正圆天然适配——`porcelainThumb` 的圆角取 `minOf(w,h)/2`。
 *
 * **深色档没有月光沿，这不是缺陷**：`porcelainThumb` 的月光沿带 `size.width > size.height` 守卫，正圆时
 * 该线的起终点同为 `x = radius`，几何上长度为零、画不出来（守卫本身是为药丸「线不戳出弧外」而设，不为
 * 圆钮改）。深色的浮起感由更重的接触影（42%）承担。
 *
 * **修饰链顺序锁定**：`porcelainThumb` 必须排在 [clickable] **之前**——它链尾自带 `clip(AppShapes.full)`，
 * ripple 才会被裁成圆；调用方与本组件都不再自写 `clip` / `background` / `border`。
 *
 * 手感：按下整钮 [AppMotion.calmSpring] 轻缩到 **0.92**（[rememberReduceMotion] 门控·关动画时不缩）。
 * 这里沿用圆钮自身的既有过审值，**不改用** [AppPorcelain.PRESS_SCALE]（0.975）——那一档是给药丸「压进
 * 凹槽」配的位移感，本件没有凹槽。**换脸不换手感。**
 *
 * a11y：[Role.Button] + [contentDescription] 作可读名（图标自身 `null` 装饰）；[minimumInteractiveComponentSize]
 * 把触达扩到 48dp（视觉 40dp）。
 *
 * [enabled] = false（如「创建中禁止退出」）：**钮灰掉但节点仍在原位**——「不能退出」与「没有退出」是两回事，
 * 突然缺一块顶栏比灰掉更吓人。落值复用白瓷开关的既有过审禁用口径（[porcelainThumb] `raised = false` 去影
 * + 整件 `alpha(0.38f)`），`clickable(enabled = false)` 让读屏拿到正确的 disabled 语义。
 */
@Composable
fun AppTopBarAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val reduceMotion = rememberReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled && !reduceMotion) 0.92f else 1f,
        animationSpec = AppMotion.calmSpring(),
        label = "topBarActionPress",
    )
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(40.dp)
            // 禁用态 = 白瓷开关的既有过审口径：去影（禁用的东西不该还浮着）+ 整件 38% 透明。
            .porcelainThumb(pressed = pressed, raised = enabled)
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = { haptics.light(); onClick() },
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.accent.text,
            modifier = Modifier.size(22.dp),
        )
    }
}
