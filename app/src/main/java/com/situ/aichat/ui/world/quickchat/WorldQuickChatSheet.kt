package com.situ.aichat.ui.world.quickchat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.ui.chat.rememberRelativeTimeStrings
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.world.WorldSceneColors
import com.situ.aichat.ui.world.WorldSceneColors.QcBody
import com.situ.aichat.ui.world.WorldSceneColors.QcBusyEnd
import com.situ.aichat.ui.world.WorldSceneColors.QcBusyStart
import com.situ.aichat.ui.world.WorldSceneColors.QcBusyText
import com.situ.aichat.ui.world.WorldSceneColors.QcClose
import com.situ.aichat.ui.world.WorldSceneColors.QcHandle
import com.situ.aichat.ui.world.WorldSceneColors.QcHairline
import com.situ.aichat.ui.world.WorldSceneColors.QcHeadBg
import com.situ.aichat.ui.world.WorldSceneColors.QcInputBg
import com.situ.aichat.ui.world.WorldSceneColors.QcInputBorder
import com.situ.aichat.ui.world.WorldSceneColors.QcInputFocus
import com.situ.aichat.ui.world.WorldSceneColors.QcPill
import com.situ.aichat.ui.world.WorldSceneColors.QcPlaceholder
import com.situ.aichat.ui.world.WorldSceneColors.QcRetryBg
import com.situ.aichat.ui.world.WorldSceneColors.QcSendIcon
import com.situ.aichat.ui.world.WorldSceneColors.QcStatus
import com.situ.aichat.ui.world.WorldSceneColors.QcTypingDot
import com.situ.aichat.ui.world.WorldSceneColors.QcUserEnd
import com.situ.aichat.ui.world.WorldSceneColors.QcUserStart
import com.situ.aichat.util.DateFormatters

private val SheetEnterEasing = CubicBezierEasing(0.3f, 1.2f, 0.4f, 1f)
private val BubbleEnterEasing = CubicBezierEasing(0.3f, 1.3f, 0.4f, 1f)

/**
 * 临时快聊弹窗（W12 图纸 §4.1–4.5·quickchat demo 对版）：底部 sheet·深玻璃头部 + 忙碌条 + 暖纸消息区 + 纯文字输入排。
 * Known 态全件（历史尾巴 / 同步 pill / AI·用户气泡 / 打字态 / 失败重试）。初遇 meetcard/「初次见面」tag 归 C7。
 * 状态来自 [WorldQuickChatViewModel]；发送/重试/关闭/跳聊天页经回调外抛（宿主接 VM + 导航）。
 */
@Composable
internal fun BoxScope.WorldQuickChatSheet(
    state: WorldQuickChatUiState,
    reduceMotion: Boolean,
    onSend: (String) -> Unit,
    onRetry: () -> Unit,
    onConfirmMeet: () -> Unit,
    onClose: () -> Unit,
    onOpenChatPage: (String) -> Unit,
) {
    var shown by remember { mutableStateOf<WorldQuickChatUiState?>(null) }
    if (state.target != null) shown = state

    AnimatedVisibility(
        visible = state.target != null,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = if (reduceMotion) fadeIn(tween(120)) else slideInVertically(tween(340, easing = SheetEnterEasing), initialOffsetY = { (it * 1.1f).toInt() }) + fadeIn(tween(340)),
        exit = if (reduceMotion) fadeOut(tween(120)) else slideOutVertically(tween(260, easing = SheetEnterEasing), targetOffsetY = { (it * 1.1f).toInt() }) + fadeOut(tween(260)),
    ) {
        val s = shown
        val target = s?.target
        if (s != null && target != null) {
            val maxH = (LocalConfiguration.current.screenHeightDp * 0.76f).dp // 总高 ≤76% 屏高（§4.1）
            Column(
                Modifier
                    .fillMaxWidth()
                    .imePadding() // 随键盘上推（§4.1·消息区收缩·头部恒可见）
                    .heightIn(max = maxH)
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .background(QcBody),
            ) {
                QcHead(target, s.firstMeet, onClose = onClose, onOpenChatPage = onOpenChatPage)
                if (s.busy) QcBusyBar()
                QcMessages(s, reduceMotion, onRetry, onConfirmMeet, Modifier.weight(1f, fill = false))
                Box(Modifier.fillMaxWidth().height(1.dp).background(QcHairline)) // 输入排顶 hairline（§4.5）
                QcInputRow(onSend)
            }
        }
    }
}

