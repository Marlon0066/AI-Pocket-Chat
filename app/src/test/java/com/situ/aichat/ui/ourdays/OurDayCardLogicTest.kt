package com.situ.aichat.ui.ourdays

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.local.entity.OurDayCalendarRow
import com.situ.aichat.ourdays.OurDayDiaryFact
import com.situ.aichat.ourdays.OurDayFacts
import com.situ.aichat.ourdays.OurDayFactsJson
import com.situ.aichat.ourdays.OurDayGiftFact
import com.situ.aichat.ourdays.OurDayMeetingFact
import com.situ.aichat.ourdays.OurDayMilestoneFact
import com.situ.aichat.ourdays.OurDayPromiseEvent
import com.situ.aichat.ourdays.OurDayPromiseFact
import com.situ.aichat.ourdays.OurDayRedPacketFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * T1-4（卷三图纸 §7.2·纯 JVM）：六状态判定、chips 固定序与去重 + 通话秒钳 1 分钟、`firstSentence` 四例、
 * 十类 `factItems` 各一 + 零项省略 + 红包无金额、facts null 分支、用户日记过滤、全部分段按识别色序、页脚。
 * 文案字面量在假 [OurDayCardStrings] 里重新打字（不引资源），断言从 §3.5 / §3.7 独立反推。
 */
class OurDayCardLogicTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 9, 15)
    private val yesterday = LocalDate.of(2026, 9, 14)

    private fun row(
        key: String = "2026-09-14", char: String = "c1", note: String = "今天很好。", status: String = "ok",
        mc: Int = 0, cs: Int = 0, meeting: Boolean = false, hidden: Boolean = false, deleted: Boolean = false, facts: String = "",
    ) = OurDayCalendarRow(
        uuid = "$char-$key", characterUuid = char, dayKey = key, factsJson = facts, messageCount = mc, callSeconds = cs,
        hasMeeting = meeting, hasRelation = false, hasLife = false, note = note, factLine = "", noteStatus = status, noteAttempts = 0,
        noteEdited = false, hiddenFromMemory = hidden, deleted = deleted, generatedAt = 123L, createdAtMillis = 1, updatedAtMillis = 1,
    )

    private fun at(h: Int, m: Int) = yesterday.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private val strings = object : OurDayCardStrings {
        private val fmt = DateTimeFormatter.ofPattern("HH:mm")
        override fun time(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).format(fmt)
        override fun chat(count: Int) = "聊了 $count 条"
        override fun timeRange(from: String, to: String) = "$from – $to"
        override fun call(minutes: Int) = "语音通话 $minutes 分钟"
        override fun callCount(count: Int) = "$count 次"
        override fun meeting(place: String) = "线下见面 · $place"
        override fun duration(minutes: Int) = "约 $minutes 分钟"
        override fun initiatedUser() = "你约的"
        override fun initiatedChar(name: String) = "${name}约的"
        override fun giftUser(gift: String) = "你送了「$gift」"
        override fun giftChar(name: String, gift: String) = "${name}送了「$gift」"
        override fun reaction(name: String, text: String) = "$name：「$text」"
        override fun affinity(gain: Int) = "亲密 +$gain"
        override fun redPacketUser() = "你发了红包"
        override fun redPacketChar(name: String) = "${name}发了红包"
        override fun redPacketStatus(status: String) = when (status) { "pending" -> "还没拆"; "accepted" -> "收下了"; "rejected" -> "没收"; "expired" -> "过期了"; else -> status }
        override fun promiseCreated(content: String) = "定下约定「$content」"
        override fun promiseFulfilled(content: String) = "约定兑现「$content」"
        override fun promiseCancelled(content: String) = "约定取消「$content」"
        override fun milestone(text: String) = "里程碑 · $text"
        override fun quote(text: String) = "「$text」"
        override fun moments() = "朋友圈"
        override fun momentsDetail(name: String, posts: Int, interactions: Int) = "${name}发了 $posts 条，互动 $interactions 次"
        override fun momentsInteract(name: String, interactions: Int) = "你和${name}互动了 $interactions 次"
        override fun exchangeDiary(name: String) = "${name}的交换日记"
        override fun schedule(name: String) = "${name}的一天"
    }

    // ── 状态 ──

    @Test fun 六状态判定序_今天优先_无行EMPTY_墓碑_手记空白_hidden_NORMAL() {
        assertEquals(CardStatus.TODAY, OurDayCardLogic.status(null, isToday = true))
        assertEquals(CardStatus.TODAY, OurDayCardLogic.status(row(), isToday = true))
        assertEquals(CardStatus.EMPTY, OurDayCardLogic.status(null, isToday = false))
        assertEquals(CardStatus.DELETED, OurDayCardLogic.status(row(note = "", status = "none", deleted = true, hidden = true), false))
        assertEquals(CardStatus.FAILED, OurDayCardLogic.status(row(note = "", status = "failed"), false))
        assertEquals(CardStatus.FAILED, OurDayCardLogic.status(row(note = "  ", status = "none"), false))
        assertEquals(CardStatus.HIDDEN_NORMAL, OurDayCardLogic.status(row(hidden = true), false))
        assertEquals(CardStatus.NORMAL, OurDayCardLogic.status(row(), false))
    }

    // ── chips ──

    @Test fun chips固定序_零项省略_同类去重_约定内容截12字() {
        val f = OurDayFacts(
            promises = listOf(
                OurDayPromiseFact("p1", "周末一起去海边看日落然后吃海鲜", OurDayPromiseEvent.CREATED),
                OurDayPromiseFact("p2", "x", OurDayPromiseEvent.FULFILLED),
                OurDayPromiseFact("p3", "y", OurDayPromiseEvent.FULFILLED),
                OurDayPromiseFact("p4", "z", OurDayPromiseEvent.CANCELLED),
            ),
            milestones = listOf(OurDayMilestoneFact("m", "朋友")),
            gifts = listOf(OurDayGiftFact("g", true, "花", false)),
            redPackets = listOf(OurDayRedPacketFact("r", false, "pending")),
            momentInteractions = 2,
            exchangeDiary = OurDayDiaryFact("d"),
        )
        val chips = OurDayCardLogic.chips(row(mc = 3, cs = 45, meeting = true), f)
        assertEquals(
            listOf(ChipKind.CHAT, ChipKind.CALL, ChipKind.MEETING, ChipKind.PROMISE, ChipKind.PROMISE_FULFILLED, ChipKind.PROMISE_CANCELLED, ChipKind.MILESTONE, ChipKind.GIFT, ChipKind.RED_PACKET, ChipKind.MOMENTS, ChipKind.DIARY),
            chips.map { it.kind },
        )
        assertEquals(3, chips[0].count)
        assertEquals("通话不足一分钟钳 1", 1, chips[1].count)
        assertEquals("周末一起去海边看日落然后", chips[3].text)
    }

    @Test fun chips_通话分钟整除_零项全省() {
        val chips = OurDayCardLogic.chips(row(cs = 130), OurDayFacts())
        assertEquals(listOf(ChipKind.CALL), chips.map { it.kind }); assertEquals(2, chips[0].count)
        assertTrue(OurDayCardLogic.chips(row(), OurDayFacts()).isEmpty())
    }

    @Test fun 通话分钟钳位_1到59秒为1_60起整除() {
        assertEquals(listOf(1, 1, 1, 1, 2, 2, 3), listOf(1, 30, 59, 60, 120, 179, 180).map { OurDayCardLogic.callMinutes(it) })
    }

    @Test fun chips_facts为null只出聊天通话见面() {
        val chips = OurDayCardLogic.chips(row(mc = 1, cs = 61, meeting = true), null)
        assertEquals(listOf(ChipKind.CHAT, ChipKind.CALL, ChipKind.MEETING), chips.map { it.kind })
    }

    // ── 首句 ──

    @Test fun 首句四例_句号含_换行不含_超40码点截_首尾空白去() {
        assertEquals("今天很好。", OurDayCardLogic.firstSentence("今天很好。后面还有话"))
        assertEquals("第一行", OurDayCardLogic.firstSentence("第一行\n第二行"))
        assertEquals("你好！", OurDayCardLogic.firstSentence("  你好！  再见"))
        val long = "一".repeat(41)
        assertEquals("一".repeat(40) + "…", OurDayCardLogic.firstSentence(long))
        assertEquals("一".repeat(40), OurDayCardLogic.firstSentence("一".repeat(40)))
        assertEquals("Really?", OurDayCardLogic.firstSentence("Really? Yes."))
    }

    // ── factItems ──

    @Test fun 十类事实各一_固定序_拼接格式() {
        val f = OurDayFacts(
            messageCount = 12, firstMessageAt = at(9, 5), lastMessageAt = at(21, 30),
            callCount = 2, callSeconds = 150,
            meetings = listOf(OurDayMeetingFact("m", "江边", "散步", at(19, 0), 45, initiatedByUser = false)),
            gifts = listOf(OurDayGiftFact("g", true, "向日葵", false, reactionText = "好喜欢", affinityGain = 3)),
            redPackets = listOf(OurDayRedPacketFact("r", false, "accepted")),
            promises = listOf(OurDayPromiseFact("p", "去看海", OurDayPromiseEvent.CREATED)),
            milestones = listOf(OurDayMilestoneFact("s", "恋人", phase = "热恋期", reason = "表白成功")),
            momentPosts = 1, momentInteractions = 3,
            exchangeDiary = OurDayDiaryFact("d1", "🌙", "今晚月色真美"),
            scheduleLine = "清晨 起床 → 上午 上班",
        )
        val items = OurDayFactItems.build(f, "林晚", "2026-09-14", strings)
        assertEquals(FactKind.entries, items.map { it.kind })
        assertEquals(FactItem(FactKind.CHAT, "聊了 12 条", "09:05 – 21:30", null), items[0])
        assertEquals(FactItem(FactKind.CALL, "语音通话 2 分钟", "2 次", null), items[1])
        assertEquals(FactItem(FactKind.MEETING, "线下见面 · 江边", "19:00 · 约 45 分钟 · 散步 · 林晚约的", FactLink.MEETINGS), items[2])
        assertEquals(FactItem(FactKind.GIFT, "你送了「向日葵」", "林晚：「好喜欢」 · 亲密 +3", null), items[3])
        assertEquals(FactItem(FactKind.RED_PACKET, "林晚发了红包", "收下了", null), items[4])
        assertEquals(FactItem(FactKind.PROMISE, "定下约定「去看海」", "", FactLink.PROMISES), items[5])
        assertEquals(FactItem(FactKind.MILESTONE, "里程碑 · 恋人 · 热恋期", "「表白成功」", null), items[6])
        assertEquals(FactItem(FactKind.MOMENTS, "朋友圈", "林晚发了 1 条，互动 3 次", FactLink.MOMENTS), items[7])
        assertEquals(FactItem(FactKind.EXCHANGE_DIARY, "林晚的交换日记", "🌙 今晚月色真美", FactLink.DIARY("d1")), items[8])
        assertEquals(FactItem(FactKind.SCHEDULE, "林晚的一天", "清晨 起床 → 上午 上班", FactLink.SCHEDULE("2026-09-14")), items[9])
    }

    @Test fun 事实分支_同分钟单时刻_通话一次无次数_地点空用活动_你约的_角色送礼无反应_只互动() {
        val f = OurDayFacts(
            messageCount = 1, firstMessageAt = at(9, 5), lastMessageAt = at(9, 5), callCount = 1, callSeconds = 30,
            meetings = listOf(OurDayMeetingFact("m", "", "散步", at(19, 0), 0, initiatedByUser = true)),
            gifts = listOf(OurDayGiftFact("g", false, "手作", true, reactionText = "谢谢", affinityGain = 0)),
            promises = listOf(OurDayPromiseFact("p", "看海", OurDayPromiseEvent.FULFILLED), OurDayPromiseFact("q", "跑步", OurDayPromiseEvent.CANCELLED)),
            milestones = listOf(OurDayMilestoneFact("s", "朋友")),
            momentInteractions = 2,
        )
        val items = OurDayFactItems.build(f, "林晚", "2026-09-14", strings)
        assertEquals("09:05", items[0].detail)
        assertEquals(FactItem(FactKind.CALL, "语音通话 1 分钟", "", null), items[1])
        assertEquals(FactItem(FactKind.MEETING, "线下见面 · 散步", "19:00 · 你约的", FactLink.MEETINGS), items[2])
        assertEquals(FactItem(FactKind.GIFT, "林晚送了「手作」", "", null), items[3])
        assertEquals(listOf("约定兑现「看海」", "约定取消「跑步」"), items.filter { it.kind == FactKind.PROMISE }.map { it.title })
        assertEquals(FactItem(FactKind.MILESTONE, "里程碑 · 朋友", "", null), items.first { it.kind == FactKind.MILESTONE })
        assertEquals("你和林晚互动了 2 次", items.first { it.kind == FactKind.MOMENTS }.detail)
        assertFalse(items.any { it.kind == FactKind.RED_PACKET || it.kind == FactKind.EXCHANGE_DIARY || it.kind == FactKind.SCHEDULE })
    }

    @Test fun 零项全省_空facts为空列表() {
        assertTrue(OurDayFactItems.build(OurDayFacts(), "林晚", "2026-09-14", strings).isEmpty())
    }

    @Test fun 红包永不含金额() {
        val f = OurDayFacts(redPackets = listOf(OurDayRedPacketFact("r1", true, "pending"), OurDayRedPacketFact("r2", false, "expired")))
        val items = OurDayFactItems.build(f, "林晚", "2026-09-14", strings)
        assertEquals(2, items.size)
        items.forEach { item ->
            assertFalse(item.title + item.detail, (item.title + item.detail).any { it.isDigit() || it == '¥' || it == '￥' })
        }
        assertEquals(listOf("你发了红包" to "还没拆", "林晚发了红包" to "过期了"), items.map { it.title to it.detail })
    }

    // ── 日卡 / 全部 / 日记 ──

    @Test fun 单角色日卡_坏facts时chips仍出聊天_scheduleLine为空() {
        val c = OurDayCardLogic.card(yesterday, today, row(mc = 2, facts = "{bad"), DayDecor("廿三", false, null))
        assertEquals(CardStatus.NORMAL, c.status); assertEquals(listOf(ChipKind.CHAT), c.chips.map { it.kind }); assertEquals("", c.scheduleLine)
        assertEquals("今天很好。", c.note); assertEquals(123L, c.generatedAt); assertFalse(c.isToday); assertFalse(c.isFuture)
        val empty = OurDayCardLogic.card(yesterday, today, null, null)
        assertEquals(CardStatus.EMPTY, empty.status); assertNull(empty.generatedAt)
        val future = OurDayCardLogic.card(LocalDate.of(2026, 9, 16), today, null, null)
        assertTrue(future.isFuture)
        val sched = OurDayCardLogic.card(yesterday, today, row(facts = OurDayFactsJson.encode(OurDayFacts(scheduleLine = "清晨 起床"))), null)
        assertEquals("清晨 起床", sched.scheduleLine)
    }

    @Test fun 用户日记过滤_排除草稿宠物角色作者_取最早() {
        val entries = listOf(
            DiaryEntryEntity("draft", content = "草", timestamp = 1, isDraft = true),
            DiaryEntryEntity("pet", content = "宠", timestamp = 2, isPetDiary = true),
            DiaryEntryEntity("char", content = "角", timestamp = 3, authorCharacterUuid = "c1"),
            DiaryEntryEntity("later", content = "晚", timestamp = 9),
            DiaryEntryEntity("early", content = "早\n第二行", timestamp = 5, moodEmoji = "🌧"),
        )
        val picked = OurDayCardLogic.pickUserDiary(entries)!!
        assertEquals("early", picked.uuid)
        assertEquals(UserDiaryLine("🌧", "早 第二行"), OurDayCardLogic.userDiaryLine(picked))
        assertNull(OurDayCardLogic.pickUserDiary(entries.take(3)))
        assertEquals("一".repeat(30), OurDayCardLogic.userDiaryLine(DiaryEntryEntity("x", content = "一".repeat(31))).firstLine)
    }

    @Test fun 全部模式分段_按角色升序识别色序_未知角色行跳过_日记行_空则EMPTY() {
        val chars = (1..8).map { CharacterEntity(uuid = "c$it", name = "角$it", creationDate = it.toLong()) }
        val rows = listOf(row(char = "c8", note = "八"), row(char = "c2", note = "二"), row(char = "ghost"))
        val diary = DiaryEntryEntity("d", content = "我的日记", timestamp = 1)
        val card = OurDayCardLogic.allCard(yesterday, today, rows, chars, null, diary)
        assertEquals(listOf("c2", "c8"), card.segments.map { it.characterUuid })
        assertEquals(listOf(1, 1), card.segments.map { it.identityIndex }) // c2 → 1；c8 → 7 % 6 = 1
        assertEquals("二", card.segments[0].card.note); assertEquals("角8", card.segments[1].name)
        assertEquals(UserDiaryLine(null, "我的日记"), card.userDiary)
        assertEquals(CardStatus.NORMAL, card.status); assertTrue(card.chips.isEmpty())
        assertEquals(CardStatus.EMPTY, OurDayCardLogic.allCard(yesterday, today, emptyList(), chars, null, null).status)
        assertEquals(CardStatus.TODAY, OurDayCardLogic.allCard(today, today, emptyList(), chars, null, null).status)
    }

    @Test fun 页脚_今天未来无行墓碑NONE_hidden_HIDDEN_否则REMEMBERS() {
        assertEquals(FooterKind.NONE, OurDayCardLogic.footer(row(), isToday = true, isFuture = false))
        assertEquals(FooterKind.NONE, OurDayCardLogic.footer(null, false, isFuture = true))
        assertEquals(FooterKind.NONE, OurDayCardLogic.footer(null, false, false))
        assertEquals(FooterKind.NONE, OurDayCardLogic.footer(row(deleted = true, hidden = true), false, false))
        assertEquals(FooterKind.HIDDEN, OurDayCardLogic.footer(row(hidden = true), false, false))
        assertEquals(FooterKind.REMEMBERS, OurDayCardLogic.footer(row(), false, false))
    }
}
