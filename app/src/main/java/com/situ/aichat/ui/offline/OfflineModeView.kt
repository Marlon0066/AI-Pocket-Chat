package com.situ.aichat.ui.offline

import android.os.SystemClock
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.model.OfflineInviteJson
import com.situ.aichat.offline.OfflineContentBlock
import com.situ.aichat.offline.OfflineContentParser
import com.situ.aichat.prompt.DirtyMessageDetector
import com.situ.aichat.R
import com.situ.aichat.sticker.StickerTagParser
import com.situ.aichat.ui.chat.OfflineEndCardBubble
import com.situ.aichat.ui.chat.VoiceMessageBubble
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.util.DateFormatters
import kotlinx.coroutines.launch

/**
 * 线下见面沉浸剧场主视图（1:1 iOS `OfflineModeView`）：见面期间整个聊天列表被它替换。
 *
 * 全屏滚动列表（锚底）；舞台背景（[OfflineBackgroundView]）**不在本层**——2026-07-06 全屏恒暗舞台修订后由
 * ChatScreen 窗口层绘制（铺满含状态栏/输入托盘后），本视图只负责内容。AI 第三人称叙事按 [OfflineContentParser]
 * 解析成 10 类内容块逐块淡入渲染；尚无内容时显示「正在前往见面地点」呼吸头像加载页；结束卡走交互卡片。
 *
 * [offlineMessages] 已由调用方过滤为当前 session 的可见消息（排除 marker/systemHint）。
 */
