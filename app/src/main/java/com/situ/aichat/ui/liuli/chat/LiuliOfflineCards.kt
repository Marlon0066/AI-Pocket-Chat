package com.situ.aichat.ui.liuli.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.data.model.OfflineInviteData
import com.situ.aichat.ui.chat.rememberBubbleMaxWidth
import com.situ.aichat.ui.chat.rememberOfflineDividerReveal
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.Palette
import com.situ.aichat.ui.liuli.designsystem.LiuliPalette
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.offline.offlineSceneTransitionEntry
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/**
 * 琉璃线下卡族（图纸 2026-09-05 卷二C §4.8 · A-4）：邀约卡 / 结束确认卡 = **恒暗卡**（`stageInk` 底 +
 * 楷体标题 + 顶部钴蓝微光·与见面剧场同源口径，不随浅色主题翻白），离场分隔线 = 两侧发丝 + 中间玻璃 pill。
 *
 * 文案、`responded` 三态、按钮语义与落成动画时序**逐字照抄**暖陶 `OfflineInviteCardBubble` /
 * `OfflineEndCardBubble` / `OfflineEndDivider`（F12）；落成时序直接借同一枚
 * [rememberOfflineDividerReveal]（500ms 揭线体 → 240ms 揭文案）与 [offlineSceneTransitionEntry]。
 */
@Composable
internal fun LiuliOfflineInviteCard(
    data: OfflineInviteData,
    characterName: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LiuliStageCard(modifier = modifier) {
        LiuliCardHeader(
            icon = Icons.Outlined.Place,
            title = "☕ ${characterName.ifBlank { FALLBACK_NAME }} 想和你一起",
            subtitle = null,
            titleColor = LiuliPalette.stageText,
            titleStyle = StageTitleStyle,
            iconBlockColor = Palette.White.copy(alpha = STAGE_ICON_BLOCK_ALPHA),
            iconColor = LiuliPalette.stageIcon,
        )
        LiuliCardBody {
            data.activity?.takeIf { it.isNotBlank() }?.let { LiuliStageBodyText(it) }
            data.location?.takeIf { it.isNotBlank() }?.let { LiuliStageBodyText("📍 $it") }
            data.invitation?.takeIf { it.isNotBlank() }?.let { LiuliStageBodyText("「$it」") }
            data.tensionHint?.takeIf { it.isNotBlank() }?.let { LiuliStageBodyText("✨ $it") }
            when (data.responded) {
                RESPONDED_ACCEPTED -> LiuliStageBodyText("已接受邀约")
                RESPONDED_DECLINED -> LiuliStageBodyText("已婉拒")
                else -> Unit
            }
        }
        if (data.responded != RESPONDED_ACCEPTED && data.responded != RESPONDED_DECLINED) {
            LiuliCardButtonRow {
                LiuliCardButton(text = "好呀", prominent = true, onClick = onAccept)
                LiuliCardButton(
                    text = "下次吧",
                    prominent = false,
                    onClick = onDecline,
                    softContainer = Palette.White.copy(alpha = STAGE_SOFT_ALPHA),
                    softContent = LiuliPalette.stageText,
                )
            }
        }
    }
}

/** 见面结束确认卡（**不直接退出**·照抄 F12）。 */
@Composable
internal fun LiuliOfflineEndCard(
    data: OfflineInviteData,
    onEndMeeting: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LiuliStageCard(modifier = modifier) {
        LiuliCardHeader(
            icon = Icons.Outlined.Schedule,
            title = "要结束这次见面吗？",
            subtitle = null,
            titleColor = LiuliPalette.stageText,
            titleStyle = StageTitleStyle,
            iconBlockColor = Palette.White.copy(alpha = STAGE_ICON_BLOCK_ALPHA),
            iconColor = LiuliPalette.stageIcon,
        )
        if (data.responded == RESPONDED_CONTINUED) {
            LiuliCardBody { LiuliStageBodyText("已继续见面") }
        } else {
            LiuliCardButtonRow {
                LiuliCardButton(text = "结束见面", prominent = true, onClick = onEndMeeting)
                LiuliCardButton(
                    text = "再待一会儿",
                    prominent = false,
                    onClick = onContinue,
                    softContainer = Palette.White.copy(alpha = STAGE_SOFT_ALPHA),
                    softContent = LiuliPalette.stageText,
                )
            }
        }
    }
}

