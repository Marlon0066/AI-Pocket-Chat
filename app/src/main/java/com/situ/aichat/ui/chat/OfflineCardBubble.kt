package com.situ.aichat.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.model.OfflineInviteData
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.offline.OfflineTheater
import com.situ.aichat.ui.offline.offlineSceneTransitionEntry
import kotlinx.coroutines.delay

/**
 * 线下见面邀约卡气泡（10.2c-3c，1:1 iOS OfflineInviteCardView）：☕活动 + 📍地点 + 「邀约台词」+ ✨暗示 +
 * 好呀/下次吧。[data].responded 非空（accepted/declined）→ 按钮变状态文案（置灰，不可再点）。
 */
@Composable
fun OfflineInviteCardBubble(
    data: OfflineInviteData,
    characterName: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    // Fable-5：teal → 暖陶卡（raised 暖白 + 16dp + 发丝描边·标题=陶土玫功能深档），与礼物/日历卡同壳口径。
    Card(
        modifier = Modifier.widthIn(max = 280.dp),
        shape = AppShapes.medium,
        colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface.raised),
        border = BorderStroke(1.dp, AppTheme.colors.surface.stroke),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // offline-3：标题行带角色名「☕ {角色名} 想和你一起」(1:1 iOS OfflineInviteCard «%@ 想和你一起»)，活动单独成行。
            Text(
                "☕ ${characterName.ifBlank { "对方" }} 想和你一起",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.colors.accent.text,
            )
            data.activity?.takeIf { it.isNotBlank() }?.let { activity ->
                Text(activity, style = MaterialTheme.typography.bodyMedium)
            }
            if (!data.location.isNullOrBlank()) {
                Text("📍 ${data.location}", style = MaterialTheme.typography.bodyMedium)
            }
            if (!data.invitation.isNullOrBlank()) {
                Text(
                    "「${data.invitation}」",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!data.tensionHint.isNullOrBlank()) {
                Text(
                    "✨ ${data.tensionHint}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (data.responded) {
                "accepted" -> RespondedLabel("已接受邀约")
                "declined" -> RespondedLabel("已婉拒")
                else -> Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AppButton(onClick = onAccept, modifier = Modifier.weight(1f), style = AppButtonStyle.Primary) { Text("好呀") }
                    AppButton(onClick = onDecline, modifier = Modifier.weight(1f), style = AppButtonStyle.Tonal) { Text("下次吧") }
                }
            }
        }
    }
}

/**
 * 线下见面结束确认卡（10.2c-3c，1:1 iOS OfflineEndCardView，**不直接退出**）：「要结束这次见面吗？」+
 * 结束见面/再待一会儿。[data].responded == "continued" → 显示「已继续」状态。
 */
@Composable
fun OfflineEndCardBubble(
    data: OfflineInviteData,
    onEndMeeting: () -> Unit,
    onContinue: () -> Unit,
    onStage: Boolean = false,
) {
    // onStage=true（见面剧场内渲染）：舞台深皮（scrimPill 底 + pillStroke 边 + textBright/textDim 字·§4.4/§4.5）；
    // 默认 false（普通聊天历史里回看结束卡·ChatMessageRow）逐像素不动。
    val container = if (onStage) OfflineTheater.scrimPill else AppTheme.colors.surface.raised
    val borderColor = if (onStage) OfflineTheater.pillStroke else AppTheme.colors.surface.stroke
    Card(
        modifier = Modifier.widthIn(max = 280.dp),
        shape = AppShapes.medium,
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "要结束这次见面吗？",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (onStage) OfflineTheater.textBright else Color.Unspecified,
            )
            if (data.responded == "continued") {
                RespondedLabel("已继续见面", onStage)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // onStage 结束卡按钮 = 陶土 AppButton（R1 拍板 TODO-2：陶土=全局主强调、深陶 Primary + 暖白字
                    // 在 scrimPill 暗底上可读；不为单屏给设计系统组件开 accent 洞）。现状即终态。
                    AppButton(onClick = onEndMeeting, modifier = Modifier.weight(1f), style = AppButtonStyle.Primary) { Text("结束见面") }
                    AppButton(onClick = onContinue, modifier = Modifier.weight(1f), style = AppButtonStyle.Tonal) { Text("再待一会儿") }
                }
            }
        }
    }
}