@Composable
fun OfflineModeView(
    offlineMessages: List<MessageEntity>,
    isWaitingForContent: Boolean,
    characterName: String,
    characterAvatarPath: String?,
    userName: String,
    userAvatarPath: String?,
    themeColorHex: String?,
    chatWallpaperPath: String? = null,
    entryAnimationsEnabled: Boolean,
    /** 卷三 §4.2：剧场内语音回听三参（全部转发 ChatScreen 现有同源值·本屏零新状态）——正在播放的消息 UUID。 */
    playingVoiceId: String?,
    /** 播放进度 lambda（非播放行喂 [ZeroProgress] 零常量·同 ChatMessageList 纪律）。 */
    voiceProgress: () -> Float,
    onVoiceToggle: (MessageEntity) -> Unit,
    onEndMeeting: () -> Unit,
    onContinueMeeting: (endCardUuid: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 舞台调和色（§4.1·屏内所有用彩处的唯一来源）：角色主题色朝暖白混 35%，压振动。
    val themeColor = OfflineTheater.harmonize(parseOfflineThemeColor(themeColorHex))
    val reduceMotion = rememberReduceMotion()
    // 照片类背景 → 内容块加字幕微影（§4.3）。K9（2026-07-12 性能线程专项）：原「OfflineBackgrounds 角色图/
    // 全局图」存在性检查随死读路清除（该目录自始无写入方·恒空）——照片类底现仅聊天壁纸一种，纯同步判定。
    val onPhotoBackdrop = chatWallpaperPath != null
    // 已播放入场动画的消息集合（避免滚动回来重播，1:1 iOS animatedMessageIDs）。
    val playedIds = remember { mutableStateSetOf<String>() }
    // D1 历史不重播：进屏时刻之前落库的消息一律直显（照常规列表 animateArrivalsSinceMillis 惯例）——
    // 旧 playedIds 只活在组合内，退出重进会整屏重播历史入场动画。
    val enteredAtMs = remember { System.currentTimeMillis() }

    val hasAIContent = offlineMessages.any { it.roleRaw == "assistant" && it.content.isNotEmpty() }
    val showLoadingScene = !hasAIContent && isWaitingForContent
    val showMiniLoading = !showLoadingScene && isWaitingForContent

    val listState = rememberLazyListState()
    // 首次定位只认「内容项」数，恒存的 offline_header 装饰项不计：重进见面屏时 Room 流首帧吐空列表，
    // 若在「仅剩装饰项」那帧消耗掉首次定位，随后消息灌入（声明序在装饰项之前）会让 key 锚点追着装饰项
    // 从 index 0 漂到 N（视口=视觉顶部），isNearBottom 守卫再误判「用户已上翻」→ 永卡顶部不回底
    //（PITFALLS「首项之前插入新项锚住原首项」的反转列表变体·2026-08-26）。
    val contentCount = if (showLoadingScene) 1 else offlineMessages.size + if (showMiniLoading) 1 else 0
    // 反转底部锚定（2026-07-08 V10·契约 FABLE5_CHAT_REVERSE_LIST_PROPOSAL §8 收编）：与主聊天同配方——
    // index 0=最新内容钉底边，键盘/输入栏缩放视口时天然钉底（旧「IME 增长逐帧贴底」补偿及其竞态窗口整体退役）。
    // 声明序随之反转（先声明=视觉更靠底）；底部留白由 contentPadding 承担（替旧 spacer 项）。
    // 过渡丝滑化·C 语义保留：首次定位瞬时（反转下初始即在底）、其后内容增长才动画滚。
    var didInitialScroll by remember { mutableStateOf(false) }
    // 反转口径：贴近底部 = 视觉底第一可见项 index ≤ 1。
    val isNearBottom by remember {
        derivedStateOf { listState.firstVisibleItemIndex <= 1 }
    }
    LaunchedEffect(contentCount) {
        if (contentCount > 0 && (!didInitialScroll || isNearBottom)) {
            if (didInitialScroll && !reduceMotion) listState.animateScrollToItem(0)
            else { listState.scrollToItem(0); didInitialScroll = true }
        }
    }
    // D1 揭示跟随：块展开长高时底边已天然钉住（反转锚定）；贴底时补一次对齐保揭示块完整入画，上翻看历史不强拉。
    val scope = rememberCoroutineScope()
    val onBlockRevealed: () -> Unit = {
        if (isNearBottom) {
            scope.launch { listState.animateScrollToItem(0) }
        }
    }

    Box(modifier) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            // 反转默认排布是 Bottom；锁 Top 保「内容铺不满一屏时贴顶」现状（同主聊天 ChatMessageList 口径）。
            verticalArrangement = Arrangement.Top,
            contentPadding = PaddingValues(bottom = 20.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (showLoadingScene) {
                item(key = "offline_loading") {
                    SceneLoadingView(characterName, characterAvatarPath, themeColor, reduceMotion)
                }
            } else {
                if (showMiniLoading) {
                    item(key = "offline_mini_loading") { MiniLoadingIndicator(themeColor, reduceMotion) }
                }
                items(offlineMessages.asReversed(), key = { it.messageUUID }) { message ->
                    OfflineMessageContent(
                        message = message,
                        characterName = characterName,
                        characterAvatarPath = characterAvatarPath,
                        userName = userName,
                        userAvatarPath = userAvatarPath,
                        themeColor = themeColor,
                        reduceMotion = reduceMotion,
                        onPhotoBackdrop = onPhotoBackdrop,
                        entryAnimationsEnabled = entryAnimationsEnabled,
                        playedBefore = message.timestamp <= enteredAtMs,
                        playedIds = playedIds,
                        onBlockRevealed = onBlockRevealed,
                        playingVoiceId = playingVoiceId,
                        voiceProgress = voiceProgress,
                        onVoiceToggle = onVoiceToggle,
                        onEndMeeting = onEndMeeting,
                        onContinueMeeting = onContinueMeeting,
                    )
                }
            }

            item(key = "offline_header") { HeaderDecoration(themeColor) }
        }
    }
}

// MARK: - 单条消息

