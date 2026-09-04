package com.situ.aichat.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.data.local.entity.CustomStickerEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.local.entity.RedPacketRecordEntity
import com.situ.aichat.data.model.CallRecordJson
import com.situ.aichat.data.model.GiftCardJson
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.ui.offline.OfflineModeView
import com.situ.aichat.data.model.OfflineInviteJson
import com.situ.aichat.offline.OfflineChatVisibility
import com.situ.aichat.offline.OfflineMarkerEndPayload
import com.situ.aichat.data.model.FutureMeetingChangeJson
import com.situ.aichat.data.model.FutureMeetingProposalData
import com.situ.aichat.data.model.FutureMeetingProposalJson
import com.situ.aichat.data.model.MeetingStatus
import com.situ.aichat.data.model.RedPacketData
import com.situ.aichat.data.model.RedPacketJson
import com.situ.aichat.data.model.RedPacketStatus
import com.situ.aichat.data.model.SystemEventJson
import com.situ.aichat.prompt.CalendarItemParser
import com.situ.aichat.prompt.DirtyMessageDetector
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.sticker.StickerTagParser
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.util.DateFormatters
import com.situ.aichat.R
import com.situ.aichat.gift.GiftCatalog
import com.situ.aichat.gift.FestivalCalendar
import com.situ.aichat.ui.gift.GiftCardBubble
import com.situ.aichat.ui.meeting.AppointmentChangeBubble
import com.situ.aichat.ui.meeting.AppointmentProposalBubble
import com.situ.aichat.ui.voicecall.CallRecordCardBubble
import com.situ.aichat.ui.redpacket.RedPacketCardBubble
import com.situ.aichat.ui.redpacket.RedPacketSystemEventCard
import kotlinx.coroutines.flow.Flow

/**
 * [MessageRow] 的动作面（审计 S7·33 参收敛）：21 个 on…/observe… 回调收进单个 @Immutable 对象，
 * 由 [ChatMessageList] `remember` 一次构造——回调作为参数从「每次列表重组新建的 lambda」变成稳定引用，
 * 行级 skip 恢复生效（性能 P 批顺带受益）。per-消息回调改带 [MessageEntity]/id 入参，行内薄适配。
 */
@androidx.compose.runtime.Immutable
internal class MessageRowActions(
    val onVoiceToggle: (MessageEntity) -> Unit,
    /** 点开图片气泡 → 全屏查看器（传原图路径，非缩略图）。 */
    val onOpenImage: (String) -> Unit,
    /** 长按菜单「保存到相册」（仅图片消息出现）。 */
    val onSaveImage: (MessageEntity) -> Unit,
    val onQuote: (MessageEntity) -> Unit,
    val onDelete: (MessageEntity) -> Unit,
    /** M2 沉浸菜单：长按开菜单（消息 + 气泡在窗口内的边界·供覆盖层原位浮起与菜单定位）。 */
    val onOpenMenu: (message: MessageEntity, bubbleBounds: Rect, canRegenerate: Boolean) -> Unit,
    /** M3b 发送飞入：飞行目标气泡每次布局上报边界（握手就位决议 + 飞行移动靶·仅 flightTracking 行调）。 */
    val onFlightBubblePositioned: (MessageEntity, Rect) -> Unit,
    val onRegenerate: () -> Unit,
    val loadDiyImage: suspend (String) -> Bitmap?,
    val onOpenDiyDetail: (String) -> Unit,
    val observeRedPacket: (String) -> Flow<RedPacketRecordEntity?>,
    val onRedPacketClick: (RedPacketData) -> Unit,
    val onAcceptInvite: (String) -> Unit,
    val onDeclineInvite: (String) -> Unit,
    val onEndMeeting: () -> Unit,
    val onContinueMeeting: (String) -> Unit,
    val onReviewOffline: (String) -> Unit,
    val observeAppointment: (String) -> Flow<MeetingAppointmentEntity?>,
    val onAppointmentAccept: (String) -> Unit,
    val onAppointmentDecline: (String) -> Unit,
    val onAppointmentReschedule: (String) -> Unit,
    val onAppointmentChangeApply: (String) -> Unit,
    val onAppointmentChangeKeep: (String) -> Unit,
    val onVoiceCascadePlayed: (MessageEntity) -> Unit,
    /** VU3 通话卡琥珀尾巴：点击深链语音设置（按当前 need 分支·全局配置→ttsConfig / 其余→角色编辑?focusVoice）。 */
    val onOpenVoiceSetup: () -> Unit,
)

