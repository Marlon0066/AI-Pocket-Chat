package com.situ.aichat.ui.diary

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.prompt.diary.DiaryExchangeService
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.appCardSurface

/** 信封位 UI 态（VM 包装：服务态 + 拆信中 + 失败一次性提示）。 */
data class DiaryExchangeUiState(
    val state: DiaryExchangeService.State = DiaryExchangeService.State.Hidden,
    val unlocking: Boolean = false,
    val failed: Boolean = false,
)

/**
 * 交换日记信封位（方向 C · 火漆折角信封）：状态驱动四形态——没聊过 / 去写日记 / 拆开信 / 失败重试。
 * 信生成后本位隐藏，信笺卡在时间线自然展示。Canvas 自绘 V 型信封折痕 + 左侧凸起火漆印章（待拆态微弱呼吸，
 * 遵守 reduceMotion），暖陶 / 琉璃通过 [surface] 材质外给共用。
 */
@Composable
internal fun DiaryExchangeSlot(
    ui: DiaryExchangeUiState,
    onCompose: () -> Unit,
    onUnlock: () -> Unit,
    surface: Modifier = Modifier.appCardSurface(cornerRadius = 18.dp),
) {
    val state = ui.state
    if (state is DiaryExchangeService.State.Hidden || state is DiaryExchangeService.State.Unlocked) return
    val colors = AppTheme.colors
    val dashColor = colors.accent.primary.copy(alpha = 0.38f)
    val flapLineColor = colors.accent.primary.copy(alpha = if (colors.isDark) 0.16f else 0.18f)
    val flapTint = colors.accent.container.copy(alpha = if (colors.isDark) 0.10f else 0.22f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenGutter, vertical = 8.dp)
            .then(surface)
            .drawBehind {
                // 顶部 V 型信封封口折角（轻透三角形铺底 + 极细折痕）
                val flapDepth = (size.height * 0.42f).coerceAtMost(38.dp.toPx())
                val flapPath = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width / 2f, flapDepth)
                    lineTo(size.width, 0f)
                    close()
                }
                drawPath(flapPath, color = flapTint)
                drawLine(
                    color = flapLineColor,
                    start = Offset(10.dp.toPx(), 1.dp.toPx()),
                    end = Offset(size.width / 2f, flapDepth),
                    strokeWidth = 0.8.dp.toPx(),
                )
                drawLine(
                    color = flapLineColor,
                    start = Offset(size.width - 10.dp.toPx(), 1.dp.toPx()),
                    end = Offset(size.width / 2f, flapDepth),
                    strokeWidth = 0.8.dp.toPx(),
                )
                drawRoundRect(
                    color = dashColor,
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
                    ),
                    cornerRadius = CornerRadius(18.dp.toPx()),
                )
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        when (state) {
            is DiaryExchangeService.State.NoChatToday -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(colors.surface.sunken),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Email,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = colors.text.secondary,
                        )
                    }
                    Text(
                        stringResource(R.string.diary_exchange_slot_no_chat),
                        style = AppTheme.typography.secondary,
                        color = colors.text.secondary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            is DiaryExchangeService.State.NeedPublish -> {
                ExchangeEnvelopeRow(
                    characterName = state.characterName,
                    locked = true,
                    pulsing = false,
                    kicker = stringResource(R.string.diary_exchange_kicker_need_write),
                    headline = stringResource(R.string.diary_exchange_slot_unsealed, state.characterName),
                    subtitle = stringResource(R.string.diary_exchange_slot_need_publish),
                    subtitleColor = colors.text.secondary,
                    ctaLabel = stringResource(R.string.diary_exchange_slot_cta_write),
                    onCtaClick = onCompose,
                )
            }
            is DiaryExchangeService.State.ReadyToUnlock -> {
                ExchangeEnvelopeRow(
                    characterName = state.characterName,
                    locked = false,
                    pulsing = !ui.unlocking,
                    kicker = stringResource(R.string.diary_exchange_kicker_sealed),
                    headline = stringResource(R.string.diary_exchange_slot_unsealed, state.characterName),
                    subtitle = when {
                        ui.unlocking -> stringResource(R.string.diary_exchange_slot_loading, state.characterName)
                        ui.failed -> stringResource(R.string.diary_exchange_slot_failed)
                        else -> stringResource(R.string.diary_exchange_slot_ready)
                    },
                    subtitleColor = if (ui.failed && !ui.unlocking) colors.status.onError else colors.text.secondary,
                    ctaLabel = if (ui.unlocking) null else stringResource(R.string.diary_exchange_slot_cta_open),
                    onCtaClick = onUnlock,
                )
            }
        }
    }
}

