package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.Palette
import com.situ.aichat.ui.liuli.page.liuliFootprint

/**
 * 发送圆钮：钴蓝对角渐变 + 顶沿 1px 迎光 + 4dp 彩影——与 `LiuliButtonStyle.Prominent` **同一配方同一落值**。
 * 有意不复用那个组件：它在 `onClick` 之前无条件发触觉，而发送的轻触觉必须**只在被受理时**响
 * （照抄暖陶 `if (viewModel.send(text)) { haptics.light() ... }`）。
 */
@Composable
internal fun LiuliSendButton(
    onClick: () -> Unit,
    /** 读屏标签：默认「发送」；语音草稿条那一枚照 F28 传「发送语音消息」（§9 ① 锁定 cd）。 */
    contentDescription: String = stringResource(R.string.a11y_send),
) {
    val colors = AppTheme.colors
    val gradientStart = colors.accent.gradientStart
    val gradientEnd = colors.accent.gradientEnd
    Box(
        modifier = Modifier
            // 触达框 48 不占版：布局脚印 44 与输入胶囊同高（复核 R1 🔴-2）。
            .liuliFootprint(LiuliChatGeometry.inputPieceSize)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(LiuliChatGeometry.inputPieceSize)
                .shadow(
                    elevation = SendShadowElevation,
                    shape = CircleShape,
                    clip = false,
                    ambientColor = gradientEnd.copy(alpha = SEND_SHADOW_ALPHA),
                    spotColor = gradientEnd.copy(alpha = SEND_SHADOW_ALPHA),
                )
                .clip(CircleShape)
                .drawWithCache {
                    val brush = Brush.linearGradient(
                        colors = listOf(gradientStart, gradientEnd),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    )
                    onDrawBehind {
                        drawRect(brush)
                        drawRect(color = Palette.White.copy(alpha = SEND_SPECULAR_ALPHA), size = size.copy(height = 1f))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = contentDescription,
                tint = Palette.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** 发送钮的 Prominent 落值（同 `LiuliButton` 的 Prominent 档·契约 §4.1）。 */
private val SendShadowElevation = 4.dp
private const val SEND_SHADOW_ALPHA = 0.35f
private const val SEND_SPECULAR_ALPHA = 0.35f
