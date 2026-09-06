package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.chat.ChatImageState
import com.situ.aichat.ui.chat.ChatImmersiveMenuState
import com.situ.aichat.ui.chat.ChatInputPanelState
import com.situ.aichat.ui.chat.ChatScrollCoordinator
import com.situ.aichat.ui.chat.ChatSendFlightState
import com.situ.aichat.ui.chat.ChatSheetsState
import com.situ.aichat.ui.chat.ChatViewModel
import com.situ.aichat.ui.chat.MessageRowActions
import com.situ.aichat.ui.chat.MicPermissionState
import com.situ.aichat.ui.chat.captureImmersiveBackdrop
import com.situ.aichat.ui.chat.rememberChatImageState
import com.situ.aichat.ui.chat.rememberChatInputPanelState
import com.situ.aichat.ui.chat.rememberChatScrollCoordinator
import com.situ.aichat.ui.chat.rememberChatSheetsState
import com.situ.aichat.ui.chat.rememberMessageDeleteSound
import com.situ.aichat.ui.chat.rememberMicPermissionState
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.liuli.glass.BackdropState
import com.situ.aichat.ui.liuli.glass.rememberBackdropState
import com.situ.aichat.ui.voicecall.VoiceCallPreflightViewModel
import com.situ.aichat.ui.voicecall.VoiceSetupNeed
import kotlinx.coroutines.launch

/**
 * 琉璃聊天屏的**编排面**（图纸 2026-09-05 卷二A §2.1）：把暖陶 `ChatScreen` 的编排段落（弹层管家 / 面板状态机 /
 * 列表滚动件 / 沉浸菜单 / 发送飞入 / 25 个行级动作 / 动画记账集合）原样搬进一个可传递的持有对象。
 *
 * **不改机制**：这里的每一件都是暖陶那一份（`ui/chat` 只读复用），语义与存活口径（`rememberSaveable` 三件、
 * 面板 min/max 钳位、协调员单飞）逐字照抄——琉璃只换长相，不换大脑（图纸 §2.3「必须保持不变」2/3）。
 * 效应块另住 [LiuliChatEffects]（图纸 §9 ⑦ 允许的「只搬不改」拆分）。
 */
@Stable
internal class LiuliChatSession(
    val sheets: ChatSheetsState,
    val inputPanel: ChatInputPanelState,
    val inputFieldFocus: FocusRequester,
    val inputFocusManager: FocusManager,
    val micPermission: MicPermissionState,
    val panelFallbackPx: Int,
    val listState: LazyListState,
    val scrollCoordinator: ChatScrollCoordinator,
    val dismissKeyboardOnDrag: NestedScrollConnection,
    val sendFlight: ChatSendFlightState,
    val immersiveMenu: ChatImmersiveMenuState,
    /** 卷二B：表情回应爆点（纯瞬态·普通 remember·重建即清·A-8）。 */
    val reaction: LiuliReactionState,
    /** 卷二C：长文折叠的会话级记账（纯瞬态·普通 remember·重建即全部折回·A-1 / §3.4）。 */
    val fold: LiuliFoldState,
    val imageState: ChatImageState,
    val deleteArm: MutableState<Long>,
    val actions: MessageRowActions,
    val entryScalePlayed: MutableSet<String>,
    val emotionPlayed: MutableList<String>,
    val emotionHiddenIntervals: MutableList<LongRange>,
    val snackbarHost: SnackbarHostState,
    val backdrop: BackdropState,
    /** 视觉底部第一条可见项不是最新一条 → 出回底钮（反转口径·照抄暖陶 `ChatScreen.kt:451-453`）。 */
    val showScrollDownState: State<Boolean>,
    /** 贴近底部（决定上翻加载触发与回底缩窗·照抄 `ChatScreen.kt:455-457`）。 */
    val isNearBottomState: State<Boolean>,
    private val inputState: MutableState<String>,
    private val userHasScrolledState: MutableState<Boolean>,
    private val didInitialScrollState: MutableState<Boolean>,
    private val animateArrivalsState: MutableState<Long>,
    private val sendTurnState: MutableState<Int>,
) {
    /** 输入框文字（`rememberSaveable`·跨进程死亡存活语义同暖陶）。 */
    var input: String
        get() = inputState.value
        set(value) { inputState.value = value }

    /** 用户手动滚动过（saveable·审计 R4）。 */
    var userHasScrolled: Boolean
        get() = userHasScrolledState.value
        set(value) { userHasScrolledState.value = value }

    /** 首次定位已完成（saveable·审计 R4）。 */
    var didInitialScroll: Boolean
        get() = didInitialScrollState.value
        set(value) { didInitialScrollState.value = value }

    /** 入场动效「可见窗口」起点（P1-13）。 */
    var animateArrivalsSinceMillis: Long
        get() = animateArrivalsState.value
        set(value) { animateArrivalsState.value = value }

    /**
     * 发送成功计数（心情四色绕位轮转的相位·图纸 §4.1）。普通 `remember`：重建从 0 起，只影响轮转相位，
     * 不影响任何数据（图纸 §3.4）。
     */
    var sendTurn: Int
        get() = sendTurnState.value
        set(value) { sendTurnState.value = value }

    val showScrollDown: Boolean get() = showScrollDownState.value
    val isNearBottom: Boolean get() = isNearBottomState.value
}

