package com.situ.aichat.ui.liuli.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import com.situ.aichat.tts.EmotionType
import com.situ.aichat.ui.chat.ChatWallpaper
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppColors
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.Palette
import java.time.LocalTime
import kotlin.math.max

/**
 * 琉璃聊天屏背景（图纸 2026-09-05 卷二A §4.1 · 契约 FABLE5_THEME_LIULI_PROPOSAL §5.4）。
 *
 * 无壁纸 → **心情四色**：角色当前情绪归到 5 个色族，四个色点按 [MOOD_SLOTS] 站位、用四个**径向渐变**画
 * （不用 `Modifier.blur`——API 29–30 无模糊也同样成立、零帧成本）；只在**族**变化时换色（2600ms EaseOut），
 * 每发送成功一条绕位轮转一步（gentle 弹簧）。有壁纸 → 画壁纸（照抄暖陶 `ChatScreen` 壁纸层）。
 * 见面态由调用方不渲染本件（舞台层铺满）。
 */

/** 心情色族（12 类 [EmotionType] 归并·图纸 §0 ② 4 落定）。 */
internal enum class LiuliMoodFamily { JOY, SHY, SAD, ANGER, CALM }

/** 四个色点在屏幕上的站位（占宽 / 高比例·图纸 §3.2 锁）。 */
internal val MOOD_SLOTS = listOf(
    Offset(0.18f, 0.12f),
    Offset(0.85f, 0.25f),
    Offset(0.25f, 0.85f),
    Offset(0.85f, 0.90f),
)

/** 径向半径 = 0.55 × max(宽, 高)（图纸 §3.2 锁）。 */
private const val MOOD_RADIUS_FRACTION = 0.55f

/** 换色时长（族变化才动·图纸 §3.2 锁）。 */
private const val MOOD_COLOR_MS = 2600

/** 昼档向白混合比 / 昼档 22:00–06:00 更沉一档 / 夜档向底混合比（图纸 §3.2 锁）。 */
private const val MIX_DAY = 0.62f
private const val MIX_DAY_NIGHT_HOURS = 0.70f
private const val MIX_DARK = 0.72f

/** 12 类情绪 → 5 色族（图纸 §0 ② 4 · 穷举无 else 兜底：新增情绪必须显式归族）。 */
internal fun liuliMoodFamily(emotion: EmotionType): LiuliMoodFamily = when (emotion) {
    EmotionType.HAPPY, EmotionType.EXCITED, EmotionType.PLAYFUL -> LiuliMoodFamily.JOY
    EmotionType.LOVE, EmotionType.SHY -> LiuliMoodFamily.SHY
    EmotionType.SAD, EmotionType.SIGH -> LiuliMoodFamily.SAD
    EmotionType.ANGRY, EmotionType.SCARED -> LiuliMoodFamily.ANGER
    EmotionType.THINKING, EmotionType.SHOCKED, EmotionType.NEUTRAL -> LiuliMoodFamily.CALM
}

/**
 * 四色派生（图纸 §0 ② 4 公式）：`[e, lerp(e, accent.container, .5), calm, lerp(e, surface.base, .5)]`，
 * 再按档位向底混——昼 `lerp(c, White, 0.62)`（22:00–06:00 升到 0.70 更沉）、夜 `lerp(c, surface.base, 0.72)`。
 * [hour] = 24 小时制当前小时。纯函数（T1-2）。
 */
internal fun liuliMoodBlobColors(family: LiuliMoodFamily, colors: AppColors, hour: Int): List<Color> {
    val emotion = colors.emotion
    val e = when (family) {
        LiuliMoodFamily.JOY -> emotion.joy
        LiuliMoodFamily.SHY -> emotion.shy
        LiuliMoodFamily.SAD -> emotion.sad
        LiuliMoodFamily.ANGER -> emotion.anger
        LiuliMoodFamily.CALM -> emotion.calm
    }
    val raw = listOf(
        e,
        lerp(e, colors.accent.container, 0.5f),
        emotion.calm,
        lerp(e, colors.surface.base, 0.5f),
    )
    return raw.map { c ->
        if (colors.isDark) {
            lerp(c, colors.surface.base, MIX_DARK)
        } else {
            lerp(c, Palette.White, if (isLateHour(hour)) MIX_DAY_NIGHT_HOURS else MIX_DAY)
        }
    }
}

