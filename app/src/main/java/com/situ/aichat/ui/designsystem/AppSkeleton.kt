package com.situ.aichat.ui.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.rememberReduceMotion

/**
 * 通用骨架屏占位（设计系统此前缺口件）——sunken 底色块 + 呼吸脉动，用于卡片列表 / sheet 内容等加载态，
 * 取代「一行小字」式素朴兜底。典型用法：
 *
 * ```
 * AppSkeleton(Modifier.fillMaxWidth().height(64.dp))
 * AppSkeletonRow()               // 头像圆 + 两行文字条的常用组合
 * ```
 *
 * reduceMotion 全局门控开启时退化为静态色块（无脉动）；形状默认主导 16dp 圆角，
 * 胶囊条用 [AppShapes.full]、圆形头像传 [CircleShape]。世界屏恒深夜区请继续用
 * WorldSceneColors 玻璃/暖纸体系自绘骨架（本组件走 AppTheme 双主题令牌）。
 */
@Composable
fun AppSkeleton(
    modifier: Modifier = Modifier,
    shape: Shape = AppShapes.medium,
    pulsing: Boolean = true,
) {
    val base = AppTheme.colors.surface.sunken
    val alpha: Float = if (pulsing && !rememberReduceMotion()) {
        val transition = rememberInfiniteTransition(label = "AppSkeletonPulse")
        val value by transition.animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 750, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "AppSkeletonAlpha",
        )
        value
    } else {
        0.8f
    }
    Box(modifier.background(color = base.copy(alpha = alpha), shape = shape))
}

/** 常用的「头像圆 + 标题条 + 副文条」骨架组合（整宽一行的列表占位）。 */
@Composable
fun AppSkeletonRow(
    modifier: Modifier = Modifier,
    avatarSize: Dp = 40.dp,
    pulsing: Boolean = true,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        AppSkeleton(Modifier.size(avatarSize), shape = CircleShape, pulsing = pulsing)
        Spacer(Modifier.size(AppSpacing.m))
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s)) {
            AppSkeleton(
                Modifier.size(width = 132.dp, height = 12.dp),
                shape = AppShapes.full,
                pulsing = pulsing,
            )
            AppSkeleton(
                Modifier.size(width = 88.dp, height = 12.dp),
                shape = AppShapes.full,
                pulsing = pulsing,
            )
        }
    }
}
