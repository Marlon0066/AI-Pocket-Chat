package com.situ.aichat.ourdays

import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.local.entity.RedPacketRecordEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.model.CallRecordData
import com.situ.aichat.data.model.CallRecordJson
import com.situ.aichat.gift.GiftCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * T1-3（卷一图纸 §7.2）：事实层计数口径纯函数。断言从总图纸 §3.3（锁定口径）与 §5 E18–E20 E26 独立反推。
 */
class OurDayFactsBuilderTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val day = "2026-09-02"
    private fun ms(d: Int, h: Int, m: Int = 0, s: Int = 0) = LocalDateTime.of(2026, 9, d, h, m, s).atZone(zone).toInstant().toEpochMilli()

    private fun msg(
        id: String, at: Long, content: String = "你好", role: String = "user", held: Boolean = false,
        voiceCall: Boolean = false, kind: String = "plain_text",
    ) = MessageEntity(
        messageUUID = id, conversationUuid = "conv", roleRaw = role, content = content, timestamp = at,
        isHeldForDelivery = held, isPartOfVoiceCall = voiceCall, messageKindRaw = kind,
    )

    private fun build(
        sources: OurDaySources = OurDaySources.EMPTY,
        messages: List<MessageEntity> = emptyList(),
        events: List<ScheduleEventEntity> = emptyList(),
    ) = OurDayFactsBuilder.build(sources, messages, events, day, zone)

    @Test
    fun 消息计数排除system空暂扣通话行结构化卡_首末时刻取合格消息() {
        val facts = build(
            messages = listOf(
                msg("a", ms(2, 9, 5)),
                msg("sys", ms(2, 9, 6), role = "system"),
                msg("empty", ms(2, 9, 7), content = ""),
                msg("held", ms(2, 9, 8), held = true),
                msg("call-line", ms(2, 9, 9), voiceCall = true),
                msg("gift-card", ms(2, 9, 10), kind = "gift_card"),
                msg("red-packet", ms(2, 9, 11), kind = "red_packet"),
                msg("sched", ms(2, 9, 12), content = "[#E1] 开会", kind = "schedule_card"), // 文本类·计
                msg("b", ms(2, 22, 40), role = "assistant"),
            ),
        )
        assertEquals(3, facts.messageCount)
        assertEquals(ms(2, 9, 5), facts.firstMessageAt)
        assertEquals(ms(2, 22, 40), facts.lastMessageAt)
    }

    @Test
    fun 窗外消息不计_E1() {
        val facts = build(messages = listOf(msg("prev", ms(1, 23, 58)), msg("in", ms(2, 0, 3)), msg("next", ms(3, 0, 0))))
        assertEquals(1, facts.messageCount)
        assertEquals(ms(2, 0, 3), facts.firstMessageAt)
    }

    @Test
    fun 通话计数与秒数_坏JSON计1次0秒_E19() {
        val good = CallRecordJson.encode(CallRecordData("call_record", 125, "2026-09-02T10:00:00Z", emptyList()))
        val facts = build(
            messages = listOf(
                msg("c1", ms(2, 10), content = good, role = "assistant", kind = "call_record_card"),
                msg("c2", ms(2, 11), content = "{bad json", role = "assistant", kind = "call_record_card"),
                msg("t", ms(2, 12)),
            ),
        )
        assertEquals(2, facts.callCount)
        assertEquals(125, facts.callSeconds)
        assertEquals("通话卡不算文字消息", 1, facts.messageCount)
    }

    @Test
    fun 礼物方向与名字_DIY标题_DIY空回落手作礼物_目录名_未知id回落id_反应截60() {
        val catalogId = "gift_boba_tea"
        val catalogName = GiftCatalog.find(catalogId)?.name
        val longReaction = "谢".repeat(70)
        val facts = build(
            sources = OurDaySources.EMPTY.copy(
                gifts = listOf(
                    GiftRecordEntity(uuid = "g1", timestamp = ms(2, 9), senderType = "user", isDIY = true, diyTitle = "手绘卡片", reactionText = longReaction, affinityGain = 5),
                    GiftRecordEntity(uuid = "g2", timestamp = ms(2, 10), senderType = "user", isDIY = true, diyTitle = "  "),
                    GiftRecordEntity(uuid = "g3", timestamp = ms(2, 11), senderType = "character", giftItemId = catalogId),
                    GiftRecordEntity(uuid = "g4", timestamp = ms(2, 12), senderType = "character", giftItemId = "gift_unknown_xyz"),
                    GiftRecordEntity(uuid = "out", timestamp = ms(3, 12), senderType = "user"),
                ),
            ),
        )
        assertEquals(listOf("g1", "g2", "g3", "g4"), facts.gifts.map { it.uuid })
        val g1 = facts.gifts[0]
        assertTrue(g1.fromUser); assertTrue(g1.isDIY); assertEquals("手绘卡片", g1.giftName)
        assertEquals(60, g1.reactionText.codePointCount(0, g1.reactionText.length)); assertEquals(5, g1.affinityGain)
        assertEquals("手作礼物", facts.gifts[1].giftName)
        assertFalse(facts.gifts[2].fromUser)
        if (catalogName != null) { assertEquals(catalogName, facts.gifts[2].giftName); assertNotEquals(catalogId, facts.gifts[2].giftName) }
        assertEquals("gift_unknown_xyz", facts.gifts[3].giftName)
    }

    @Test
    fun 红包只带方向与状态_JSON不含amount_E20() {
        val facts = build(
            sources = OurDaySources.EMPTY.copy(
                redPackets = listOf(
                    RedPacketRecordEntity(uuid = "r1", createdAt = ms(2, 9), senderType = "user", amount = 888, status = "accepted"),
                    RedPacketRecordEntity(uuid = "r2", createdAt = ms(2, 10), senderType = "character", amount = 66, status = "pending"),
                    RedPacketRecordEntity(uuid = "r3", createdAt = ms(1, 10), senderType = "user", amount = 1),
                ),
            ),
        )
        assertEquals(listOf("r1", "r2"), facts.redPackets.map { it.uuid })
        assertTrue(facts.redPackets[0].fromUser); assertEquals("accepted", facts.redPackets[0].status)
        assertFalse(facts.redPackets[1].fromUser); assertEquals("pending", facts.redPackets[1].status)
        val json = OurDayFactsJson.encode(facts)
        assertFalse("facts JSON 绝不含 amount", json.contains("amount"))
        assertFalse(json.contains("888"))
    }

    @Test
    fun 约定同日定下又兑现出两条事件_按时间序_E26() {
        val facts = build(
            sources = OurDaySources.EMPTY.copy(
                promises = listOf(
                    PromiseEntity(uuid = "p1", characterUuid = "c1", content = "一起看电影", statusRaw = "fulfilled", createdAtMillis = ms(2, 9), updatedAtMillis = 0, resolvedAtMillis = ms(2, 20)),
                    PromiseEntity(uuid = "p2", characterUuid = "c1", content = "早起跑步", statusRaw = "cancelled", createdAtMillis = ms(1, 9), updatedAtMillis = 0, resolvedAtMillis = ms(2, 8)),
                    PromiseEntity(uuid = "p3", characterUuid = "c1", content = "下周见", statusRaw = "open", createdAtMillis = ms(2, 12), updatedAtMillis = 0),
                    PromiseEntity(uuid = "p4", characterUuid = "c1", content = "别的日子", statusRaw = "fulfilled", createdAtMillis = ms(1, 9), updatedAtMillis = 0, resolvedAtMillis = ms(3, 8)),
                ),
            ),
        )
        assertEquals(
            listOf("p2:cancelled", "p1:created", "p3:created", "p1:fulfilled"),
            facts.promises.map { "${it.uuid}:${it.event}" },
        )
        assertEquals("一起看电影", facts.promises[1].content)
    }

    @Test
    fun 里程碑按establishedDate入窗() {
        val facts = build(
            sources = OurDaySources.EMPTY.copy(
                milestones = listOf(
                    MilestoneEntity(uuid = "m1", characterUuid = "c1", relationshipName = "恋人", establishedDate = ms(2, 21), reason = "她说了喜欢", phase = "sweet"),
                    MilestoneEntity(uuid = "m0", characterUuid = "c1", relationshipName = "朋友", establishedDate = ms(1, 21)),
                ),
            ),
        )
        assertEquals(1, facts.milestones.size)
        assertEquals("恋人", facts.milestones[0].relationshipName)
        assertEquals("她说了喜欢", facts.milestones[0].reason)
        assertEquals("sweet", facts.milestones[0].phase)
    }

    @Test
    fun 朋友圈发帖与互动按窗计数() {
        val facts = build(
            sources = OurDaySources.EMPTY.copy(
                momentPostTimestamps = listOf(ms(2, 8), ms(2, 18), ms(3, 8)),
                momentInteractionTimestamps = listOf(ms(2, 9), ms(2, 10), ms(2, 11), ms(1, 23, 59)),
            ),
        )
        assertEquals(2, facts.momentPosts)
        assertEquals(3, facts.momentInteractions)
    }

    @Test
    fun 交换日记取当日最早一篇_首行截40字_草稿排除() {
        val longFirst = "今".repeat(50) + "\n第二行不该出现"
        val facts = build(
            sources = OurDaySources.EMPTY.copy(
                exchangeDiaries = listOf(
                    DiaryEntryEntity(uuid = "late", timestamp = ms(2, 21), authorCharacterUuid = "c1", triggerTypeRaw = "exchange", content = "晚一点的"),
                    DiaryEntryEntity(uuid = "draft", timestamp = ms(2, 7), authorCharacterUuid = "c1", triggerTypeRaw = "exchange", isDraft = true, content = "草稿"),
                    DiaryEntryEntity(uuid = "early", timestamp = ms(2, 9), authorCharacterUuid = "c1", triggerTypeRaw = "exchange", content = longFirst, moodEmoji = "😊"),
                ),
            ),
        )
        val d = facts.exchangeDiary!!
        assertEquals("early", d.uuid)
        assertEquals("😊", d.moodEmoji)
        assertEquals("今".repeat(40), d.firstLine)
    }

    @Test
    fun 日程行过滤userInteraction并按startTime排_经schedulePastLine() {
        val events = listOf(
            ScheduleEventEntity("e2", "s1", ms(2, 9), ms(2, 11, 30), "上午", "咖啡店", "开店"),
            ScheduleEventEntity("e1", "s1", ms(2, 8), ms(2, 9), "早上", "家里阳台", "晾被单", moodText = "惬意"),
            ScheduleEventEntity("ui", "s1", ms(2, 10), ms(2, 10, 30), "上午", "", "和用户聊天", eventTypeRaw = "userInteraction"),
        )
        val facts = build(events = events)
        assertEquals("早上 晾被单 → 上午 开店", facts.scheduleLine)
        assertFalse("只有日程 ⇒ 无互动", facts.hasActivity)
    }

    @Test
    fun hasActivity边界_只有日程为假_任一互动为真() {
        assertFalse(OurDayFacts(scheduleLine = "早上 晾被单").hasActivity)
        assertFalse(OurDayFacts().hasActivity)
        assertTrue(OurDayFacts(messageCount = 1).hasActivity)
        assertTrue(OurDayFacts(callCount = 1).hasActivity)
        assertTrue(OurDayFacts(momentInteractions = 1).hasActivity)
        assertTrue(OurDayFacts(exchangeDiary = OurDayDiaryFact("d")).hasActivity)
        assertTrue(OurDayFacts(redPackets = listOf(OurDayRedPacketFact("r", true, "pending"))).hasActivity)
    }

    @Test
    fun 反规范化列口径() {
        val f = OurDayFacts(
            meetings = listOf(OurDayMeetingFact("m", "a", "b", 0L, 0)),
            promises = listOf(OurDayPromiseFact("p", "c", "created")),
        )
        assertTrue(f.hasMeeting); assertTrue(f.hasRelation); assertFalse(f.hasLife)
        assertTrue(OurDayFacts(gifts = listOf(OurDayGiftFact("g", true, "x", false))).hasLife)
        assertTrue(OurDayFacts(milestones = listOf(OurDayMilestoneFact("m", "朋友"))).hasRelation)
        assertTrue(OurDayFacts(momentPosts = 1).hasLife)
    }

    @Test
    fun heatScore三档边界与通话折算() {
        assertEquals(0, OurDayFacts().heatScore)
        assertEquals(9, OurDayFacts(messageCount = 9).heatScore)
        assertEquals(10, OurDayFacts(messageCount = 10).heatScore)
        assertEquals(39, OurDayFacts(messageCount = 39).heatScore)
        assertEquals(40, OurDayFacts(messageCount = 40).heatScore)
        assertEquals("59 秒不足一分钟 = 0", 0, OurDayFacts(callSeconds = 59).heatScore)
        assertEquals("每分钟折 3", 3, OurDayFacts(callSeconds = 60).heatScore)
        assertEquals("整除·130 秒 = 2 分钟 = 6", 6, OurDayFacts(callSeconds = 130).heatScore)
        assertEquals(4 + 6, OurDayFacts(messageCount = 4, callSeconds = 130).heatScore)
    }

    @Test
    fun 见面时长_ended为0得0_不足一分钟至少1_legacy排除() {
        val facts = build(
            sources = OurDaySources.EMPTY.copy(
                meetings = listOf(
                    OfflineMeetingMemoryEntity(uuid = "m1", characterUuid = "c1", startedAtMillis = ms(2, 19), endedAtMillis = 0L, location = "老街", activity = "散步", initiatedByUser = true, moodRaw = "warm", createdAtMillis = 1, updatedAtMillis = 1),
                    OfflineMeetingMemoryEntity(uuid = "m2", characterUuid = "c1", startedAtMillis = ms(2, 20), endedAtMillis = ms(2, 20, 0, 30), createdAtMillis = 1, updatedAtMillis = 1),
                    OfflineMeetingMemoryEntity(uuid = "m3", characterUuid = "c1", startedAtMillis = ms(2, 21), endedAtMillis = ms(2, 22, 30), createdAtMillis = 1, updatedAtMillis = 1),
                    OfflineMeetingMemoryEntity(uuid = "legacy", characterUuid = "c1", kindRaw = "legacy", startedAtMillis = ms(2, 12), createdAtMillis = 1, updatedAtMillis = 1),
                    OfflineMeetingMemoryEntity(uuid = "other-day", characterUuid = "c1", startedAtMillis = ms(3, 12), createdAtMillis = 1, updatedAtMillis = 1),
                ),
            ),
        )
        assertEquals(listOf("m1", "m2", "m3"), facts.meetings.map { it.uuid })
        assertEquals(0, facts.meetings[0].durationMinutes)
        assertEquals("老街", facts.meetings[0].location); assertEquals(true, facts.meetings[0].initiatedByUser); assertEquals("warm", facts.meetings[0].moodRaw)
        assertEquals(1, facts.meetings[1].durationMinutes)
        assertEquals(90, facts.meetings[2].durationMinutes)
        assertNull(facts.meetings[1].initiatedByUser)
        assertTrue(facts.hasMeeting)
    }

    @Test
    fun JSON往返_零值不落键_坏串解null() {
        val facts = build(messages = listOf(msg("a", ms(2, 9))))
        val json = OurDayFactsJson.encode(facts)
        assertEquals(facts, OurDayFactsJson.decodeOrNull(json))
        assertFalse("默认值不写键", json.contains("callCount"))
        assertNull(OurDayFactsJson.decodeOrNull(""))
        assertNull(OurDayFactsJson.decodeOrNull("{oops"))
    }
}
