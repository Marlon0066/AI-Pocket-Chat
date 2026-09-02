package com.situ.aichat.prompt.ourdays

import com.situ.aichat.data.local.entity.OurDayEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.ZoneId

/**
 * T1-3（卷二图纸 §7.2）：注入块渲染。断言从 §3.4 算法 + §4.1 文案 + §5 边界独立反推（块头两行**重新打字**）。
 * today = 2026-09-02 周三；时区上海。文本避开节日名（那条走 ICU·由 T1-1 覆盖）。
 */
class OurDaysInjectionBuilderTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today: LocalDate = LocalDate.of(2026, 9, 2)

    private fun row(
        dayKey: String,
        factLine: String = "林晚和小澄聊了$dayKey",
        messageCount: Int = 1,
        callSeconds: Int = 0,
        hasMeeting: Boolean = false,
    ) = OurDayEntity(
        uuid = "u-$dayKey", characterUuid = "c1", dayKey = dayKey, factLine = factLine,
        messageCount = messageCount, callSeconds = callSeconds, hasMeeting = hasMeeting,
        createdAtMillis = 0L, updatedAtMillis = 0L,
    )

    private fun render(
        rows: List<OurDayEntity>,
        text: String,
        windowEarliestMillis: Long? = null,
        userName: String = "小澄",
        characterBirthday: MonthDay? = null,
        userBirthday: MonthDay? = null,
        on: LocalDate = today,
    ) = OurDaysInjection.render(rows, text, on, windowEarliestMillis, zone, "林晚", userName, characterBirthday, userBirthday)

    private fun millis(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()

    // ── 块头 + 行格式 ──

    @Test fun 块头两行逐字_日期行格式() {
        val out = render(listOf(row("2026-08-26", "林晚陪小澄看了场电影")), "上周三我们干嘛了")
        val lines = out.split("\n")
        assertEquals("[我们的日子 · 按日期翻到的记录]", lines[0])
        assertEquals("这是林晚和小澄当天的记录。同一天若与记忆里的概括有出入，以这里为准。", lines[1])
        assertEquals("[2026-08-26 周三] 林晚陪小澄看了场电影", lines[2])
        assertEquals(3, lines.size)
        assertFalse("块末无换行", out.endsWith("\n"))
    }

    @Test fun 无昵称时块头用调用方给的参照名_E49() {
        val out = render(listOf(row("2026-09-01")), "昨天", userName = "用户")
        assertTrue(out.contains("这是林晚和用户当天的记录。"))
    }

    @Test fun 多日按升序输出_不按提及序() {
        val out = render(listOf(row("2026-08-26"), row("2026-09-01")), "昨天和上周三")
        val lines = out.split("\n").drop(2)
        assertEquals(listOf("[2026-08-26 周三] 林晚和小澄聊了2026-08-26", "[2026-09-01 周二] 林晚和小澄聊了2026-09-01"), lines)
    }

    // ── 排除 ──

    @Test fun 昨天在原文窗口内则不出_E11() {
        val window = millis(LocalDateTime.of(2026, 9, 1, 10, 0))
        assertEquals("", render(listOf(row("2026-09-01")), "昨天", windowEarliestMillis = window))
    }

    @Test fun 窗口起日之前的日子照常出() {
        val window = millis(LocalDateTime.of(2026, 9, 1, 10, 0))
        val out = render(listOf(row("2026-08-31"), row("2026-09-01")), "昨天和前天", windowEarliestMillis = window)
        assertTrue(out.contains("[2026-08-31 周一]"))
        assertFalse(out.contains("[2026-09-01"))
    }

    @Test fun 窗口为空时不排除任何日_E56() {
        assertTrue(render(listOf(row("2026-09-01")), "昨天", windowEarliestMillis = null).contains("[2026-09-01 周二]"))
    }

    @Test fun 提到的日子无页则无行_E42() {
        assertEquals("", render(listOf(row("2026-08-01")), "昨天"))
        assertFalse(render(listOf(row("2026-08-01"), row("2026-08-26")), "昨天和上周三").contains("没有记录"))
    }

    @Test fun 今天永不出行() {
        val out = render(listOf(row("2026-09-02"), row("2026-09-01")), "2026年9月2日和昨天")
        assertFalse(out.contains("[2026-09-02"))
        assertTrue(out.contains("[2026-09-01"))
    }

    @Test fun rows为空即空串_E40() {
        assertEquals("", render(emptyList(), "昨天和上个月和一年前的今天"))
    }

    // ── 上限 7 ──

    @Test fun 超过七个日期取前七按提及序_输出升序_E44() {
        val rows = (1..9).map { row("2026-08-0$it") }
        val text = (9 downTo 1).joinToString("、") { "8月${it}日" }
        val lines = render(rows, text).split("\n").drop(2)
        // 前 7 = 提及序的 8月9日…8月3日；升序输出。8月2日虽被 7 上限切掉，但它恰是「一个月前的今天」且不在日期行里 ⇒ 按 §3.4 另出一行。
        val dateLines = lines.filter { it.startsWith("[") }
        assertEquals(7, dateLines.size)
        assertEquals("[2026-08-03 周一]", dateLines.first().substringBefore("]") + "]")
        assertEquals("[2026-08-09 周日]", dateLines.last().substringBefore("]") + "]")
        assertTrue(dateLines == dateLines.sorted())
        assertEquals(listOf("一个月前的今天：[2026-08-02 周日] 林晚和小澄聊了2026-08-02"), lines.filter { !it.startsWith("[") })
    }

    // ── 范围段 ──

    @Test fun 上个月_天数行逐字与事最多五天按热度升序输出_E12() {
        val rows = listOf(
            row("2026-08-01", messageCount = 1),
            row("2026-08-05", messageCount = 50),
            row("2026-08-10", messageCount = 9),
            row("2026-08-12", messageCount = 25),
            row("2026-08-15", messageCount = 2, callSeconds = 600), // 2 + 10×3 = 32
            row("2026-08-20", messageCount = 30),
            row("2026-08-25", messageCount = 3),
            row("2026-08-28", messageCount = 40),
        )
        val lines = render(rows, "上个月我们怎么样").split("\n")
        assertEquals("那段时间你们有 8 天有记录，事最多的几天：", lines[2])
        val top = lines.drop(3)
        assertEquals(5, top.size)
        // 热度：08-05(50) 08-28(40) 08-15(32) 08-20(30) 08-12(25) 入选；输出升序。
        assertEquals(
            listOf("2026-08-05", "2026-08-12", "2026-08-15", "2026-08-20", "2026-08-28"),
            top.map { it.substring(1, 11) },
        )
    }

    @Test fun 范围内零候选整段省略_E45() {
        assertEquals("", render(listOf(row("2026-07-01")), "上个月"))
        val out = render(listOf(row("2026-07-01"), row("2026-09-01")), "上个月和昨天")
        assertFalse(out.contains("那段时间"))
        assertTrue(out.contains("[2026-09-01"))
    }

    @Test fun 范围内不足五天全部列出() {
        val lines = render(listOf(row("2026-08-03"), row("2026-08-09")), "上月").split("\n")
        assertEquals("那段时间你们有 2 天有记录，事最多的几天：", lines[2])
        assertEquals(2, lines.drop(3).size)
    }

    @Test fun 范围段与日期行撞日只出一次_天数仍全计_R1() {
        val rows = listOf(row("2026-08-05", messageCount = 50), row("2026-08-15", messageCount = 2), row("2026-08-20", messageCount = 30))
        val out = render(rows, "上个月15号那天，还有上个月整体怎么样")
        val lines = out.split("\n")
        assertEquals("[2026-08-15 周六] 林晚和小澄聊了2026-08-15", lines[2])
        assertEquals("那段时间你们有 3 天有记录，事最多的几天：", lines[3])
        assertEquals(listOf("2026-08-05", "2026-08-20"), lines.drop(4).map { it.substring(1, 11) })
        assertEquals("08-15 的行全文只出一次", 1, out.split("[2026-08-15").size - 1)
    }

    @Test fun 范围内全部已在日期行则范围段整段省略_R1() {
        val out = render(listOf(row("2026-08-15")), "上月15号和上个月")
        assertEquals(listOf("[2026-08-15 周六] 林晚和小澄聊了2026-08-15"), out.split("\n").drop(2))
        assertFalse(out.contains("那段时间"))
    }

    // ── 那年今日 ──

    @Test fun 一年前与一个月前各恰一行_前缀逐字() {
        val out = render(listOf(row("2025-09-02", "去年今天的事"), row("2026-08-02", "上月今天的事")), "随便聊聊")
        val lines = out.split("\n")
        assertEquals(4, lines.size)
        assertEquals("一年前的今天：[2025-09-02 周二] 去年今天的事", lines[2])
        assertEquals("一个月前的今天：[2026-08-02 周日] 上月今天的事", lines[3])
    }

    @Test fun 那年今日无页即无行() {
        assertEquals("", render(listOf(row("2025-09-03")), "随便聊聊"))
    }

    @Test fun 那年今日与日期指名撞日只出一次_日期行优先_E46() {
        val out = render(listOf(row("2025-09-02", "去年今天的事"), row("2026-08-02", "上月今天的事")), "一年前的今天")
        val lines = out.split("\n")
        assertEquals(listOf("[2025-09-02 周二] 去年今天的事", "一个月前的今天：[2026-08-02 周日] 上月今天的事"), lines.drop(2))
        assertEquals("一年前那行只出现一次", 1, out.split("2025-09-02").size - 1)
    }

    @Test fun 那年今日与范围段撞日不重复() {
        val out = render(listOf(row("2026-08-02", "上月今天的事")), "上个月")
        assertTrue(out.contains("那段时间你们有 1 天有记录"))
        assertFalse(out.contains("一个月前的今天："))
    }

    @Test fun 三段顺序_日期行_范围段_那年今日() {
        val rows = listOf(row("2026-08-26"), row("2026-08-05"), row("2025-09-02"))
        val lines = render(rows, "上周三和上个月").split("\n")
        assertEquals("[2026-08-26 周三] 林晚和小澄聊了2026-08-26", lines[2])
        assertEquals("那段时间你们有 2 天有记录，事最多的几天：", lines[3])
        assertEquals("[2026-08-05 周三] 林晚和小澄聊了2026-08-05", lines[4])
        // R1 🔵-5：08-26 已在日期行 ⇒ 范围段不再重复它（原断言 lines[5] = 08-26 是撞日双出的形态）。
        assertEquals("一年前的今天：[2025-09-02 周二] 林晚和小澄聊了2025-09-02", lines[5])
        assertEquals(6, lines.size)
    }

    // ── 锚点与生日 ──

    @Test fun 锚点来自rows_上次见面与刚认识() {
        val rows = listOf(row("2025-03-01"), row("2025-06-06", hasMeeting = true), row("2026-08-20", hasMeeting = true))
        val out = render(rows, "上次见面和刚认识那天")
        assertTrue(out.contains("[2026-08-20 周四]"))
        assertTrue(out.contains("[2025-03-01 周六]"))
        assertFalse(out.contains("[2025-06-06"))
    }

    @Test fun 生日锚点从参数来() {
        val out = render(listOf(row("2026-08-15")), "生日那天", characterBirthday = MonthDay.of(8, 15))
        assertTrue(out.contains("[2026-08-15 周六]"))
    }
}