@Composable
private fun OfflineMessageContent(
    message: MessageEntity,
    characterName: String,
    characterAvatarPath: String?,
    userName: String,
    userAvatarPath: String?,
    themeColor: Color,
    reduceMotion: Boolean,
    onPhotoBackdrop: Boolean,
    entryAnimationsEnabled: Boolean,
    playedBefore: Boolean,
    playedIds: MutableSet<String>,
    onBlockRevealed: () -> Unit,
    playingVoiceId: String?,
    voiceProgress: () -> Float,
    onVoiceToggle: (MessageEntity) -> Unit,
    onEndMeeting: () -> Unit,
    onContinueMeeting: (String) -> Unit,
) {
    val kind = remember(message.messageKindRaw) { MessageKind.fromRaw(message.messageKindRaw) }
    val isUser = message.roleRaw == "user"
    // 脏消息彻底隐身（图纸 2026-09-01 件①·用户拍板取代原折叠占位）：LLM 复读的 JSON/Markdown 不占一格
    // 舞台。新脏内容已在落库前被 AssistantOutputGate 丢弃；这里兜的是库内历史脏行。
    val dirtyReason = remember(message.messageUUID, message.content) {
        DirtyMessageDetector.detect(message.content, kind)
    }

    when {
        dirtyReason != null -> Unit

        // 卷三 E1：剧场内用户语音消息=可回听的舞台深玻璃药丸 + 楷体转写随行（原先只剩转写文字·播放机制全复用）。
        isUser && message.isVoiceMessage -> OfflineUserVoice(
            message = message,
            userName = userName,
            themeColor = themeColor,
            playingVoiceId = playingVoiceId,
            voiceProgress = voiceProgress,
            onVoiceToggle = onVoiceToggle,
        )

        isUser -> OfflineUserBlocks(message.content, characterName, characterAvatarPath, userName, userAvatarPath, themeColor, onPhotoBackdrop)

        kind == MessageKind.OFFLINE_END_CARD -> {
            val invite = remember(message.content) { OfflineInviteJson.parse(message.content) }
            if (invite != null) {
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    OfflineEndCardBubble(
                        data = invite,
                        onEndMeeting = onEndMeeting,
                        onContinue = { onContinueMeeting(message.messageUUID) },
                        onStage = true,
                    )
                }
            }
        }

        else -> {
            // AI 消息：解析为内容块，按阅读节奏逐块揭示（D1·OfflineRevealPacing）。
            val blocks = remember(message.content) { OfflineContentParser.parse(message.content) }
            val delays = remember(blocks) { OfflineRevealPacing.revealDelays(blocks) }
            // 本条消息的揭示起点（uptime 口径）：流式晚到的块从同一起点计时，不过度顺延。
            val revealEpoch = remember { SystemClock.uptimeMillis() }
            val hasPlayed = playedBefore || message.messageUUID in playedIds
            Column(Modifier.fillMaxWidth()) {
                blocks.forEachIndexed { index, block ->
                    OfflineBlockReveal(
                        epochUptimeMillis = revealEpoch,
                        delayFromEpochMs = delays[index],
                        hasPlayed = hasPlayed,
                        // P1-5：=iOS OfflineModeView.swift:144 用 emotionAnimationEnabled 同一开关门控；线下块族无 1h 窗（iOS 同）。
                        enabled = entryAnimationsEnabled,
                        reduceMotion = reduceMotion,
                        onRevealed = onBlockRevealed,
                        onPlayed = { if (index == blocks.lastIndex) playedIds.add(message.messageUUID) },
                    ) {
                        OfflineContentBlockView(
                            block = block,
                            characterName = characterName,
                            characterAvatarPath = characterAvatarPath,
                            userName = userName,
                            userAvatarPath = userAvatarPath,
                            themeColor = themeColor,
                            reduceMotion = reduceMotion,
                            onPhotoBackdrop = onPhotoBackdrop,
                        )
                    }
                }
            }
        }
    }
}

/** 非播放行的进度常量（无快照依赖=绝不失效·同 ChatMessageList 的同名纪律）。 */
private val ZeroProgress: () -> Float = { 0f }

/**
 * 剧场内的用户语音消息（卷三 §4.2·契约 FABLE5_MEETING_SEAM_PROPOSAL §5②/E1）：右对齐的舞台深玻璃药丸
 * （[VoiceMessageBubble] onStage 换肤·播放/波形/时长机制零改）+ 其下楷体转写小字常显——剧场读的就是转写，
 * 故不走气泡内部的「点击展开」（防双转写）；长按菜单剧场恒无、贴纸芯片不渲染（J5/J6）。
 */
