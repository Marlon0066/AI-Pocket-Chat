package com.situ.aichat.ui.voicecall

import android.graphics.Bitmap
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.voice.CallState

/**
 * 120 dp 圆形角色头像 + 陶土暖光环（FABLE5_VOICE_CALL_REDESIGN_PROPOSAL.md D-2「一环」）。光环三态：
 *  - 在听/说话中（用户侧）= 静环（低透明度常亮）；
 *  - 思考（PROCESSING）= 缓慢明暗呼吸（O-3 拍板·RM 时静止在中间亮度）；
 *  - TA 说话（AI_SPEAKING）= 随语音电平呼吸的暖辉（数据驱动·RM 保留=电平计信息）。
 * 拨号态微缩 + 变暗，接通回弹（scale 动画在 Screen 层由状态自然驱动）。无头像回退 person glyph。
 */
@Composable
fun VoiceCallAvatar(
    avatar: Bitmap?,
    state: CallState,
    audioLevel: Float,
    modifier: Modifier = Modifier,
) {
    val isSpeaking = state == CallState.AI_SPEAKING
    val isThinking = state == CallState.PROCESSING
    val isDialing = state == CallState.DIALING

    // 拨号头像缩小被 RM 门控（沿用既定惯例）；变暗不门控。
    val reduceMotion = rememberReduceMotion()
    val scale by animateFloatAsState(if (isDialing && !reduceMotion) 0.85f else 1f, label = "avatarScale")
    val avatarAlpha by animateFloatAsState(if (isDialing) 0.7f else 1f, label = "avatarAlpha")

    // 思考呼吸：0.22 ↔ 0.42 缓慢明暗（O-3）；RM 静止在中点 0.32。
    val thinkingAlpha = if (isThinking && !reduceMotion) {
        val transition = rememberInfiniteTransition(label = "thinkBreath")
        transition.animateFloat(
            initialValue = 0.22f,
            targetValue = 0.42f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_600),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "thinkBreath-alpha",
        ).value
    } else 0.32f

    // 目标光环：说话=电平驱动 0.30–0.62（数据驱动不受 RM 门控）；思考=呼吸；其余=静环 0.24。
    val targetGlowAlpha = when {
        isSpeaking -> 0.30f + 0.32f * audioLevel.coerceIn(0f, 1f)
        isThinking -> thinkingAlpha
        else -> 0.24f
    }
    val glowAlpha by animateFloatAsState(targetGlowAlpha, label = "avatarGlowAlpha")
    val glowSize by animateDpAsState(if (isSpeaking) 196.dp else 168.dp, label = "avatarGlowSize")

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // 陶土暖光环（原蓝色辉光换装·D-2）。
        Box(
            modifier = Modifier
                .size(glowSize)
                .blur(34.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            VoiceCallPalette.glow.copy(alpha = glowAlpha),
                            androidx.compose.ui.graphics.Color.Transparent,
                        ),
                    ),
                    shape = CircleShape,
                ),
        )

        Box(modifier = Modifier.size(120.dp).scale(scale).alpha(avatarAlpha)) {
            Box(modifier = Modifier.fillMaxSize().clip(CircleShape)) {
                if (avatar != null) {
                    Image(
                        bitmap = avatar.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .background(VoiceCallPalette.warmWhite.copy(alpha = 0.08f)),
                    ) {
                        Icon(
                            imageVector = AppActionIcons.Person,
                            contentDescription = null,
                            tint = VoiceCallPalette.warmWhite.copy(alpha = 0.36f),
                            modifier = Modifier.fillMaxSize().padding(18.dp),
                        )
                    }
                }
            }
            // Hairline ring on top so it isn't clipped away.
            Box(
                modifier = Modifier.fillMaxSize()
                    .border(1.5.dp, VoiceCallPalette.warmWhite.copy(alpha = 0.18f), CircleShape),
            )
        }
    }
}