/**
 * 离场分隔线（A-4）：两侧 0.5 发丝 + 中间一枚玻璃 pill 写「线下见面结束 · {时长}」，有 sessionId 时
 * 右缀「· 回顾」并整条可点。落成时序与暖陶同一枚 [rememberOfflineDividerReveal]。
 *
 * pill 走 `liuliGlass` = 内容层**自动退纯染色**（拿不到 `LocalBackdrop`），与卷二A 日期胶囊同判例。
 */
@Composable
internal fun LiuliOfflineEndDivider(
    durationText: String,
    onClick: (() -> Unit)? = null,
    entryAnimation: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val dark = LocalIsDarkTheme.current
    val animate = entryAnimation && !rememberReduceMotion()
    val (lineRevealed, captionRevealed) = rememberOfflineDividerReveal(animate)
    val captionAlpha by animateFloatAsState(
        targetValue = if (captionRevealed) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(CAPTION_FADE_MS, easing = AppMotion.EaseOut),
        label = "liuliOfflineEndDividerCaption",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = DIVIDER_SIDE, vertical = DIVIDER_VERTICAL),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DIVIDER_GAP),
    ) {
        LiuliDividerHair(Modifier.weight(1f), colors.surface.stroke)
        Box(
            modifier = when {
                !animate -> Modifier
                lineRevealed -> Modifier.offlineSceneTransitionEntry(reduceMotion = false)
                else -> Modifier.alpha(0f)
            }
                .clip(LiuliShapes.pill)
                .liuliGlass(LiuliShapes.pill, dark = dark)
                .padding(horizontal = PILL_SIDE, vertical = PILL_VERTICAL),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PILL_PART_GAP),
            ) {
                Text("线下见面结束 · $durationText", style = DividerTextStyle, color = colors.text.tertiary)
                if (onClick != null) {
                    Text(
                        "· 回顾",
                        style = DividerTextStyle,
                        color = colors.accent.text,
                        modifier = if (animate) Modifier.graphicsLayer { alpha = captionAlpha } else Modifier,
                    )
                }
            }
        }
        LiuliDividerHair(Modifier.weight(1f), colors.surface.stroke)
    }
}

@Composable
private fun LiuliDividerHair(modifier: Modifier, color: Color) {
    Box(modifier.height(HAIRLINE).background(color))
}

/**
 * 恒暗卡的壳（A-4 / §3.2 暗卡一节）：`stageInk` 底 + 白 8% 发丝 + 黑 25% 落影 + 顶部钴蓝椭圆微光
 * （CSS `radial-gradient(60% 40% at 70% 0%, …)` 逐字落地：横半径 0.6×宽、纵半径 0.4×高，70% 处收干净）。
 */