@Composable
private fun OfflineUserVoice(
    message: MessageEntity,
    userName: String,
    themeColor: Color,
    playingVoiceId: String?,
    voiceProgress: () -> Float,
    onVoiceToggle: (MessageEntity) -> Unit,
) {
    val transcript = remember(message.content) { StickerTagParser.stripStickerTags(message.content).trim() }
    val isPlaying = message.messageUUID == playingVoiceId
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        VoiceMessageBubble(
            message = message,
            isUser = true,
            isPlaying = isPlaying,
            progress = if (isPlaying) voiceProgress else ZeroProgress,
            customStickers = emptyList(),
            onToggle = { onVoiceToggle(message) },
            onLongClick = {},
            a11yDescription = stringResource(
                R.string.a11y_bubble_voice,
                userName,
                DateFormatters.shortTime(message.timestamp),
                transcript,
            ),
            shape = AppShapes.full,
            onStage = true,
            stageAccent = themeColor,
        )
        Text(
            transcript,
            style = OfflineTheater.voiceTranscript,
            color = OfflineTheater.textFaint,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 用户消息：沉浸标签按块渲染，普通文本 → 用户行为块（1:1 iOS userMessageContent，无入场动画）。 */
@Composable
private fun OfflineUserBlocks(
    content: String,
    characterName: String,
    characterAvatarPath: String?,
    userName: String,
    userAvatarPath: String?,
    themeColor: Color,
    onPhotoBackdrop: Boolean,
) {
    val blocks = remember(content) { OfflineContentParser.parseUserBlocks(content) }
    Column(Modifier.fillMaxWidth()) {
        val rendered = blocks.ifEmpty { listOf(OfflineContentBlock.UserAction(content)) }
        rendered.forEach { block ->
            OfflineContentBlockView(
                block = block,
                characterName = characterName,
                characterAvatarPath = characterAvatarPath,
                userName = userName,
                userAvatarPath = userAvatarPath,
                themeColor = themeColor,
                onPhotoBackdrop = onPhotoBackdrop,
            )
        }
    }
}

// MARK: - 顶部装饰

@Composable
private fun HeaderDecoration(themeColor: Color) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "── ✦ ──",
            style = AppTypography.label.copy(fontSize = 12.sp),
            color = OfflineTheater.textFaint,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.DirectionsWalk,
                contentDescription = null,
                tint = themeColor,
                modifier = Modifier.size(16.dp),
            )
            Text("线下见面", style = AppTypography.label.copy(fontSize = 12.sp), color = themeColor)
        }
    }
}

// MARK: - 沉浸式等待画面

@Composable
private fun SceneLoadingView(characterName: String, characterAvatarPath: String?, themeColor: Color, reduceMotion: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.height(60.dp))
        BreathingAvatar(characterName, characterAvatarPath, themeColor, reduceMotion)
        Text(
            "正在前往见面地点…",
            style = AppTypography.label,
            color = OfflineTheater.textBody,
        )
        Spacer(Modifier.height(40.dp))
    }
}

/** 等待画面头像：呼吸缩放 + 透明度（1:1 iOS avatarForLoading；reduceMotion 静态）。 */
@Composable
private fun BreathingAvatar(name: String, avatarPath: String?, themeColor: Color, reduceMotion: Boolean) {
    val transition = rememberInfiniteTransition(label = "offlineLoadingAvatar")
    val scale by transition.animateFloat(
        0.95f, 1.05f, infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "offlineLoadingAvatarScale",
    )
    val avatarAlpha by transition.animateFloat(
        0.7f, 1f, infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "offlineLoadingAvatarAlpha",
    )
    Box(
        Modifier
            .graphicsLayer {
                val s = if (reduceMotion) 1f else scale
                scaleX = s
                scaleY = s
                alpha = if (reduceMotion) 1f else avatarAlpha
            }
            .shadow(20.dp, CircleShape, ambientColor = themeColor.copy(alpha = 0.2f), spotColor = themeColor.copy(alpha = 0.2f)),
    ) {
        CharacterAvatar(name = name, avatarPath = avatarPath, size = 80.dp)
    }
}

// MARK: - 小加载指示器

@Composable
private fun MiniLoadingIndicator(themeColor: Color, reduceMotion: Boolean) {
    val transition = rememberInfiniteTransition(label = "offlineMiniDots")
    val rowAlpha by transition.animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "offlineMiniDotsAlpha",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .graphicsLayer { alpha = if (reduceMotion) 1f else rowAlpha },
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        val dotAlpha = if (reduceMotion) 0.6f else 0.4f
        repeat(3) {
            Box(Modifier.size(4.dp).background(themeColor.copy(alpha = dotAlpha), CircleShape))
        }
    }
}
