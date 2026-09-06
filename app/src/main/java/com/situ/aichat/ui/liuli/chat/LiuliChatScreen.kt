package com.situ.aichat.ui.liuli.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.ui.chat.ChatViewModel
import com.situ.aichat.ui.chat.ChatWallpaper
import com.situ.aichat.ui.chat.ChatWorldStatusViewModel
import com.situ.aichat.ui.chat.loadChatWallpaper
import com.situ.aichat.ui.chat.peekChatWallpaper
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.voicecall.VoiceCallPreflightViewModel
import com.situ.aichat.ui.voicecall.rememberPreflightVoiceCallStarter

/**
 * 琉璃聊天屏根（第二张脸·图纸 2026-09-05 卷二A §2.1）。签名与暖陶 `ChatScreen` **逐字相同**——
 * 导航层只按 `LocalAppSkin` 选调哪一个（`AIChatApp.kt` 聊天路由·图纸 §2.2），实参一字不差。
 *
 * **一个大脑**：同一个 [ChatViewModel]（作用域仍是 NavBackStackEntry）+ 两个辅助 VM，全部状态机 / 纯函数
 * 与暖陶共用；本屏只负责「长什么样」。编排段落见 [rememberLiuliChatSession]，效应块见 [LiuliChatEffects]，
 * 排版见 [LiuliChatLayout]。
 */
@Composable
fun LiuliChatScreen(
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenStickerManagement: () -> Unit,
    onOpenVoiceCall: (String) -> Unit,
    onOpenCharacterVoiceSettings: (String) -> Unit,
    onOpenTtsConfig: () -> Unit,
    onOpenWorldAt: (String) -> Unit = {},
    onOpenPromises: (String) -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val conversationWithWallpaper by viewModel.conversationWithWallpaper.collectAsStateWithLifecycle()
    val conversation = conversationWithWallpaper?.conversation
    val character by viewModel.character.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val typingSlot by viewModel.pendingAssistantSlot.collectAsStateWithLifecycle(null)
    val scheduleStatus by viewModel.currentScheduleStatus.collectAsStateWithLifecycle()
    val characterUuid = conversation?.characterUuid
    val offlineChrome = conversation?.isInOfflineMode == true

    // W13 照抄：refresh 驱动·无轮询（首刷 + ON_RESUME 复刷，后者在 [LiuliChatEffects] 的生命周期观察者里）。
    val worldStatusVm: ChatWorldStatusViewModel = hiltViewModel()
    val worldPill by worldStatusVm.pill.collectAsStateWithLifecycle()
    LaunchedEffect(characterUuid) { characterUuid?.let(worldStatusVm::refresh) }

    // VU1 门 + VU3 尾巴自愈判定单源（同 owner 单实例·照抄）。
    val preflightVm: VoiceCallPreflightViewModel = hiltViewModel()
    val voiceSetupNeed by preflightVm.setupNeed.collectAsStateWithLifecycle()
    LaunchedEffect(character?.uuid) { character?.uuid?.let(preflightVm::refresh) }
    val startVoiceCall = rememberPreflightVoiceCallStarter(
        onCallStarted = onOpenVoiceCall,
        onOpenCharacterVoiceSettings = onOpenCharacterVoiceSettings,
        onOpenTtsConfig = onOpenTtsConfig,
        preflightVm = preflightVm,
    )

    // 过渡丝滑化·B3 照抄：暖缓存命中同步取出（与角色信息同帧），冷/未命中走异步补齐。
    val chatWallpaperPath = conversationWithWallpaper?.chatWallpaperPath
    val peekedWallpaper = remember(chatWallpaperPath) { peekChatWallpaper(chatWallpaperPath) }
    val loadedWallpaper by produceState<ChatWallpaper?>(initialValue = null, chatWallpaperPath) {
        value = chatWallpaperPath?.let { loadChatWallpaper(it) }
    }
    val chatWallpaper = loadedWallpaper ?: peekedWallpaper

    val reduceMotion = rememberReduceMotion()
    val session = rememberLiuliChatSession(
        viewModel = viewModel,
        preflightVm = preflightVm,
        characterUuid = character?.uuid,
        onOpenCharacterVoiceSettings = onOpenCharacterVoiceSettings,
        onOpenTtsConfig = onOpenTtsConfig,
    )
    LiuliChatEffects(
        session = session,
        viewModel = viewModel,
        worldStatusVm = worldStatusVm,
        preflightVm = preflightVm,
        characterUuid = characterUuid,
        characterUuidForVoice = character?.uuid,
        typingSlot = typingSlot,
        messages = messages,
        reduceMotion = reduceMotion,
    )
    LiuliChatSystemBars(chatWallpaper = chatWallpaper, offlineChrome = offlineChrome)

    LiuliChatLayout(
        viewModel = viewModel,
        session = session,
        conversation = conversation,
        character = character,
        messages = messages,
        typingSlot = typingSlot,
        worldPill = if (offlineChrome) null else worldPill,
        scheduleStatus = scheduleStatus,
        chatWallpaper = chatWallpaper,
        chatWallpaperPath = chatWallpaperPath,
        wallpaperPeeked = peekedWallpaper != null,
        voiceSetupNeeded = voiceSetupNeed != null,
        reduceMotion = reduceMotion,
        canStartCall = conversation != null && character != null,
        onStartCall = {
            val convo = conversation
            val char = character
            if (convo != null && char != null) startVoiceCall(convo.uuid, char.uuid)
        },
        onBack = onBack,
        onOpenProfile = onOpenProfile,
        onOpenPromises = onOpenPromises,
        onOpenStickerManagement = onOpenStickerManagement,
        onOpenWorldAt = onOpenWorldAt,
    )
}