@Composable
internal fun rememberLiuliChatSession(
    viewModel: ChatViewModel,
    preflightVm: VoiceCallPreflightViewModel,
    /** 当前角色 uuid（VU3 尾巴深链用·经 [rememberUpdatedState] 活读，绝不冻在首帧）。 */
    characterUuid: String?,
    onOpenCharacterVoiceSettings: (String) -> Unit,
    onOpenTtsConfig: () -> Unit,
): LiuliChatSession {
    val density = LocalDensity.current
    val haptics = LocalAppHaptics.current
    val playDeleteSound = rememberMessageDeleteSound()
    val snackbarHost = remember { SnackbarHostState() }
    val backdrop = rememberBackdropState()

    val sheets = rememberChatSheetsState()
    val softwareKeyboard = LocalSoftwareKeyboardController.current
    val inputFocusManager = LocalFocusManager.current
    val inputFieldFocus = remember { FocusRequester() }
    // 面板高度钳制边界（契约 PLUS_PANEL §3 边界硬化·逐值照抄暖陶 `ChatScreen.kt:349-351`）。
    val panelMinPx = with(density) { 160.dp.roundToPx() }
    val panelMaxPx = with(density) { (LocalConfiguration.current.screenHeightDp * 0.6f).dp.roundToPx() }
    val inputPanel = rememberChatInputPanelState(softwareKeyboard, inputFocusManager, inputFieldFocus, panelMinPx, panelMaxPx)
    val panelFallbackPx = with(density) { 300.dp.roundToPx() }
    val micPermission = rememberMicPermissionState()

    val listState = rememberLazyListState()
    val scrollCoordinator = rememberChatScrollCoordinator(listState)
    // P0-16 照抄：真实手指拖动才清焦（程序化滚动不误收键盘）。
    val dismissKeyboardOnDrag = remember(inputFocusManager) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) inputFocusManager.clearFocus()
                return Offset.Zero
            }
        }
    }
    val sendFlight = remember { ChatSendFlightState() }
    val immersiveMenu = remember { ChatImmersiveMenuState() }
    val reaction = remember { LiuliReactionState() }
    val fold = remember { LiuliFoldState() }
    val imageState = rememberChatImageState(viewModel)
    val deleteArm = remember { mutableStateOf(0L) }

    val inputState = rememberSaveable { mutableStateOf("") }
    val entryScalePlayed = remember { mutableSetOf<String>() }
    val emotionPlayed = rememberSaveable { ArrayList<String>() }
    // 审计 R5 照抄：不可见区间表同升 saveable（拍平成 Long 对存）。
    val emotionHiddenIntervals = rememberSaveable(
        saver = listSaver(
            save = { intervals -> intervals.flatMap { listOf(it.first, it.last) } },
            restore = { flat -> flat.chunked(2).mapTo(mutableListOf()) { (a, b) -> a..b } },
        ),
    ) { mutableListOf<LongRange>() }
    val animateArrivalsState = remember { mutableStateOf(Long.MAX_VALUE) }
    val sendTurnState = remember { mutableStateOf(0) }
    val userHasScrolledState = rememberSaveable { mutableStateOf(false) }
    val didInitialScrollState = rememberSaveable { mutableStateOf(false) }

    val showScrollDownState = remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val isNearBottomState = remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }

    val chatRootView = LocalView.current
    val menuScope = rememberCoroutineScope()
    // VU3 尾巴深链的角色 uuid 活读（PITFALLS §1d「捕获过期」第三入口：remember 的 key 少于 lambda 所读）。
    val voiceSetupUuid by rememberUpdatedState(characterUuid)
    val actions = remember(viewModel, sheets) {
        MessageRowActions(
            onVoiceToggle = { viewModel.toggleVoicePlayback(it) },
            onOpenImage = { path -> imageState.viewerImagePath = path },
            onSaveImage = { msg -> viewModel.image.saveToGallery(msg.imageRelativePath) },
            onQuote = { viewModel.setReplyTarget(it) },
            onDelete = { msg ->
                playDeleteSound()
                haptics.light()
                deleteArm.value++ // V9：先于删除帧武装位移弹簧窗
                viewModel.deleteMessage(msg)
            },
            onOpenMenu = { msg, boundsInWindow, canRegenerate ->
                menuScope.launch {
                    val backdropShot = captureImmersiveBackdrop(chatRootView)
                    val bounds = backdropShot?.let {
                        boundsInWindow.translate(-it.viewOffsetInWindow.x.toFloat(), -it.viewOffsetInWindow.y.toFloat())
                    } ?: boundsInWindow
                    immersiveMenu.open(msg, bounds, backdropShot?.snapshot, backdropShot?.frosted, canRegenerate)
                }
            },
            onFlightBubblePositioned = { msg, bounds ->
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
            onOpenVoiceSetup = {
                if (preflightVm.setupNeed.value == VoiceSetupNeed.GLOBAL_CONFIG) {
                    onOpenTtsConfig()
                } else {
                    voiceSetupUuid?.let(onOpenCharacterVoiceSettings) ?: onOpenTtsConfig()
                }
            },
        )
    }

    return remember(
        sheets, inputPanel, micPermission, listState, scrollCoordinator, dismissKeyboardOnDrag,
        sendFlight, immersiveMenu, reaction, fold, imageState, actions, snackbarHost, backdrop,
    ) {
        LiuliChatSession(
            sheets = sheets,
            inputPanel = inputPanel,
            inputFieldFocus = inputFieldFocus,
            inputFocusManager = inputFocusManager,
            micPermission = micPermission,
            panelFallbackPx = panelFallbackPx,
            listState = listState,
            scrollCoordinator = scrollCoordinator,
            dismissKeyboardOnDrag = dismissKeyboardOnDrag,
            sendFlight = sendFlight,
            immersiveMenu = immersiveMenu,
            reaction = reaction,
            fold = fold,
            imageState = imageState,
            deleteArm = deleteArm,
            actions = actions,
            entryScalePlayed = entryScalePlayed,
            emotionPlayed = emotionPlayed,
            emotionHiddenIntervals = emotionHiddenIntervals,
            snackbarHost = snackbarHost,
            backdrop = backdrop,
            showScrollDownState = showScrollDownState,
            isNearBottomState = isNearBottomState,
            inputState = inputState,
            userHasScrolledState = userHasScrolledState,
            didInitialScrollState = didInitialScrollState,
            animateArrivalsState = animateArrivalsState,
            sendTurnState = sendTurnState,
        )
    }
}
