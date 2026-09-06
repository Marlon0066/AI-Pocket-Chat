package com.situ.aichat.ui.liuli.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.chat.ChatWorldPill
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import com.situ.aichat.ui.world.WorldSceneColors

/**
 * 琉璃世界位置胶囊（图纸 2026-09-05 卷二A §4.3 · 契约 §5.1 Q1）：名片下方正中央的玻璃 pill，
 * 24dp 高、48dp 透明触达外框（**不占版**：`requiredHeight` 居中外溢，布局脚印恒 24dp——复核 R1 🔴-2：
 * 占版的 48 框会把胶囊推低 12dp、再把日期胶囊压进它的框里）；[pill] 为 null 或见面态 → 淡出收起
 * （退出动画期间用最后一次非空值渲染·照抄暖陶）。
 * 行为（点击跳世界地图落点 / 数据来源）与暖陶 `ChatWorldStatusRow` 逐字同——只换长相。
 */
@Composable
internal fun LiuliWorldPill(
    pill: ChatWorldPill?,
    offline: Boolean,
    onOpenWorldAt: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val dark = LocalIsDarkTheme.current
    val onGlass = LiuliTheme.onGlass
    val a11y = stringResource(R.string.world_chat_pill_a11y)
    var lastPill by remember { mutableStateOf(pill) }
    if (pill != null) lastPill = pill
    AnimatedVisibility(
        visible = pill != null && !offline,
        modifier = modifier,
        enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(AppMotion.SMOOTH_MS)),
        exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(AppMotion.SMOOTH_MS)),
    ) {
        val shown = lastPill ?: return@AnimatedVisibility
        Box(
            modifier = Modifier
                .height(LiuliChatGeometry.worldPillHeight)
                .requiredHeight(LiuliChatGeometry.touchTarget)
                .clickable(role = Role.Button) { onOpenWorldAt(shown.focusSpec) }
                .semantics { contentDescription = a11y },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .height(LiuliChatGeometry.worldPillHeight)
                    .liuliGlass(LiuliShapes.pill, dark = dark)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(WorldSceneColors.gold))
                Text(shown.emoji, style = AppTypography.caption)
                Text(
                    shown.text,
                    style = AppTypography.caption,
                    color = onGlass.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
