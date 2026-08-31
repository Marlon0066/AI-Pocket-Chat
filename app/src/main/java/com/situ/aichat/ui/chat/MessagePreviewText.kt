package com.situ.aichat.ui.chat

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.FutureMeetingChangeData
import com.situ.aichat.data.model.FutureMeetingChangeJson
import com.situ.aichat.data.model.FutureMeetingProposalJson
import com.situ.aichat.data.model.GiftCardJson
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.model.OfflineInviteJson
import com.situ.aichat.data.model.SystemEventJson
import com.situ.aichat.offline.OfflineContentParser
import com.situ.aichat.offline.OfflineMarkerEndPayload
import com.situ.aichat.prompt.CalendarItemParser
import com.situ.aichat.prompt.DirtyMessageDetector
import com.situ.aichat.sticker.StickerTagParser

/**
 * 把一条消息映射成「给用户看的一行预览文本」，供**不经聊天气泡渲染**的可见面复用：
 * 通知栏回复线程（[com.situ.aichat.work.NotificationReplyWorker]）+ 列表内联快捷回复预览（[QuickReplySheet]）。
 *
 * 这两个面历史上直接显示 [MessageEntity.content] 原文 → 结构化卡片（礼物/红包/通话/红包结算/邀约）会吐**原始 JSON**、
 * 日程卡会露 `[#E1]` 标签、红包甚至露出刻意隐藏的金额。本函数统一按类型转成人话，口径对齐会话列表「最后一条」预览
 * （礼物=[礼物]名 / 红包=🧧 红包 / 通话=📞 语音通话 …），结构化 content **绝不**直接示人。
 *
 * 纯函数、无副作用。解析失败一律回退到「清洗后的 content」（剥 `[#E1]` 标签 + 贴纸标签）或固定占位串，
 * 不抛异常、不露 JSON。[forKind] 设 internal 供单测从规格独立反推（[com.situ.aichat.ui.chat.MessagePreviewTextTest]）。
 *
 * 注：会话列表「最后一条」预览（第 6 面）是在各插入点写死人话串（recordLastMessage），口径正确、不经本函数；
 * 下列常量与那些插入点常量**同口径**（改文案建议两处同步）。彻底单源化为后续可选项，非本次范围。
 */
object MessagePreviewText {

    /** 便捷入口：从一条消息取预览文本（读 kind + content）。 */
    fun forMessage(message: MessageEntity): String =
        forKind(MessageKind.fromRaw(message.messageKindRaw), message.content)

    internal fun forKind(kind: MessageKind, content: String): String = when (kind) {
        // 结构化卡片：解析取人话，失败回退占位串（绝不吐原始 JSON）。
        MessageKind.GIFT_CARD ->
            GiftCardJson.parse(content)?.let { "[礼物]${it.giftName}" } ?: GIFT_FALLBACK
        MessageKind.RED_PACKET -> RED_PACKET_PREVIEW
        MessageKind.CALL_RECORD_CARD -> CALL_RECORD_PREVIEW
        MessageKind.SYSTEM_EVENT_CARD ->
            SystemEventJson.parse(content)?.title?.takeIf { it.isNotBlank() } ?: SYSTEM_EVENT_FALLBACK
        MessageKind.OFFLINE_INVITE_CARD ->
            OfflineInviteJson.parse(content)?.let { d ->
                (d.activity?.takeIf { it.isNotBlank() } ?: d.location?.takeIf { it.isNotBlank() })?.let { "☕ $it" }
            } ?: OFFLINE_INVITE_FALLBACK
        MessageKind.OFFLINE_END_CARD -> OFFLINE_END_PREVIEW
        // 确认卡：取时间或活动当人话，失败回退占位（绝不吐 JSON）。
        MessageKind.FUTURE_MEETING_PROPOSAL_CARD ->
            FutureMeetingProposalJson.parse(content)?.let { d ->
                (d.whenDisplay?.takeIf { it.isNotBlank() } ?: d.activity?.takeIf { it.isNotBlank() })?.let { "📅 $it" }
            } ?: FUTURE_MEETING_FALLBACK
        // 变更确认卡：按变更类型给人话（绝不吐 JSON）。
        MessageKind.FUTURE_MEETING_CHANGE_CARD ->
            FutureMeetingChangeJson.parse(content)?.let { d ->
                if (d.changeKind == FutureMeetingChangeData.KIND_CANCEL) FUTURE_MEETING_CANCEL_PREVIEW else FUTURE_MEETING_RESCHEDULE_PREVIEW
            } ?: FUTURE_MEETING_FALLBACK
        MessageKind.OFFLINE_MARKER_END ->
            OfflineMarkerEndPayload.parse(content)?.let { "$OFFLINE_MEETING_ENDED · ${it.durationText}" }
                ?: OFFLINE_MEETING_ENDED
        // 系统耳语 / 入场标记：已被 getRecentVisible SQL 过滤（理论到不了这里）；兜底空串，绝不露内部文本。
        MessageKind.SYSTEM_HINT, MessageKind.OFFLINE_MARKER_START -> ""
        // 日程卡 = 带 [#E1] 标签的纯文本 / 普通文本：剥日历标签 + 贴纸标签后当文本（与气泡复制/朗读同口径）。
        // 脏文本（模型复读的段标题/schema）出空串（图纸 2026-09-01 件①）——通知路的 isBlank() 守卫据此不弹，
        // 绝不让一段脏内容从通知栏漏出去。SCHEDULE_CARD 经检测器恒 false，实际只作用于 PLAIN_TEXT。
        MessageKind.SCHEDULE_CARD, MessageKind.PLAIN_TEXT ->
            if (DirtyMessageDetector.isDirty(content, kind)) "" else cleaned(content)
    }

    /**
     * 普通文本预览清洗：剥 `[#E1]`/`[#R1]` 日历标签 + 贴纸标签（与 [ChatScreen] 复制/朗读同口径），
     * 链尾再剥线下叙事标签（卷一 C3 **防御层**）：任何带 `[叙述]/[对话]/[场景：…]` 的文本进通知/快捷回复预览
     * 前先剥干净——正常路径已被上游见面闸拦下，这里只兜脏态与历史遗留；普通文本无此类标签 = 原样返回，零副作用。
     */
    private fun cleaned(content: String): String =
        OfflineContentParser.stripAllTags(
            StickerTagParser.replaceStickerTagsForDisplay(CalendarItemParser.stripCalendarRefs(content)),
        )

    // 与各插入点会话列表预览常量同口径：
    private const val RED_PACKET_PREVIEW = "🧧 红包" //          = ChatViewModel(发红包) / ProactiveGiftExecutor
    private const val CALL_RECORD_PREVIEW = "📞 语音通话" //      = VoiceCallPersistence.CALL_RECORD_PREVIEW
    private const val OFFLINE_END_PREVIEW = "[线下结束]" //       = OfflineMeetingService.PREVIEW_END_CARD
    private const val OFFLINE_MEETING_ENDED = "线下见面结束" //    = ChatScreen OfflineEndDivider 文案
    private const val OFFLINE_INVITE_FALLBACK = "☕ 线下邀约"
    private const val FUTURE_MEETING_FALLBACK = "📅 未来约定"
    private const val FUTURE_MEETING_RESCHEDULE_PREVIEW = "📅 约定改期"
    private const val FUTURE_MEETING_CANCEL_PREVIEW = "📅 取消约定"
    private const val GIFT_FALLBACK = "[礼物]"
    private const val SYSTEM_EVENT_FALLBACK = "🧧 红包消息"
}
