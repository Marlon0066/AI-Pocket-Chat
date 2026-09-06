package com.situ.aichat.ui.liuli.chat

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.data.local.entity.CustomStickerEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.FutureMeetingChangeData
import com.situ.aichat.data.model.FutureMeetingProposalData
import com.situ.aichat.data.model.GiftCardData
import com.situ.aichat.data.model.MeetingStatus
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.model.OfflineInviteData
import com.situ.aichat.data.model.RedPacketData
import com.situ.aichat.data.model.RedPacketStatus
import com.situ.aichat.gift.FestivalCalendar
import com.situ.aichat.gift.GiftCatalog
import com.situ.aichat.sticker.StickerTagParser
import com.situ.aichat.ui.chat.MessageRowActions
import com.situ.aichat.ui.chat.StickerImage

/** 五张卡的解析产物（只搬 `when` 用·避免分派函数参数表爆炸）。 */
@Immutable
internal class LiuliParsedCards(
    val gift: GiftCardData?,
    val redPacket: RedPacketData?,
    val offline: OfflineInviteData?,
    val proposal: FutureMeetingProposalData?,
    val change: FutureMeetingChangeData?,
    val isScheduleCard: Boolean,
)

/** 贴纸两态（判据在 [LiuliMessageRow] 里算·此处只消费）。 */
@Immutable
internal class LiuliStickerState(val isStickerOnly: Boolean, val hasStickerTags: Boolean)

/**
 * 行内容分派（图纸 2026-09-05 卷二C §3.1 · A-11 · §9 ⑦ 「> 300 行即拆·只搬 `when`」）：
 * **分派序逐条照抄暖陶 F4 ⑥** —— 语音 → 红包 → 礼物 → 线下邀约 → 线下结束 → 约见面 → 改期 →
 * 图片（排在贴纸 / 脏消息之前）→ 日程卡 → 纯贴纸 → 混合贴纸 → 用户文字 → AI 文字。
 *
 * 订阅与懒加载的 `remember` key 也逐字照抄（审计 P2：Flow 实例 remember，否则每次重组新建 Flow 重启
 * Room 订阅）。
 */
