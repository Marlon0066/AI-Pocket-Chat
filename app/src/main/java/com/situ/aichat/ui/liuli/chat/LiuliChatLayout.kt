package com.situ.aichat.ui.liuli.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.ui.chat.MessagePreviewText
import com.situ.aichat.ui.chat.rememberQuoteTextOnlyHint
import com.situ.aichat.ui.chat.ChatViewModel
import com.situ.aichat.ui.chat.ChatWallpaper
import com.situ.aichat.ui.chat.ChatWorldPill
import com.situ.aichat.ui.chat.SEND_FLIGHT_ENABLED
import com.situ.aichat.ui.chat.sendFlightGatesOpen
import com.situ.aichat.ui.chat.TypingSlot
import com.situ.aichat.ui.chat.buildChatRenderItems
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.liuli.glass.BackdropHost
import com.situ.aichat.ui.offline.OfflineBackgroundView
import com.situ.aichat.ui.offline.OfflineModeView
import com.situ.aichat.ui.offline.OfflineTheater
import com.situ.aichat.ui.offline.parseOfflineThemeColor

/**
 * 琉璃聊天屏布局（图纸 2026-09-05 卷二A §3.1 通路图）：
 * [BackdropHost] `content` = 背景（心情四色 / 壁纸）+ 见面舞台 + 列表区 + 面板区；`overlay` = 顶栏 / 世界胶囊
 * / 输入区（玻璃片必须在 overlay，否则录进自己的 layer 成递归·卷一 `BackdropHost` KDoc）。
 *
 * **C1 过渡态**：列表区暂借暖陶 `ChatMessageList`、输入区暂借 `ChatBottomBar`（图纸 §8 明写「本 chunk 内临时」），
 * C2 / C3 分别替换成 `LiuliChatList` / `LiuliInputBar`。
 */
