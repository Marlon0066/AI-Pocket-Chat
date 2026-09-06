package com.situ.aichat.ui.liuli.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.delay

/**
 * 表情回应的**纯瞬态**状态（图纸 2026-09-05 卷二B A-8 · 契约 §5.3「不入库、不进上下文」）：
 * 住 `LiuliChatSession` 的普通 `remember`——重建即清、转屏即散；绝不落库、绝不计数、绝不进提示词。
 *
 * [LiuliReactionBurstTarget.token] 每次 [play] 自增：同一条泡上重复双击 = 徽章从头再弹一次，
 * 而不是叠出第二枚。
 */
@Stable
internal class LiuliReactionState {
    var burst by mutableStateOf<LiuliReactionBurstTarget?>(null)
        private set

    private var token by mutableIntStateOf(0)

    fun play(messageUuid: String, emoji: String) {
        token++
        burst = LiuliReactionBurstTarget(messageUuid, emoji, token)
    }
}

/** 一次回应爆点（哪条泡、什么表情、第几次）。 */
internal data class LiuliReactionBurstTarget(val messageUuid: String, val emoji: String, val token: Int)

/**
 * 双击 / 菜单表情回应的徽章 + 四颗小心（图纸 §4.6 · 对版稿 D 节）：徽章画在**气泡外**的右下角
 * （所以不被泡的 `clip` 裁掉），弹出 → 驻留 1400ms → 缩回；四颗小心自徽章处错峰上飘。
 *
 * 调用方给的 [modifier] 应是 `Modifier.matchParentSize()`——本件寄生在气泡那一格上，既拿到泡的边界
 * 用来定位，又完全不参与父 Box 的定尺（否则短泡会被撑宽）。对读屏隐形（纯装饰，回应不进任何记录）。
 */
@Composable
internal fun LiuliReactionBurst(
    burst: LiuliReactionBurstTarget?,
    messageUuid: String,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val active = burst?.takeIf { it.messageUuid == messageUuid } ?: return
    val colors = AppTheme.colors
    val dark = LocalIsDarkTheme.current
    val haptics = LocalAppHaptics.current

    // token 进 key：同一条泡再点一次 = 整套从头再来。
    val scale = remember(active.token) { Animatable(0f) }
    var visible by remember(active.token) { mutableStateOf(true) }
    LaunchedEffect(active.token) {
        haptics.light()
        if (reduceMotion) scale.snapTo(1f) else scale.animateTo(1f, AppMotion.likeBounceSpring())
        delay(BADGE_HOLD_MS)
        if (!reduceMotion) scale.animateTo(0f, tween(BADGE_EXIT_MS, easing = AppMotion.EaseInOut))
        visible = false
    }
    if (!visible) return

    Box(modifier = modifier.zIndex(1f).clearAndSetSemantics {}) {
        // BottomEnd 对齐 = 徽章右下角贴泡右下角；再外扩 6 / 10 ⇒ 盒左上角 = (泡右 − 20, 泡底 − 16)。
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = BADGE_OVERHANG_X, y = BADGE_OVERHANG_Y)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                .then(if (dark) Modifier else Modifier.shadow(BADGE_SHADOW, CircleShape, clip = false))
                .size(LiuliChatGeometry.reactionBadge)
                .clip(CircleShape)
                .background(if (dark) colors.bubble.ai else colors.surface.raised)
                .border(BADGE_HAIRLINE, colors.surface.stroke, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(active.emoji, style = AppTypography.body.copy(fontSize = BADGE_EMOJI_SIZE))
        }
        // 减弱动画：只留徽章，不放小心（飘散是纯装饰的空间动画）。
        if (!reduceMotion) LiuliFloatingHearts(token = active.token, emoji = active.emoji)
    }
}

/** 四颗小心自徽章处错峰上飘（图纸 §3.2 回应徽章一节·x / y / 错峰三组落值逐个照表）。 */
@Composable
private fun BoxScope.LiuliFloatingHearts(token: Int, emoji: String) {
    repeat(HEART_COUNT) { i ->
        val rise = remember(token, i) { Animatable(0f) }
        LaunchedEffect(token, i) {
            delay(HEART_STAGGER_MS[i])
            rise.animateTo(1f, tween(HEART_FLY_MS, easing = AppMotion.EaseOut))
        }
        val (dx, dy) = liuliHeartOffsets(i)
        Text(
            emoji,
            style = AppTypography.body.copy(fontSize = HEART_EMOJI_SIZE),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = BADGE_OVERHANG_X, y = BADGE_OVERHANG_Y)
                .graphicsLayer {
                    translationX = dx.toPx()
                    translationY = -dy.toPx() * rise.value
                    alpha = 1f - rise.value
                },
        )
    }
}

/** 第 [index] 颗小心的 (横偏移, 上飘距离)（纯函数·图纸 §3.2 落表）。越界索引钳回表内，绝不崩。 */
internal fun liuliHeartOffsets(index: Int): Pair<Dp, Dp> {
    val i = index.coerceIn(0, HEART_COUNT - 1)
    return HEART_DX[i] to HEART_DY[i]
}

// 落值（图纸 §3.2 回应徽章一节 / §4.10 表·孤值即打回）。
/**
 * 徽章相对泡右下角的外扩量（复核 R1 🔴-2 改定·用户可反悔）：对版稿 `right:-6` 换算成盒子会让 26dp 徽章有 20dp
 * 伸进泡内，正压在泡内右下的时间戳上（实拍遮住末位约 7dp·零重叠 ⑯ 不过）。改为横向外扩 20 ⇒ 盒左缘 = 泡右 − 6，
 * 比泡的右内边距（[LiuliBubblePadEnd] 11）浅，时间戳右缘永远在徽章左缘之左；纵向 10 不变（戳在泡内，纵向不相干）。
 * `LiuliReactionBurstTest` 钉 `reactionBadge − BADGE_OVERHANG_X ≤ LiuliBubblePadEnd`。
 */
internal val BADGE_OVERHANG_X = 20.dp
private val BADGE_OVERHANG_Y = 10.dp
private const val BADGE_HOLD_MS = 1400L
private const val BADGE_EXIT_MS = 180
private val BADGE_SHADOW = 2.dp
private val BADGE_HAIRLINE = 0.5.dp
private val BADGE_EMOJI_SIZE = 14.sp
private const val HEART_COUNT = 4
private val HEART_DX = listOf((-14).dp, (-4).dp, 6.dp, 16.dp)
private val HEART_DY = listOf(52.dp, 44.dp, 60.dp, 48.dp)
private val HEART_STAGGER_MS = listOf(0L, 60L, 120L, 180L)
private const val HEART_FLY_MS = 700
private val HEART_EMOJI_SIZE = 12.sp