@Composable
internal fun LiuliMessageContent(
    message: MessageEntity,
    isUser: Boolean,
    kind: MessageKind,
    parsed: LiuliParsedCards,
    stickerState: LiuliStickerState,
    characterName: String,
    customStickers: List<CustomStickerEntity>,
    isVoicePlaying: Boolean,
    voiceProgress: () -> Float,
    voiceCascadePlay: Boolean,
    actions: MessageRowActions,
    deliveryRead: Boolean?,
    tail: Boolean,
    bubbleMaxWidth: Dp,
    bubbleSentence: String?,
    openMenu: () -> Unit,
    onDoubleReact: (() -> Unit)?,
    fold: LiuliFoldState,
) {
    when {
        message.isVoiceMessage -> LiuliVoiceBubble(
            message = message,
            isUser = isUser,
            isPlaying = isVoicePlaying,
            progress = voiceProgress,
            customStickers = customStickers,
            tail = tail,
            onToggle = { actions.onVoiceToggle(message) },
            onLongClick = openMenu,
            a11yDescription = bubbleSentence,
            cascadePlay = voiceCascadePlay,
            onCascadePlayed = { actions.onVoiceCascadePlayed(message) },
            deliveryRead = deliveryRead,
        )
        parsed.redPacket != null -> {
            val packet = parsed.redPacket
            // 审计 P2 照抄：Flow 实例 remember——否则行每次重组新建 Flow 重启 Room 订阅（含一次查询）。
            val rpRecord by remember(packet.recordUUID) { actions.observeRedPacket(packet.recordUUID) }
                .collectAsStateWithLifecycle(null)
            val status = rpRecord?.let { RedPacketStatus.fromRaw(it.status) } ?: RedPacketStatus.PENDING
            val festivalName = remember(packet.festivalId) {
                packet.festivalId?.let { FestivalCalendar.festivalById(it)?.name }
            }
            LiuliRedPacketCard(
                data = packet,
                isFromUser = isUser,
                status = status,
                festivalName = festivalName,
                onClick = { actions.onRedPacketClick(packet) },
            )
        }
        parsed.gift != null -> {
            val gift = parsed.gift
            val isUserDiy = gift.giftItemId.startsWith(GiftCatalog.userDIYIdPrefix)
            var diyBitmap by remember(gift.giftRecordId) { mutableStateOf<Bitmap?>(null) }
            if (isUserDiy) {
                LaunchedEffect(gift.giftRecordId) { diyBitmap = actions.loadDiyImage(gift.giftRecordId) }
            }
            LiuliGiftCard(
                data = gift,
                isFromUser = isUser,
                diyImage = diyBitmap,
                onDiyClick = if (isUserDiy) ({ actions.onOpenDiyDetail(gift.giftRecordId) }) else null,
            )
        }
        parsed.offline != null && kind == MessageKind.OFFLINE_INVITE_CARD -> LiuliOfflineInviteCard(
            data = parsed.offline,
            characterName = characterName,
            onAccept = { actions.onAcceptInvite(message.messageUUID) },
            onDecline = { actions.onDeclineInvite(message.messageUUID) },
        )
        parsed.offline != null && kind == MessageKind.OFFLINE_END_CARD -> LiuliOfflineEndCard(
            data = parsed.offline,
            onEndMeeting = actions.onEndMeeting,
            onContinue = { actions.onContinueMeeting(message.messageUUID) },
        )
        parsed.proposal != null -> {
            val proposal = parsed.proposal
            // 审计 P2 照抄：订阅随 uuid 稳定。
            val appt by remember(proposal.appointmentUuid) { actions.observeAppointment(proposal.appointmentUuid) }
                .collectAsStateWithLifecycle(null)
            LiuliAppointmentProposalCard(
                data = proposal,
                status = appt?.let { MeetingStatus.fromRaw(it.status) },
                characterName = characterName,
                onAccept = { actions.onAppointmentAccept(proposal.appointmentUuid) },
                onReschedule = { actions.onAppointmentReschedule(proposal.appointmentUuid) },
                onDecline = { actions.onAppointmentDecline(proposal.appointmentUuid) },
            )
        }
        parsed.change != null -> LiuliAppointmentChangeCard(
            data = parsed.change,
            characterName = characterName,
            onApply = { actions.onAppointmentChangeApply(message.messageUUID) },
            onKeep = { actions.onAppointmentChangeKeep(message.messageUUID) },
        )
        // 图片（照 iOS 口径 = PLAIN_TEXT + 侧车 imageRelativePath）：排在贴纸 / 脏消息之前——它的正文
        // 恒是哨兵 `[图片]`，不该再走那些文本判定（F4 ⑥ 照抄）。
        message.imageRelativePath != null -> LiuliImageBubble(
            imagePath = message.imageRelativePath,
            thumbnailPath = message.imageThumbnailRelativePath,
            isUser = isUser,
            maxWidth = bubbleMaxWidth,
            timestampMs = message.timestamp,
            deliveryRead = deliveryRead,
            onClick = { actions.onOpenImage(message.imageRelativePath) },
            onLongClick = openMenu,
            a11yDescription = bubbleSentence,
        )
        parsed.isScheduleCard -> LiuliScheduleCard(content = message.content, onLongClick = openMenu)
        stickerState.isStickerOnly -> LiuliStickerStack(
            content = message.content,
            customStickers = customStickers,
            isUser = isUser,
            timestampMs = message.timestamp,
            deliveryRead = deliveryRead,
            onLongClick = openMenu,
            a11yDescription = bubbleSentence,
        )
        stickerState.hasStickerTags -> Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(MixedStickerGap),
        ) {
            val textPart = StickerTagParser.stripStickerTags(message.content)
            if (textPart.isNotEmpty()) {
                LiuliBubble(message, textPart, isUser, deliveryRead, tail, bubbleMaxWidth, characterName, openMenu, onDoubleReact, bubbleSentence, fold)
            }
            StickerTagParser.extractStickerIds(message.content).forEach { id ->
                StickerImage(stickerId = id, customStickers = customStickers, size = LiuliChatGeometry.stickerSize)
            }
        }
        else -> LiuliBubble(message, message.content, isUser, deliveryRead, tail, bubbleMaxWidth, characterName, openMenu, onDoubleReact, bubbleSentence, fold)
    }
}

/** 用户 / AI 两支的收口（引用块的发送者标签口径照抄暖陶：user → 「你」，否则角色名）。 */
@Composable
private fun LiuliBubble(
    message: MessageEntity,
    text: String,
    isUser: Boolean,
    deliveryRead: Boolean?,
    tail: Boolean,
    maxWidth: Dp,
    characterName: String,
    onLongClick: () -> Unit,
    onDoubleClick: (() -> Unit)?,
    a11yDescription: String?,
    fold: LiuliFoldState,
) {
    val quotedSender = message.quotedSenderRole?.let { if (it == "user") "你" else characterName }
    if (isUser) {
        LiuliUserBubble(
            text = text,
            quotedContent = message.quotedContent,
            quotedSender = quotedSender,
            timestampMs = message.timestamp,
            deliveryRead = deliveryRead,
            tail = tail,
            maxWidth = maxWidth,
            onLongClick = onLongClick,
            a11yDescription = a11yDescription,
        )
    } else {
        LiuliAssistantBubble(
            revealed = message.isContentRevealed,
            text = text,
            quotedContent = message.quotedContent,
            quotedSender = quotedSender,
            timestampMs = message.timestamp,
            tail = tail,
            maxWidth = maxWidth,
            onLongClick = onLongClick,
            onDoubleClick = onDoubleClick,
            a11yDescription = a11yDescription,
            messageUuid = message.messageUUID,
            fold = fold,
        )
    }
}

/** 混合贴纸行内的行距（照抄暖陶 `MessageRow` 的 6dp；贴纸尺寸走琉璃档 110·A-7）。 */
private val MixedStickerGap = 6.dp
