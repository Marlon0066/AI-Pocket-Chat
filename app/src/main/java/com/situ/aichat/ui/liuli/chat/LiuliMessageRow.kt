package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CustomStickerEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.CallRecordJson
import com.situ.aichat.data.model.FutureMeetingChangeJson
import com.situ.aichat.data.model.FutureMeetingProposalJson
import com.situ.aichat.data.model.GiftCardJson
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.model.OfflineInviteJson
import com.situ.aichat.data.model.RedPacketJson
import com.situ.aichat.data.model.SystemEventJson
import com.situ.aichat.offline.OfflineChatVisibility
import com.situ.aichat.offline.OfflineMarkerEndPayload
import com.situ.aichat.prompt.CalendarItemParser
import com.situ.aichat.prompt.DirtyMessageDetector
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.sticker.StickerTagParser
import com.situ.aichat.ui.chat.MessageRowActions
import com.situ.aichat.ui.chat.messageCanBeQuoted
import com.situ.aichat.ui.chat.rememberBubbleMaxWidth
import com.situ.aichat.ui.chat.rememberMessageRowA11yActions
import com.situ.aichat.ui.chat.rememberRelativeTimeStrings
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.util.DateFormatters

/**
 * 琉璃消息行（图纸 2026-09-05 卷二C §4 · A-11：卷二A 的 `LiuliTextRow` 升级成**全族分派器**）。
 * C4 起琉璃聊天屏**不再 import 任何暖陶行级 UI**——`MessageRow` 与它内部十三件一并卸下。
 *
 * 门与语义**逐条照抄**暖陶 `MessageRow`（F4）：前置返回三件（系统事件 / 通话记录 / 离场分隔线）→
 * 见面期隐藏兜底 → 五解析 `remember` → 脏消息整行不渲染 → 贴纸两态 → 合并朗读句 → 右滑引用两道闸 →
 * 行级 a11y 动作面 → 长按上报气泡窗口边界；`when` 分派序与 F4 ⑥ 逐条同。琉璃只换长相。
 */
