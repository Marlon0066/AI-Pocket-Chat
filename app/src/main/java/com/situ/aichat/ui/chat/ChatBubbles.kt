package com.situ.aichat.ui.chat

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.animation.core.snap
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.util.DateFormatters
import com.situ.aichat.R
import kotlinx.coroutines.delay

// Fable-5 聊天气泡渲染件（契约 FABLE5_CHAT_BUBBLE_REFACTOR_PROPOSAL · 从 ChatScreen 巨石抽出·一文件一职）：
// 文本气泡(用户渐变/AI 暖白)、AI 会变身气泡(点↔字交叉淡入)、脏消息折叠、气泡内时间戳+回执、打字三点。
// 由 ChatMessageRow.MessageRow 调用(故对外 internal)；TypingDots 仅本文件 AssistantTextBubble 用(private)。

/**
 * chat-ui-5：气泡下方一行「HH:mm + 回执」（1:1 iOS BubbleInlineTimestamp）。仅用户消息显回执：
 * [read]=true → ✓✓（主题强调色，对应 iOS iMessage 蓝映射 accent）；false → ✓（灰，发出 1s 后才显，前 1s 仅时间）；
 * null（AI 消息）→ 只显时间无勾。
 */
@Composable
internal fun BubbleInlineTimestamp(timestampMs: Long, isUser: Boolean, read: Boolean?, a11yHidden: Boolean = false) {
    val colors = AppTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = if (a11yHidden) Modifier.clearAndSetSemantics {} else Modifier,
    ) {
        // WCAG 决议：功能性小字（时间戳）用 text.secondary（tertiary 降为纯装饰）；tnum 防分秒跳动错位。
        Text(
            DateFormatters.hourMinute(timestampMs),
            style = AppTypography.captionNumeric,
            color = colors.text.secondary,
        )
        if (isUser && read != null) {
            Crossfade(targetState = read, label = "receipt") { isRead ->
                if (isRead) {
                    Icon(
                        Icons.Filled.DoneAll,
                        contentDescription = stringResource(R.string.a11y_message_read),
                        tint = colors.accent.text,
                        modifier = Modifier.size(14.dp),
                    )
                } else {
                    // 送达：发出 1s 后才显单勾（1:1 iOS deliveryReceiptRevealDelaySeconds=1.0；前 1s 仅时间）。
                    var revealed by remember(timestampMs) { mutableStateOf(false) }
                    LaunchedEffect(timestampMs) {
                        delay(1000)
                        revealed = true
                    }
                    if (revealed) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = stringResource(R.string.a11y_message_delivered),
                            tint = colors.text.secondary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Fable-5 文本气泡（契约 §3.2·D1/D3/D4 + WCAG 决议）：用户=陶土玫 135° 双 stop 渐变 + 深墨字（微信式·
 * 白字 on #BE8A76 实测 2.96:1 不达 4.5）；AI=raised 暖白纸 + 浅档极浅投影 / 深档 1px 暖灰描边（明度分层
 * 替阴影）。形状由调用方按连续卡段位传入；宽度=屏宽比例钳位（替 300dp 定死·折叠屏自适应）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Bubble(
    isUser: Boolean,
    text: String,
    quotedContent: String?,
    quotedSender: String?,
    shape: Shape,
    maxWidth: Dp,
    onLongClick: () -> Unit,
    a11yDescription: String? = null,
) {
    val colors = AppTheme.colors
    val textColor = if (isUser) colors.bubble.onUser else colors.text.primary
    Column(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .then(if (!isUser && !colors.isDark) Modifier.shadow(1.dp, shape, clip = false) else Modifier)
            .clip(shape)
            .then(
                if (isUser) {
                    // 审计 P5：渐变按主题色 remember，重组不再逐次新建 Brush。
                    Modifier.background(remember(colors) { Brush.linearGradient(listOf(colors.bubble.userStart, colors.bubble.userEnd)) })
                } else {
                    Modifier.background(colors.bubble.ai)
                },
            )
            .then(if (!isUser && colors.isDark) Modifier.border(1.dp, colors.bubble.aiStroke, shape) else Modifier)
            .combinedClickable(onClick = {}, onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.a11y_message_menu)) // Y2：长按菜单对读屏可发现
            // P1-1：合并朗读句覆盖气泡默认逐节点朗读（节点本身是 clickable=单停，长按菜单保留）。
            .then(a11yDescription?.let { Modifier.semantics { contentDescription = it } } ?: Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (!quotedContent.isNullOrEmpty()) {
            Text(
                text = "「${quotedSender ?: ""}: ${quotedContent.take(40)}」",
                style = AppTypography.secondary,
                color = textColor.copy(alpha = 0.75f),
                modifier = Modifier
                    .clip(AppShapes.small)
                    .background(textColor.copy(alpha = 0.08f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
            Spacer(Modifier.size(4.dp))
        }
        // 心情只在顶栏副标题显示，不进气泡（emotionTag 仍存消息上，供 M15 语音情绪跟随）。
        Text(text = text, style = AppTypography.body, color = textColor)
    }
}

/**
 * Fable-5 AI 文本气泡（契约 B1/B4·仿 iOS AssistantTransitionContent）：三点与正文**同处一个气泡内**，按 [revealed]
 * 交叉变身——未显形（占位/打字态）显三点；内容到达时三点淡出 + 缩到 0.94（左缘锚），正文淡入，气泡尺寸经
 * [animateContentSize] 平滑长高（外层 clip=内容随气泡长出而显露）。占位与真实消息共用同一 composable 子树
 * （同 LazyColumn key）→ 原地变身不删插不跳位。皮肤=AI 暖白纸同 [Bubble]（浅档极浅投影 / 深档 1px 暖灰描边）。
 * reduceMotion → 全 snap 直出；历史消息首帧即 revealed=true → morph 初值即 1、不自播。
 *
 * 机制说明（2026-07-08 V9 回退方案 A·手搓双层）：官方 AnimatedContent+SizeTransform（方案 B）在当前 BOM
 * 下**尺寸不动画**——隔离行为测试实证内容已切换而容器高度纹丝不动（52dp 恒定），设备上呈现为「三点一帧
 * 瞬变正文」的跳动（列表重排捎带把终态尺寸修正，动画全程缺失）。手搓双层 = 变身进度单值驱动交叉淡入/缩放
 * + [animateContentSize] 承担长高（最扎实的尺寸动画原语），行为由 AssistantBubbleMorphTest 三采样点钉住。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AssistantTextBubble(
    revealed: Boolean,
    text: String,
    quotedContent: String?,
    quotedSender: String?,
    shape: Shape,
    maxWidth: Dp,
    onLongClick: () -> Unit,
    a11yDescription: String? = null,
) {
    val colors = AppTheme.colors
    val reduceMotion = rememberReduceMotion()
    // 变身进度 0(三点)→1(正文)：单值驱动两层交叉淡入 + 三点 0.94 缩放；历史消息首组合 revealed=true → 初值即 1。
    val morph by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else AppMotion.gentleSpring(),
        label = "bubbleMorph",
    )
    Box(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .then(if (!colors.isDark) Modifier.shadow(1.dp, shape, clip = false) else Modifier)
            .clip(shape)
            .background(colors.bubble.ai)
            .then(if (colors.isDark) Modifier.border(1.dp, colors.bubble.aiStroke, shape) else Modifier)
            .combinedClickable(onClick = {}, onLongClick = { if (revealed) onLongClick() }, onLongClickLabel = stringResource(R.string.a11y_message_menu))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        // 尺寸容器：正文一进组合即参与测量（Box=max 子尺寸），animateContentSize 把高度/宽度差抹成平滑长高；
        // 外层 clip(shape) 使内容随气泡长出而显露（=旧 SizeTransform clip=true 观感）。
        Box(
            modifier = Modifier.animateContentSize(if (reduceMotion) snap() else AppMotion.gentleSpring()),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (revealed) {
                // P1-1：合并朗读句挂正文 Column（占位三点层另挂「正在输入」）。
                Column(
                    modifier = (if (a11yDescription != null) Modifier.semantics { contentDescription = a11yDescription } else Modifier)
                        .graphicsLayer { alpha = morph },
                ) {
                    if (!quotedContent.isNullOrEmpty()) {
                        Text(
                            text = "「${quotedSender ?: ""}: ${quotedContent.take(40)}」",
                            style = AppTypography.secondary,
                            color = colors.text.primary.copy(alpha = 0.75f),
                            modifier = Modifier
                                .clip(AppShapes.small)
                                .background(colors.text.primary.copy(alpha = 0.08f))
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                        Spacer(Modifier.size(4.dp))
                    }
                    Text(text = text, style = AppTypography.body, color = colors.text.primary)
                }
            }
            if (morph < 0.999f) {
                // 打字三点层（淡出 + 缩 0.94 左缘锚·交叉期叠画在正文上）。上下高度 = 正常单行消息气泡
                // （隐形单行 body 文本作高度锚·三点垂直居中）：与 [Bubble]/正文分支同 8dp 纵 padding + 同一行高
                // → 单行回复变身只长宽、不跳高（仿 iOS minHeight=bodyLineHeight）。
                val typingCd = stringResource(R.string.a11y_typing_indicator)
                Box(
                    modifier = Modifier
                        .clearAndSetSemantics { contentDescription = typingCd } // Y5②：资源化（非中文 locale 读屏不再混读）
                        .graphicsLayer {
                            alpha = 1f - morph
                            scaleX = 1f - 0.06f * morph
                            scaleY = 1f - 0.06f * morph
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(" ", style = AppTypography.body)
                    TypingDots()
                }
            }
        }
    }
}

/**
 * chat-ui-7：iMessage 三点弹跳打字指示器（1:1 iOS TypingDotsView）——8dp 灰点、上跳 4dp、sin 包络；
 * bounce 0.3s / stagger 0.15s / pause 0.3s → 周期 0.9s。替换原静态「正在输入…」文字。
 */
@Composable
private fun TypingDots(modifier: Modifier = Modifier) {
    val bounceMs = 300f
    val staggerMs = 150f
    val cycleMs = staggerMs * 2 + bounceMs + 300f // 0.9s：3 点依次跳 + 末点完成 + 暂停 0.3s
    // P1-23：RM 三点全停基线（phase 0 → dot0 sin(0)=0、dot1/2 localTime<0 → bounce 0 = iOS
    // dotsRow(time: nil) 静帧）；iOS TypingDotsView 只有 isAnimating 参数不读 RM=既定惯例加项。
    // 审计 P5：phase 保持 State、在 offset{} 布局 lambda 里读——生成期（可达数十秒）动画帧只重排三点，
    // 不再逐帧重组整个 TypingDots。
    val phaseState: State<Float> = if (rememberReduceMotion()) {
        remember { mutableStateOf(0f) }
    } else {
        val transition = rememberInfiniteTransition(label = "typing")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = cycleMs,
            animationSpec = infiniteRepeatable(
                animation = tween(cycleMs.toInt(), easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "typingPhase",
        )
    }
    // D5：三点=陶土玫 @60%（品牌温度·不破 ≤3 常驻彩稀缺感；替原 M3 灰点）。
    val dotColor = AppTheme.colors.accent.primary.copy(alpha = 0.6f)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(3) { index ->
            Box(
                Modifier
                    .offset {
                        val localTime = phaseState.value - index * staggerMs
                        val bounce = if (localTime in 0f..bounceMs) sin(localTime / bounceMs * PI).toFloat() else 0f
                        IntOffset(0, (-4f * bounce).dp.roundToPx())
                    }
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
    }
}
