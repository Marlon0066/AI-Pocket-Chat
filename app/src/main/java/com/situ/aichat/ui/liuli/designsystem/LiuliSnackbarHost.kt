package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.glass.LiuliGlassStyle
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.liuliTouchHeight
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/** 正文 / 动作字号与字重（图纸 2026-09-06 卷五 A-4 ④）。 */
private val BODY_SIZE = 15.sp
private val ACTION_WEIGHT = FontWeight.W600
/** 正文 ↔ 动作的缝。 */
private val ACTION_GAP = 12.dp

/**
 * 琉璃 Snackbar 宿主（图纸 2026-09-06 卷五 A-4 ④）。
 *
 * **禁 M3 `SnackbarHost`**（§9 ⑤）：只借它的 [SnackbarHostState] 当**队列数据结构**（`showSnackbar` 的挂起、
 * 去重、时长由它管），长相全自画——一枚 Panel 档玻璃 pill 悬在底部：左右 [LiuliPageGeometry.gutter] ·
 * 距导航栏 [LiuliPageGeometry.snackbarBottom] · 高 ≥ [LiuliPageGeometry.snackbarMinHeight] ·
 * 内距 [LiuliPageGeometry.snackbarPadV] / [LiuliPageGeometry.snackbarPadH] · 正文 15 `onGlass.primary` ·
 * 动作 15/600 `accent.text`。进出 = [AppMotion.calmSpring] 从下滑入 + fade（[rememberReduceMotion] 时直切）。
 *
 * 退场那一帧 [SnackbarHostState.currentSnackbarData] 已经是 null，所以要留一份 [last] 给出场动画用——
 * 否则滑出的是一枚空 pill。
 */
@Composable
fun LiuliSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val onGlass = LiuliTheme.onGlass
    val dark = LocalIsDarkTheme.current
    val reduceMotion = rememberReduceMotion()
    val data = hostState.currentSnackbarData
    var last by remember { mutableStateOf<SnackbarData?>(null) }
    LaunchedEffect(data) { if (data != null) last = data }

    AnimatedVisibility(
        visible = data != null,
        modifier = modifier.fillMaxWidth(),
        enter = if (reduceMotion) {
            EnterTransition.None
        } else {
            slideInVertically(AppMotion.calmSpring()) { it } + fadeIn(AppMotion.calmSpring())
        },
        exit = if (reduceMotion) {
            ExitTransition.None
        } else {
            slideOutVertically(AppMotion.calmSpring()) { it } + fadeOut(AppMotion.calmSpring())
        },
    ) {
        val shown = data ?: last ?: return@AnimatedVisibility
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = LiuliPageGeometry.gutter, vertical = LiuliPageGeometry.snackbarBottom)
                .heightIn(min = LiuliPageGeometry.snackbarMinHeight)
                .liuliGlass(LiuliShapes.pill, dark = dark, style = LiuliGlassStyle.Panel)
                .padding(
                    horizontal = LiuliPageGeometry.snackbarPadH,
                    vertical = LiuliPageGeometry.snackbarPadV,
                )
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                shown.visuals.message,
                style = AppTypography.body.copy(fontSize = BODY_SIZE),
                color = onGlass.primary,
                modifier = Modifier.weight(1f),
            )
            val action = shown.visuals.actionLabel
            if (action != null) {
                Spacer(Modifier.width(ACTION_GAP))
                Text(
                    action,
                    style = AppTypography.body.copy(fontSize = BODY_SIZE, fontWeight = ACTION_WEIGHT),
                    color = colors.accent.text,
                    // 视觉链在前、点击面在后；四周是非点击内容，48 触达上下外溢不会压到别人。
                    modifier = Modifier
                        .liuliTouchHeight()
                        .clickable(role = Role.Button) { shown.performAction() },
                )
            }
        }
    }
}