@Composable
internal fun LiuliChatLayout(
    viewModel: ChatViewModel,
    session: LiuliChatSession,
    conversation: ConversationEntity?,
    character: CharacterEntity?,
    messages: List<MessageEntity>,
    typingSlot: TypingSlot?,
    worldPill: ChatWorldPill?,
    scheduleStatus: String?,
    chatWallpaper: ChatWallpaper?,
    chatWallpaperPath: String?,
    wallpaperPeeked: Boolean,
    voiceSetupNeeded: Boolean,
    reduceMotion: Boolean,
    canStartCall: Boolean,
    onStartCall: () -> Unit,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenPromises: (String) -> Unit = {},
    onOpenStickerManagement: () -> Unit,
    onOpenWorldAt: (String) -> Unit,
) {
    val characterName = character?.name?.takeIf { it.isNotBlank() } ?: conversation?.title.orEmpty()
    val avatarPath = character?.avatarPath
    // B2 照抄：会话与角色都未就绪 = 加载中（conversation 非空即可判定）。
    val headerLoading = conversation == null && character == null
    val characterUuid = conversation?.characterUuid
    val offlineChrome = conversation?.isInOfflineMode == true
    val moodEmoji = conversation?.moodEmoji.orEmpty()

    val messagesLoaded by viewModel.messagesLoaded.collectAsStateWithLifecycle()
    val customStickers by viewModel.customStickers.collectAsStateWithLifecycle()
    val isSending by viewModel.isSending.collectAsStateWithLifecycle()
    val coinBalance by viewModel.coinBalance.collectAsStateWithLifecycle()
    val networkConnected by viewModel.networkConnected.collectAsStateWithLifecycle()
    val networkStatusChanged by viewModel.networkStatusChanged.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val replyTarget by viewModel.replyTarget.collectAsStateWithLifecycle()
    val pendingCalendarActions by viewModel.pendingCalendarActions.collectAsStateWithLifecycle()
    val calendarToast by viewModel.calendarToast.collectAsStateWithLifecycle()
    val promiseHint by viewModel.promiseHint.collectAsStateWithLifecycle() // 约定记账当场提示（图纸 2026-09-06）
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val offlineRecoveryVisible by viewModel.offlineRecoveryPromptVisible.collectAsStateWithLifecycle()
    val offlineMessages by viewModel.offlineSessionMessages.collectAsStateWithLifecycle()
    val voiceDraft by viewModel.voiceDraft.collectAsStateWithLifecycle()
    val voiceRecording by viewModel.voiceRecording.collectAsStateWithLifecycle()
    val voiceRecordingLevel by viewModel.voiceRecordingLevel.collectAsStateWithLifecycle()
    val voiceRecordingDurationMs by viewModel.voiceRecordingDurationMs.collectAsStateWithLifecycle()
    val voiceRecordingCancelling by viewModel.voiceRecordingCancelling.collectAsStateWithLifecycle()
    // 审计 P3 照抄：播放态只持句柄不整读——progress 以 lambda 下传。
    val playbackState = viewModel.playbackState.collectAsStateWithLifecycle()
    val playingVoiceId by remember {
        derivedStateOf { playbackState.value.let { if (it.isPlaying) it.playingId else null } }
    }
    val voiceProgressProvider = remember { { playbackState.value.progress } }

    // A-6：副标「此刻」= UI 侧纯派生（VM 零改 / 数据零新增）。在本层算——`userProfile` / `appSettings`
    // 两流已经在这里收集过一次，挪到 `LiuliChatScreen` 会变成两处收集（图纸 §2.2 二选一，见 §11 D-7）。
    val innerStateLine = rememberLiuliInnerStateLine(
        character = character,
        userNickname = userProfile?.nickname,
        growthEnabled = appSettings.growthSystemEnabled,
    )
    val userName = userProfile?.nickname.orEmpty()
    val userAvatarPath = userProfile?.avatarPath

    // B1 照抄：渲染列表 = Room 消息流 + 打字占位槽合并（同 key 原地变身的承重点）。
    val renderItems = remember(messages, typingSlot) {
        buildChatRenderItems(messages, typingSlot, System.currentTimeMillis())
    }
    val listItems = remember(renderItems) { renderItems.asReversed() }

    // 排版几何（§4.7）：列表区自窗口顶起（edge-to-edge）→ 顶留白要含状态栏 inset；底留白另加导航栏 inset。
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val hasWorldPill = worldPill != null && !offlineChrome
    // 日期胶囊 ↔ 横幅互斥（照抄暖陶 topBannerVisible 口径）。
    val arrivalAppt by viewModel.arrivalAppointment.collectAsStateWithLifecycle()
    val countdownAppt by viewModel.nextCountdownAppointment.collectAsStateWithLifecycle()
    val topBannerVisible = !networkConnected || networkStatusChanged == true ||
        arrivalAppt != null || countdownAppt != null || promiseHint != null
    // 引用一期 E：三个触发点（语音 / 表情 / 照片）共用一条提示 → 由本层持有、分给输入区与面板区。
    val quoteHint = rememberQuoteTextOnlyHint(replyTarget)
    // 审计 P4：只捕获句柄，取值全在事件 / 布局 lambda 里（组合期绝不读 ime）。
    val imeInsets = WindowInsets.ime
    val navBarInsets = WindowInsets.navigationBars
    val density = LocalDensity.current
    // 复核 R1 🔴-1：输入区是 overlay 浮件，不占列表区高度——它长高（引用条 / 日历卡 / 草稿条 / 多行输入）时
    // 列表底留白、回底钮、snackbar 必须一起让位，否则盖住最新一条气泡。实测高（不含导航栏）经 onSizeChanged 回流，
    // 首帧初值取默认 56dp 免跳。
    var inputOverlayHeightPx by remember {
        mutableIntStateOf(with(density) { LiuliChatGeometry.inputOverlayDefaultHeight.roundToPx() })
    }
    val inputOverlayHeight = with(density) { inputOverlayHeightPx.toDp() }
    val listBottomPadding = LiuliChatGeometry.listBottomPadding(inputOverlayHeight) + navBarBottom
    val inputRegionPx: () -> Int = { session.inputPanel.regionPx(imeInsets.exclude(navBarInsets).getBottom(density)) }

    Box(Modifier.fillMaxSize()) {
        BackdropHost(
            modifier = Modifier.fillMaxSize(),
            state = session.backdrop,
            content = {
                if (!offlineChrome) {
                    LiuliChatBackground(
                        moodEmoji = moodEmoji,
                        sendTurn = session.sendTurn,
                        wallpaper = chatWallpaper,
                        peeked = wallpaperPeeked,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // 卷三 V1 照抄：旗标翻 false 后幕布多活 450ms（delay 250）渐掀；进入方向 None。
                AnimatedVisibility(
                    visible = offlineChrome,
                    enter = EnterTransition.None,
                    exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(450, delayMillis = 250, easing = AppMotion.EaseOut)),
                ) {
                    OfflineBackgroundView(
                        backgroundStyle = appSettings.offlineBackgroundStyleRaw,
                        particleStyle = appSettings.offlineParticleStyleRaw,
                        backgroundColor = appSettings.offlineBackgroundColor,
                        themeColorHex = character?.offlineThemeColorHex,
                        chatWallpaperPath = chatWallpaperPath,
                    )
                }
                Column(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            // 面板开着点空白 = 缩回（E9 第三路·图纸 §4.4）：父级手势，列表照滚、气泡 / 回底钮照点
                            // （复核 R1 🔴-1：兄弟层会独占指针）。沉浸菜单开着时不装（它自己收场）。
                            .liuliDismissPanelOnTap(
                                active = session.inputPanel.panelOpen && !session.immersiveMenu.isOpen,
                                onTap = { session.inputPanel.dismiss(reduceMotion) },
                            ),
                    ) {
                        // 审计 Y1 照抄：读屏播报节点（1dp 视觉无感）。
                        if (!offlineChrome) {
                            val latestAssistant = messages.lastOrNull { it.roleRaw == "assistant" }
                            val announce = if (typingSlot != null) {
                                stringResource(R.string.a11y_ai_typing, characterName)
                            } else {
                                latestAssistant?.let {
                                    stringResource(R.string.a11y_ai_replied, characterName, MessagePreviewText.forMessage(it))
                                } ?: ""
                            }
                            Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announce })
                        }
                        AnimatedContent(
                            targetState = offlineChrome,
                            transitionSpec = {
                                if (initialState && !targetState && !reduceMotion) {
                                    val curtainFall = fadeOut(tween(350, easing = AppMotion.EaseOut)) +
                                        scaleOut(targetScale = 0.98f, animationSpec = tween(350, easing = AppMotion.EaseOut))
                                    fadeIn(tween(450, delayMillis = 250, easing = AppMotion.EaseOut)) togetherWith curtainFall
                                } else {
                                    EnterTransition.None togetherWith ExitTransition.None
                                }.using(SizeTransform(clip = false))
                            },
                            label = "liuliOfflineExitTransition",
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
                                    chatWallpaperPath = chatWallpaperPath,
                                    entryAnimationsEnabled = appSettings.emotionAnimationEnabled,
                                    playingVoiceId = playingVoiceId,
                                    voiceProgress = voiceProgressProvider,
                                    onVoiceToggle = { viewModel.toggleVoicePlayback(it) },
                                    onEndMeeting = { viewModel.exitOfflineMode() },
                                    onContinueMeeting = { viewModel.continueOfflineMeeting(it) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else if (messagesLoaded && messages.isEmpty() && typingSlot == null) {
                                LiuliEmptyHint(
                                    characterName = characterName,
                                    avatarPath = avatarPath,
                                    persona = character?.personalityDescription.orEmpty(),
                                    // 心情圈与顶栏同源（同一个 moodEmoji → 同一处取色·A-18）。
                                    moodEmoji = moodEmoji,
                                    onStarter = { viewModel.send(it) },
                                    modifier = Modifier.fillMaxSize().padding(horizontal = LiuliChatGeometry.listHorizontal),
                                )
                            } else {
                                LiuliChatList(
                                    listState = session.listState,
                                    listItems = listItems,
                                    messages = messages,
                                    dismissKeyboardOnDrag = session.dismissKeyboardOnDrag,
                                    playingVoiceId = playingVoiceId,
                                    voiceProgress = voiceProgressProvider,
                                    reduceMotion = reduceMotion,
                                    emotionAnimationEnabled = appSettings.emotionAnimationEnabled,
                                    animateArrivalsSinceMillis = session.animateArrivalsSinceMillis,
                                    entryScalePlayed = session.entryScalePlayed,
                                    emotionPlayed = session.emotionPlayed,
                                    emotionHiddenIntervals = session.emotionHiddenIntervals,
                                    actions = session.actions,
                                    userScrollEnabled = !session.immersiveMenu.isOpen,
                                    deleteArm = session.deleteArm,
                                    sendFlight = session.sendFlight,
                                    reaction = session.reaction,
                                    fold = session.fold,
                                    characterName = characterName,
                                    avatarPath = avatarPath,
                                    userName = userName,
                                    userAvatarPath = userAvatarPath,
                                    customStickers = customStickers,
                                    isSending = isSending,
                                    contentTopPadding = LiuliChatGeometry.listTopPadding(statusBarTop, hasWorldPill),
                                    contentBottomPadding = listBottomPadding,
                                    voiceSetupNeeded = voiceSetupNeeded,
                                )
                            }
                        }
                        // 横幅族（过渡借用·卷二C 换脸）+ 日期胶囊 + 回底钮：列表区 Box 的兄弟位（图纸 §3.1）。
                        // 声明序照抄暖陶：胶囊在横幅**之前**（横幅压其上），且横幅在场时胶囊并入抑制。
                        LiuliDatePill(
                            listState = session.listState,
                            listItems = listItems,
                            topBannerVisible = topBannerVisible,
                            topOffset = LiuliChatGeometry.datePillOffset(statusBarTop, hasWorldPill),
                        )
                        LiuliChatBanners(
                            viewModel = viewModel,
                            sheets = session.sheets,
                            networkConnected = networkConnected,
                            networkStatusChanged = networkStatusChanged,
                            arrivalAppt = arrivalAppt,
                            countdownAppt = countdownAppt,
                            characterName = characterName,
                            topPadding = LiuliChatGeometry.listTopPadding(statusBarTop, hasWorldPill),
                            reduceMotion = reduceMotion,
                            promiseHintVisible = promiseHint != null,
                        )
                        LiuliScrollToBottom(
                            visible = session.showScrollDown,
                            reduceMotion = reduceMotion,
                            // 底缘 = 输入区实测顶 + 12dp（与列表底留白同一个数·见该件 KDoc）。
                            bottomPadding = listBottomPadding,
                            onClick = { if (listItems.isNotEmpty()) session.scrollCoordinator.stickToBottom(animate = !reduceMotion) },
                        )
                        LiuliCalendarToast(
                            text = calendarToast?.text,
                            isDelete = calendarToast?.isDelete == true,
                            reduceMotion = reduceMotion,
                            onDismiss = { viewModel.dismissCalendarToast() },
                        )
                        LiuliPromiseHint(
                            hint = promiseHint,
                            topPadding = LiuliChatGeometry.listTopPadding(statusBarTop, hasWorldPill),
                            reduceMotion = reduceMotion,
                            onOpenLedger = { character?.uuid?.let(onOpenPromises) },
                            onUndo = viewModel::undoPromiseHint,
                            onDismiss = viewModel::dismissPromiseHint,
                        )
                    }
                    // 卷二B A-5：面板本体已搬到 overlay 层（LiuliPlusPanel），这里只剩占位撑高度。
                    LiuliChatPanelRegion(inputPanel = session.inputPanel)
                }
            },
            overlay = {
                Column(
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(LiuliChatGeometry.worldPillTop),
                ) {
                    LiuliChatTopBar(
                        characterName = characterName,
                        loading = headerLoading,
                        avatarPath = avatarPath,
                        innerStateLine = innerStateLine,
                        scheduleStatus = scheduleStatus,
                        moodEmoji = moodEmoji,
                        moodText = conversation?.moodText.orEmpty(),
                        isInOfflineMode = offlineChrome,
                        characterUuid = characterUuid,
                        onBack = onBack,
                        onOpenProfile = onOpenProfile,
                        onEndMeeting = { viewModel.exitOfflineMode() },
                        canStartCall = canStartCall,
                        onStartCall = onStartCall,
                    )
                    LiuliWorldPill(
                        pill = worldPill,
                        offline = offlineChrome,
                        onOpenWorldAt = onOpenWorldAt,
                    )
                }
                // 声明序即层级：面板在输入区**之前**——托盘永远盖在面板之上（图纸 §2.2 ③）。
                LiuliPlusPanel(
                    viewModel = viewModel,
                    sheets = session.sheets,
                    inputPanel = session.inputPanel,
                    replyTarget = replyTarget,
                    quoteHint = quoteHint,
                    isOfflineMode = offlineChrome,
                    chatModelHasVision = session.imageState.chatModelHasVision,
                    reduceMotion = reduceMotion,
                    regionPx = inputRegionPx,
                )
                LiuliInputBar(
                    input = session.input,
                    onInputChange = { session.input = it },
                    // T2-4 / T2-5 可测点：输入区不持 VM，发送经回调（返回「是否被受理」）；
                    // 清空押后到飞入握手（A-7）——闸关时 tryBegin 立即 commit = 与旧写法同帧。
                    onSend = { text ->
                        liuliSendHandler(
                            text = text,
                            send = viewModel::send,
                            gatesOpen = sendFlightGatesOpen(
                                enabled = SEND_FLIGHT_ENABLED,
                                reduceMotion = reduceMotion,
                                listAtBottom = !session.showScrollDown,
                                quoteReplyActive = replyTarget != null,
                                flightBusy = session.sendFlight.busy,
                            ),
                            sendFlight = session.sendFlight,
                            commit = { session.input = "" },
                            // 心情四色绕位一步（图纸 §4.1·只在发送被受理时·E7）。
                            onAccepted = { session.sendTurn++ },
                        )
                    },
                    panelOpen = session.inputPanel.panelOpen,
                    onTogglePanel = {
                        if (session.inputPanel.panelOpen) {
                            session.inputPanel.requestKeyboard()
                        } else {
                            // 事件时读 ime（审计 P4·绝不在组合期读）。
                            session.inputPanel.openPanel(imeInsets.exclude(navBarInsets).getBottom(density), session.panelFallbackPx)
                        }
                    },
                    inputFieldModifier = Modifier
                        .focusRequester(session.inputFieldFocus)
                        .onFocusChanged { if (it.isFocused) session.inputPanel.onFieldFocused() }
                        .onGloballyPositioned { session.sendFlight.inputBounds = it.boundsInWindow() },
                    characterName = characterName,
                    replyTarget = replyTarget,
                    onClearReply = { viewModel.clearReplyTarget() },
                    quoteHint = quoteHint,
                    pendingCalendarAction = pendingCalendarActions.firstOrNull(),
                    onConfirmCalendar = { viewModel.confirmPendingCalendarAction() },
                    onCancelCalendar = { viewModel.cancelPendingCalendarAction() },
                    voiceDraft = voiceDraft,
                    draftPlaying = playingVoiceId != null && playingVoiceId == voiceDraft?.id,
                    onPlayDraft = { viewModel.toggleVoiceDraftPlayback() },
                    onCancelDraft = { viewModel.cancelVoiceDraft() },
                    onSendDraft = { viewModel.sendVoiceDraft() },
                    onRetryTranscription = { viewModel.retryVoiceTranscription() },
                    micPermissionGranted = session.micPermission.granted,
                    onRequestMicPermission = session.micPermission.request,
                    onStartRecording = { viewModel.startVoiceRecording() },
                    onRecordingDrag = { viewModel.updateVoiceRecordingDrag(it) },
                    onFinishRecording = { viewModel.finishVoiceRecording() },
                    voiceRecording = voiceRecording,
                    voiceRecordingLevel = voiceRecordingLevel,
                    voiceRecordingDurationMs = voiceRecordingDurationMs,
                    voiceRecordingCancelling = voiceRecordingCancelling,
                    offlineImmersiveInput = offlineChrome && appSettings.offlineImmersiveInputEnabled,
                    offlineThemeColor = OfflineTheater.harmonize(parseOfflineThemeColor(character?.offlineThemeColorHex)),
                    reduceMotion = reduceMotion,
                    // M3b ④：握手 / 飞行期抑制占位符，免得输入框在飞行泡落地前就闪回「说点什么…」。
                    hidePlaceholder = session.sendFlight.busy,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .offset { IntOffset(0, -session.inputPanel.regionPx(imeInsets.exclude(navBarInsets).getBottom(this))) }
                        // 导航栏 padding 之内量（= 不含 navBar 的内容高·§4.7 底留白算式的入参）。
                        .onSizeChanged { inputOverlayHeightPx = it.height },
                )
            },
        )

        LiuliChatBorrowedOverlays(
            viewModel = viewModel,
            session = session,
            character = character,
            characterName = characterName,
            userName = userName,
            userAvatarPath = userAvatarPath,
            avatarPath = avatarPath,
            appSettings = appSettings,
            chatWallpaperPath = chatWallpaperPath,
            customStickers = customStickers,
            coinBalance = coinBalance,
            offlineRecoveryVisible = offlineRecoveryVisible,
            onOpenStickerManagement = onOpenStickerManagement,
            inputOverlayHeight = inputOverlayHeight,
            inputRegionPx = inputRegionPx,
            reduceMotion = reduceMotion,
        )
    }
}
