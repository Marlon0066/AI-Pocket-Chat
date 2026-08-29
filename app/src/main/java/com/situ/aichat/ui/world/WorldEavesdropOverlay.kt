package com.situ.aichat.ui.world

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.world.WorldSceneColors.EavesBubbleShadow
import com.situ.aichat.ui.world.WorldSceneColors.EavesChipGlass
import com.situ.aichat.ui.world.WorldSceneColors.EavesDot
import com.situ.aichat.ui.world.WorldSceneColors.EavesWhisperGlass

/**
 * 偷听 overlay（W12 图纸 §4.6·quickchat demo:L27-29/L150-176 逐值）：邀请 chip / 双气泡 / whisper 落账条。
 * 位置由 [InteriorSceneView] 投影后经 modifier 定位（同 pcard 家族）；颜色已收编 [WorldSceneColors] 单源
 * （§4.6 精确值不变）。旁观动效：气泡入场 260ms 过冲·同人新句旧泡 600ms 渐隐（[exiting]）。
 */

private val EavesBubbleEnter = CubicBezierEasing(0.3f, 1.3f, 0.4f, 1f)

/** 偷听气泡态（同一人新句 → 旧泡置 [exiting] 渐隐退场·绝不叠罗汉·§4.6）。 */
internal data class EavesBubbleState(val id: Int, val speaker: String, val text: String, val exiting: Boolean)

/**
 * 邀请 chip（§4.6）：玻璃 r-full padding 6×12dp·11sp onGlass·前缀三点 4dp #EAD9BE（同打字节奏 1200ms/逐点 180ms）·
 * 可点 + 48dp 触达 + contentDescription。[reduceMotion] → 三点静态 60% alpha。
 */
@Composable
internal fun EavesInviteChip(text: String, reduceMotion: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickableScale(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.clip(AppShapes.full).background(EavesChipGlass).padding(horizontal = 12.dp, vertical = 6.dp).semantics { contentDescription = text },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            EavesTypingDots(reduceMotion)
            Text(text, color = WorldSceneColors.onGlass, fontSize = 11.sp, modifier = Modifier.padding(start = 3.dp))
        }
    }
}

/** 三点（4dp·1200ms 循环·逐点 180ms·上跳 4dp·§4.6 = 快聊打字点节奏）·reduce = 静态 60%。 */
@Composable
private fun EavesTypingDots(reduceMotion: Boolean) {
    if (reduceMotion) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(3) { Box(Modifier.size(4.dp).alpha(0.6f).clip(CircleShape).background(EavesDot)) }
        }
        return
    }
    val t = rememberInfiniteTransition(label = "eavesDots")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { i ->
            val f by t.animateFloat(
                0f, 1f,
                infiniteRepeatable(tween(1200), RepeatMode.Reverse, StartOffset(i * 180)),
                label = "dot$i",
            )
            Box(Modifier.size(4.dp).graphicsLayer { translationY = -4.dp.toPx() * f; alpha = 0.45f + 0.55f * f }.clip(CircleShape).background(EavesDot))
        }
    }
}

/**
 * 偷听气泡（§4.6）：暖纸 r13dp·padding 7×11dp·12sp #2E2925 行高 1.55·maxWidth 190dp·边 1.5dp·投影·底部 45° 尾巴。
 * 入场 260ms cubic(0.3,1.3,0.4,1) scale0.9→1 + fade；[exiting] → 600ms 渐隐。[reduceMotion] → 纯 fade 120ms。
 * 锚定 = 底部中心（tail 朝下指向说话者卡·caller offset 到卡顶上方）。
 */
@Composable
internal fun EavesBubble(text: String, exiting: Boolean, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val appear = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduceMotion) appear.snapTo(1f) else appear.animateTo(1f, tween(260, easing = EavesBubbleEnter))
    }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(exiting) { if (exiting) fade.animateTo(0f, tween(if (reduceMotion) 120 else 600)) }
    val scale = if (reduceMotion) 1f else 0.9f + 0.1f * appear.value

    Box(
        modifier.graphicsLayer {
            alpha = appear.value * fade.value
            scaleX = scale; scaleY = scale
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f) // 从底部中心生长（尾巴锚点）
        },
        contentAlignment = Alignment.BottomCenter,
    ) {
        // 底部 45° 尾巴（8dp·bottom -6dp·继承底色描边·demo:L155-157）。
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .offset(y = 6.dp)
                .size(8.dp)
                .rotate(45f)
                .background(WorldSceneColors.pcardPaperBottom)
                .border(1.5.dp, WorldSceneColors.cardStroke),
        )
        Text(
            text,
            color = WorldSceneColors.sheetTitle,
            fontSize = 12.sp,
            lineHeight = 18.6.sp, // 12sp × 1.55
            modifier = Modifier
                .widthIn(max = 190.dp)
                .shadow(8.dp, RoundedCornerShape(13.dp), spotColor = EavesBubbleShadow, ambientColor = EavesBubbleShadow)
                .clip(RoundedCornerShape(13.dp))
                .background(Brush.verticalGradient(listOf(WorldSceneColors.pcardPaperTop, WorldSceneColors.pcardPaperBottom)))
                .border(1.5.dp, WorldSceneColors.cardStroke, RoundedCornerShape(13.dp))
                .padding(horizontal = 11.dp, vertical = 7.dp),
        )
    }
}

/**
 * 偷听落账 whisper（§4.6）：玻璃 r-full padding 8×16dp·11.5sp onGlass·摘要段 w600 #EAD9BE（caller 以 [AnnotatedString] 传）·
 * 淡入淡出 500ms/驻留 3400ms 由 caller 控（AnimatedVisibility）。底部居中定位由 caller。
 */
@Composable
internal fun EavesWhisper(text: AnnotatedString, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(AppShapes.full).background(EavesWhisperGlass).padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = WorldSceneColors.onGlass, fontSize = 11.5.sp)
    }
}

/** whisper 摘要段样式（w600 #EAD9BE·§4.6·caller 构 AnnotatedString 用）。 */
internal val eavesWhisperEmphasis = androidx.compose.ui.text.SpanStyle(color = EavesDot, fontWeight = FontWeight.SemiBold)
