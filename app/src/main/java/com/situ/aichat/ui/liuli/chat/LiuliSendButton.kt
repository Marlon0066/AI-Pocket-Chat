package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import com.situ.aichat.ui.designsystem.AppActionIcons
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.Palette
import com.situ.aichat.ui.liuli.designsystem.liuliAccentFill
import com.situ.aichat.ui.liuli.page.liuliFootprint

/**
 * 发送圆钮：主色渐变实底 [liuliAccentFill]（三段 135° + 顶沿 1px 迎光 + 6dp 彩影·卷三 §4.4）——与
 * `LiuliButtonStyle.Prominent` **同一个修饰符**。
 * 有意不复用那个组件：它在 `onClick` 之前无条件发触觉，而发送的轻触觉必须**只在被受理时**响
 * （照抄暖陶 `if (viewModel.send(text)) { haptics.light() ... }`）。
 */
@Composable
internal fun LiuliSendButton(
    onClick: () -> Unit,
    /** 读屏标签：默认「发送」；语音草稿条那一枚照 F28 传「发送语音消息」（§9 ① 锁定 cd）。 */
    contentDescription: String = stringResource(R.string.a11y_send),
) {
    Box(
        modifier = Modifier
            // 触达框 48 不占版：布局脚印 44 与输入胶囊同高（复核 R1 🔴-2）。
            .liuliFootprint(LiuliChatGeometry.inputPieceSize)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(LiuliChatGeometry.inputPieceSize).liuliAccentFill(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppActionIcons.Send,
                contentDescription = contentDescription,
                tint = Palette.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
