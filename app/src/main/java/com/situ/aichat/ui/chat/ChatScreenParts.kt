package com.situ.aichat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography

// 聊天屏杂项自绘件（从 ChatScreen 抽出·纯搬 composable）：空对话引导 / 心情副标题 / 网络横幅 /
// 引用预览 / 用户气泡入场缩放。被主屏(或顶栏)跨文件调的均 internal。（时间分隔行已于 V8 退役,见下方注。）

/**
 * chat-ui-10：空对话引导（1:1 iOS emptyConversationHint）——呼吸头像 + 角色名 + 人设 + 三个可点 starter 气泡。
 * iOS 还在「无可用聊天配置」时显示去加 API 的警告；安卓 ChatViewModel 暂未暴露该信号，待接信号后补该分支。
 */
@Composable
internal fun EmptyConversationHint(
    characterName: String,
    avatarPath: String?,
    persona: String,
    onStarter: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // P1-23：RM 静止=scale 1.0 基线（非 1.03）；iOS ChatView+Overlays.swift:32-33 呼吸不读 RM=加项。
    // 审计 P5：scale 保持 State、在 graphicsLayer{} 绘制 lambda 里读——呼吸帧只重绘头像层，不再逐帧重组整个引导块。
    val scaleState: State<Float> = if (rememberReduceMotion()) {
        remember { mutableStateOf(1f) }
    } else {
        val breath = rememberInfiniteTransition(label = "breath")
        breath.animateFloat(
            initialValue = 1f,
            targetValue = 1.03f,
            animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Reverse),
            label = "breathScale",
        )
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CharacterAvatar(
            characterName, avatarPath, 96.dp,
            modifier = Modifier.graphicsLayer { scaleX = scaleState.value; scaleY = scaleState.value },
        )
        Spacer(Modifier.height(16.dp))
        Text(
            characterName.ifEmpty { "?" },
            style = AppTypography.titleSmall,
            color = AppTheme.colors.text.primary,
        )
        if (persona.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                persona,
                style = AppTypography.label,
                color = AppTheme.colors.text.secondary,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "试试这样开场：",
            style = AppTypography.secondary,
            color = AppTheme.colors.text.secondary,
        )
        Spacer(Modifier.height(10.dp))
        listOf("早上好呀～", "在忙什么呢？", "给我讲个故事吧").forEach { starter ->
            Surface(
                onClick = { onStarter(starter) },
                shape = AppShapes.full,
                color = AppTheme.colors.surface.sunken,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                Text(
                    starter,
                    style = AppTypography.label,
                    color = AppTheme.colors.accent.text,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
internal fun MoodSubtitle(emoji: String, text: String, colorName: String, textColor: Color? = null) {
    if (emoji.isEmpty() && text.isEmpty()) return
    // 心情点=emotion 莫兰迪装饰浅档（替硬编码红黄绿三色）；辨识靠 emoji+文案冗余，色只承载氛围（兼顾红绿色弱）。
    val emotion = AppTheme.colors.emotion
    val dotColor = when (colorName) {
        "red" -> emotion.anger
        "yellow" -> emotion.joy
        else -> emotion.calm
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = listOf(emoji, text).filter { it.isNotEmpty() }.joinToString(" "),
            style = AppTypography.secondary,
            color = textColor ?: AppTheme.colors.text.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** P0-2 网络状态横幅（D9）：离线=status.warning 琥珀（常驻·离线是状态非错误），恢复=status.success 灰绿（2s 自动消）。 */
@Composable
internal fun NetworkStatusBanner(connected: Boolean, recovered: Boolean, onRecoveredShown: () -> Unit) {
    val offline = !connected
    if (recovered && connected) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(2000)
            onRecoveredShown()
        }
    }
    val colors = AppTheme.colors
    val container = if (offline) colors.status.warningContainer else colors.status.successContainer
    val content = if (offline) colors.status.onWarning else colors.status.onSuccess
    Surface(
        color = container,
        shape = AppShapes.large,
        shadowElevation = 1.dp,
        // 审计 Y4：断网红条/恢复绿条（2s 自动消）弹出即 Polite 播报——消息发不出去时读屏用户能知道网络断了。
        modifier = Modifier.padding(top = 8.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (offline) Icons.Filled.WifiOff else Icons.Filled.Wifi,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(if (offline) R.string.chat_network_disconnected else R.string.chat_network_recovered),
                style = AppTypography.secondary,
                color = content,
            )
        }
    }
}

@Composable
internal fun ReplyPreview(senderLabel: String, content: String, onClear: () -> Unit) {
    val colors = AppTheme.colors
    // 托盘内引用卡：sunken 底 + 左缘 3dp 陶土玫胶囊竖条（「即将引用」的品牌确认色）。
    Surface(
        color = colors.surface.sunken,
        shape = AppShapes.medium,
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(24.dp)
                    .clip(AppShapes.full)
                    .background(colors.accent.primary),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "引用 $senderLabel：${content.take(40)}",
                style = AppTypography.secondary,
                color = colors.text.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClear) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.a11y_cancel_quote),
                    tint = colors.text.secondary,
                )
            }
        }
    }
}

