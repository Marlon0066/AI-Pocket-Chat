package com.situ.aichat.ui.liuli.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.chat.SwipeAction
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.liuliPressable
import com.situ.aichat.ui.liuli.glass.LiuliGlassStyle
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/** 圆钮视觉 44 / 图标 20 / 字距钮 4（对版稿·A-12）。 */
private val BUTTON = 44.dp
private val ICON = 20.dp
private val LABEL_TOP = 4.dp

/**
 * 琉璃的左滑动作面（图纸 2026-09-06 卷四 A-12·卷三B ②）。
 *
 * 76 宽的面**底是纸面**（`surface.base`·与行同色，滑开时像是「行让开露出下面的纸」），中央一枚 44 玻璃圆钮
 * （Button 档）+ 图标 + 一行 11 号小字。暖陶那边是整块实色面——两张脸的动作面长相不同，但手势 / 吸附 /
 * 触觉 / a11y `customActions` 全在 `SwipeActionsRow` 里共用一份机制（只经 add-only 的 `actionFace` 形参换脸）。
 *
 * **点击面 = 整个 76 宽的面**（与暖陶 `ActionButton` 同·复核 R1 🟡-5：只让圆钮可点会把小字与面的其余部分变成死区）；
 * 圆钮只是视觉（按压缩放跟着面的 interaction 走）。
 *
 * 图标色由调用方经 [SwipeAction.contentColor] 给（置顶 = `accent.text` / 删除 = `status.error`）；
 * [SwipeAction.containerColor] 在琉璃侧传 `Color.Transparent`——面底由本件自己画。
 */
@Composable
fun LiuliSwipeActionFace(action: SwipeAction, modifier: Modifier, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val dark = LocalIsDarkTheme.current
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .background(colors.surface.base)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = action.label,
                onClick = onClick,
            )
            .semantics { contentDescription = action.label },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .liuliPressable(interactionSource = interaction, enabled = true, brighten = true)
                .size(BUTTON)
                .liuliGlass(CircleShape, dark = dark, style = LiuliGlassStyle.Button),
            contentAlignment = Alignment.Center,
        ) {
            Icon(action.icon, contentDescription = null, tint = action.contentColor, modifier = Modifier.size(ICON))
        }
        Text(
            action.label,
            color = colors.text.secondary,
            style = AppTypography.caption,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = LABEL_TOP),
        )
    }
}
