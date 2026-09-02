package com.situ.aichat.prompt.ourdays

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId

/**
 * T1-1（卷二图纸 §7.2）：日期指名十四条词表。断言从 §3.5 规格独立反推（today = 2026-09-02 周三），
 * 非照抄实现。走 Robolectric 只因规则 13 节日回溯经 [com.situ.aichat.gift.FestivalCalendar] 吃 `android.icu`
 * 农历（其余规则纯 JVM）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OurDayDateMentionTest {

    private val today: LocalDate = LocalDate.of(2026, 9, 2) // 周三
    private val zone: ZoneId = ZoneId.systemDefault()
    private val none = OurDayDateMention.Anchors()

    private fun d(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d)
    private fun days(text: String, anchors: OurDayDateMention.Anchors = none, on: LocalDate = today) =
        OurDayDateMention.resolve(text, on, anchors, zone).days
    private fun range(text: String, on: LocalDate = today) = OurDayDateMention.resolve(text, on, none, zone).range

    // ── 1 昨天 / 前天 / 大前天 ──

    @Test fun 规则1_昨天前天大前天() {
        assertEquals(listOf(d(2026, 9, 1)), days("昨天聊的那个"))
        assertEquals(listOf(d(2026, 8, 31)), days("前天呢"))
        assertEquals(listOf(d(2026, 8, 30)), days("大前天"))
    }

    // ── 2 n 天前 ──

    @Test fun 规则2_阿拉伯与中文数字天前() {
        assertEquals(listOf(d(2026, 8, 30)), days("三天前"))
        assertEquals(listOf(d(2026, 8, 23)), days("10天前那事"))
        assertEquals(listOf(d(2026, 8, 12)), days("二十一天前"))
        assertEquals(listOf(d(2026, 8, 23)), days("十天前"))
    }

    @Test fun 规则2_零天前与表外中文数字不触发() {
        assertTrue(days("0天前").isEmpty())
        assertTrue("四十 不在 §3.5b 表内 ⇒ 不触发", days("四十天前").isEmpty())
    }

    // ── 3 周几（周一为周首） ──

    @Test fun 规则3_上周三与上周日() {
        assertEquals(listOf(d(2026, 8, 26)), days("上周三我们聊了什么"))
        assertEquals(listOf(d(2026, 8, 30)), days("上周日"))
        assertEquals(listOf(d(2026, 8, 30)), days("上星期天"))
    }

    @Test fun 规则3_上上周与本周已过日() {
        assertEquals(listOf(d(2026, 8, 21)), days("上上周五"))
        assertEquals(listOf(d(2026, 8, 31)), days("本周一"))
        assertEquals(listOf(d(2026, 9, 1)), days("这周二"))
    }

    @Test fun 规则3_本周今天及未来丢弃() {
        assertTrue("本周三 = today ⇒ 丢弃", days("本周三").isEmpty())
        assertTrue("这星期五在未来 ⇒ 丢弃", days("这星期五").isEmpty())
        assertTrue("下周三不是触发词 ⇒ 空（E10）", days("下周三见").isEmpty())
    }

    // ── 4 上个月（范围） ──

    @Test fun 规则4_上个月整月范围() {
        val r = range("上个月我们是不是吵架了")
        assertEquals(d(2026, 8, 1), r!!.start)
        assertEquals(d(2026, 8, 31), r.endInclusive)
        assertEquals(d(2026, 8, 1), range("上月")!!.start)
    }

    @Test fun 规则4_上个月接非数字仍触发_接号日形态让位规则5() {
        assertEquals(d(2026, 8, 1), range("上个月一直很忙")!!.start)
        assertNull("「上月15号」是规则 5 的起点 ⇒ 不出范围", range("上月15号"))
        assertEquals(listOf(d(2026, 8, 15)), days("上月15号"))
    }

    // ── 5 上月 X 号（钳月末） ──

    @Test fun 规则5_上月某日与中文数字() {
        assertEquals(listOf(d(2026, 8, 31)), days("上个月三十一号"))
        assertEquals(listOf(d(2026, 8, 3)), days("上月3日"))
    }

    @Test fun 规则5和6_中文数字四到九_R1() {
        assertEquals(listOf(d(2026, 8, 5)), days("上月五号"))
        assertEquals(listOf(d(2026, 8, 28)), days("上个月二十八号"))
        assertNull("「上个月五号」是规则 5 的起点 ⇒ 不再退化成整月范围", range("上个月五号"))
        assertEquals(listOf(d(2026, 9, 18)), days("本月十八号", on = d(2026, 9, 20)))
        assertTrue("09-18 在 today(09-02) 之后 ⇒ 丢弃", days("本月十八号").isEmpty())
    }

    @Test fun 规则5_超月末钳月末() {
        assertEquals(listOf(d(2026, 2, 28)), days("上月30号", on = d(2026, 3, 15)))
    }

    // ── 6 本月 X 号 ──

    @Test fun 规则6_本月已过日_今天与未来丢弃() {
        assertEquals(listOf(d(2026, 9, 1)), days("本月1号"))
        assertTrue(days("本月2号").isEmpty())
        assertTrue(days("这个月10号").isEmpty())
    }

    // ── 7 精确日 ──

    @Test fun 规则7_中文与ISO精确日() {
        assertEquals(listOf(d(2026, 8, 22)), days("2026年8月22日"))
        assertEquals(listOf(d(2026, 8, 22)), days("2026-08-22 那天"))
        assertEquals(listOf(d(2025, 12, 25)), days("2025年12月25号"))
    }

    @Test fun 规则7_非法日与未来日丢弃() {
        assertTrue(days("2026年13月1日").isEmpty())
        assertTrue(days("2026-02-30").isEmpty())
        assertTrue(days("2027年1月1日").isEmpty())
    }

    // ── 8 X月Y日（今年·≥ today 折去年） ──

    @Test fun 规则7b_去年X月Y日精确日_不出范围_R1() {
        assertEquals(listOf(d(2025, 8, 22)), days("去年8月22日那天"))
        assertNull("规则 11 让位给 7′", range("去年8月22日那天"))
        assertEquals(listOf(d(2025, 8, 15)), days("去年八月十五号"))
        assertTrue(days("去年2月30日").isEmpty())
        assertEquals("裸「去年八月」仍是范围", d(2025, 8, 1), range("去年八月")!!.start)
    }

    @Test fun 规则8_今年已过日与折去年() {
        assertEquals(listOf(d(2026, 8, 22)), days("8月22日"))
        assertEquals(listOf(d(2025, 10, 1)), days("10月1日"))
        assertEquals(listOf(d(2025, 9, 2)), days("9月2号"))
    }

    @Test fun 规则8_带年份不重复命中() {
        assertEquals("规则 7 已吃掉·规则 8 look-behind 挡住「年」", listOf(d(2026, 8, 22)), days("2026年8月22日"))
    }

    // ── 9 一年前 ──

    @Test fun 规则9_四种写法同一日去重() {
        assertEquals(listOf(d(2025, 9, 2)), days("去年今天"))
        assertEquals(listOf(d(2025, 9, 2)), days("去年的今天"))
        assertEquals(listOf(d(2025, 9, 2)), days("一年前的今天，一年前"))
    }

    // ── 10 半年前 / n 个月前 ──

    @Test fun 规则10_半年前与两个月前() {
        assertEquals(listOf(d(2026, 3, 2)), days("半年前"))
        assertEquals(listOf(d(2026, 7, 2)), days("两个月前"))
        assertEquals(listOf(d(2026, 6, 2)), days("3个月前"))
    }

    @Test fun 规则10_minusMonths自然钳位_E47() {
        assertEquals(listOf(d(2026, 2, 28)), days("一个月前", on = d(2026, 3, 31)))
    }

    // ── 11 去年 X 月（范围） ──

    @Test fun 规则11_去年某月整月范围() {
        val r = range("去年八月")
        assertEquals(d(2025, 8, 1), r!!.start)
        assertEquals(d(2025, 8, 31), r.endInclusive)
        assertEquals(d(2025, 12, 31), range("去年12月")!!.endInclusive)
        assertEquals(d(2025, 2, 28), range("去年二月")!!.endInclusive)
    }

    @Test fun 范围只取首个命中的规则_4优先于11() {
        val r = range("去年八月和上个月")
        assertEquals("规则 4 先于 11", d(2026, 8, 1), r!!.start)
    }

    // ── 12 锚点 ──

    @Test fun 规则12_三类锚点() {
        val anchors = OurDayDateMention.Anchors(
            firstDay = d(2025, 3, 1), firstMeetingDay = d(2025, 6, 6), lastMeetingDay = d(2026, 8, 20),
        )
        assertEquals(listOf(d(2026, 8, 20)), days("上次见面", anchors))
        assertEquals(listOf(d(2026, 8, 20)), days("上一次见面", anchors))
        assertEquals(listOf(d(2025, 6, 6)), days("第一次见面", anchors))
        assertEquals(listOf(d(2025, 6, 6)), days("初次见面", anchors))
        assertEquals(listOf(d(2025, 3, 1)), days("刚认识那会儿", anchors))
        assertEquals(listOf(d(2025, 3, 1)), days("认识的那天", anchors))
    }

    @Test fun 规则12_锚点为空即跳过() {
        assertTrue(days("上次见面").isEmpty())
        assertTrue(days("第一次聊天").isEmpty())
    }

    // ── 13 节日名 ──

    @Test fun 规则13_七夕回溯到最近一次() {
        assertEquals(listOf(d(2026, 8, 19)), days("七夕那天"))
    }

    @Test fun 规则13_公历节日与跨年回溯() {
        assertEquals(listOf(d(2026, 2, 14)), days("情人节"))
        assertEquals("圣诞在今年还没到 ⇒ 去年", listOf(d(2025, 12, 25)), days("圣诞"))
        assertEquals("跨年夜 ⇒ 2025-12-31", listOf(d(2025, 12, 31)), days("跨年夜"))
    }

    @Test fun 规则13_节日名撞词_最长名优先_R1() {
        assertEquals("「白色情人节」不再连带命中「情人节」", listOf(d(2026, 3, 14)), days("白色情人节那天"))
        assertEquals(setOf(d(2026, 2, 14), d(2026, 3, 14)), days("情人节和白色情人节").toSet())
    }

    @Test fun 规则13_520仅整词() {
        assertEquals(listOf(d(2026, 5, 20)), days("520 那天"))
        assertTrue("1520 不是整词", days("发了1520块").isEmpty())
        assertTrue("5201 不是整词", days("5201").isEmpty())
    }

    // ── 14 生日 ──

    @Test fun 规则14_双锚各取最近一次() {
        val anchors = OurDayDateMention.Anchors(userBirthday = MonthDay.of(3, 15), characterBirthday = MonthDay.of(9, 2))
        val got = days("我生日那天", anchors)
        assertEquals(setOf(d(2026, 3, 15), d(2025, 9, 2)), got.toSet())
        assertEquals("两日都入", 2, got.size)
    }

    @Test fun 规则14_二月二十九非闰年钳二月二十八() {
        val anchors = OurDayDateMention.Anchors(userBirthday = MonthDay.of(2, 29))
        assertEquals(listOf(d(2026, 2, 28)), days("生日", anchors))
    }

    @Test fun 规则14_无生日锚点跳过() {
        assertTrue(days("生日").isEmpty())
    }

    // ── 去重 / 顺序 / 空 ──

    @Test fun 同一日期多规则命中去重_E43() {
        assertEquals(listOf(d(2026, 9, 1)), days("昨天，就是9月1日，昨天"))
    }

    @Test fun 保持首次出现的文本顺序() {
        assertEquals(listOf(d(2026, 9, 1), d(2026, 8, 26)), days("昨天和上周三"))
        assertEquals(listOf(d(2026, 8, 26), d(2026, 9, 1)), days("上周三和昨天"))
    }

    @Test fun 纯文本无日期与空串为空() {
        val m = OurDayDateMention.resolve("今天天气不错，还记得吗", today, none, zone)
        assertTrue(m.days.isEmpty())
        assertNull(m.range)
        val e = OurDayDateMention.resolve("", today, none, zone)
        assertTrue(e.days.isEmpty())
        assertNull(e.range)
    }

    @Test fun 中文数字表() {
        assertEquals(1, OurDayDateMention.parseNumber("一"))
        assertEquals(2, OurDayDateMention.parseNumber("两"))
        assertEquals(10, OurDayDateMention.parseNumber("十"))
        assertEquals(19, OurDayDateMention.parseNumber("十九"))
        assertEquals(20, OurDayDateMention.parseNumber("二十"))
        assertEquals(31, OurDayDateMention.parseNumber("三十一"))
        assertEquals(15, OurDayDateMention.parseNumber("15"))
        assertNull(OurDayDateMention.parseNumber("四十"))
        assertNull(OurDayDateMention.parseNumber("十十"))
    }
}
