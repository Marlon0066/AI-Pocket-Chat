package com.situ.aichat.ui.liuli.chat

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import com.situ.aichat.data.local.entity.CustomStickerEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.ui.chat.ChatRenderItem
import com.situ.aichat.ui.chat.ChatSendFlightState
import com.situ.aichat.ui.chat.MessageRowActions
import com.situ.aichat.ui.chat.RegenerableTurn
import com.situ.aichat.ui.chat.bubbleGroupsWith
import com.situ.aichat.ui.chat.emotionBubbleEntry
import com.situ.aichat.ui.chat.giftRedPacketArcEntry
import com.situ.aichat.ui.chat.isChatTimeBreak
import com.situ.aichat.ui.chat.userBubbleEntryScale
import com.situ.aichat.ui.components.AppMotion
import kotlinx.coroutines.delay

/**
 * 琉璃消息列表（图纸 2026-09-05 卷二A §4.4）：**反转底部锚定**四参数与行级动画记账逐条照抄暖陶
 * `ChatMessageList`（契约 REVERSE_LIST §2·`LiuliChatListBehaviorTest` 按同配置钉），只做两件琉璃事——
 * ① 每一行分派给 [LiuliMessageRow]（卷二C 收口后**不再 import 任何暖陶行级 UI**）② 连发段末条带尾巴（[isRunLast]）。
 *
 * 横幅族 / 日期胶囊 / 滚到底钮**不在本件内**（图纸 §3.1 把它们排在列表区 Box 的兄弟位，见 [LiuliChatLayout]）。
 */
