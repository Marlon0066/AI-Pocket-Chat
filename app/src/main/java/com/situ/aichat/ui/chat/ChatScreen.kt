package com.situ.aichat.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import com.situ.aichat.ui.components.AppMotion
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateFloatAsState
import com.situ.aichat.ui.designsystem.AppPanelIcons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.ui.designsystem.AppSnackbarHost
import com.situ.aichat.ui.offline.OfflineImmersiveInputView
import com.situ.aichat.ui.offline.OfflineBackgroundView
import com.situ.aichat.ui.offline.OfflineModeView
import com.situ.aichat.ui.offline.OfflineTheater
import com.situ.aichat.ui.offline.parseOfflineThemeColor
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.OnGlass
import com.situ.aichat.ui.designsystem.GlassBackdrop
import com.situ.aichat.ui.designsystem.GlassDivider
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.Activity
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.situ.aichat.R
import com.situ.aichat.ui.voicecall.VoiceCallPreflightViewModel
import com.situ.aichat.ui.voicecall.VoiceSetupNeed
import com.situ.aichat.ui.voicecall.rememberPreflightVoiceCallStarter
import com.situ.aichat.ui.offline.OfflineReviewView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenStickerManagement: () -> Unit,
    onOpenVoiceCall: (String) -> Unit,
    onOpenCharacterVoiceSettings: (String) -> Unit,
    onOpenTtsConfig: () -> Unit,
    onOpenWorldAt: (String) -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    // 过渡丝滑化·B1：首屏消息是否已从 DB 首次返回（区分加载中 vs 真·空会话），用于门控空会话引导，避免开会话闪一下引导。
    val messagesLoaded by viewModel.messagesLoaded.collectAsStateWithLifecycle()
    val customStickers by viewModel.customStickers.collectAsStateWithLifecycle()
    // B1：打字占位槽（替代旧 assistantTyping 布尔）——渲染层据此合成「会变身的占位气泡」与首段同 key。
    val typingSlot by viewModel.pendingAssistantSlot.collectAsStateWithLifecycle(null)
    val isSending by viewModel.isSending.collectAsStateWithLifecycle()
    val coinBalance by viewModel.coinBalance.collectAsStateWithLifecycle()
    // 过渡丝滑化：会话 + 壁纸路径从同一合并查询单流读、本地派生二者 → 进会话时壁纸(含状态栏/底部手势条)与内容同帧（不再割裂）。
    val conversationWithWallpaper by viewModel.conversationWithWallpaper.collectAsStateWithLifecycle()
    val conversation = conversationWithWallpaper?.conversation
    val character by viewModel.character.collectAsStateWithLifecycle()
    val scheduleStatus by viewModel.currentScheduleStatus.collectAsStateWithLifecycle()
    val networkConnected by viewModel.networkConnected.collectAsStateWithLifecycle()
    val networkStatusChanged by viewModel.networkStatusChanged.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val replyTarget by viewModel.replyTarget.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val pendingCalendarActions by viewModel.pendingCalendarActions.collectAsStateWithLifecycle()
    val calendarToast by viewModel.calendarToast.collectAsStateWithLifecycle()
    // 审计 P3：播放态只持句柄不整读——「正在播放的 uuid」经 derivedStateOf 派生（起停/切换才变），
    // progress 以 lambda 下传、只有播放中那一行读高频值（80ms tick 不再整屏波及）。
    val playbackState = viewModel.playbackState.collectAsStateWithLifecycle()
    val playingVoiceId by remember { derivedStateOf { playbackState.value.let { if (it.isPlaying) it.playingId else null } } }
    val voiceProgressProvider = remember { { playbackState.value.progress } }
    val infoToast by viewModel.infoToast.collectAsStateWithLifecycle()
    val offlineRecoveryVisible by viewModel.offlineRecoveryPromptVisible.collectAsStateWithLifecycle()
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    // P13.4b 语音消息录制状态
    val voiceRecording by viewModel.voiceRecording.collectAsStateWithLifecycle()
    val voiceRecordingDurationMs by viewModel.voiceRecordingDurationMs.collectAsStateWithLifecycle()
    val voiceRecordingLevel by viewModel.voiceRecordingLevel.collectAsStateWithLifecycle()
    val voiceRecordingCancelling by viewModel.voiceRecordingCancelling.collectAsStateWithLifecycle()
    val voiceDraft by viewModel.voiceDraft.collectAsStateWithLifecycle()
    // offline-1：点「线下见面结束」分隔条 → 只读见面回顾覆盖层。
    val offlineReviewInfo by viewModel.offlineReviewInfo.collectAsStateWithLifecycle()
    val offlineReviewMessages by viewModel.offlineReviewMessages.collectAsStateWithLifecycle()

    // Prefer the live character name (reflects edits); fall back to the conversation title.
    val characterName = character?.name?.takeIf { it.isNotBlank() } ?: conversation?.title.orEmpty()
    val avatarPath = character?.avatarPath
    // 过渡丝滑化·B2：会话/角色都未就绪前不渲染顶栏占位（不闪假「聊天」+占位字母圈），就绪后即填真名/真头像。
    // conversation 由合并查询 conversationWithWallpaper 派生、一旦非空即单向稳定；character 是其下游，故 conversation 非空时已可判定。
    val headerLoading = conversation == null && character == null
    val userName = userProfile?.nickname.orEmpty()
    val userAvatarPath = userProfile?.avatarPath
    val characterUuid = conversation?.characterUuid

    // W13 聊天世界状态行（图纸 §3.6）：refresh 驱动·无轮询（LaunchedEffect 首刷 + 下方 observer ON_RESUME 复刷）。
    val worldStatusVm: ChatWorldStatusViewModel = hiltViewModel()
    val worldPill by worldStatusVm.pill.collectAsStateWithLifecycle()
    LaunchedEffect(characterUuid) { characterUuid?.let(worldStatusVm::refresh) }

    // VU1 门 + VU3 尾巴自愈的判定单源（同 owner 单实例·绝不进 ChatViewModel·J5）；setupNeed=首刷+ON_START 复刷。
    val preflightVm: VoiceCallPreflightViewModel = hiltViewModel()
    val voiceSetupNeed by preflightVm.setupNeed.collectAsStateWithLifecycle()
    LaunchedEffect(character?.uuid) { character?.uuid?.let(preflightVm::refresh) }

    // chunk3 聊天壁纸：per-角色壁纸（清晰+磨砂+亮度），为空时不构造、聊天逐像素保持现状（契约 §3.4）。
    // 过渡丝滑化：壁纸路径与 conversation 同出一次合并查询（conversationWithWallpaper），二者**同帧到**——
    // 进会话时壁纸(含状态栏与底部手势条)与聊天内容同步出现、不再"晚一拍/割裂"（逐帧实证）。
    val chatWallpaperPath = conversationWithWallpaper?.chatWallpaperPath
    // 过渡丝滑化·B3：暖缓存命中则同步取出壁纸（与角色信息同帧出现，免"壁纸第三段弹入"）；冷/未命中走异步加载补齐。
    val peekedWallpaper = remember(chatWallpaperPath) { peekChatWallpaper(chatWallpaperPath) }
    val loadedWallpaper by produceState<ChatWallpaper?>(initialValue = null, chatWallpaperPath) {
        value = chatWallpaperPath?.let { loadChatWallpaper(it) }
    }
    val chatWallpaper = loadedWallpaper ?: peekedWallpaper
    // A4 剧场态 chrome（§4.8）：见面态 chrome 恒深玻璃——顶栏强制深向；非见面态传参逐字不动（仍按壁纸亮度自适应）。
    // 全屏恒暗舞台（2026-07-06 拍板修订）：见面态玻璃源**恒取**舞台源（不再优先亮壁纸磨砂）——舞台已铺满 chrome
    // 背后（下方窗口层），亮磨砂会与暗幕布割裂。底栏见面态玻璃源/深向由 ChatBottomBar 内部接线（§2.2）。
    val offlineChrome = conversation?.isInOfflineMode == true
    val offlineStageBackdrop = OfflineTheater.rememberStageBackdrop()
    val chromeFrosted = if (offlineChrome) offlineStageBackdrop else chatWallpaper?.frosted
    val chromeTopDark = if (offlineChrome) true else (chatWallpaper?.topDark == true)
    // chunk3 系统栏图标随壁纸亮度翻色（契约 §4.3/§7#4）：顶部壁纸深→状态栏图标转浅、浅→转深，保「时间/信号/电量」在
    // 壁纸上始终可读；底部导航手势条同理用 bottomDark。仅在有壁纸时介入；离开聊天/换主题/移除壁纸→恢复跟随 App 主题。
    // R-2 拍板 TODO-3（见面态纳入）：见面剧场恒暗舞台下，即使无壁纸也强制深底——顶/底一律浅图标，
    // 修「浅色 App 主题 + 无壁纸 + 见面」时深图标压恒暗舞台不可读的缺陷。非见面路径（含无壁纸普通聊天）逐字节不动。
    val barView = LocalView.current
    val appIsDark = AppTheme.colors.isDark
    DisposableEffect(chatWallpaper?.topDark, chatWallpaper?.bottomDark, appIsDark, offlineChrome) {
        val window = (barView.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, barView) }
        val wp = chatWallpaper
        val engage = wp != null || offlineChrome
        // 见面态强制深底（与 chrome 玻璃 chromeTopDark 同口径）；否则沿壁纸亮度自适应。
        val topDark = if (offlineChrome) true else (wp?.topDark == true)
        val bottomDark = if (offlineChrome) true else (wp?.bottomDark == true)
        val prevNavContrast = window?.isNavigationBarContrastEnforced
        if (engage && window != null && controller != null) {
            controller.isAppearanceLightStatusBars = !topDark
            controller.isAppearanceLightNavigationBars = !bottomDark
            // 壁纸要透到导航栏后：关掉系统在导航栏的对比 scrim——3 键/2 键导航默认会盖一层近白(浅)/暗(深)遮罩，
            // 即「底部白条」在非手势导航机型的来源（手势导航导航栏本就透明、不受影响）。离开本屏恢复原值（API 29+）。
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

    // M16 线下沉浸：当前 session 的可见消息（排除入场/离场标记与系统提示），供沉浸剧场渲染。
    // 12.3：常规列表已窗口化到最新 50 条，沉浸剧场改用 VM 的【全 session 不窗口化】流，避免长会话(>窗口)丢见面开头。
    val offlineMessages by viewModel.offlineSessionMessages.collectAsStateWithLifecycle()

    val snackbarHost = remember { SnackbarHostState() }
    // C3-haptics（契约 §2 触觉口径）：聊天屏全链路改经 LocalAppHaptics 分级语义，不再直调 LocalHapticFeedback。
    val haptics = LocalAppHaptics.current
    val reduceMotion = rememberReduceMotion()
    // 删除消息（问题①）：仅给「轻一声 + 轻触觉」反馈；删除走**数据驱动**——删库 → Flow 回灌 → 列表项即时移除，
    // 下方消息由 animateItem 的 placementSpec 平滑上收。本表里 animateItem 的「消失淡出」会把已移除项留在叠层按
    // alpha=1 画着不释放=残影，故在 item 处显式 fadeOutSpec=null（见那里注释）。不手搓退出动画。
    val playDeleteSound = rememberMessageDeleteSound()
    // chat-ui-8：AI 回复完成（打字占位 有→无）给轻触觉（≈ iOS 接收 soft impact）。
    // 审计 R6：触觉须「武装」后才发——ON_STOP 解除武装（下方生命周期观察者），回屏后首次 slot 派发只对齐基线：
    // AI 在后台答完的场景，回屏那刻 slot 有→无不再补一记迟到的「完成」触觉（幽灵触觉）。代价（有意接受）：
    // 后台往返且回屏时 AI 仍在打字 → 该轮完成触觉静默一次（对齐优先于误触发）。
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
            haptics.error() // chat-ui-8：出错给 error 档（≈ iOS .error）
            snackbarHost.showSnackbar(it)
            viewModel.clearError()
        }
    }
    LaunchedEffect(infoToast) {
        infoToast?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearInfoToast()
        }
    }
    LaunchedEffect(Unit) { viewModel.markRead() }
    // M16：进入会话即幂等修复线下脏状态 + 判定异常恢复提示（对齐 iOS onAppear）。
    LaunchedEffect(Unit) { viewModel.onChatAppear() }

    // P1-13：入场动效「可见窗口」——只有可见期间到达的消息播放入场动画（=iOS 仅实时到达的消息有 transition）；
    // 进入会话的历史消息与不可见期一次性插入的段都不动画（防回屏爆一串入场）。ON_STOP 关窗、ON_START 重开。
    var animateArrivalsSinceMillis by remember { mutableStateOf(Long.MAX_VALUE) }
    // 用户气泡入场缩放只播一次（滚走再滚回不重播；fadeIn 由 animateItem 按列表 diff 天然只播一次）。
    val entryScalePlayed = remember { mutableSetOf<String>() }
    // P1-5（批5）：情绪入场动画——已播集合（=iOS ChatViewModel.animatedMessageIDs 的本屏生命周期；
    // rememberSaveable 比 iOS 更保守：转屏/进程死恢复不重播）+ 不可见区间表。1h 窗与门控见 item 处。
    // 批5 复核 #1 修：不可见区间改「累积区间表」而非单水位区间——单水位会把『可见期到达但未组合』的
    // 消息（用户上滑时落底部）在一次后台往返后误杀（落入 openedAt..新水位，iOS 此场景滚到会播）；
    // 区间表精确只杀不可见期插入者（§10#11 永不播不标记），任意次往返不泄漏不过杀；开屏已在场的
    // 历史消息时间戳必早于一切区间起点，天然照播（=iOS 重进重播），无需单独水位子句。
    val emotionPlayed = rememberSaveable { ArrayList<String>() }
    // 审计 R5：区间表同升 saveable（拍平成 Long 对存）——emotionPlayed 已存活而本表裸 remember 时，
    // 后台积压情绪消息 + 回屏 1h 内转屏 → 区间表清空 → 本要压住的入场动画爆串（恰是区间表要防的）。
    val emotionHiddenIntervals = rememberSaveable(
        saver = listSaver(
            save = { intervals -> intervals.flatMap { listOf(it.first, it.last) } },
            restore = { flat -> flat.chunked(2).mapTo(mutableListOf()) { (a, b) -> a..b } },
        ),
    ) { mutableListOf<LongRange>() }

    // P0-27：退后台(ON_STOP)即停语音消息录制——安卓 pointerInput 后台不保证派发 up，故需显式生命周期兜底
    // (iOS 由手势中断自然收尾、无需此)。释放麦/清状态/丢半截草稿，避免悬空录音线程。顶层放置避免与录音手势 owner 冲突。
    // P1-4（批1）：同一观察者顺带喂视图可见性（=iOS isViewVisible）——组合存在且 STARTED 才算可见；
    // 退后台(ON_STOP)/退组合(onDispose) → 不可见，分段递送跳过打字节奏逐段即时插入。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val initiallyStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        viewModel.setViewVisible(initiallyStarted)
        if (initiallyStarted) animateArrivalsSinceMillis = System.currentTimeMillis()
        // P1-5：不可见区间起点（进入即不可见的边角同样计入，ON_START 时闭合成区间）。
        var emotionHiddenSince: Long? = if (initiallyStarted) null else System.currentTimeMillis()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> characterUuid?.let(worldStatusVm::refresh) // W13：回聊天页复刷世界位置行
                Lifecycle.Event.ON_START -> {
                    viewModel.setViewVisible(true)
                    animateArrivalsSinceMillis = System.currentTimeMillis()
                    // 观察者派发先于恢复帧重组 → 区间闭合先于任何 item 门控求值，无竞态。
                    emotionHiddenSince?.let { emotionHiddenIntervals.add(it..System.currentTimeMillis()) }
                    emotionHiddenSince = null
                    character?.uuid?.let(preflightVm::refresh) // VU3：从设置页修好返回聊天即刷 setupNeed→撤尾巴
                }
                Lifecycle.Event.ON_STOP -> {
                    viewModel.setViewVisible(false)
                    animateArrivalsSinceMillis = Long.MAX_VALUE
                    emotionHiddenSince = System.currentTimeMillis()
                    // 审计 R6：解除「回复完成」触觉武装——回屏后首次打字态派发只对齐、不发迟到触觉。
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

    // VU1 拨号门（§3.1）：无可用音色先弹「暖夜门厅」拦截 sheet 而非直拨（preflightVm 已在上方声明）。
    val startVoiceCall = rememberPreflightVoiceCallStarter(
        onCallStarted = onOpenVoiceCall,
        onOpenCharacterVoiceSettings = onOpenCharacterVoiceSettings,
        onOpenTtsConfig = onOpenTtsConfig,
        preflightVm = preflightVm,
    )

    var input by rememberSaveable { mutableStateOf("") }
    // 审计 S1：全部 sheet/dialog 开合状态收进管家（ChatScreenSheets.kt）——本屏只写开关、弹层渲染整体外移；
    // E1#0 的 showRedPacketSheet 跨重建存活语义在 holder 的 Saver 里逐位保留。
    val sheets = rememberChatSheetsState()
    // chat「+」功能面板（契约 FABLE5_CHAT_PLUS_PANEL_PROPOSAL.md）：键盘↔面板无缝切换、输入托盘锚定不动。
    val softwareKeyboard = LocalSoftwareKeyboardController.current
    val inputFocusManager = LocalFocusManager.current
    val inputFieldFocus = remember { FocusRequester() }
    // 面板高度钳制边界（契约 §3 边界硬化）：min=160dp 滤悬浮/分屏键盘的 0/极小 inset、防塌一条（真实键盘恒高于此）；
    // max=屏高 60% 防异常畸高（键盘绝不会这么高）。
    val panelMinPx = with(LocalDensity.current) { 160.dp.roundToPx() }
    val panelMaxPx = with(LocalDensity.current) { (LocalConfiguration.current.screenHeightDp * 0.6f).dp.roundToPx() }
    val inputPanel = rememberChatInputPanelState(softwareKeyboard, inputFocusManager, inputFieldFocus, panelMinPx, panelMaxPx)
    val panelFallbackPx = with(LocalDensity.current) { 300.dp.roundToPx() }
    BackHandler(enabled = inputPanel.panelOpen) { inputPanel.dismiss(reduceMotion) }
    val micPermission = rememberMicPermissionState() // P13.4b 语音消息录音入口
    val listState = rememberLazyListState()
    val scrollCoordinator = rememberChatScrollCoordinator(listState)
    // P0-16：拖动消息列表即收键盘（≈ iOS scrollDismissesKeyboard(.interactively)，方向无关）。
    // 仅在真实手指拖动（UserInput）时清焦，避免新消息到达时的 animateScrollToItem 误收键盘。
    val dismissKeyboardOnDrag = remember(inputFocusManager) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) inputFocusManager.clearFocus()
                return Offset.Zero
            }
        }
    }
    // M3a ④发送飞入·握手地基（契约 §4·拍板 D6/D7/D8·总闸 SEND_FLIGHT_ENABLED 关=行为零变化）：
    // 押后清空握手状态 + 200ms 超时兜底；闸链在发送点击时评估（列表在底=回底 FAB 口径取反·见下方传参）。
    val sendFlight = remember { ChatSendFlightState() }
    LaunchedEffect(sendFlight.pending) {
        if (sendFlight.pending != null) {
            delay(HANDSHAKE_TIMEOUT_MS)
            sendFlight.resolveByTimeout()
        }
    }
    // M2 沉浸菜单（契约 FABLE5_CHAT_TELEGRAM_MOTION_PROPOSAL §3·拍板 D4/D5）：长按气泡 → 拍窗口快照
    // （冻结画面+一次性毛玻璃）→ 开覆盖层。快照失败优雅退纯压暗档。状态瞬态不入 saveable（转屏即关=Telegram）。
    val immersiveMenu = remember { ChatImmersiveMenuState() }
    // 图片相关瞬态（查看器开关 + 发图入口显隐）——收在 ChatImageState 里，屏内不再逐个摊开。
    val imageState = rememberChatImageState(viewModel)
    // V9 删除弹簧预武装（契约 REVERSE_LIST §9）：删除点击时**先于下一帧**递增，列表据此临时启用位移弹簧
    // 保「收拢」观感（位移动画平时关闭=变身长高刚性锁步，见 ChatMessageList 声明处）。
    val deleteArm = remember { mutableStateOf(0L) }
    val chatRootView = LocalView.current
    val menuScope = rememberCoroutineScope()
    // 审计 S7：21+1 个行级回调 remember 一次收进稳定对象（M2 自 ChatMessageList 上提——覆盖层与行共用同源动作面）。
    val actions = remember(viewModel, sheets) {
        MessageRowActions(
            onVoiceToggle = { viewModel.toggleVoicePlayback(it) },
            onOpenImage = { path -> imageState.viewerImagePath = path },
            onSaveImage = { msg -> viewModel.image.saveToGallery(msg.imageRelativePath) },
            onQuote = { viewModel.setReplyTarget(it) },
            onDelete = { msg ->
                playDeleteSound() // 轻一声（静音/振动档自动跳过）
                haptics.light() // 轻微震动
                deleteArm.value++ // V9：先于删除帧武装位移弹簧窗（收拢动画）
                viewModel.deleteMessage(msg) // 即删——删除窗内 placementSpec 负责收拢
            },
            onOpenMenu = { msg, boundsInWindow, canRegenerate ->
                menuScope.launch {
                    val backdrop = captureImmersiveBackdrop(chatRootView)
                    val bounds = backdrop?.let {
                        boundsInWindow.translate(-it.viewOffsetInWindow.x.toFloat(), -it.viewOffsetInWindow.y.toFloat())
                    } ?: boundsInWindow
                    immersiveMenu.open(msg, bounds, backdrop?.snapshot, backdrop?.frosted, canRegenerate)
                }
            },
            onFlightBubblePositioned = { msg, bounds ->
                // M3b ④就位帧：入场记账先标（该行入场动画由飞行接管·落地后绝不补播），再交状态机
                // （握手中=清空+起飞同帧；飞行中=移动靶更新）。
                if (sendFlight.pending?.matches(msg) == true) entryScalePlayed.add(msg.messageUUID)
                sendFlight.onBubblePositioned(msg, bounds)
            },
            onRegenerate = { viewModel.regenerate() },
            loadDiyImage = { viewModel.loadGiftDiyImage(it) },
            onOpenDiyDetail = { recordUuid -> menuScope.launch { sheets.diyDetailRecord = viewModel.giftRecord(recordUuid) } },
            observeRedPacket = { viewModel.observeRedPacketRecord(it) },
            onRedPacketClick = { sheets.redPacketDetail = it },
            onAcceptInvite = { viewModel.acceptOfflineInvite(it) },
            onDeclineInvite = { viewModel.declineOfflineInvite(it) },
            onEndMeeting = { viewModel.exitOfflineMode() },
            onContinueMeeting = { viewModel.continueOfflineMeeting(it) },
            onReviewOffline = { viewModel.openOfflineReview(it) },
            observeAppointment = { viewModel.observeAppointment(it) },
            onAppointmentAccept = { viewModel.acceptAppointment(it) },
            onAppointmentDecline = { viewModel.declineAppointment(it) },
            onAppointmentReschedule = { sheets.rescheduleAppointmentUuid = it },
            onAppointmentChangeApply = { viewModel.applyMeetingChange(it) },
            onAppointmentChangeKeep = { viewModel.keepMeetingChange(it) },
            onVoiceCascadePlayed = { entryScalePlayed.add(it.messageUUID) },
            // VU3 尾巴点击深链（§3.3）：点击读 setupNeed.value（无陈旧快照）·全局配置缺→ttsConfig / 其余→角色编辑?focusVoice·
            // character null 兜底全局页；preflightVm/nav 跨重组稳定→不扩 remember key。
            onOpenVoiceSetup = { if (preflightVm.setupNeed.value == VoiceSetupNeed.GLOBAL_CONFIG) onOpenTtsConfig() else character?.uuid?.let(onOpenCharacterVoiceSettings) ?: onOpenTtsConfig() },
        )
    }
    // B1：渲染列表 = Room 消息流 + 打字占位槽合并（builder 算时间分隔 / iOS 间距 / 占位 dedup）。打字气泡与首段同 key
    // → 同一列表项原地变身（替代旧「itemsIndexed(messages) + 独立 TypingRow + 让位」三件套）。
    val renderItems = remember(messages, typingSlot) { buildChatRenderItems(messages, typingSlot, System.currentTimeMillis()) }
    // 底部锚定（契约 FABLE5_CHAT_REVERSE_LIST_PROPOSAL §2 ②）：builder 仍按时间正序（分隔/间距语义零碰），
    // 喂给反转 LazyColumn 的视图取 asReversed（轻量视图·index 0 = 最新）。
    val listItems = remember(renderItems) { renderItems.asReversed() }
    val itemCount = renderItems.size
    // 12.3 窗口化：只在【新消息到达底部】（最后一条 id 变化）或打字态切换时锚定到底，**不**在【上翻加载更早
    // 消息】时滚动——反转列表下旧消息落在尾部索引，天然不扰动视口（契约 §2.2 末行·较旧顶锚的 key 锚定保位更稳）。
    // chat-ui-2：到底锚定的 LaunchedEffect 下移到 isNearBottom/userHasScrolled 声明之后（见下方守卫版）。
    // 反转口径（契约 §2.2）：firstVisibleItem = 视觉底部第一条可见项；index 0 = 最新消息。
    val showScrollDown by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }
    // 是否贴近列表底部（最新消息区域）：决定上翻加载触发（仅离底时）与回底缩窗。
    val isNearBottom by remember {
        derivedStateOf { listState.firstVisibleItemIndex <= 1 }
    }
    // 仅在用户【手动】滚动过之后才允许上翻加载（1:1 iOS hasUserInteractedWithScroll）：避免初次打开自动滚到底
    // 的过程中（首帧从顶部渲染）误触发加载。监听拖拽交互——程序化 animateScrollToItem 不发 DragInteraction，
    // 故初始自动滚底不会置 true。
    // 审计 R4：saveable 存活转屏/深色切换（Activity 重建）——否则复位 false 后，下方到底锚定效果首跑
    // 会把 rememberLazyListState 刚恢复的阅读位置瞬时砸到底（上翻读历史时转屏即丢位置）。
    var userHasScrolled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) userHasScrolled = true
        }
    }
    // chat-ui-2：新消息/打字态变化时，仅当用户未手动上翻、或仍贴近底部时才自动滚到底（1:1 iOS
    // shouldHonorBottomRequest = !hasUserInteractedWithScroll || isNearBottom，ChatView+Scroll.swift:115）。
    // 上翻看历史时来新消息不再被强拉到底，改由 showScrollDown 回底 FAB 让用户自行回底。
    val lastMessageId = messages.lastOrNull()?.messageUUID
    // 过渡丝滑化·C：首次定位（打开会话/消息首次到位）瞬时落底（scrollToItem），仅之后新消息/打字态才动画滚，
    // 避免打开会话时从顶部可见地滚到底的不稳定感。沿用「未上翻或仍贴底才跟随」守卫。
    var didInitialScroll by rememberSaveable { mutableStateOf(false) } // 审计 R4：随 userHasScrolled 同升 saveable
    LaunchedEffect(lastMessageId, typingSlot) {
        if (itemCount > 0 && (!userHasScrolled || isNearBottom)) {
            // 首次定位瞬时落底、其后动画滚（减弱动画→仍瞬时）；统一交协调员单飞合并，杜绝连发竞速（P2·G4）。
            // 落点恒 index 0（反转列表·内化在协调员）。
            scrollCoordinator.stickToBottom(animate = didInitialScroll && !reduceMotion)
            didInitialScroll = true
        }
    }
    // 键盘联动锚底（契约 FABLE5_CHAT_REVERSE_LIST_PROPOSAL §2 ④）：反转列表视口缩放天然钉底——键盘/面板
    // 升降只裁视觉顶部旧消息，最新气泡物理钉在托盘上沿。旧顶锚时代「IME inset 增长逐帧 scrollToItem 贴底」
    // 效应已删除（其守卫与新消息贴底在竞态帧同时撒手=遮挡病根，见契约 §1）。
    // 审计 P4：ime insets 只捕获句柄、绝不在组合期读值——读点全部下沉到 snapshotFlow（下方面板学高效应）与
    // 面板区高度的布局 lambda（bottomBar 处）。键盘升降动画期间（15-25 帧）ChatScreen 不再整屏逐帧重组；
    // 面板状态机与「实时学键盘高」机制原样（契约 FABLE5_CHAT_PLUS_PANEL §3/§5 硬指标不动）。
    val imeInsets = WindowInsets.ime
    // chat「+」面板用「消费感知」的键盘高度：原始 WindowInsets.ime 含手势条那一段，而本屏外层已消费导航栏 inset
    // （内容垫在导航栏内），直接用原始值会让底部区域多垫一条导航栏高、托盘与键盘间空一道缝。减去导航栏 inset
    // = 旧 imePadding 的消费感知等效值，托盘即贴紧键盘（边到边布局贴键盘的标准做法）。
    val navBarInsets = WindowInsets.navigationBars
    val density = LocalDensity.current
    // chat「+」面板：每帧 IME 高度变化驱动「学习真实键盘高度 + 切回键盘时无缝释放面板锁定」（契约 §3/§5）。
    LaunchedEffect(Unit) {
        snapshotFlow { imeInsets.exclude(navBarInsets).getBottom(density) }
            .collect { inputPanel.onImeHeightChanged(it) }
    }
    // 12.3 上翻加载更早消息：用户滚动过、滑到接近顶部、离底、且还有更早消息 → 窗口 +50（1:1 iOS shouldLoadMore：
    // hasMore && hasUserInteractedWithScroll && !isNearBottom；延迟 200ms 防快速滑动连触）。Compose 按 key 锚定，prepend 不跳。
    val hasMoreOlder by viewModel.hasMoreOlderMessages.collectAsStateWithLifecycle()
    val shouldLoadOlder by remember {
        derivedStateOf {
            // 反转口径：接近视觉顶部 = 最大可见 index 进入最早 4 项（契约 §2.2）。
            val layout = listState.layoutInfo
            val topVisible = layout.visibleItemsInfo.lastOrNull()
            userHasScrolled && hasMoreOlder && !isNearBottom &&
                topVisible != null && topVisible.index >= layout.totalItemsCount - 4
        }
    }
    LaunchedEffect(shouldLoadOlder) {
        if (shouldLoadOlder) {
            delay(200)
            viewModel.loadOlderMessages()
        }
    }
    // 12.3 回到底部停留 5s → 缩窗回 50，释放多余历史（1:1 iOS 近底缩减；中途离底则取消，不缩）。
    LaunchedEffect(isNearBottom) {
        if (isNearBottom) {
            delay(5_000)
            viewModel.shrinkMessageWindow()
        }
    }

    // offline-1：用外层 Box 容纳全屏「见面回顾」覆盖层（盖住顶栏，避免误用聊天返回键）。
    Box(Modifier.fillMaxSize()) {
    // 壁纸全屏沉浸重构②（参照 RikkaHub）：NavHost 不再垫付 → 本屏已铺满整个 window，壁纸直接 fillMaxSize 自然
    // 铺到状态栏/导航栏后（不再需要 clawback 手术）。顶栏/输入托盘各自 statusBarsPadding/navigationBarsPadding 让位。
    // 全屏恒暗舞台（2026-07-06 拍板修订·契约 §4.2 修正）：见面态舞台层从内容区上移到窗口层——壁纸+幕布（或粒子/
    // 纯色舞台）铺满整屏含状态栏/输入托盘后，消灭「顶底亮壁纸夹中间暗舞台」的三明治割裂与两次裁切错位；
    // 亮壁纸层见面态不再绘制（舞台自绘壁纸且幕布底不透明，画了也是纯 overdraw）。非见面路径逐字节不动。
    // 卷三 V1（图纸 §4.1-A）：舞台层改 AnimatedVisibility——旗标翻 false 后幕布多活 450ms（delay 250）渐掀；进入方向 None（N2/J1）。
    if (!offlineChrome) chatWallpaper?.let { wp ->
        // 冷加载兜底（2026-07-06 拍板配套）：暖 peek 命中→壁纸第一帧即在、随整页从右滑入（含状态栏后）；
        // 异步晚到（冷）→柔和淡入一次、不再硬弹「状态栏事后适配」。背景垫 surface 底色置于 graphicsLayer 外
        // ——淡入期间保持整页不透明，避免透出转场中的底页；alpha 在 draw 层读取，不逐帧触发重组。
        val wallpaperAlpha = remember { Animatable(if (peekedWallpaper == null) 0f else 1f) }
        LaunchedEffect(Unit) { wallpaperAlpha.animateTo(1f, tween(AppMotion.SMOOTH_MS, easing = AppMotion.EaseOut)) }
        Image(
            wp.sharp,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .background(AppTheme.colors.surface.base)
                .graphicsLayer { alpha = wallpaperAlpha.value },
            contentScale = ContentScale.Crop,
        )
    }
    AnimatedVisibility(
        visible = offlineChrome,
        enter = EnterTransition.None,
        exit = if (reduceMotion) ExitTransition.None // E1：减弱动画→直切
        else fadeOut(tween(450, delayMillis = 250, easing = AppMotion.EaseOut)),
    ) {
        OfflineBackgroundView(
            backgroundStyle = appSettings.offlineBackgroundStyleRaw,
            particleStyle = appSettings.offlineParticleStyleRaw,
            backgroundColor = appSettings.offlineBackgroundColor,
            themeColorHex = character?.offlineThemeColorHex,
            chatWallpaperPath = chatWallpaperPath,
        )
    }
    Scaffold(
        // M2 沉浸菜单开着时：背后内容对读屏整体隐藏（Telegram IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        // 等价）——读屏只见覆盖层菜单；顺带静默期间的 liveRegion 播报（=Telegram 菜单期暂停应用内通知）。
        modifier = if (immersiveMenu.isOpen) Modifier.clearAndSetSemantics {} else Modifier,
        // 卷三复核 R1·D-6 代办（遮挡类功能缺陷·REDLINES UI 例外②）：见面态（offlineChrome）必须透明——
        // 无聊天壁纸的角色进见面时，恒暗舞台画在窗口层，此处不透明 surface.base 会把整个舞台盖住
        //（浅底+被强制转浅的状态栏图标=几乎不可读）。A/B 已证为预存在缺陷（非卷三引入），修法=补一个透明条件。
        containerColor = if (chatWallpaper != null || offlineChrome) Color.Transparent else AppTheme.colors.surface.base,
        // 壁纸全屏沉浸重构②：顶栏(ChatTopBar statusBarsPadding)/底栏(托盘 navigationBarsPadding)各自让位系统栏，
        // 故 content 区不再吃系统栏 inset（NavHost 去 consume 后默认 systemBars 会双吃，显式归零）。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            // Fable-5 顶栏（契约 §3.1·D2）：36dp 头像引领的左对齐「角色此刻在场」信息条；整块=单一档案入口。
            // E3 定点修：外层 AIChatApp Scaffold 无 topBar，innerPadding 已含状态栏高度——旧 M3 TopAppBar 自带
            // windowInsets 再吃一遍状态栏造成顶部双倍空隙；自绘顶栏不再附加状态栏 inset。
            ChatTopBar(
                characterName = characterName,
                loading = headerLoading,
                avatarPath = avatarPath,
                scheduleStatus = scheduleStatus,
                moodEmoji = conversation?.moodEmoji.orEmpty(),
                moodText = conversation?.moodText.orEmpty(),
                moodColorName = conversation?.moodColorName.orEmpty(),
                isInOfflineMode = conversation?.isInOfflineMode == true,
                characterUuid = characterUuid,
                wallpaperFrosted = chromeFrosted,
                wallpaperDark = chromeTopDark,
                onBack = onBack,
                onOpenProfile = onOpenProfile,
                onEndMeeting = { viewModel.exitOfflineMode() },
                canStartCall = conversation != null && character != null,
                onStartCall = {
                    val convo = conversation
                    val char = character
                    if (convo != null && char != null) startVoiceCall(convo.uuid, char.uuid)
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbarHost) },
        bottomBar = {
            // 审计刀C（2026-07-02 到期执行·文件破 ⛔800 触发）：输入托盘+面板宿主+弹层管家整块只搬不改
            // 抽 ChatBottomBar.kt；input 状态仍归本屏（saveable 语义不变），经 onInputChange 回写。
            ChatBottomBar(
                viewModel = viewModel,
                sheets = sheets,
                inputPanel = inputPanel,
                micPermission = micPermission,
                inputFieldFocus = inputFieldFocus,
                panelFallbackPx = panelFallbackPx,
                input = input,
                onInputChange = { input = it },
                sendFlight = sendFlight,
                // M3a 闸链（D6/D7/D8）：总闸(M3a 恒关)/减弱动画/列表在底(回底 FAB 口径取反)/无引用面板/无进行中握手。
                sendFlightGates = {
                    sendFlightGatesOpen(
                        enabled = SEND_FLIGHT_ENABLED,
                        reduceMotion = reduceMotion,
                        listAtBottom = !showScrollDown,
                        quoteReplyActive = replyTarget != null,
                        flightBusy = sendFlight.busy,
                    )
                },
                chatWallpaper = chatWallpaper,
                pendingCalendarActions = pendingCalendarActions,
                characterName = characterName,
                avatarPath = avatarPath,
                customStickers = customStickers,
                coinBalance = coinBalance,
                isOfflineMode = conversation?.isInOfflineMode == true,
                offlineImmersiveInputEnabled = appSettings.offlineImmersiveInputEnabled,
                offlineThemeColorHex = character?.offlineThemeColorHex,
                replyTarget = replyTarget,
                voiceDraft = voiceDraft,
                playingVoiceId = playingVoiceId,
                voiceRecording = voiceRecording,
                voiceRecordingLevel = voiceRecordingLevel,
                voiceRecordingDurationMs = voiceRecordingDurationMs,
                voiceRecordingCancelling = voiceRecordingCancelling,
                offlineRecoveryVisible = offlineRecoveryVisible,
                chatModelHasVision = imageState.chatModelHasVision,
                onOpenStickerManagement = onOpenStickerManagement,
                reduceMotion = reduceMotion,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // W13 聊天状态行（图纸 §4.6）：content 区顶插胶囊·offline 态传 null 由 Row 内 AnimatedVisibility 收起（E12）。
            ChatWorldStatusRow(
                pill = if (conversation?.isInOfflineMode == true) null else worldPill,
                hasWallpaper = chatWallpaper != null,
                wallpaperFrosted = chromeFrosted,
                wallpaperDark = chromeTopDark,
                onOpenWorldAt = onOpenWorldAt,
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
            // 审计 Y1（本屏最大 a11y 缺口）：读屏播报节点（1dp 视觉无感）——AI 打字中/新回复到达时 TalkBack
            // Polite 自动播报（照 VoiceCallScreen/RedeemCode 惯例·文案稳定防刷屏；回复用脱敏预览，结构卡绝不露 JSON）。
            if (conversation?.isInOfflineMode != true) {
                val latestAssistant = messages.lastOrNull { it.roleRaw == "assistant" }
                val announce = if (typingSlot != null) {
                    stringResource(R.string.a11y_ai_typing, characterName)
                } else {
                    latestAssistant?.let { stringResource(R.string.a11y_ai_replied, characterName, MessagePreviewText.forMessage(it)) } ?: ""
                }
                Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announce })
            }
            // M16 线下沉浸剧场：见面中整列表替换为 OfflineModeView（锚底滚动；舞台背景在上方窗口层全屏绘制）；否则常规消息列表。
            // 卷三 V2（图纸 §4.1-B）：新内容渐亮 togetherWith 旧舞台谢幕（alpha→0+scale 0.98）；进入方向/RM 走 None（E1/E2）·分支体逐字未改。
            AnimatedContent(
                targetState = conversation?.isInOfflineMode == true,
                transitionSpec = {
                    if (initialState && !targetState && !reduceMotion) {
                        val curtainFall = fadeOut(tween(350, easing = AppMotion.EaseOut)) +
                            scaleOut(targetScale = 0.98f, animationSpec = tween(350, easing = AppMotion.EaseOut))
                        fadeIn(tween(450, delayMillis = 250, easing = AppMotion.EaseOut)) togetherWith curtainFall
                    } else {
                        EnterTransition.None togetherWith ExitTransition.None
                    }.using(SizeTransform(clip = false))
                },
                label = "offlineExitTransition",
            ) { inOffline ->
                if (inOffline) {
                    OfflineModeView(
                        offlineMessages = offlineMessages,
                        isWaitingForContent = typingSlot != null || isSending,
                        characterName = characterName,
                        characterAvatarPath = avatarPath,
                        userName = userName,
                        userAvatarPath = userAvatarPath,
                        themeColorHex = character?.offlineThemeColorHex,
                        // chunk4：per-角色聊天壁纸盖过见面全局粒子/纯色背景（契约 §3.3/D8）。
                        chatWallpaperPath = chatWallpaperPath,
                        // P1-5：线下块入场动画与气泡情绪动画同一开关门控（=iOS OfflineModeView.swift:144
                        // `let animEnabled = settings.emotionAnimationEnabled`）。
                        entryAnimationsEnabled = appSettings.emotionAnimationEnabled,
                        playingVoiceId = playingVoiceId, // 卷三 V5：剧场语音回听三参（与聊天列表同源·零新状态）
                        voiceProgress = voiceProgressProvider,
                        onVoiceToggle = { viewModel.toggleVoicePlayback(it) },
                        onEndMeeting = { viewModel.exitOfflineMode() },
                        onContinueMeeting = { viewModel.continueOfflineMeeting(it) },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (messagesLoaded && messages.isEmpty() && typingSlot == null) {
                    // B1：仅在"消息已加载且确实为空"时显空会话引导；加载中(!messagesLoaded)走下方空列表分支=稳定背景，不闪引导。
                    EmptyConversationHint(
                        characterName = characterName,
                        avatarPath = avatarPath,
                        persona = character?.personalityDescription.orEmpty(),
                        onStarter = { viewModel.send(it) },
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    )
                } else {
                    // 审计 S2：常规消息列表分支整体搬 ChatMessageList.kt（含网络横幅/约定倒数条/回底 FAB·自带同尺寸
                    // Box=原 align 语义原位保留）；日历 toast 留本层外 Box = 堆叠次序不变。
                    ChatMessageList(
                        viewModel = viewModel,
                        sheets = sheets,
                        listState = listState,
                        scrollCoordinator = scrollCoordinator,
                        listItems = listItems,
                        messages = messages,
                        dismissKeyboardOnDrag = dismissKeyboardOnDrag,
                        playingVoiceId = playingVoiceId,
                        voiceProgress = voiceProgressProvider,
                        reduceMotion = reduceMotion,
                        emotionAnimationEnabled = appSettings.emotionAnimationEnabled,
                        animateArrivalsSinceMillis = animateArrivalsSinceMillis,
                        entryScalePlayed = entryScalePlayed,
                        emotionPlayed = emotionPlayed,
                        emotionHiddenIntervals = emotionHiddenIntervals,
                        actions = actions,
                        userScrollEnabled = !immersiveMenu.isOpen,
                        deleteArm = deleteArm,
                        sendFlight = sendFlight,
                        characterName = characterName,
                        avatarPath = avatarPath,
                        userName = userName,
                        userAvatarPath = userAvatarPath,
                        customStickers = customStickers,
                        isSending = isSending,
                        networkConnected = networkConnected,
                        networkStatusChanged = networkStatusChanged,
                        showScrollDown = showScrollDown,
                        wallpaper = chatWallpaper,
                        voiceSetupNeeded = voiceSetupNeed != null, // VU3：当前仍缺可用音色 → 失败通话卡长琥珀尾巴
                    )
                }
            }

            // P5.3b 日历操作成功提示，浮现在消息区顶部，4 秒自动消失（VM 计时）。
            // W13：抽成 BoxScope 收方（无 ColumnScope）——外层 Column 引入的 ColumnScope 会与 AnimatedVisibility 隐式接收者冲突。
            ChatCalendarToast(
                text = calendarToast?.text,
                isDelete = calendarToast?.isDelete == true,
                reduceMotion = reduceMotion,
                onDismiss = { viewModel.dismissCalendarToast() },
            )
            } // W13：闭合 content Box(weight 1f)（原 Box 子树只降一层·图纸 §4.6）
        }
    }

        // M3b ④发送飞入覆盖层（契约 §4）：画在列表与输入托盘之上（=Telegram MessageEnterTransitionContainer
        // 层级）；沉浸菜单/见面回顾声明在后=仍居其上。
        ChatSendFlightOverlay(state = sendFlight, wallpaper = chatWallpaper)

        // M2 沉浸菜单覆盖层（契约 TELEGRAM_MOTION §3）：盖顶栏/输入栏/系统栏区域=Telegram 整屏 scrim；
        // 见面回顾覆盖层声明在后=仍居其上（二者不并存：菜单只在常规列表长按开启）。
        ChatImmersiveMenuOverlay(
            state = immersiveMenu,
            actions = actions,
            reduceMotion = reduceMotion,
        )

        // offline-1：只读见面回顾覆盖层（盖住聊天页含顶栏；系统返回键关闭）。
        ChatOfflineReviewOverlay(
            info = offlineReviewInfo,
            messages = offlineReviewMessages,
            characterName = characterName,
            avatarPath = avatarPath,
            userName = userName,
            userAvatarPath = userAvatarPath,
            themeColorHex = character?.offlineThemeColorHex,
            appSettings = appSettings,
            chatWallpaperPath = chatWallpaperPath,
            onBack = { viewModel.closeOfflineReview() },
        )

        // 全屏图片查看器（Dialog 覆盖全屏·恒黑底）：点图片气泡开、单击/返回关。
        ChatImageViewerHost(imageState)
    }
}