/** 昼档「更沉」时段：22:00–06:00（含 22 点，不含 6 点）。 */
internal fun isLateHour(hour: Int): Boolean = hour >= 22 || hour < 6

/**
 * 换色动效档（图纸 §4.1）：族变化 2600ms EaseOut；[reduceMotion] 直落终值。抽成纯函数便于 T2-6 钉住
 * 「RM 下目标即终值」与 2600 / EaseOut 落值（不必靠截屏取色）。
 */
internal fun liuliMoodColorSpec(reduceMotion: Boolean): AnimationSpec<Color> =
    if (reduceMotion) snap() else tween(MOOD_COLOR_MS, easing = AppMotion.EaseOut)

/** 绕位动效档（图纸 §4.1）：gentle 弹簧；[reduceMotion] 直落。 */
internal fun liuliMoodSlotSpec(reduceMotion: Boolean): FiniteAnimationSpec<Offset> =
    if (reduceMotion) snap() else AppMotion.gentleSpring()

/** 第 [index] 个色点在第 [sendTurn] 轮的站位（每发送成功一条整体绕位一步·纯函数）。 */
internal fun liuliMoodSlot(index: Int, sendTurn: Int): Offset =
    MOOD_SLOTS[((index + sendTurn) % MOOD_SLOTS.size + MOOD_SLOTS.size) % MOOD_SLOTS.size]

/**
 * 绕位相位（图纸 §0 ② 4 / E14「RM 不轮转」·复核 R1 🟡-4）：减弱动画时相位恒 0——四点站原位不动，
 * 发送再多也不「瞬跳」一格（瞬跳正是 RM 用户要避开的突变）。纯函数（T2-6）。
 */
internal fun liuliMoodSlotTurn(sendTurn: Int, reduceMotion: Boolean): Int = if (reduceMotion) 0 else sendTurn

@Composable
internal fun LiuliChatBackground(
    moodEmoji: String,
    sendTurn: Int,
    wallpaper: ChatWallpaper?,
    peeked: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    if (wallpaper != null) {
        // 照抄暖陶壁纸层（ChatScreen.kt:535-550）：暖 peek 命中即第一帧就在；冷加载柔和淡入一次。
        val wallpaperAlpha = remember { Animatable(if (peeked) 1f else 0f) }
        LaunchedEffect(Unit) { wallpaperAlpha.animateTo(1f, tween(AppMotion.SMOOTH_MS, easing = AppMotion.EaseOut)) }
        Image(
            wallpaper.sharp,
            contentDescription = null,
            modifier = modifier
                .fillMaxSize()
                .background(colors.surface.base)
                .graphicsLayer { alpha = wallpaperAlpha.value },
            contentScale = ContentScale.Crop,
        )
        return
    }

    val reduceMotion = rememberReduceMotion()
    val family = remember(moodEmoji) { liuliMoodFamily(EmotionType.from(moodEmoji)) }
    // 时段只在进屏时定一次（跨午夜不即时翻档·与日期胶囊「今天」冻结同口径）。
    val hour = remember { LocalTime.now().hour }
    val targets = liuliMoodBlobColors(family, colors, hour)
    val colorSpec = liuliMoodColorSpec(reduceMotion)
    val offsetSpec = liuliMoodSlotSpec(reduceMotion)
    val animatedColors: List<State<Color>> = targets.mapIndexed { i, target ->
        animateColorAsState(targetValue = target, animationSpec = colorSpec, label = "moodBlob$i")
    }
    val slotTurn = liuliMoodSlotTurn(sendTurn, reduceMotion)
    val animatedSlots: List<State<Offset>> = targets.indices.map { i ->
        animateOffsetAsState(targetValue = liuliMoodSlot(i, slotTurn), animationSpec = offsetSpec, label = "moodSlot$i")
    }
    val base = colors.surface.base
    Canvas(modifier.fillMaxSize()) {
        drawRect(base)
        val radius = MOOD_RADIUS_FRACTION * max(size.width, size.height)
        animatedColors.forEachIndexed { i, colorState ->
            val slot = animatedSlots[i].value
            val center = Offset(slot.x * size.width, slot.y * size.height)
            val c = colorState.value
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(c, c.copy(alpha = 0f)),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
    }
}