@Composable
internal fun LiuliChatList(
    listState: LazyListState,
    listItems: List<ChatRenderItem>,
    messages: List<MessageEntity>,
    dismissKeyboardOnDrag: NestedScrollConnection,
    playingVoiceId: String?,
    voiceProgress: () -> Float,
    reduceMotion: Boolean,
    emotionAnimationEnabled: Boolean,
    animateArrivalsSinceMillis: Long,
    entryScalePlayed: MutableSet<String>,
    emotionPlayed: MutableList<String>,
    emotionHiddenIntervals: List<LongRange>,
    actions: MessageRowActions,
    userScrollEnabled: Boolean,
    deleteArm: State<Long>,
    sendFlight: ChatSendFlightState,
    /** 卷二B：表情回应爆点（纯瞬态·A-8）。 */
    reaction: LiuliReactionState,
    /** 卷二C：会话级长文折叠记账（纯瞬态·A-1）。 */
    fold: LiuliFoldState,
    characterName: String,
    avatarPath: String?,
    userName: String,
    userAvatarPath: String?,
    customStickers: List<CustomStickerEntity>,
    isSending: Boolean,
    /** 列表顶留白 = chrome 底 + 12dp（[LiuliChatGeometry.listTopPadding]）。 */
    contentTopPadding: Dp,
    /** 列表底留白 = 68dp + 导航栏 inset（[LiuliChatGeometry.listBottomPadding]）。 */
    contentBottomPadding: Dp,
    voiceSetupNeeded: Boolean = false,
) {
    // chat-ui-5 照抄：派生「已读」用户消息集合（其后存在 assistant 消息 = AI 已回 → ✓✓）。
    val readUserMessageUuids = remember(messages) {
        val set = HashSet<String>()
        var seenAssistant = false
        for (i in messages.indices.reversed()) {
            when (messages[i].roleRaw) {
                "assistant" -> seenAssistant = true
                "user" -> if (seenAssistant) set.add(messages[i].messageUUID)
            }
        }
        set
    }
    val regenerableUuids = remember(messages) { RegenerableTurn.trailingUuids(messages) }
    // 末条尾巴按 key 预算（保住 `items(listItems, key, contentType)` 的锁定形态·F5）。
    val tailByKey = remember(listItems) {
        listItems.indices.associate { listItems[it].key to isRunLast(listItems, it) }
    }
    val flightPending = sendFlight.pending
    val flightUuid = sendFlight.flightUuid
    // V9 照抄：位移动画默认关（与变身长高刚性锁步），唯删除窗内启用弹簧收拢。
    var removalSpringWindow by remember { mutableStateOf(false) }
    LaunchedEffect(deleteArm.value) {
        if (deleteArm.value > 0L) {
            removalSpringWindow = true
            delay(600)
            removalSpringWindow = false
        }
    }
    val placementSpec: FiniteAnimationSpec<IntOffset>? =
        if (removalSpringWindow) AppMotion.messageReceiveSpring(IntOffset.VisibilityThreshold) else null

    LazyColumn(
        state = listState,
        // ⚠️ 四参数（reverseLayout / Arrangement.Top / contentPadding / key）被 LiuliChatListBehaviorTest
        // 按同配置钉——改任一参数须同步该测试（契约 REVERSE_LIST §2·图纸 §3.2 锁）。
        reverseLayout = true,
        verticalArrangement = Arrangement.Top,
        userScrollEnabled = userScrollEnabled,
        modifier = Modifier
            .nestedScroll(dismissKeyboardOnDrag)
            .fillMaxSize()
            .padding(horizontal = LiuliChatGeometry.listHorizontal),
        // 反转布局下 bottom padding 即 beforeContentPadding（视觉仍在底部）；top = chrome 让位。
        contentPadding = PaddingValues(top = contentTopPadding, bottom = contentBottomPadding),
    ) {
        items(
            listItems,
            key = { it.key },
            contentType = {
                when (it) {
                    is ChatRenderItem.Message -> it.entity.messageKindRaw
                }
            },
        ) { renderItem ->
            when (renderItem) {
                is ChatRenderItem.Message -> {
                    val msg = renderItem.entity
                    val voicePlaying = playingVoiceId == msg.messageUUID
                    val newArrival = msg.timestamp >= animateArrivalsSinceMillis
                    val flightRow = (flightPending != null && flightPending.matches(msg)) ||
                        flightUuid == msg.messageUUID
                    val emotionPlay = !reduceMotion && emotionAnimationEnabled &&
                        msg.emotionTag != null && msg.messageUUID !in emotionPlayed &&
                        msg.timestamp > System.currentTimeMillis() - 3_600_000L &&
                        emotionHiddenIntervals.none { msg.timestamp in it }
                    val isGiftOrRedPacket = msg.messageKindRaw == MessageKind.GIFT_CARD.raw ||
                        msg.messageKindRaw == MessageKind.RED_PACKET.raw
                    val voiceCascadePlay = newArrival && msg.isVoiceMessage &&
                        msg.messageUUID !in entryScalePlayed
                    val rowModifier = if (reduceMotion) {
                        Modifier
                    } else {
                        Modifier
                            .animateItem(
                                fadeInSpec = if (newArrival && !flightRow) {
                                    if (msg.roleRaw == "user") AppMotion.messageSendSpring() else AppMotion.messageReceiveSpring()
                                } else {
                                    null
                                },
                                placementSpec = placementSpec,
                                fadeOutSpec = null,
                            )
                            .userBubbleEntryScale(
                                play = newArrival && !flightRow && msg.roleRaw == "user" && !isGiftOrRedPacket &&
                                    msg.messageUUID !in entryScalePlayed,
                                onPlayed = { entryScalePlayed.add(msg.messageUUID) },
                            )
                            .giftRedPacketArcEntry(
                                play = newArrival && isGiftOrRedPacket && msg.messageUUID !in entryScalePlayed,
                                fromUser = msg.roleRaw == "user",
                                onPlayed = { entryScalePlayed.add(msg.messageUUID) },
                            )
                    }
                    Box(
                        rowModifier
                            .emotionBubbleEntry(
                                emotionTag = msg.emotionTag,
                                play = emotionPlay,
                                onPlayed = { emotionPlayed.add(msg.messageUUID) },
                            )
                            .then(if (flightRow) Modifier.alpha(0f) else Modifier),
                    ) {
                        val deliveryRead = if (msg.roleRaw == "user") msg.messageUUID in readUserMessageUuids else null
                        val canRegenerate = RegenerableTurn.canRegenerate(msg.messageUUID, regenerableUuids, isSending)
                        // C4 收口（图纸 §8 C4 · A-11）：每一行都走琉璃自己的分派器，暖陶 `MessageRow` 自此卸下。
                        LiuliMessageRow(
                            message = msg,
                            topPadding = renderItem.topPadding,
                            characterName = characterName,
                            avatarPath = avatarPath,
                            userName = userName,
                            userAvatarPath = userAvatarPath,
                            customStickers = customStickers,
                            isVoicePlaying = voicePlaying,
                            voiceProgress = if (voicePlaying) voiceProgress else ZeroProgress,
                            actions = actions,
                            canRegenerate = canRegenerate,
                            deliveryRead = deliveryRead,
                            tail = tailByKey[renderItem.key] ?: true,
                            flightTracking = flightRow,
                            reaction = reaction,
                            reduceMotion = reduceMotion,
                            fold = fold,
                            voiceCascadePlay = voiceCascadePlay,
                            voiceSetupNeeded = voiceSetupNeeded,
                            dividerEntryAnimation = newArrival,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 连发段**末条**判定（图纸 §0 ② 7 · 纯函数 · T1-3）：反转序里 index 0 恒是末条（含打字占位）；
 * 其余看它与「更新的那一条」（index−1）成不成组——不成组即本段到此为止，带尾巴。
 * 成组判据复用暖陶纯函数 [bubbleGroupsWith] / [isChatTimeBreak]（同角色 + 无时间断层 + 双方 PLAIN_TEXT）。
 */
internal fun isRunLast(items: List<ChatRenderItem>, index: Int): Boolean {
    if (index <= 0) return true
    val current = (items[index] as? ChatRenderItem.Message)?.entity ?: return true
    val newer = (items[index - 1] as? ChatRenderItem.Message)?.entity ?: return true
    val timeBreak = isChatTimeBreak(current.timestamp, newer.timestamp)
    return !bubbleGroupsWith(
        earlierRole = current.roleRaw,
        earlierKindRaw = current.messageKindRaw,
        laterRole = newer.roleRaw,
        laterKindRaw = newer.messageKindRaw,
        separatedByTimeBreak = timeBreak,
    )
}

/** 非播放行的进度常量（审计 P3 照抄）：不触任何快照状态 → 行绝不因播放 tick 失效。 */
private val ZeroProgress: () -> Float = { 0f }