/**
 * 落成两阶段揭示态（卷三 V3·图纸 §4.1-C 的时序单源）：[animate]=true 时 500ms 揭线体、再 240ms 揭「字后到」；
 * =false（历史回看 / 系统「移除动画」）时两态初值即 true、零延迟零动画。抽成可组合 helper = 时序可被
 * `OfflineEndDividerEntryTest` 用 compose mainClock 逐毫秒实证（UI 层 alpha 不进语义树、无法直接断言）。
 */
internal data class OfflineDividerReveal(val lineRevealed: Boolean, val captionRevealed: Boolean)

@Composable
internal fun rememberOfflineDividerReveal(animate: Boolean): OfflineDividerReveal {
    var lineRevealed by remember { mutableStateOf(!animate) }
    var captionRevealed by remember { mutableStateOf(!animate) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        delay(500)
        lineRevealed = true
        delay(240)
        captionRevealed = true
    }
    return OfflineDividerReveal(lineRevealed, captionRevealed)
}

/**
 * 线下见面离场标记 → 居中灰字分隔「— 线下见面结束 · 约X分钟 —」（10.2c-3c；沉浸视图样式见 10.2e）。
 * offline-1：[onClick] 非空（= 有 sessionId）时整条可点进见面回顾，并补「点击查看见面详情」引导
 * （对齐 iOS OfflineMarkerCard 退场分支 onTapGesture + book.pages 提示，themeColor 0.5 透明）。
 *
 * 卷三 V3「落成」（图纸 §4.1-C·契约 FABLE5_MEETING_SEAM_PROPOSAL §5①）：[entryAnimation]=true（该行**新到达**
 * 那一刻·历史回看恒 false）时——500ms 后**分隔线**（= 本条破折号包裹的居中细字，即这条分隔线的视觉本体）走场景
 * 过渡线单源 [offlineSceneTransitionEntry] 中心展开，再 240ms 后详情行 200ms 淡入「字后到」，940ms 收束。
 * 文案行**恒组合**（未落成时 alpha 0 占位）：结构全程恒在，反转列表不跳动、点进回顾全过程可用。
 * [entryAnimation]=false 或系统「移除动画」→ 两态初值即 true、零动画（渲染与改造前逐字一致）。
 */
@Composable
fun OfflineEndDivider(durationText: String, onClick: (() -> Unit)? = null, entryAnimation: Boolean = false) {
    val animate = entryAnimation && !rememberReduceMotion()
    val (lineRevealed, captionRevealed) = rememberOfflineDividerReveal(animate)
    val captionAlpha by animateFloatAsState(
        targetValue = if (captionRevealed) 1f else 0f,
        animationSpec = tween(200, easing = AppMotion.EaseOut),
        label = "offlineEndDividerCaption",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "— 线下见面结束 · $durationText —",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = when {
                !animate -> Modifier
                lineRevealed -> Modifier.offlineSceneTransitionEntry(reduceMotion = false)
                else -> Modifier.alpha(0f)
            },
        )
        if (onClick != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = if (animate) Modifier.graphicsLayer { alpha = captionAlpha } else Modifier,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.MenuBook,
                    contentDescription = null,
                    tint = AppTheme.colors.accent.text,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    "点击查看见面详情",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.colors.accent.text,
                )
            }
        }
    }
}

@Composable
private fun RespondedLabel(text: String, onStage: Boolean = false) {
    Text(
        text,
        modifier = Modifier.padding(top = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = if (onStage) OfflineTheater.textDim else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
    )
}
