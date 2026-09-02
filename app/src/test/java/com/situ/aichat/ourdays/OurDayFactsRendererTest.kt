package com.situ.aichat.ourdays

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * T1-4（卷一图纸 §7.2）：事实层渲染十四种行**逐字**（总图纸 §4.2 锁定文本在此重新打字），零项整行省略、
 * 发起方三态、时长 0 省略、reactionText 空省略、朋友圈双零省略、状态词四种 + 其它原串。
 */
class OurDayFactsRendererTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private fun ms(h: Int, m: Int) = LocalDateTime.of(2026, 9, 2, h, m).atZone(zone).toInstant().toEpochMilli()
    private fun render(f: OurDayFacts) = OurDayFactsRenderer.render(f, characterName = "林晚", userRefName = "小明", zone = zone)

    @Test
    fun 聊天行_HHmm按时区() {
        val f = OurDayFacts(messageCount = 12, firstMessageAt = ms(9, 5), lastMessageAt = ms(22, 40))
        assertEquals("- 聊天：12 条，09:05–22:40", render(f))
    }

    @Test
    fun 通话行_秒整除成分钟() {
        assertEquals("- 语音通话：2 次，共约 4 分钟", render(OurDayFacts(callCount = 2, callSeconds = 250)))
    }

    @Test
    fun 见面行_发起方三态与时长0省略() {
        assertEquals(
            "- 线下见面：老街咖啡馆，喝咖啡，约 45 分钟，小明约的",
            render(OurDayFacts(meetings = listOf(OurDayMeetingFact("m", "老街咖啡馆", "喝咖啡", 0L, 45, initiatedByUser = true)))),
        )
        assertEquals(
            "- 线下见面：老街咖啡馆，喝咖啡，约 45 分钟，林晚约的",
            render(OurDayFacts(meetings = listOf(OurDayMeetingFact("m", "老街咖啡馆", "喝咖啡", 0L, 45, initiatedByUser = false)))),
        )
        assertEquals(
            "- 线下见面：老街咖啡馆，喝咖啡",
            render(OurDayFacts(meetings = listOf(OurDayMeetingFact("m", "老街咖啡馆", "喝咖啡", 0L, 0, initiatedByUser = null)))),
        )
        assertEquals(
            "- 线下见面：老街咖啡馆，喝咖啡，林晚约的",
            render(OurDayFacts(meetings = listOf(OurDayMeetingFact("m", "老街咖啡馆", "喝咖啡", 0L, 0, initiatedByUser = false)))),
        )
    }

    @Test
    fun 礼物行_用户送带反应_反应空省略_角色送() {
        val f = OurDayFacts(
            gifts = listOf(
                OurDayGiftFact("g1", fromUser = true, giftName = "奶茶", isDIY = false, reactionText = "好甜，谢谢你"),
                OurDayGiftFact("g2", fromUser = true, giftName = "手绘卡片", isDIY = true, reactionText = ""),
                OurDayGiftFact("g3", fromUser = false, giftName = "手作礼物", isDIY = true, reactionText = "不该出现"),
            ),
        )
        assertEquals(
            "- 礼物：小明送了「奶茶」，林晚的反应：好甜，谢谢你\n- 礼物：小明送了「手绘卡片」\n- 礼物：林晚送了「手作礼物」",
            render(f),
        )
    }

    @Test
    fun 红包行_状态词四种加其它原串_方向() {
        val f = OurDayFacts(
            redPackets = listOf(
                OurDayRedPacketFact("r1", true, "pending"),
                OurDayRedPacketFact("r2", false, "accepted"),
                OurDayRedPacketFact("r3", true, "rejected"),
                OurDayRedPacketFact("r4", false, "expired"),
                OurDayRedPacketFact("r5", true, "weird"),
            ),
        )
        assertEquals(
            "- 红包：小明发了一个红包（还没拆）\n- 红包：林晚发了一个红包（收下了）\n- 红包：小明发了一个红包（没收）\n" +
                "- 红包：林晚发了一个红包（过期了）\n- 红包：小明发了一个红包（weird）",
            render(f),
        )
    }

    @Test
    fun 约定行三种() {
        val f = OurDayFacts(
            promises = listOf(
                OurDayPromiseFact("p", "一起看电影", "created"),
                OurDayPromiseFact("p", "一起看电影", "fulfilled"),
                OurDayPromiseFact("q", "早起跑步", "cancelled"),
            ),
        )
        assertEquals("- 约定：定下「一起看电影」\n- 约定：兑现「一起看电影」\n- 约定：取消「早起跑步」", render(f))
    }

    @Test
    fun 里程碑行_reason空省略() {
        assertEquals(
            "- 里程碑：关系变成「恋人」，因为她说了喜欢",
            render(OurDayFacts(milestones = listOf(OurDayMilestoneFact("m", "恋人", reason = "她说了喜欢")))),
        )
        assertEquals("- 里程碑：关系变成「朋友」", render(OurDayFacts(milestones = listOf(OurDayMilestoneFact("m", "朋友")))))
    }

    @Test
    fun 朋友圈行_双零省略_其一为0保留() {
        assertEquals("", render(OurDayFacts(momentPosts = 0, momentInteractions = 0)))
        assertEquals("- 朋友圈：林晚发了 0 条动态，互动 3 次", render(OurDayFacts(momentInteractions = 3)))
        assertEquals("- 朋友圈：林晚发了 2 条动态，互动 0 次", render(OurDayFacts(momentPosts = 2)))
    }

    @Test
    fun 交换日记行_心情空省略() {
        assertEquals(
            "- 交换日记：林晚写了一篇日记（心情 😊）：今天的云像棉花糖",
            render(OurDayFacts(exchangeDiary = OurDayDiaryFact("d", "😊", "今天的云像棉花糖"))),
        )
        assertEquals(
            "- 交换日记：林晚写了一篇日记：今天的云像棉花糖",
            render(OurDayFacts(exchangeDiary = OurDayDiaryFact("d", null, "今天的云像棉花糖"))),
        )
        assertEquals(
            "- 交换日记：林晚写了一篇日记：今天的云像棉花糖",
            render(OurDayFacts(exchangeDiary = OurDayDiaryFact("d", "", "今天的云像棉花糖"))),
        )
    }

    @Test
    fun 日程行_空省略() {
        assertEquals("- 林晚这天的日程：早上 晾被单 → 上午 开店", render(OurDayFacts(scheduleLine = "早上 晾被单 → 上午 开店")))
        assertEquals("", render(OurDayFacts(scheduleLine = "")))
    }

    @Test
    fun 全空返空串() {
        assertEquals("", render(OurDayFacts()))
    }

    @Test
    fun 行序照总图纸4_2_多行以换行拼接() {
        val f = OurDayFacts(
            messageCount = 3, firstMessageAt = ms(8, 0), lastMessageAt = ms(9, 0),
            callCount = 1, callSeconds = 60,
            meetings = listOf(OurDayMeetingFact("m", "公园", "散步", 0L, 30)),
            gifts = listOf(OurDayGiftFact("g", false, "花", false)),
            redPackets = listOf(OurDayRedPacketFact("r", true, "accepted")),
            promises = listOf(OurDayPromiseFact("p", "看海", "created")),
            milestones = listOf(OurDayMilestoneFact("ms", "朋友")),
            momentPosts = 1, momentInteractions = 0,
            exchangeDiary = OurDayDiaryFact("d", null, "首行"),
            scheduleLine = "早上 晾被单",
        )
        val expected = listOf(
            "- 聊天：3 条，08:00–09:00",
            "- 语音通话：1 次，共约 1 分钟",
            "- 线下见面：公园，散步，约 30 分钟",
            "- 礼物：林晚送了「花」",
            "- 红包：小明发了一个红包（收下了）",
            "- 约定：定下「看海」",
            "- 里程碑：关系变成「朋友」",
            "- 朋友圈：林晚发了 1 条动态，互动 0 次",
            "- 交换日记：林晚写了一篇日记：首行",
            "- 林晚这天的日程：早上 晾被单",
        ).joinToString("\n")
        assertEquals(expected, render(f))
        assertFalse(render(f).endsWith("\n"))
    }
}