/** 「引用时只能发文字」提示的停留时长（毫秒·图纸 §4.1 逐字锁定：重复触发**重新计时**、不叠第二条）。 */
internal const val QUOTE_HINT_DURATION_MS = 3000L

/**
 * 「引用时只能发文字」提示的瞬态状态（引用一期 E·图纸 §3.5）。**不进 ViewModel**——三个触发点
 * （麦克风 / 「照片」/「表情」）都在 [ChatBottomBar] 内，且提示是纯瞬态表现，没有跨屏 / 跨进程语义。
 */
@Stable
internal class QuoteTextOnlyHintState {
    var visible by mutableStateOf(false)
        private set

    /** 重启计时用的令牌：每次触发自增，让 3 秒倒计时从头再来（而不是叠出第二条）。 */
    var token by mutableIntStateOf(0)
        private set

    /** 三个触发点共用：亮起并重启计时。 */
    fun trigger() {
        visible = true
        token++
    }

    fun hide() {
        visible = false
    }
}

/**
 * 提示状态的持有 + 两条自动收场（图纸 §3.5·抽成独立 hook 便于 T2 直接驱动，语义与内联在 [ChatBottomBar]
 * 里的局部 `remember` 完全一致）：
 * - 触发后 [QUOTE_HINT_DURATION_MS] 自动消；期间再次触发 → 令牌自增 → 计时重启（B11）。
 * - [replyTarget] 变 null（点了引用卡的 ✕，或引用被这一次发送消费掉）→ 提示**立即**一起消失（B12/B13）。
 */
@Composable
internal fun rememberQuoteTextOnlyHint(replyTarget: MessageEntity?): QuoteTextOnlyHintState {
    val state = remember { QuoteTextOnlyHintState() }
    LaunchedEffect(state.token) {
        if (state.visible) {
            kotlinx.coroutines.delay(QUOTE_HINT_DURATION_MS)
            state.hide()
        }
    }
    LaunchedEffect(replyTarget) { if (replyTarget == null) state.hide() }
    return state
}

/**
 * 「引用时只能发文字」提示条（引用一期 E·图纸 §4.1·mockup 变体 B「陶土玫呼应条」·D-2 用户选定）。
 *
 * 长相与引用卡同一套语言——同 `surface.sunken` 底、同 [AppShapes.medium] 圆角、同陶土玫左缘竖条
 * （仅高度 24→18 以配更矮的行）：一眼读作「这条提示是那张引用卡的话」，而不是「你操作错了」。
 * **有意不用琥珀**：琥珀在本 App 是警示语义（断网 / 删日程），这里说的是引用状态下的规矩。
 *
 * 挂在 [ReplyPreview] 正上方（D-5），壁纸态与它同包一层 `MaybeTrayGlass`。
 */
