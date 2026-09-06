package com.situ.aichat.ui.liuli.chat

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.ui.chat.ChatViewModel
import com.situ.aichat.ui.chat.ChatWallpaper
import com.situ.aichat.ui.chat.ChatWorldStatusViewModel
import com.situ.aichat.ui.chat.HANDSHAKE_TIMEOUT_MS
import com.situ.aichat.ui.chat.TypingSlot
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.voicecall.VoiceCallPreflightViewModel
import kotlinx.coroutines.delay

/**
 * 琉璃聊天屏的**效应面**（图纸 2026-09-05 卷二A §2.1 · §9 ⑦ 允许的「只搬不改」拆分）：暖陶 `ChatScreen`
 * 的 12 个效应块逐段照抄（触觉武装 / snackbar / markRead / 生命周期观察者 / 飞入握手超时 / 滚动守卫 /
 * 键盘学高 / 上翻加载 / 回底缩窗），**顺序与暖陶相同**（图纸 §9 ④）。
 *
 * 单独一文件的理由：它与 [rememberLiuliChatSession] 合住会破 300 行硬顶；两者之间只经 [LiuliChatSession] 传状态。
 */
@Composable
internal fun LiuliChatEffects(
    session: LiuliChatSession,
    viewModel: ChatViewModel,
    worldStatusVm: ChatWorldStatusViewModel,
    preflightVm: VoiceCallPreflightViewModel,
    characterUuid: String?,
    characterUuidForVoice: String?,
    typingSlot: TypingSlot?,
    messages: List<MessageEntity>,
    reduceMotion: Boolean,
) {
    val haptics = LocalAppHaptics.current
    val error by viewModel.error.collectAsStateWithLifecycle()
    val infoToast by viewModel.infoToast.collectAsStateWithLifecycle()

    // chat-ui-8 + 审计 R6 照抄：AI 回复完成（打字占位 有→无）给轻触觉；触觉须「武装」后才发（ON_STOP 解除武装）。
    var wasTyping by remember { mutableStateOf(false) }
    var typingHapticArmed by remember { mutableStateOf(true) }
    LaunchedEffect(typingSlot) {
        val nowTyping = typingSlot != null
        if (typingHapticArmed && wasTyping && !nowTyping) haptics.soft()
        wasTyping = nowTyping
        typingHapticArmed = true
    }
    LaunchedEffect(error) {
        error?.let {
            haptics.error()
            session.snackbarHost.showSnackbar(it)
            viewModel.clearError()
        }
    }
    LaunchedEffect(infoToast) {
        infoToast?.let {
            session.snackbarHost.showSnackbar(it)
            viewModel.clearInfoToast()
        }
    }
    LaunchedEffect(Unit) { viewModel.markRead() }
    LaunchedEffect(Unit) { viewModel.onChatAppear() }

    // P1-13 / P1-4 / P0-27 / 审计 R6 照抄：入场可见窗口 + 视图可见性 + 退后台停录音 + 情绪不可见区间表。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val initiallyStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        viewModel.setViewVisible(initiallyStarted)
        if (initiallyStarted) session.animateArrivalsSinceMillis = System.currentTimeMillis()
        var emotionHiddenSince: Long? = if (initiallyStarted) null else System.currentTimeMillis()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> characterUuid?.let(worldStatusVm::refresh)
                Lifecycle.Event.ON_START -> {
                    viewModel.setViewVisible(true)
                    session.animateArrivalsSinceMillis = System.currentTimeMillis()
                    emotionHiddenSince?.let { session.emotionHiddenIntervals.add(it..System.currentTimeMillis()) }
                    emotionHiddenSince = null
                    characterUuidForVoice?.let(preflightVm::refresh)
                }
                Lifecycle.Event.ON_STOP -> {
                    viewModel.setViewVisible(false)
                    session.animateArrivalsSinceMillis = Long.MAX_VALUE
                    emotionHiddenSince = System.currentTimeMillis()
                    typingHapticArmed = false
                    viewModel.cancelVoiceRecordingIfActive()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            viewModel.setViewVisible(false)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 「+」面板打开时返回键先收面板（契约 PLUS_PANEL·照抄暖陶 `ChatScreen.kt:358`）。
    BackHandler(enabled = session.inputPanel.panelOpen) { session.inputPanel.dismiss(reduceMotion) }

    // M3a ④握手超时兜底（本卷闸恒关·状态机仍在场·图纸 §0 ② 6）。
    LaunchedEffect(session.sendFlight.pending) {
        if (session.sendFlight.pending != null) {
            delay(HANDSHAKE_TIMEOUT_MS)
            session.sendFlight.resolveByTimeout()
        }
    }

    // 1:1 iOS hasUserInteractedWithScroll 照抄：只认真实拖拽（程序化滚动不发 DragInteraction）。
    val listState = session.listState
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) session.userHasScrolled = true
        }
    }
    // 过渡丝滑化·C 照抄：首次定位瞬时落底，其后动画滚；沿用「未上翻或仍贴底才跟随」守卫。
    // （`itemCount > 0` 的等价判据：渲染列表 = 消息 + 可能的打字占位。）
    val hasItems = messages.isNotEmpty() || typingSlot != null
    val lastMessageId = messages.lastOrNull()?.messageUUID
    LaunchedEffect(lastMessageId, typingSlot) {
        if (hasItems && (!session.userHasScrolled || session.isNearBottom)) {
            session.scrollCoordinator.stickToBottom(animate = session.didInitialScroll && !reduceMotion)
            session.didInitialScroll = true
        }
    }
    // 审计 P4 照抄：ime 只在 snapshotFlow 里读值（组合期绝不读）——键盘升降不整屏重组。
    val imeInsets = WindowInsets.ime
    val navBarInsets = WindowInsets.navigationBars
    val density = LocalDensity.current
    LaunchedEffect(Unit) {
        snapshotFlow { imeInsets.exclude(navBarInsets).getBottom(density) }
            .collect { session.inputPanel.onImeHeightChanged(it) }
    }
    // 12.3 上翻加载更早消息（照抄·反转口径：接近视觉顶部 = 最大可见 index 进入最早 4 项）。
    val hasMoreOlder by viewModel.hasMoreOlderMessages.collectAsStateWithLifecycle()
    val shouldLoadOlder by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val topVisible = layout.visibleItemsInfo.lastOrNull()
            session.userHasScrolled && hasMoreOlder && !session.isNearBottom &&
                topVisible != null && topVisible.index >= layout.totalItemsCount - 4
        }
    }
    LaunchedEffect(shouldLoadOlder) {
        if (shouldLoadOlder) {
            delay(200)
            viewModel.loadOlderMessages()
        }
    }
    // 12.3 回到底部停留 5s → 缩窗回 50（照抄）。
    LaunchedEffect(session.isNearBottom) {
        if (session.isNearBottom) {
            delay(5_000)
            viewModel.shrinkMessageWindow()
        }
    }
}

