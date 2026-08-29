package com.situ.aichat.ui.chat

import com.situ.aichat.data.model.FutureMeetingProposalData
import com.situ.aichat.data.model.FutureMeetingProposalJson
import com.situ.aichat.data.model.GiftCardData
import com.situ.aichat.data.model.GiftCardJson
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.model.OfflineInviteJson
import com.situ.aichat.data.model.RedPacketData
import com.situ.aichat.data.model.RedPacketJson
import com.situ.aichat.data.model.SystemEventData
import com.situ.aichat.data.model.SystemEventJson
import com.situ.aichat.offline.OfflineMarkerEndPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MessagePreviewText] 规格锁定：不经聊天气泡渲染的可见面（通知线程 / 快捷回复预览）把消息转人话预览。
 * 断言从「绝不向用户泄漏结构化原文」规格独立反推——结构化卡永不吐 JSON、红包永不露金额、日程卡剥 [#E1]。
 */
class MessagePreviewTextTest {

    private fun preview(kind: MessageKind, content: String) = MessagePreviewText.forKind(kind, content)

    @Test
    fun `红包永远只显示🧧红包·绝不露金额或JSON`() {
        val content = RedPacketJson.encode(
            RedPacketData(type = "red_packet", recordUUID = "r1", amount = 520, blessingText = "我爱你"),
        )
        val out = preview(MessageKind.RED_PACKET, content)
        assertEquals("🧧 红包", out)
        assertFalse("不得露金额", out.contains("520"))
        assertFalse("不得吐 JSON", out.contains("{"))
    }

    @Test
    fun `礼物卡显示礼物名·不吐JSON`() {
        val content = GiftCardJson.encode(
            GiftCardData(type = "gift_card", giftItemId = "g1", giftRecordId = "rec1", cost = 20, giftName = "一支玫瑰", isHandmade = false),
        )
        assertEquals("[礼物]一支玫瑰", preview(MessageKind.GIFT_CARD, content))
    }

    @Test
    fun `礼物卡content损坏时回退占位·不抛不吐JSON`() {
        val out = preview(MessageKind.GIFT_CARD, "{坏的不是JSON")
        assertEquals("[礼物]", out)
        assertFalse(out.contains("{"))
    }

    @Test
    fun `通话记录卡显示📞语音通话`() {
        assertEquals("📞 语音通话", preview(MessageKind.CALL_RECORD_CARD, """{"type":"call_record","transcript":[]}"""))
    }

    @Test
    fun `红包结算系统卡显示事件标题·不吐JSON`() {
        val content = SystemEventJson.encode(
            SystemEventData(type = "system_event", eventType = "red_packet_accepted", title = "你收下了小七的红包", emoji = "🧧", timestamp = "2026-06-19T00:00:00Z"),
        )
        assertEquals("你收下了小七的红包", preview(MessageKind.SYSTEM_EVENT_CARD, content))
    }

    @Test
    fun `线下邀约卡显示☕活动·不吐JSON`() {
        val content = OfflineInviteJson.makeInvite(location = "咖啡馆", activity = "喝咖啡", invitation = "一起？")
        assertEquals("☕ 喝咖啡", preview(MessageKind.OFFLINE_INVITE_CARD, content))
    }

    @Test
    fun `离场标记显示线下见面结束加时长·不露内部标记文本`() {
        val content = OfflineMarkerEndPayload("约30分钟", "14:00", "你们自然地结束了这次见面").makeContent()
        val out = preview(MessageKind.OFFLINE_MARKER_END, content)
        assertEquals("线下见面结束 · 约30分钟", out)
        assertFalse("不得露原始标记括号", out.contains("【"))
    }

    @Test
    fun `日程卡剥掉E1标签`() {
        val out = preview(MessageKind.SCHEDULE_CARD, "[#E1] 周六聚餐 我们去吃火锅吧")
        assertFalse("必须剥掉 [#E1] 标签", out.contains("[#E1]"))
        assertTrue(out.contains("火锅"))
    }

    @Test
    fun `普通文本原样显示`() {
        assertEquals("晚上好呀", preview(MessageKind.PLAIN_TEXT, "晚上好呀"))
    }

    @Test
    fun `文本类剥贴纸与日历标签·堵忙碌通知栏裸标签泄漏`() {
        // 忙碌延迟回复通知正文走本函数（BusyReplyService.registerDeliveryNotifications）：内部 [sticker:UUID] / [#E1]
        // 标签绝不裸进通知栏。
        val plain = preview(MessageKind.PLAIN_TEXT, "想你了 [sticker:abc-123]")
        assertFalse("不得露裸贴纸标签", plain.contains("sticker:"))
        assertTrue("贴纸归一为友好文本", plain.contains("[表情包]"))
        assertTrue("正文保留", plain.contains("想你了"))
        // 日程卡同时含 [#E1] + 贴纸：两种内部标签都不外泄。
        val sched = preview(MessageKind.SCHEDULE_CARD, "[#E1] 周六见面 [sticker:xyz]")
        assertFalse("剥日历标签", sched.contains("#E1"))
        assertFalse("剥裸贴纸标签", sched.contains("sticker:"))
    }

    @Test
    fun `系统耳语与入场标记兜底空串·绝不露内部文本`() {
        // 这两类已被 getRecentVisible SQL 过滤·正常到不了这里；兜底必须是空串而非内部文本。
        assertEquals("", preview(MessageKind.SYSTEM_HINT, "（用户打开了「发起见面」界面…取消了）"))
        assertEquals("", preview(MessageKind.OFFLINE_MARKER_START, "【线下见面开始 | 地点：家】从现在起你们面对面"))
    }

    @Test
    fun `确认卡显示📅时间或活动·不吐JSON`() {
        val content = FutureMeetingProposalJson.encode(
            FutureMeetingProposalData(appointmentUuid = "a1", whenDisplay = "6月27日 周六", activity = "看电影"),
        )
        val out = preview(MessageKind.FUTURE_MEETING_PROPOSAL_CARD, content)
        assertEquals("📅 6月27日 周六", out) // 优先时间，回退活动
        assertFalse("不得吐 JSON", out.contains("{"))
    }

    @Test
    fun `确认卡content损坏回退占位`() {
        assertEquals("📅 未来约定", preview(MessageKind.FUTURE_MEETING_PROPOSAL_CARD, "{坏"))
    }

    // ── 卷一 C3 防御层：线下叙事标签绝不进预览/通知（E13 幂等）──

    @Test
    fun 普通文本预览_剥线下叙事标签() {
        assertEquals("你来啦。今天风挺大的", preview(MessageKind.PLAIN_TEXT, "[对话]你来啦。[/对话][动作]今天风挺大的[/动作]"))
        assertEquals("咖啡馆靠窗的位置", preview(MessageKind.PLAIN_TEXT, "[场景：咖啡馆]咖啡馆靠窗的位置"))
    }

    @Test
    fun 普通文本预览_无线下标签时原样(){
        // E13：剥标签对普通文本零副作用（幂等），既有清洗口径不变。
        assertEquals("晚上一起吃饭吗", preview(MessageKind.PLAIN_TEXT, "晚上一起吃饭吗"))
        assertEquals("[表情包]", preview(MessageKind.PLAIN_TEXT, "[sticker:abc]"))
    }

    @Test
    fun `所有结构化JSON卡的预览都不含裸JSON签名`() {
        val redPacket = RedPacketJson.encode(RedPacketData(type = "red_packet", recordUUID = "r1", amount = 99, blessingText = ""))
        val gift = GiftCardJson.encode(GiftCardData(type = "gift_card", giftItemId = "g", giftRecordId = "r", cost = 5, giftName = "花", isHandmade = false))
        val sysEvent = SystemEventJson.encode(SystemEventData(type = "system_event", eventType = "red_packet_expired", title = "红包过期了", emoji = "🧧", timestamp = "2026-06-19T00:00:00Z"))
        val invite = OfflineInviteJson.makeInvite(location = "公园", activity = "散步", invitation = "")
        val futureMeeting = FutureMeetingProposalJson.encode(
            FutureMeetingProposalData(appointmentUuid = "a", whenDisplay = "6月27日 周六", location = "公园", activity = "散步"),
        )
        val cases = listOf(
            MessageKind.RED_PACKET to redPacket,
            MessageKind.GIFT_CARD to gift,
            MessageKind.SYSTEM_EVENT_CARD to sysEvent,
            MessageKind.OFFLINE_INVITE_CARD to invite,
            MessageKind.FUTURE_MEETING_PROPOSAL_CARD to futureMeeting,
        )
        cases.forEach { (kind, content) ->
            val out = preview(kind, content)
            assertFalse("$kind 不得吐裸 JSON: $out", out.contains("\"type\""))
            assertFalse("$kind 不得以 { 开头: $out", out.trimStart().startsWith("{"))
        }
    }
}