// Fable-5 聊天消息行（契约 · 从 ChatScreen 巨石抽出·仿 iOS MessageRow.swift）：按 kind 分发到各气泡(文本/语音/红包/
// 礼物/邀约/日程/系统事件/通话/贴纸)、长按菜单、右滑引用、气泡内时间戳;占位(isContentRevealed=false)走会变身气泡。
// 对外 internal(ChatScreen 的 LazyColumn 调)。

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageRow(
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
    /** 本行是否给「重新生成」（=末尾连续 assistant 段成员 × 当前无在跑回合·算法单源见 ChatMessageList）。 */
    canRegenerate: Boolean,
    /** chat-ui-5 回执：null=非用户消息（不显）/ true=已读 ✓✓ / false=送达 ✓（1s 后显）。 */
    deliveryRead: Boolean?,
    /** Chunk 3：true=该语音消息新到达一刻，波形从左到右依次长出（门控见 ChatMessageList·复用 entryScalePlayed 记账）。 */
    voiceCascadePlay: Boolean = false,
    /** M3b ④：true=本行是发送飞入的目标（握手匹配/飞行中）——气泡每次布局上报边界供就位决议与移动靶。 */
    flightTracking: Boolean = false,
    /** VU3：当前角色仍缺可用音色（自愈显示门控）——仅失败通话卡（hadTtsFailure）且此为 true 时才长琥珀尾巴。 */
    voiceSetupNeeded: Boolean = false,
    /** 卷三 V3：true=离场分隔条「新到达」一刻，走落成动画（历史回看恒 false·门控见 ChatMessageList 的 newArrival）。 */
    dividerEntryAnimation: Boolean = false,
) {
    val isUser = message.roleRaw == "user"
    val kind = MessageKind.fromRaw(message.messageKindRaw)

    // 系统事件（红包 accepted/rejected/expired）→ 居中灰字卡，不进头像+气泡行（1:1 iOS SystemEventCard 极简样式）。
    // chat-ui-9：系统事件在 iOS 也是带 topPadding 的 message item，故同样施加分组上间距。
    if (kind == MessageKind.SYSTEM_EVENT_CARD) {
        SystemEventJson.parse(message.content)?.let {
            RedPacketSystemEventCard(it, modifier = Modifier.padding(top = topPadding))
        }
        return
    }

    // 通话记录卡（P10.1i）→ 居中绿卡（通话是双方共同事件），同样不进头像+气泡行（1:1 iOS CallRecordBubbleView）。
    if (kind == MessageKind.CALL_RECORD_CARD) {
        CallRecordJson.parse(message.content)?.let { record ->
            CallRecordCardBubble(
                data = record,
                characterName = characterName,
                characterAvatarPath = avatarPath,
                userName = userName,
                userAvatarPath = userAvatarPath,
                modifier = Modifier.padding(top = topPadding),
                // VU3 自愈显示：本通有过失声 且 当前仍没修好，才长琥珀尾巴。
                showVoiceSetupHint = record.hadTtsFailure && voiceSetupNeeded,
                onOpenVoiceSetup = actions.onOpenVoiceSetup,
            )
        }
        return
    }

    // M16 线下离场标记：居中灰字分隔「线下见面结束 · 时长」——唯一进入日常聊天的见面消息。
    if (kind == MessageKind.OFFLINE_MARKER_END) {
        OfflineMarkerEndPayload.parse(message.content)?.let {
            // offline-1：有 sessionId 才可点进回顾（对齐 iOS `if sessionId != nil`）。
            val onClick = message.offlineSessionId?.let { sid -> { actions.onReviewOffline(sid) } }
            Box(Modifier.padding(top = topPadding)) {
                OfflineEndDivider(it.durationText, onClick = onClick, entryAnimation = dividerEntryAnimation)
            }
        }
        return
    }
    // 系统耳语（给 AI 的旁白·如取消见面提示）+ 见面期间细节（叙事/动作块/入场标记/「准备出发」确认/结束确认卡）
    // + 语音通话逐轮转写（只留通话记录卡）都不进日常聊天：数据源 observeVisibleWindowed 已在 SQL 过滤，
    // 此处为渲染兜底（沉浸剧场走独立 OfflineModeView）。
    if (OfflineChatVisibility.isHiddenFromDailyChat(message.isOfflineMode, kind, message.isPartOfVoiceCall)) return

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
    // 脏消息彻底隐身（图纸 2026-09-01 件①·用户拍板）：整行不渲染、零高占位、长按/复制/朗读均不可达。
    // 新脏消息已在落库前被 AssistantOutputGate 丢弃；这里兜的是**库内历史脏行**（有意不物理删除，
    // 检测规则将来升级时历史行为自动跟进）。
    val isDirty = remember(message.messageUUID, message.content) {
        DirtyMessageDetector.isDirty(message.content, kind)
    }
    if (isDirty) return
    // M17 表情包：纯贴纸（无气泡大图）/ 混合（文字进气泡、贴纸独立显示）。渲染优先级对齐 iOS（dirty 在前）。
    val isStickerOnly = remember(message.content) { StickerTagParser.isStickerOnly(message.content) }
    val hasStickerTags = remember(message.content) {
        !isStickerOnly && StickerTagParser.containsStickerTag(message.content)
    }
    // C3-haptics 根因修（契约 §3.5）：长按触觉内联进 onLongClick 即时触发；medium=长按弹菜单（契约 §2），
    // 一个 lambda 覆盖所有气泡类型。M2 沉浸菜单：开合状态外移 ChatScreen 覆盖层，行只上报消息+气泡窗口边界。
    var bubbleBounds by remember { mutableStateOf(Rect.Zero) }
    val rowHaptics = LocalAppHaptics.current
    val openMenu = { rowHaptics.medium(); actions.onOpenMenu(message, bubbleBounds, canRegenerate) }

    // P1-1（批1·14.7e 登记⑥的分流再设计）：气泡合并朗读句（=iOS MessageBubbleView.swift:315-331
    // accessibilityDescription）——「{你|角色名}在{相对时间.detailed}说：{清洗正文}」/ 语音变体；只对
    // 无独立子控件的气泡（文字/混合贴纸/纯贴纸/语音）生效，红包/礼物/邀约/日程卡保留各自 Button 语义。
    // iOS role 用字面「角色」，安卓用角色名=多会话 TalkBack 可辨（有据超越）；用户消息回执态追加句尾。
    val bubbleSentence: String? = if (kind == MessageKind.PLAIN_TEXT || message.isVoiceMessage) {
        val relStrings = rememberRelativeTimeStrings()
        val roleName = if (isUser) stringResource(R.string.a11y_role_you) else characterName
        val timeText = DateFormatters.relativeTimeString(
            message.timestamp, System.currentTimeMillis(), relStrings, detailed = true,
        )
        // 图片消息的正文是内部哨兵 `[图片]`——直接念出来读屏用户只会听到三个字符。IM5 把哨兵在五条
        // 提示词旁路上都堵了，这里是漏掉的第六条（读屏）。有图片理解摘要就一并念出来。
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

    // Fable-5 气泡形状（2026-06-19 拍板取代旧 D3）：四角统一 16dp·用户/AI 同·连发不再连成水滴串。
    val bubbleShape = AppShapes.bubble
    val bubbleMaxWidth = rememberBubbleMaxWidth()

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = topPadding),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        // B2（契约取代 2026-06-20「每条带头像」）：去逐条头像、回 iOS 纯净版——靠左右对齐 + 气泡皮肤区分发送方。
        // 新架构里列表项自己就是稳定锚点（占位与首段同 key 原地变身），旧「头像当锚点」的使命消失。
        // 头像仅保留在：空会话大呼吸头像、顶栏档案入口、通话卡内部（发送方归属）。
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp), // ≈ iOS MessageBubbleContent VStack spacing 4
        ) {
        // chat-ui-4：右滑引用。两道闸——① 仅已显形的气泡（1:1 iOS isContentRevealed，流式占位不可引用）；
        // ② 2026-09-04 用户拍板：**正文有话可引的气泡才可引用**（纯文字/贴纸/转写到位的语音），判据与
        // 长按菜单/读屏动作面共用单源 [messageCanBeQuoted]（图片/卡片的引用仍挂起，做多模态引用时一并重开）。
        val canQuote = remember(message.messageUUID, message.content) { messageCanBeQuoted(message) }
        SwipeToReplyBox(
            enabled = message.isContentRevealed && canQuote,
            onTriggered = { actions.onQuote(message) },
        ) {
        // （原「脏消息点击展开」的 animateContentSize 随折叠条一并退役——脏行自 2026-09-01 起彻底不渲染。
        // 常规路径本就不挂 animateContentSize，绝不干扰 AssistantTextBubble 的打字变身。）
        // M2 Y2 收编：常规气泡族（纯文本/纯贴纸·已有合并朗读句）行级 customActions——读屏不开视觉菜单即可
        // 触发动作，条件与沉浸菜单逐字同源（immersiveMenuActions）。语音（播放/转写子控件）与混合贴纸行
        // **有意不并**：mergeDescendants 会吞掉子控件语义（Y3 转写开关等），保持 Y2 onLongClickLabel 现状。
        // 复核 R1 🟡-3：补 `isContentRevealed`——占位气泡（content=""·未落库·conversationUuid 空）原先也挂
        // 动作面，读屏用户能对一条**根本不存在的消息**按「删除」/「引用」。视觉两路早有此闸（长按走
        // ChatBubbles 的 `if (revealed)`、右滑走 enabled），只有读屏这一路漏了；契约 §3.3「isContentRevealed
        // == true 且 messageCanBeQuoted()」自此三路兑现一致。
        val a11yMenuEligible = bubbleSentence != null && message.isContentRevealed &&
            !message.isVoiceMessage && !hasStickerTags
        // 动作面构造搬 ChatMessageRowA11y.kt（含「陈旧快照」修：lambda 读最新 entity 而非首帧闭包）。
        // 恒调用、由 eligible 参数决定给不给（复核 R2 🔵-4：包 if 会让 copyScope 随组丢弃被取消）。
        val a11yMenuActions = rememberMessageRowA11yActions(message, isUser, canRegenerate, actions, a11yMenuEligible)
        Box(
            modifier = Modifier
                .onGloballyPositioned {
                    bubbleBounds = it.boundsInWindow()
                    // M3b ④：目标行每次布局上报（就位帧=清空+起飞同帧；飞行期=移动靶随列表位移逐帧更新）。
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
            when {
                message.isVoiceMessage -> VoiceMessageBubble(
                    message = message,
                    isUser = isUser,
                    isPlaying = isVoicePlaying,
                    progress = voiceProgress,
                    customStickers = customStickers,
                    onToggle = { actions.onVoiceToggle(message) },
                    onLongClick = openMenu,
                    a11yDescription = bubbleSentence,
                    shape = bubbleShape,
                    cascadePlay = voiceCascadePlay,
                    onCascadePlayed = { actions.onVoiceCascadePlayed(message) },
                )
                redPacket != null -> {
                    // 审计 P2：Flow 实例 remember——否则行每次重组新建 Flow 重启 Room 订阅（含一次查询）。
                    val rpRecord by remember(redPacket.recordUUID) { actions.observeRedPacket(redPacket.recordUUID) }.collectAsStateWithLifecycle(null)
                    val status = rpRecord?.let { RedPacketStatus.fromRaw(it.status) } ?: RedPacketStatus.PENDING
                    val festivalName = remember(redPacket.festivalId) {
                        redPacket.festivalId?.let { FestivalCalendar.festivalById(it)?.name }
                    }
                    RedPacketCardBubble(
                        data = redPacket,
                        isFromUser = isUser,
                        status = status,
                        festivalName = festivalName,
                        onClick = { actions.onRedPacketClick(redPacket) },
                    )
                }
                giftCard != null -> {
                    val isUserDiy = giftCard.giftItemId.startsWith(GiftCatalog.userDIYIdPrefix)
                    var diyBitmap by remember(giftCard.giftRecordId) { mutableStateOf<Bitmap?>(null) }
                    if (isUserDiy) {
                        LaunchedEffect(giftCard.giftRecordId) { diyBitmap = actions.loadDiyImage(giftCard.giftRecordId) }
                    }
                    GiftCardBubble(
                        data = giftCard,
                        isFromUser = isUser,
                        diyImage = diyBitmap,
                        onDiyClick = if (isUserDiy) ({ actions.onOpenDiyDetail(giftCard.giftRecordId) }) else null,
                    )
                }
                offlineCard != null && kind == MessageKind.OFFLINE_INVITE_CARD -> OfflineInviteCardBubble(
                    data = offlineCard,
                    characterName = characterName,
                    onAccept = { actions.onAcceptInvite(message.messageUUID) },
                    onDecline = { actions.onDeclineInvite(message.messageUUID) },
                )
                offlineCard != null && kind == MessageKind.OFFLINE_END_CARD -> OfflineEndCardBubble(
                    data = offlineCard,
                    onEndMeeting = actions.onEndMeeting,
                    onContinue = { actions.onContinueMeeting(message.messageUUID) },
                )
                proposal != null -> {
                    // 审计 P2：同上，订阅随 uuid 稳定。
                    val appt by remember(proposal.appointmentUuid) { actions.observeAppointment(proposal.appointmentUuid) }.collectAsStateWithLifecycle(null)
                    AppointmentProposalBubble(
                        data = proposal,
                        status = appt?.let { MeetingStatus.fromRaw(it.status) },
                        characterName = characterName,
                        onAccept = { actions.onAppointmentAccept(proposal.appointmentUuid) },
                        onReschedule = { actions.onAppointmentReschedule(proposal.appointmentUuid) },
                        onDecline = { actions.onAppointmentDecline(proposal.appointmentUuid) },
                    )
                }
                change != null -> AppointmentChangeBubble(
                    data = change,
                    characterName = characterName,
                    onApply = { actions.onAppointmentChangeApply(message.messageUUID) },
                    onKeep = { actions.onAppointmentChangeKeep(message.messageUUID) },
                )
                // 图片消息（照 iOS 口径 = PLAIN_TEXT + 侧车 imageRelativePath，不新增 MessageKind）：
                // 排在贴纸/脏消息之前——它的正文恒是哨兵 `[图片]`，不该再走那些文本判定。
                message.imageRelativePath != null -> ChatImageBubble(
                    imagePath = message.imageRelativePath,
                    thumbnailPath = message.imageThumbnailRelativePath,
                    shape = bubbleShape,
                    maxWidth = bubbleMaxWidth,
                    onClick = { actions.onOpenImage(message.imageRelativePath) },
                    onLongClick = openMenu,
                    a11yDescription = bubbleSentence,
                )
                isCard -> ScheduleCardBubble(content = message.content, onLongClick = openMenu)
                isStickerOnly -> StickerStack(
                    content = message.content,
                    customStickers = customStickers,
                    onLongClick = openMenu,
                    a11yDescription = bubbleSentence,
                )
                hasStickerTags -> Column(
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val textPart = StickerTagParser.stripStickerTags(message.content)
                    if (textPart.isNotEmpty()) {
                        Bubble(
                            isUser = isUser,
                            text = textPart,
                            quotedContent = message.quotedContent,
                            quotedSender = message.quotedSenderRole?.let { if (it == "user") "你" else characterName },
                            shape = bubbleShape,
                            maxWidth = bubbleMaxWidth,
                            onLongClick = openMenu,
                            a11yDescription = bubbleSentence,
                        )
                    }
                    StickerTagParser.extractStickerIds(message.content).forEach { id ->
                        StickerImage(stickerId = id, customStickers = customStickers, size = 120.dp)
                    }
                }
                // B1/B4：AI 文本气泡 = 会变身的气泡（占位未显形显三点 · 内容到达点↔字交叉淡入 + 随内容长高 · 仿 iOS
                // AssistantTransitionContent）；占位与真实消息同 composable 子树 → 动画状态延续、原地变身不跳。
                // 用户气泡恒已显形、无变身，走普通 Bubble。
                isUser -> Bubble(
                    isUser = true,
                    text = message.content,
                    quotedContent = message.quotedContent,
                    quotedSender = message.quotedSenderRole?.let { if (it == "user") "你" else characterName },
                    shape = bubbleShape,
                    maxWidth = bubbleMaxWidth,
                    onLongClick = openMenu,
                    a11yDescription = bubbleSentence,
                )
                else -> AssistantTextBubble(
                    revealed = message.isContentRevealed,
                    text = message.content,
                    quotedContent = message.quotedContent,
                    quotedSender = message.quotedSenderRole?.let { if (it == "user") "你" else characterName },
                    shape = bubbleShape,
                    maxWidth = bubbleMaxWidth,
                    onLongClick = openMenu,
                    a11yDescription = bubbleSentence,
                )
            }
            // （M2 沉浸菜单：旧 M3 DropdownMenu 壳整体迁 ChatImmersiveMenuOverlay——动作面/文案/图标/语义色/
            //   显示条件逐字冻结在 immersiveMenuActions()/ImmersiveMenuCard；行内只留 openMenu 上报。）
        }
        } // end SwipeToReplyBox（chat-ui-4）
            // chat-ui-5：气泡内 HH:mm + 用户消息送达/已读回执（1:1 iOS BubbleInlineTimestamp，置于气泡下方）。
            // P1-1：有合并朗读句时整行 a11y 隐藏（时间/回执已并入句中，防双念）；卡片类保留原可读性。
            // Fable-5 时间戳（照 iOS·2026-06-22 用户拍板·选项①）：**每条气泡都内嵌显示自己的 HH:mm**（=iOS
            // BubbleInlineTimestamp 无条件渲染·MessageBubbleContent.swift:50-60），不再「连发段尾才显示+让位」。
            // 时间戳是每条气泡竖列底部的固定一行、从不在气泡间「搬家」→无跳动；打字气泡(TypingRow)是下方独立行、靠
            // topPadding 隔开→不与时间戳重叠。彻底去掉安卓特有的让位逻辑（原 groupLast/timestampInstant 让位=跳动+重叠根源）。
            BubbleInlineTimestamp(
                timestampMs = message.timestamp,
                isUser = isUser,
                read = deliveryRead,
                a11yHidden = bubbleSentence != null,
            )
        }
    }
}