/** 头部（§4.2·深玻璃 chrome·手柄/头像/名字/状态行/初次见面 tag/聊天页/关闭）。 */
@Composable
private fun QcHead(target: QuickChatTarget, firstMeet: FirstMeetState?, onClose: () -> Unit, onOpenChatPage: (String) -> Unit) {
    val closeCd = stringResource(R.string.world_qc_close_a11y)
    val name = target.name()
    val status = when (target) { is QuickChatTarget.Known -> target.statusLine; is QuickChatTarget.Meet -> target.placeName }
    val convUuid = (target as? QuickChatTarget.Known)?.conversationUuid
    Box(Modifier.fillMaxWidth().background(QcHeadBg).padding(start = 14.dp, end = 14.dp, bottom = 11.dp)) {
        Box(Modifier.align(Alignment.TopCenter).padding(top = 6.dp).size(width = 36.dp, height = 4.dp).clip(AppShapes.full).background(QcHandle)) // 手柄距顶 6dp（§4.2）
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            CharacterAvatar(name = name, avatarPath = null, size = 38.dp)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(name, color = WorldSceneColors.onGlass, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (firstMeet != null) QcMeetTag(done = firstMeet.met) // 初次见面 / 已认识
                }
                if (status.isNotBlank()) { // W13 微兜底：状态行空则金点与文字一起不渲染（销 W12 挂账）。
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 2.dp)) {
                        Box(Modifier.size(5.dp).clip(CircleShape).background(WorldSceneColors.gold))
                        Text(status, color = QcStatus, fontSize = 11.sp)
                    }
                }
            }
            if (convUuid != null) {
                Text(
                    stringResource(R.string.world_qc_chat_page),
                    color = WorldSceneColors.pcardStatus, fontSize = 11.5.sp,
                    modifier = Modifier.sizeIn(minHeight = 48.dp).clickableScale(role = Role.Button, onClick = { onOpenChatPage(convUuid) }).padding(horizontal = 8.dp, vertical = 14.dp),
                )
            }
            Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickableScale(role = Role.Button, onClick = onClose), contentAlignment = Alignment.Center) {
                Text("✕", color = QcClose, fontSize = 16.sp, modifier = Modifier.semantics { contentDescription = closeCd })
            }
        }
    }
}

private fun QuickChatTarget.name(): String = when (this) { is QuickChatTarget.Known -> name; is QuickChatTarget.Meet -> name }

/** 「初次见面」/「已认识」tag（§4.2·10sp #1A2440·金渐变 r-full·padding 2×8dp）。 */
@Composable
private fun QcMeetTag(done: Boolean) {
    Text(
        stringResource(if (done) R.string.world_meet_tag_done else R.string.world_meet_tag),
        color = Color(0xFF1A2440), fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(AppShapes.full).background(Brush.linearGradient(listOf(Color(0xFFEED9A8), WorldSceneColors.gold))).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** 忙碌条（§4.3）。 */
@Composable
private fun QcBusyBar() {
    Text(
        stringResource(R.string.world_qc_busy),
        color = QcBusyText, fontSize = 11.sp,
        modifier = Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(QcBusyStart, QcBusyEnd))).padding(horizontal = 16.dp, vertical = 7.dp),
    )
}