/**
 * 系统栏图标随壁纸 / 见面态亮度翻色（图纸 §3.3 允许的分叉点 2：无壁纸 + 非见面 → 不介入，跟 App 主题；
 * 有壁纸 / 见面 → 照抄暖陶 `ChatScreen.kt:206-231` 逐字）。
 */
@Composable
internal fun LiuliChatSystemBars(chatWallpaper: ChatWallpaper?, offlineChrome: Boolean) {
    val barView = LocalView.current
    val appIsDark = AppTheme.colors.isDark
    DisposableEffect(chatWallpaper?.topDark, chatWallpaper?.bottomDark, appIsDark, offlineChrome) {
        val window = (barView.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, barView) }
        val wp = chatWallpaper
        val engage = wp != null || offlineChrome
        val topDark = if (offlineChrome) true else (wp?.topDark == true)
        val bottomDark = if (offlineChrome) true else (wp?.bottomDark == true)
        val prevNavContrast = window?.isNavigationBarContrastEnforced
        if (engage && window != null && controller != null) {
            controller.isAppearanceLightStatusBars = !topDark
            controller.isAppearanceLightNavigationBars = !bottomDark
            window.isNavigationBarContrastEnforced = false
        }
        onDispose {
            if (engage && window != null && controller != null) {
                controller.isAppearanceLightStatusBars = !appIsDark
                controller.isAppearanceLightNavigationBars = !appIsDark
                if (prevNavContrast != null) window.isNavigationBarContrastEnforced = prevNavContrast
            }
        }
    }
}