@Composable
internal fun LiuliMessageRow(
    message: MessageEntity,
    topPadding: Dp,
    characterName: String,
    avatarPath: String?,
    userName: String,
    userAvatarPath: String?,
    customStickers: List<CustomStickerEntity>,
    isVoicePlaying: Boolean,
    voiceProgress: () -> Float,
    actions: MessageRowActions,
    canRegenerate: Boolean,
    deliveryRead: Boolean?,
    /** 本条是否连发段末条（带尾巴·判据 [isRunLast]）。 */
    tail: Boolean,
    /** M3b ④：飞行目标行每次布局上报边界。 */
    flightTracking: Boolean,
    /** 卷二B：表情回应的纯瞬态爆点（A-8·不入库不进上下文）。 */
    reaction: LiuliReactionState,
    reduceMotion: Boolean,
    /** 卷二C A-1：会话级长文折叠记账。 */
    fold: LiuliFoldState,
    voiceCascadePlay: Boolean = false,
    voiceSetupNeeded: Boolean = false,
    dividerEntryAnimation: Boolean = false,
) {
    val isUser = message.roleRaw == "user"
    val kind = MessageKind.fromRaw(message.messageKindRaw)

    // ① 前置返回三件（F4 ①）：不进「左右对齐 + 气泡」那一行，各自居中成条。
    if (kind == MessageKind.SYSTEM_EVENT_CARD) {
        SystemEventJson.parse(message.content)?.let {
            LiuliSystemEventLine(it, modifier = Modifier.padding(top = topPadding))
        }
        return
    }
    if (kind == MessageKind.CALL_RECORD_CARD) {
        CallRecordJson.parse(message.content)?.let { record ->
            Box(Modifier.fillMaxWidth().padding(top = topPadding), contentAlignment = Alignment.Center) {
                LiuliCallRecordCard(
                    data = record,
                    characterName = characterName,
                    characterAvatarPath = avatarPath,
                    userName = userName,
                    userAvatarPath = userAvatarPath,
                    // VU3 自愈显示：本通有过失声 且 当前仍没修好，才长琥珀尾巴（照抄 F4 ①）。
                    showVoiceSetupHint = record.hadTtsFailure && voiceSetupNeeded,
                    onOpenVoiceSetup = actions.onOpenVoiceSetup,
                )
            }
        }
        return
    }
    if (kind == MessageKind.OFFLINE_MARKER_END) {
        OfflineMarkerEndPayload.parse(message.content)?.let {
            // offline-1 照抄：有 sessionId 才可点进回顾。
            val onClick = message.offlineSessionId?.let { sid -> { actions.onReviewOffline(sid) } }
            Box(Modifier.padding(top = topPadding)) {
                LiuliOfflineEndDivider(it.durationText, onClick = onClick, entryAnimation = dividerEntryAnimation)
            }
        }
        return
    }

    // ② 见面期细节 / 系统耳语 / 通话逐轮转写不进日常聊天（数据源已过滤·此处为渲染兜底·照抄）。
    if (OfflineChatVisibility.isHiddenFromDailyChat(message.isOfflineMode, kind, message.isPartOfVoiceCall)) return

    // ③ 五个解析（F4 ③·key 与暖陶逐字同：`content` + `kind`）。
    val isCard = kind == MessageKind.SCHEDULE_CARD
    val giftCard = remember(message.content, kind) {
        if (kind == MessageKind.GIFT_CARD) GiftCardJson.parse(message.content) else null
    }
    val redPacket = remember(message.content, kind) {
        if (kind == MessageKind.RED_PACKET) RedPacketJson.parse(message.content) else null
    }
    val offlineCard = remember(message.content, kind) {
        if (kind == MessageKind.OFFLINE_INVITE_CARD || kind == MessageKind.OFFLINE_END_CARD) {
            OfflineInviteJson.parse(message.content)
        } else {
            null
        }
    }
    val proposal = remember(message.content, kind) {
        if (kind == MessageKind.FUTURE_MEETING_PROPOSAL_CARD) FutureMeetingProposalJson.parse(message.content) else null
    }
    val change = remember(message.content, kind) {
        if (kind == MessageKind.FUTURE_MEETING_CHANGE_CARD) FutureMeetingChangeJson.parse(message.content) else null
    }

    // ④ 脏消息彻底隐身（F4 ④）：整行不渲染、零高占位、长按 / 复制 / 朗读均不可达。
    val isDirty = remember(message.messageUUID, message.content) {
        DirtyMessageDetector.isDirty(message.content, kind)
    }
    if (isDirty) return

    // ⑤ 贴纸两态（F4 ⑤·判据逐字照抄，`hasStickerTags` 排除纯贴纸）。
    val isStickerOnly = remember(message.content) { StickerTagParser.isStickerOnly(message.content) }
    val hasStickerTags = remember(message.content) {
        !isStickerOnly && StickerTagParser.containsStickerTag(message.content)
    }

    var bubbleBounds by remember { mutableStateOf(Rect.Zero) }
    val rowHaptics = LocalAppHaptics.current
    val openMenu = { rowHaptics.medium(); actions.onOpenMenu(message, bubbleBounds, canRegenerate) }
    // 双击回应只挂 AI **文字**泡（契约 §5.3·A-11：卡片 / 语音 / 图片不挂），且只在显形后。
    val onDoubleReact: (() -> Unit)? =
        if (!isUser && message.isContentRevealed) {
            { reaction.play(message.messageUUID, LiuliDoubleTapEmoji) }
        } else {
            null
        }

    // ⑥ 合并朗读句（F4 ⑦ 逐字）：只给无独立子控件的气泡（文字 / 混合贴纸 / 纯贴纸 / 语音）；
    // 红包 / 礼物 / 邀约 / 日程卡保留各自的 Button 语义。
    val bubbleSentence: String? = if (kind == MessageKind.PLAIN_TEXT || message.isVoiceMessage) {
        val relStrings = rememberRelativeTimeStrings()
        val roleName = if (isUser) stringResource(R.string.a11y_role_you) else characterName
        val timeText = DateFormatters.relativeTimeString(
            message.timestamp, System.currentTimeMillis(), relStrings, detailed = true,
        )
        // 图片消息正文恒是内部哨兵 `[图片]`——读屏这一路走同一枚语义渲染（红线 IMAGE_MULTIMODAL §B6）。
        val clean = if (message.imageRelativePath != null) {
            MemoryService.renderImageSemantics(message.content, message.mediaMemorySummary)
        } else {
            StickerTagParser.replaceStickerTagsForDisplay(CalendarItemParser.stripCalendarRefs(message.content))
        }
        val body = if (message.isVoiceMessage) {
            stringResource(R.string.a11y_bubble_voice, roleName, timeText, clean)
        } else {
            stringResource(R.string.a11y_bubble_said, roleName, timeText, clean)
        }
        when (deliveryRead) {
            true -> "$body，${stringResource(R.string.a11y_message_read)}"
            false -> "$body，${stringResource(R.string.a11y_message_delivered)}"
            null -> body
        }
    } else {
        null
    }
    val bubbleMaxWidth = rememberBubbleMaxWidth()

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = topPadding),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        // chat-ui-4 右滑引用两道闸（照抄）：① 已显形 ② 正文有话可引（判据单源 messageCanBeQuoted·卡片恒 false）。
        val canQuote = remember(message.messageUUID, message.content) { messageCanBeQuoted(message) }
        LiuliSwipeToReplyBox(
            enabled = message.isContentRevealed && canQuote,
            onTriggered = { actions.onQuote(message) },
        ) {
            // M2 Y2 收编（照抄 F4）：条件与沉浸菜单同源；恒调用、由 eligible 决定给不给。
            val a11yMenuEligible = bubbleSentence != null && message.isContentRevealed &&
                !message.isVoiceMessage && !hasStickerTags
            val a11yMenuActions = rememberMessageRowA11yActions(message, isUser, canRegenerate, actions, a11yMenuEligible)
            Box(
                modifier = Modifier
                    .onGloballyPositioned {
                        bubbleBounds = it.boundsInWindow()
                        if (flightTracking) actions.onFlightBubblePositioned(message, bubbleBounds)
                    }
                    .then(
                        if (a11yMenuActions.isNotEmpty()) {
                            Modifier.semantics(mergeDescendants = true) { customActions = a11yMenuActions }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                LiuliMessageContent(
                    message = message,
                    isUser = isUser,
                    kind = kind,
                    parsed = LiuliParsedCards(giftCard, redPacket, offlineCard, proposal, change, isCard),
                    stickerState = LiuliStickerState(isStickerOnly, hasStickerTags),
                    characterName = characterName,
                    customStickers = customStickers,
                    isVoicePlaying = isVoicePlaying,
                    voiceProgress = voiceProgress,
                    voiceCascadePlay = voiceCascadePlay,
                    actions = actions,
                    deliveryRead = deliveryRead,
                    tail = tail,
                    bubbleMaxWidth = bubbleMaxWidth,
                    bubbleSentence = bubbleSentence,
                    openMenu = openMenu,
                    onDoubleReact = onDoubleReact,
                    fold = fold,
                )
                // 徽章画在气泡那一格**之外**（不被泡的 clip 裁掉），且 matchParentSize 让它不参与定尺。
                LiuliReactionBurst(
                    burst = reaction.burst,
                    messageUuid = message.messageUUID,
                    reduceMotion = reduceMotion,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

/** 双击回应恒 ❤️（契约 §5.3·菜单顶行才给五选一）。 */
internal const val LiuliDoubleTapEmoji = "❤️"
