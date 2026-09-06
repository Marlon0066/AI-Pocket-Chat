package com.situ.aichat.ui.liuli.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.CustomStickerEntity
import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.ui.chat.ChatImageViewerHost
import com.situ.aichat.ui.chat.ChatOfflineReviewOverlay
import com.situ.aichat.ui.chat.ChatSheetsState
import com.situ.aichat.ui.chat.ChatViewModel
import com.situ.aichat.ui.designsystem.AppSnackbarHost
import com.situ.aichat.ui.liuli.chat.sheets.LiuliChatSheets

/**
 * 屏顶叠层（图纸 2026-09-05 卷二A §2.3 借用清单·卷二B 换脸两件）：发送飞入覆盖层（琉璃版
 * [LiuliSendFlightOverlay]）、沉浸菜单、见面回顾（**永久共用**·恒暗剧场不随主题）、图片查看器
 * （保留·脱主题）、snackbar。声明顺序与暖陶 `ChatScreen.kt:766-794` 逐字相同——层级即声明序。
 *
 * 自 [LiuliChatLayout] 只搬不改抽出（该文件的 §2.1 行数预算）。
 */
@Composable
internal fun BoxScope.LiuliChatBorrowedOverlays(
    viewModel: ChatViewModel,
    session: LiuliChatSession,
    character: CharacterEntity?,
    characterName: String,
    userName: String,
    userAvatarPath: String?,
    avatarPath: String?,
    appSettings: AppSettings,
    chatWallpaperPath: String?,
    customStickers: List<CustomStickerEntity>,
    coinBalance: Int,
    offlineRecoveryVisible: Boolean,
    onOpenStickerManagement: () -> Unit,
    /** 输入区 overlay 实测高（不含导航栏）——snackbar 浮在它上方，不盖托盘（复核 R1 🔴-1）。 */
    inputOverlayHeight: Dp,
    /** 面板区 / 键盘的当前高度（布局 lambda 里取值·组合期绝不读 ime）。 */
    inputRegionPx: () -> Int,
    reduceMotion: Boolean,
) {
    val offlineReviewInfo by viewModel.offlineReviewInfo.collectAsStateWithLifecycle()
    val offlineReviewMessages by viewModel.offlineReviewMessages.collectAsStateWithLifecycle()

    LiuliSendFlightOverlay(state = session.sendFlight)
    val navBarInsets = WindowInsets.navigationBars
    val density = LocalDensity.current
    LiuliImmersiveMenuOverlay(
        state = session.immersiveMenu,
        actions = session.actions,
        reduceMotion = reduceMotion,
        // 表情回应 = 纯瞬态（A-8）：只让那条泡上弹一枚徽章，绝不入库 / 计数 / 进提示词。
        onReact = { msg, emoji -> session.reaction.play(msg.messageUUID, emoji) },
        // 菜单避键盘 / 面板（E18）：本层是根 Box 里的整窗覆盖层，底部被占高度 = 面板区（regionPx·不含导航栏）
        // + 导航栏。只传 regionPx 会少算一条导航栏，菜单能压进键盘顶 24–48dp（复核 R1 🟡-4）。布局 lambda 里取值。
        bottomObstructionPx = { inputRegionPx() + navBarInsets.getBottom(density) },
    )
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
    ChatImageViewerHost(session.imageState)
    // 审计 S1 照抄：10 个 sheet / dialog 整体经管家渲染（Dialog / ModalBottomSheet 在独立 window，与树位置无关）。
    // 卷二C C6b：分派器换琉璃版（同参·同一个 ChatSheetsState·逐分支同构）。
    LiuliChatSheets(
        sheets = session.sheets,
        viewModel = viewModel,
        characterName = characterName,
        avatarPath = avatarPath,
        coinBalance = coinBalance,
        customStickers = customStickers,
        offlineRecoveryVisible = offlineRecoveryVisible,
        onOpenStickerManagement = onOpenStickerManagement,
    )
    // 暖陶 Scaffold 把 snackbar 摆在 bottomBar（托盘 + 面板区）之上；琉璃托盘是 overlay 浮件，这里手动让位：
    // 导航栏 → 面板 / 键盘（offset）→ 输入区实测高（padding）。
    AppSnackbarHost(
        session.snackbarHost,
        Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .offset { IntOffset(0, -inputRegionPx()) }
            .padding(bottom = inputOverlayHeight),
    )
}

/**
 * 列表区顶部横幅族（过渡借用·图纸 §2.3：网络横幅 / 约定倒数条 / 到点「出发赴约」钮，卷二C 换脸）。
 * 声明序与显隐条件照抄暖陶 `ChatMessageList`；[topPadding] = 列表顶留白（让开 chrome·§4.7）。
 *
 * 单独成件的第二个理由：它必须在 `BoxScope` 里调 `AnimatedVisibility`，而 [LiuliChatLayout] 那一处同时
 * 处在外层 `ColumnScope` 中 → 隐式接收者二义（编译期实证），抽出后只剩 BoxScope。
 */
@Composable
internal fun BoxScope.LiuliChatBanners(
    viewModel: ChatViewModel,
    sheets: ChatSheetsState,
    networkConnected: Boolean,
    networkStatusChanged: Boolean?,
    arrivalAppt: MeetingAppointmentEntity?,
    countdownAppt: MeetingAppointmentEntity?,
    characterName: String,
    topPadding: Dp,
    reduceMotion: Boolean,
    /** 约定记账提示在场（图纸 2026-09-06 §4.3）：倒数条让路 4 秒，「出发赴约」钮永不让路。 */
    promiseHintVisible: Boolean = false,
) {
    AnimatedVisibility(
        visible = !networkConnected || networkStatusChanged == true,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = topPadding),
        enter = if (reduceMotion) EnterTransition.None else fadeIn() + expandIn(),
        exit = if (reduceMotion) ExitTransition.None else shrinkOut() + fadeOut(),
    ) {
        LiuliNetworkBanner(
            connected = networkConnected,
            recovered = networkStatusChanged == true,
            onRecoveredShown = { viewModel.clearNetworkStatusChange() },
        )
    }
    AnimatedVisibility(
        visible = (arrivalAppt != null || countdownAppt != null) && !(promiseHintVisible && arrivalAppt == null),
        modifier = Modifier.align(Alignment.TopCenter).padding(top = topPadding),
        enter = if (reduceMotion) EnterTransition.None else fadeIn() + expandIn(),
        exit = if (reduceMotion) ExitTransition.None else shrinkOut() + fadeOut(),
    ) {
        when {
            arrivalAppt != null -> LiuliArrivalButton(onArrive = { viewModel.arriveAtAppointment(arrivalAppt.uuid) })
            countdownAppt != null -> LiuliCountdownChip(
                appt = countdownAppt,
                characterName = characterName,
                onReschedule = { sheets.rescheduleAppointmentUuid = countdownAppt.uuid },
                onCancel = { viewModel.cancelAppointment(countdownAppt.uuid) },
            )
        }
    }
}