@Composable
private fun ExchangeEnvelopeRow(
    characterName: String,
    locked: Boolean,
    pulsing: Boolean,
    kicker: String,
    headline: String,
    subtitle: String,
    subtitleColor: Color,
    ctaLabel: String?,
    onCtaClick: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WaxSealMedallion(characterName = characterName, locked = locked, pulsing = pulsing)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                kicker,
                style = AppTheme.typography.caption,
                color = colors.accent.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                headline,
                style = AppTheme.typography.label,
                color = colors.text.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = AppTheme.typography.caption,
                color = subtitleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (ctaLabel != null) {
            ExchangePill(ctaLabel, onCtaClick)
        }
    }
}

/** 左侧凸起火漆圆章：待拆态（渐变暖陶 + 角色末字楷体 + 极克制呼吸）；待解锁态（柔和锁印）。 */
@Composable
private fun WaxSealMedallion(
    characterName: String,
    locked: Boolean,
    pulsing: Boolean,
) {
    val colors = AppTheme.colors
    val reduceMotion = rememberReduceMotion()
    val scale = if (pulsing && !reduceMotion) {
        val transition = rememberInfiniteTransition(label = "waxSealPulse")
        val anim by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.055f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "waxSealScale",
        )
        anim
    } else {
        1f
    }
    val sealBrush = if (locked) {
        Brush.radialGradient(
            listOf(colors.accent.container, colors.accent.primary.copy(alpha = 0.28f)),
        )
    } else {
        Brush.radialGradient(
            listOf(colors.accent.gradientStart, colors.accent.gradientEnd),
        )
    }
    val glyph = characterName.trim().lastOrNull()?.toString().orEmpty()
    Box(
        modifier = Modifier
            .size(46.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(sealBrush)
            .border(
                width = 1.dp,
                color = if (locked) colors.accent.primary.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.35f),
                shape = CircleShape,
            )
            .padding(3.5.dp)
            .border(
                width = 0.8.dp,
                color = if (locked) colors.accent.primary.copy(alpha = 0.24f) else colors.text.onAccent.copy(alpha = 0.42f),
                shape = CircleShape,
            )
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        if (locked) {
            Icon(
                AppActionIcons.Lock,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = colors.accent.text,
            )
        } else if (glyph.isNotEmpty()) {
            Text(
                text = glyph,
                style = AppTheme.typography.kaiQuote.copy(fontSize = 16.sp, lineHeight = 18.sp),
                color = colors.text.onAccent,
            )
        } else {
            Icon(
                Icons.Filled.Email,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = colors.text.onAccent,
            )
        }
    }
}

/** 陶土渐变小胶囊 CTA（与「写一笔」同族·信封位内层）。 */
@Composable
private fun ExchangePill(label: String, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    Text(
        label,
        style = AppTheme.typography.label,
        color = colors.text.onAccent,
        modifier = Modifier
            .clip(AppTheme.shapes.full)
            .background(Brush.linearGradient(listOf(colors.accent.gradientStart, colors.accent.gradientEnd)))
            .clickable(onClickLabel = label) { haptics.light(); onClick() }
            .heightIn(min = 36.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * 拆信揭晓入场（celebrate ζ0.5·全案唯一 celebrate 位·每屏一处·契约 §1.1 S5）：信笺卡首次出现时
 * 0.92→1 弹性放大。仅对「刚拆开的那封」生效一次（rememberSaveable 防重放）；reduceMotion → 直出。
 */
@Composable
internal fun Modifier.celebrateUnlockEntrance(enabled: Boolean): Modifier {
    if (!enabled) return this
    val reduceMotion = rememberReduceMotion()
    if (reduceMotion) return this
    var played by rememberSaveable { mutableStateOf(false) }
    val scale = remember { Animatable(if (played) 1f else 0.92f) }
    LaunchedEffect(Unit) {
        if (!played) {
            played = true
            scale.animateTo(1f, AppMotion.celebrateSpring())
        }
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