/** 消息区（§4.4·暖纸·历史尾巴 / 同步·时间 pill·或初遇 intro pill·气泡·打字/失败态·meetcard/已认识 pill·自动滚到底）。 */
@Composable
private fun QcMessages(s: WorldQuickChatUiState, reduceMotion: Boolean, onRetry: () -> Unit, onConfirmMeet: () -> Unit, modifier: Modifier) {
    val scroll = rememberScrollState()
    val relStrings = rememberRelativeTimeStrings()
    val now = remember(s.messages.size) { System.currentTimeMillis() }
    val fm = s.firstMeet
    val name = s.target?.name().orEmpty()
    LaunchedEffect(s.messages.size, s.typing, s.failed, fm?.meetcardVisible, fm?.met) { scroll.animateScrollTo(scroll.maxValue) }
    // 气泡最大宽 = 消息区内容宽 78%（§4.4 demo max-width:78%）：BoxWithConstraints 测可用宽，扣横 padding 14dp×2 后取 78%
    // —— 随屏宽自适应（窄屏/平板/横屏一致），取代旧 260dp 写死（R1 🟡-1）。
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val bubbleMax: Dp = (maxWidth - 28.dp) * 0.78f
        Column(
            Modifier.fillMaxWidth().heightIn(min = 190.dp).verticalScroll(scroll).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                fm != null && !fm.met -> QcPill(stringResource(R.string.world_meet_intro_pill)) // 初遇：这将成为你们对话的开头
                s.messages.isNotEmpty() -> {
                    QcPill(DateFormatters.relativeTimeString(s.messages.first().timestamp, now, relStrings))
                    if (s.messages.any { it.isHistory }) QcPill(stringResource(R.string.world_qc_sync))
                }
            }
            s.messages.forEach { m ->
                // 事件类消息不当对话气泡：离场标记 / system 角色事件（红包结算等）走居中 pill（与主聊天屏分隔条同口径）。
                if (m.kind == MessageKind.OFFLINE_MARKER_END || m.role == "system") QcPill(m.text)
                else QcBubble(m, reduceMotion, bubbleMax)
            }
            if (s.typing) QcTyping()
            if (s.failed) QcFail(onRetry)
            if (fm != null && fm.meetcardVisible && !fm.met) QcMeetCard(name, reduceMotion, onConfirmMeet)
            if (fm != null && fm.met) QcPill(stringResource(R.string.world_meet_done_pill, name))
        }
    }
}

/** 初遇确认卡（§4.4·暖纸卡 r16dp·标题/副文/陶土主按钮·入场同气泡曲线 300ms·reduce 纯 fade）。 */
@Composable
private fun QcMeetCard(name: String, reduceMotion: Boolean, onConfirm: () -> Unit) {
    val enter = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) { if (reduceMotion) enter.snapTo(1f) else enter.animateTo(1f, tween(300, easing = BubbleEnterEasing)) }
    val ctaCd = stringResource(R.string.world_meet_card_cta)
    Column(
        Modifier.fillMaxWidth()
            .graphicsLayer { alpha = enter.value; if (!reduceMotion) { val sc = 0.97f + 0.03f * enter.value; scaleX = sc; scaleY = sc } }
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(WorldSceneColors.pcardPaperTop, WorldSceneColors.pcardPaperBottom)))
            .border(1.5.dp, WorldSceneColors.cardStroke, RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 13.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.world_meet_card_title, name), color = WorldSceneColors.sheetTitle, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(stringResource(R.string.world_meet_card_body), color = WorldSceneColors.sheetBody, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
        Box(Modifier.padding(top = 8.dp).sizeIn(minHeight = 48.dp).clickableScale(role = Role.Button, onClick = onConfirm), contentAlignment = Alignment.Center) {
            Box(Modifier.clip(AppShapes.full).background(Brush.linearGradient(listOf(QcUserStart, QcUserEnd))).padding(horizontal = 20.dp, vertical = 7.dp)) {
                Text(stringResource(R.string.world_meet_card_cta), color = WorldSceneColors.sheetTitle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { contentDescription = ctaCd })
            }
        }
    }
}