@Composable
internal fun QuoteTextOnlyHint(
    visible: Boolean,
    reduceMotion: Boolean,
    wallpaperFrosted: ImageBitmap?,
    wallpaperDark: Boolean,
) {
    val colors = AppTheme.colors
    AnimatedVisibility(
        visible = visible,
        // 减弱动画 → 直显直隐（同 ChatCalendarToast 口径）。
        enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(220)) + slideInVertically { it / 2 },
        exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(220)) + slideOutVertically { it / 2 },
    ) {
        MaybeTrayGlass(wallpaperFrosted, wallpaperDark) {
            Surface(
                color = colors.surface.sunken,
                shape = AppShapes.medium,
                // 与引用卡同 padding，两条上下贴合成一组。读屏 Polite 播报（同 NetworkStatusBanner）。
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(18.dp)
                            .clip(AppShapes.full)
                            .background(colors.accent.primary),
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        Icons.Filled.FormatQuote,
                        contentDescription = null,
                        tint = colors.accent.text,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.chat_quote_text_only_hint),
                        style = AppTypography.secondary,
                        color = colors.accent.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// （2026-07-08 V8：列表内时间分隔行 TimeDivider/formatDivider 已随拍板整体退役——气泡内嵌时间戳 +
// 滚动浮动日期胶囊接管时间定位，见 FABLE5_CHAT_REVERSE_LIST_PROPOSAL §9。）

/**
 * P1-13（批1）：用户气泡入场缩放——0.985→1.0、右缘锚点（=iOS MessageBubbleContent.swift:213-216
 * `.scale(0.985, anchor: .trailing)` + MessageSpring.send 弹簧）。[play]=false 时零开销直通；
 * [onPlayed] 在动画启动时即记账（滚走再滚回不重播）。
 */
internal fun Modifier.userBubbleEntryScale(play: Boolean, onPlayed: () -> Unit): Modifier = composed {
    if (!play) return@composed Modifier
    val scale = remember { Animatable(0.985f) }
    LaunchedEffect(Unit) {
        onPlayed()
        scale.animateTo(1f, AppMotion.messageSendSpring())
    }
    Modifier.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        transformOrigin = TransformOrigin(1f, 0.5f)
    }
}

/** Chunk 2 礼物/红包弧线入场时长（ms·参照 Telegram 飞行 220–350ms·卡片落位略放长）。 */
private const val GiftArcEntryMs = 420

/**
 * Chunk 2（参照 DrKLO/Telegram ReactionsEffectOverlay.java:390-394 分轴弧线·D2 用户定礼物/红包气泡入场）：
 * 礼物卡 / 红包卡新到达时「划一道弧线落位」——横向 [AppMotion.EaseOutQuint]（快）、纵向 [AppMotion.EaseInOut]
 * （缓）两轴异速 → 自然弯弧；落地缩放 0.82→1 带 [AppMotion.EaseOutBack] 微过冲。**淡入仍由外层 animateItem 负责，
 * 本修饰符不碰 alpha**（避免双重淡入）；用户气泡的 0.985 微缩在调用点对礼物/红包让开（避免双重缩放）。
 * [fromUser]=true 自右下角落位（origin 右下）、AI 自左下角；[play]=false 零开销直通；[onPlayed] 启动即记账
 * （滚走再滚回不重播）；reduceMotion 由调用点 rowModifier 整体门控（关动画时本修饰符根本不挂）。
 */
internal fun Modifier.giftRedPacketArcEntry(
    play: Boolean,
    fromUser: Boolean,
    onPlayed: () -> Unit,
): Modifier = composed {
    if (!play) return@composed Modifier
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        onPlayed()
        progress.animateTo(1f, tween(GiftArcEntryMs, easing = LinearEasing))
    }
    Modifier.graphicsLayer {
        val p = progress.value
        val dx = if (fromUser) 26.dp.toPx() else -26.dp.toPx()
        translationX = dx * (1f - AppMotion.EaseOutQuint.transform(p))
        translationY = 46.dp.toPx() * (1f - AppMotion.EaseInOut.transform(p))
        val s = 0.82f + 0.18f * AppMotion.EaseOutBack.transform(p)
        scaleX = s
        scaleY = s
        transformOrigin = TransformOrigin(if (fromUser) 1f else 0f, 1f)
    }
}
