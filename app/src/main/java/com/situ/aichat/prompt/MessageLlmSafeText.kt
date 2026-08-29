package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.FutureMeetingChangeJson
import com.situ.aichat.data.model.FutureMeetingProposalJson
import com.situ.aichat.data.model.GiftCardJson
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.model.RedPacketJson
import com.situ.aichat.data.model.SystemEventJson
import com.situ.aichat.data.model.SystemEventType
import com.situ.aichat.data.model.buildRedPacketLLMRepresentation
import com.situ.aichat.gift.FestivalCalendar
import com.situ.aichat.prompt.memory.MemoryService

/**
 * **单一事实源**：把一条消息渲染成「可安全喂给 LLM / 写入长期记忆」的文本——结构化价值卡脱敏，无脱敏表示的卡丢弃。
 * 返回 null = 本条不进喂 LLM 的素材（调用方 `mapNotNull` / `filter` 跳过）。
 *
 * **穷举 when（无 else）**：新增 [MessageKind] 时编译器强制在此给出 LLM 策略，从源头杜绝「某条历史→LLM 旁路忘了打码 →
 * 原始 JSON（含红包 amount / 礼物 cost / 通话逐字稿）漏进提示词」这一类遗漏。脱敏口径对齐
 * [PromptBuilder] 的 llmRepresentation（见 [com.situ.aichat.data.model.GiftCardData.llmRepresentation] /
 * [com.situ.aichat.data.model.RedPacketData.llmRepresentation]：礼物金币 / 红包卡金额永不露给 LLM）。
 *
 * 红包**状态驱动**：红包卡（「信封」）永不带金额（保「拆开前不知道」神秘感）；而**已领取/拒收/过期的系统事件**经
 * [buildRedPacketLLMRepresentation] 带精确金额（resolved 后可暴露·与正常聊天 [PromptBuilder] 完全一致）——
 * 「已拆开的红包在日记/摘要里也带金额」即源于此。
 *
 * **复用方**（所有把历史消息喂 LLM 的旁路，均须经此而非裸 `content`）：日记 [com.situ.aichat.prompt.diary.DiaryGenerationService]、
 * 日程 [com.situ.aichat.prompt.schedule.ScheduleCoordinator]、记忆摘要/分析 [com.situ.aichat.prompt.memory.MemoryService]、
 * 主动通知 [com.situ.aichat.prompt.notification.ProactiveMessageComposer]、故事 [com.situ.aichat.story.StoryChatInfluenceBuilder]。
 * 向量记忆走更严的「[MessageKind.isStructuredCard] 整条不嵌入」（卡片无语义检索价值），不经本函数。
 *
 * 注：本函数只管「结构化卡脱敏」，**不判脏消息**（[DirtyMessageDetector] 由调用方按需先筛）。
 * 系统耳语 [MessageKind.SYSTEM_HINT] 按现状返回 `content`（它本就是喂模型的旁白·与各调用方既有行为一致·不在本次收口范围）。
 */
internal fun messageLlmSafeText(message: MessageEntity, userName: String = "用户", charName: String = "角色"): String? = when (MessageKind.fromRaw(message.messageKindRaw)) {
    // 普通文本：原文进 LLM 素材。**带图则先转图片语义**——图片消息照 iOS 口径不新增 MessageKind
    //（= PLAIN_TEXT + 侧车 imageRelativePath），穷举 when 拦不住它，若这里不处理，日记/日程/主动通知/
    // 故事/见面记忆五条旁路拿到的就是三个字符的 `[图片]` 噪音，而同一条消息在记忆链路里却是有语义的。
    MessageKind.PLAIN_TEXT ->
        if (message.imageRelativePath != null) {
            MemoryService.renderImageSemantics(message.content, message.mediaMemorySummary)
        } else {
            message.content
        }
    // 其余可读文本（非结构化）：原文进 LLM 素材。
    MessageKind.SCHEDULE_CARD, MessageKind.SYSTEM_HINT -> message.content
    // 礼物 → 「[系统记录：…送出礼物 | 名称=… | 分量=…]」，**永不露金币数字 / 原始 JSON**。角色名/用户名经参传（图纸一 R1·默认「角色」/「用户」·formatMessages 命名路传真名）。解析失败 → null（宁缺勿漏原文）。
    MessageKind.GIFT_CARD -> GiftCardJson.parse(message.content)?.llmRepresentation(charName, userName)
    // 红包「信封」卡 → 「[系统记录：发出红包 | 节日=… | 祝福=…]」，**永不露 amount**（没拆的神秘感）。解析失败 → null。
    MessageKind.RED_PACKET -> RedPacketJson.parse(message.content)?.let {
        val festivalName = it.festivalId?.let { id -> FestivalCalendar.festivalById(id)?.name }
        it.llmRepresentation(festivalName)
    }
    // 红包系统事件（领取/拒收/过期）= 状态驱动的「拆开后感知」：第一人称文案 + **已 resolved 故带精确金额**，与
    // 正常聊天同口径。这是「已拆红包在日记/摘要里带金额」的来源。非红包系统事件 / 解析失败 → null（无摘要价值）。
    MessageKind.SYSTEM_EVENT_CARD -> SystemEventJson.parse(message.content)?.let { event ->
        SystemEventType.fromRaw(event.eventType)
            ?.takeIf { it.isRedPacketEvent }
            ?.let { typed -> buildRedPacketLLMRepresentation(event, typed, userName) }
    }
    // 确认卡 → 「[系统记录：向用户提出了未来见面的约定 | 时间=… | 地点=… | 活动=…]」脱敏。解析失败 → null（宁缺勿漏原文）。
    MessageKind.FUTURE_MEETING_PROPOSAL_CARD ->
        FutureMeetingProposalJson.parse(message.content)?.llmRepresentation(userName)
    // 变更确认卡 → 「[系统记录：和用户确认是否{改期/取消}约定…]」脱敏。解析失败 → null。
    MessageKind.FUTURE_MEETING_CHANGE_CARD ->
        FutureMeetingChangeJson.parse(message.content)?.llmRepresentation(userName)
    // 通话记录 / 线下邀约·结束 / 入离场标记：无脱敏表示且无 LLM 文本价值 → 整条跳过。
    MessageKind.CALL_RECORD_CARD, MessageKind.OFFLINE_INVITE_CARD, MessageKind.OFFLINE_END_CARD,
    MessageKind.OFFLINE_MARKER_START, MessageKind.OFFLINE_MARKER_END -> null
}