@Composable
private fun QcPill(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(text, color = WorldSceneColors.sheetClose, fontSize = 10.sp, modifier = Modifier.clip(AppShapes.full).background(QcPill).padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

/** AI / 用户气泡（§4.4·入场 260ms 过冲 scale0.97→1+↑6dp+fade·历史尾巴 alpha 0.60·reduce=纯 fade 120ms·[bubbleMax]=消息区 78%）。 */
@Composable
private fun QcBubble(m: QcMessage, reduceMotion: Boolean, bubbleMax: Dp) {
    val isMe = m.role == "user"
    val enter = remember(m.id) { Animatable(if (m.isHistory) 1f else 0f) }
    LaunchedEffect(m.id) {
        if (m.isHistory) enter.snapTo(1f)
        else enter.animateTo(1f, tween(if (reduceMotion) 120 else 260, easing = if (reduceMotion) LinearEasing else BubbleEnterEasing))
    }
    val baseAlpha = if (m.isHistory) 0.60f else 1f
    val motion = !reduceMotion && !m.isHistory
    val shape = if (isMe) RoundedCornerShape(16.dp, 16.dp, 6.dp, 16.dp) else RoundedCornerShape(16.dp, 16.dp, 16.dp, 6.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = if (isMe) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            m.text,
            color = WorldSceneColors.sheetTitle, fontSize = 13.5.sp, lineHeight = 20.9.sp, // 13.5×1.55
            modifier = Modifier
                .widthIn(max = bubbleMax)
                .graphicsLayer {
                    alpha = baseAlpha * enter.value
                    if (motion) { val sc = 0.97f + 0.03f * enter.value; scaleX = sc; scaleY = sc; translationY = 6.dp.toPx() * (1f - enter.value) }
                }
                .clip(shape)
                .then(
                    if (isMe) Modifier.background(Brush.linearGradient(listOf(QcUserStart, QcUserEnd)))
                    else Modifier.background(Brush.verticalGradient(listOf(WorldSceneColors.pcardPaperTop, WorldSceneColors.pcardPaperBottom))).border(1.5.dp, WorldSceneColors.cardStroke, shape)
                )
                .padding(horizontal = 12.dp, vertical = 9.dp),
        )
    }
}

/** 打字指示（§4.4·AI 气泡壳内三点 6dp·1200ms·逐点 180ms·reduce 静点）。 */
@Composable
private fun QcTyping() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Row(
            Modifier.clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 6.dp))
                .background(Brush.verticalGradient(listOf(WorldSceneColors.pcardPaperTop, WorldSceneColors.pcardPaperBottom)))
                .border(1.5.dp, WorldSceneColors.cardStroke, RoundedCornerShape(16.dp, 16.dp, 16.dp, 6.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            val t = rememberInfiniteTransition(label = "qcTyping")
            repeat(3) { i ->
                val f by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse, StartOffset(i * 180)), label = "d$i")
                Box(Modifier.size(6.dp).graphicsLayer { translationY = -4.dp.toPx() * f; alpha = 0.45f + 0.55f * f }.clip(CircleShape).background(QcTypingDot))
            }
        }
    }
}

/** 失败态（§4.4·居中两行 + 重试 chip·11sp #9C938A·chip 触达 48dp）。 */
@Composable
private fun QcFail(onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.world_qc_fail_line1), color = WorldSceneColors.sheetClose, fontSize = 11.sp, lineHeight = 18.7.sp, textAlign = TextAlign.Center)
        Text(stringResource(R.string.world_qc_fail_line2), color = WorldSceneColors.sheetClose, fontSize = 11.sp, lineHeight = 18.7.sp, textAlign = TextAlign.Center)
        Box(Modifier.padding(top = 5.dp).sizeIn(minHeight = 48.dp).clickableScale(role = Role.Button, onClick = onRetry), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.world_qc_retry),
                color = WorldSceneColors.sheetBody, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(AppShapes.full).background(QcRetryBg).padding(horizontal = 16.dp, vertical = 5.dp),
            )
        }
    }
}

/** 输入排（§4.5·纯文字·无语音无「+」·输入框 r-full + 陶土发送钮·空白禁发·发送触觉同聊天页 light）。 */
@Composable
private fun QcInputRow(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val haptics = LocalAppHaptics.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val placeholder = stringResource(R.string.world_qc_placeholder)
    val sendCd = stringResource(R.string.world_qc_send_a11y)
    fun submit() { val t = text.trim(); if (t.isNotEmpty()) { onSend(t); text = ""; haptics.light() } }
    Row(
        Modifier.fillMaxWidth().background(QcBody).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f).clip(AppShapes.full).background(QcInputBg)
                .border(1.5.dp, if (focused) QcInputFocus else QcInputBorder, AppShapes.full)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            textStyle = TextStyle(color = WorldSceneColors.sheetTitle, fontSize = 13.5.sp),
            maxLines = 4,
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(QcUserEnd),
            decorationBox = { inner ->
                if (text.isEmpty()) Text(placeholder, color = QcPlaceholder, fontSize = 13.5.sp)
                inner()
            },
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickableScale(role = Role.Button, onClick = { submit() }), contentAlignment = Alignment.Center) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(Brush.linearGradient(listOf(QcUserStart, QcUserEnd))), contentAlignment = Alignment.Center) {
                Text("↑", color = QcSendIcon, fontSize = 15.sp, modifier = Modifier.semantics { contentDescription = sendCd })
            }
        }
    }
}