@Composable
private fun LiuliStageCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .width(liuliCardWidth(LiuliChatGeometry.cardWideWidth, rememberBubbleMaxWidth()))
            .shadow(
                elevation = STAGE_SHADOW,
                shape = LiuliShapes.medium,
                clip = false,
                ambientColor = Color.Black.copy(alpha = STAGE_SHADOW_ALPHA),
                spotColor = Color.Black.copy(alpha = STAGE_SHADOW_ALPHA),
            )
            .clip(LiuliShapes.medium)
            .background(LiuliPalette.stageInk)
            .drawWithCache {
                val cx = size.width * GLOW_CENTER_X
                val rx = size.width * GLOW_RADIUS_X
                val ry = size.height * GLOW_RADIUS_Y
                val brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to LiuliPalette.stageGlow.copy(alpha = STAGE_GLOW_ALPHA),
                        GLOW_STOP to Color.Transparent,
                        1f to Color.Transparent,
                    ),
                    center = Offset(cx, 0f),
                    radius = rx.coerceAtLeast(1f),
                )
                onDrawBehind {
                    // 椭圆 = 圆 + 纵向缩放（Compose 的 radialGradient 只有圆，缩放才是等价落地而非近似）。
                    withTransform({ scale(1f, (ry / rx.coerceAtLeast(1f)), pivot = Offset(cx, 0f)) }) {
                        drawCircle(brush = brush, radius = rx.coerceAtLeast(1f), center = Offset(cx, 0f))
                    }
                }
            }
            .then(
                Modifier.drawWithCache {
                    onDrawWithContent {
                        drawContent()
                        // 白 8% 发丝**环**（对版稿 `box-shadow: 0 0 0 .5px rgba(255,255,255,.08)`·复核 R1 🔵-6 由单顶线
                        // 改回整圈）：自绘而不用 border——叠序与 CSS 同（环在微光之上，8% 白盖不住微光）。
                        val stroke = STAGE_HAIRLINE.toPx()
                        drawRoundRect(
                            color = Palette.White.copy(alpha = STAGE_HAIR_ALPHA),
                            topLeft = Offset(stroke / 2f, stroke / 2f),
                            size = Size(size.width - stroke, size.height - stroke),
                            cornerRadius = CornerRadius(LiuliChatGeometry.cardCorner.toPx()),
                            style = Stroke(stroke),
                        )
                    }
                },
            ),
        content = content,
    )
}

/** 暗卡体内小字（`stageText` 72%·对版稿 `.card.dark .bd`）。 */
@Composable
private fun LiuliStageBodyText(text: String) {
    Text(
        text,
        style = AppTypography.secondary,
        color = LiuliPalette.stageText.copy(alpha = STAGE_BODY_ALPHA),
        modifier = Modifier.padding(bottom = STAGE_BODY_GAP),
    )
}

/** `OfflineInviteData.responded` 的三个取值（照抄暖陶 F12 的字面判据）。 */
private const val RESPONDED_ACCEPTED = "accepted"
private const val RESPONDED_DECLINED = "declined"
private const val RESPONDED_CONTINUED = "continued"

/** 角色名为空时的兜底称呼（照抄暖陶 F12 的 `"对方"`）。 */
private const val FALLBACK_NAME = "对方"

/** 落值（§3.2 暗卡一节 + A-4 + 对版稿 `.card.dark` / `.divline`·孤值即打回）。 */
private val StageTitleStyle = AppTypography.kaiBody.copy(
    fontSize = 16.sp,
    lineHeight = 20.sp,
    fontWeight = androidx.compose.ui.text.font.FontWeight(520),
    letterSpacing = 0.32.sp, // = 0.02em × 16sp
)
private val DividerTextStyle = AppTypography.settingsRowValue
private const val STAGE_ICON_BLOCK_ALPHA = 0.08f
private const val STAGE_SOFT_ALPHA = 0.10f
private const val STAGE_HAIR_ALPHA = 0.08f
private const val STAGE_SHADOW_ALPHA = 0.25f
private const val STAGE_BODY_ALPHA = 0.72f
private const val STAGE_GLOW_ALPHA = 0.22f
private const val GLOW_CENTER_X = 0.70f
private const val GLOW_RADIUS_X = 0.60f
private const val GLOW_RADIUS_Y = 0.40f
private const val GLOW_STOP = 0.70f
private const val CAPTION_FADE_MS = 200
private val STAGE_SHADOW = 6.dp
private val STAGE_HAIRLINE = 0.5.dp
private val STAGE_BODY_GAP = 4.dp
private val HAIRLINE = 0.5.dp
private val DIVIDER_SIDE = 6.dp
private val DIVIDER_VERTICAL = 8.dp
private val DIVIDER_GAP = 10.dp
private val PILL_SIDE = 10.dp
private val PILL_VERTICAL = 2.dp
private val PILL_PART_GAP = 4.dp
