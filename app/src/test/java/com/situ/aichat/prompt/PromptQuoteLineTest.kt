package com.situ.aichat.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 引用旁注行纯函数单测（引用一期·图纸 2026-09-04 §3.2/§3.4）。
 *
 * 断言**从图纸规格独立反推**、不照抄实现：四形态整串在这里重新打一遍字（不引用实现常量拼），锚三档、
 * 无锚两条降级、截断三例各自钉死；另有两条「机制钉」——① 同一时间戳配不同 now 必须产出不同的锚
 * （钉死「时间锚绝不在发送时冻结」这条设计地基）；② 引用行绝不能被 [HistoryTimeDivider.isDivider] 误判成
 * 时间分割线（否则悬空清理会把它当分割线删掉）。
 */
class PromptQuoteLineTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun inst(y: Int, mo: Int, d: Int, h: Int, mi: Int): Instant =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant()

    /** 被引用消息发生在 2026-08-17 09:12（该日真实星期 = 周一）。 */
    private val quotedAt = ms(2026, 8, 17, 9, 12)

    private fun line(
        userName: String = "司徒",
        content: String = "晚上七点老地方",
        senderRole: String? = "assistant",
        ts: Long? = quotedAt,
        now: Instant? = inst(2026, 8, 17, 21, 0),
    ) = PromptQuoteLine.build(
        userName = userName,
        quotedContent = content,
        quotedSenderRole = senderRole,
        quotedTimestampMillis = ts,
        now = now,
        zone = zone,
    )

    // ---- §3.2 四形态（逐字） ----

    @Test
    fun `有锚 · 引用角色的话`() {
        assertEquals(
            "【司徒在回复你 今天 09:12 说的这句：「晚上七点老地方」】",
            line(senderRole = "assistant"),
        )
    }

    @Test
    fun `有锚 · 引用自己的话`() {
        assertEquals(
            "【司徒在回复自己 今天 09:12 说的这句：「晚上七点老地方」】",
            line(senderRole = "user"),
        )
    }

    @Test
    fun `无锚 · 引用角色的话`() {
        assertEquals(
            "【司徒在回复你先前说的这句：「晚上七点老地方」】",
            line(senderRole = "assistant", ts = null),
        )
    }

    @Test
    fun `无锚 · 引用自己的话`() {
        assertEquals(
            "【司徒在回复自己先前说的这句：「晚上七点老地方」】",
            line(senderRole = "user", now = null),
        )
    }

    @Test
    fun `senderRole 为 null 时按「角色的话」处理`() {
        // 备份重映射 / 老数据可能留下空 role；判据只认字面 "user"，其余一律当角色说的话。
        assertTrue(line(senderRole = null).contains("在回复你"))
    }

    // ---- 锚三档（借 HistoryTimeDivider.formatLabel·与历史分割线同款） ----

    @Test
    fun `锚三档 · 今天 昨天 更早`() {
        assertEquals(
            "【司徒在回复你 今天 09:12 说的这句：「晚上七点老地方」】",
            line(now = inst(2026, 8, 17, 21, 0)),
        )
        assertEquals(
            "【司徒在回复你 昨天 09:12 说的这句：「晚上七点老地方」】",
            line(now = inst(2026, 8, 18, 21, 0)),
        )
        assertEquals(
            "【司徒在回复你 8月17日 周一 09:12 说的这句：「晚上七点老地方」】",
            line(now = inst(2026, 8, 20, 21, 0)),
        )
    }

    @Test
    fun `同一时间戳配不同 now 必产出不同锚（钉死「不许发送时冻结」）`() {
        val sameDay = line(now = inst(2026, 8, 17, 21, 0))
        val nextDay = line(now = inst(2026, 8, 18, 21, 0))
        val muchLater = line(now = inst(2026, 8, 20, 21, 0))
        assertNotEquals("发送当天与次日必须说法不同", sameDay, nextDay)
        assertNotEquals(nextDay, muchLater)
        assertTrue(sameDay.contains("今天 09:12"))
        assertTrue(nextDay.contains("昨天 09:12"))
        assertTrue(muchLater.contains("8月17日 周一 09:12"))
    }

    // ---- 空昵称回退 ----

    @Test
    fun `空昵称回退「用户」`() {
        // 回退解析归调用方（`PromptBuilderHistory` 的 resolvedUserName ← R.string.pb_user_fallback），
        // 本函数只负责把拿到的名字放对位置；端到端那一跳由 PromptBuilderQuoteLineTest 钉。
        assertEquals(
            "【用户在回复你 今天 09:12 说的这句：「晚上七点老地方」】",
            line(userName = "用户"),
        )
    }

    // ---- §3.4 截断（300 / 中间省略 / 断标签守卫） ----

    @Test
    fun `恰好 300 字原样不加任何标记`() {
        val content = "甲".repeat(300)
        val out = line(content = content)
        assertTrue("300 字是边界内，必须原样", out.contains("「$content」"))
        assertFalse("没超长就不许出现省略标记", out.contains("…（中间略）…"))
    }

    @Test
    fun `超过 300 字取头 160 尾 100 中间省略`() {
        val content = "甲".repeat(200) + "乙".repeat(171)
        val out = line(content = content)
        val expected = "甲".repeat(160) + "…（中间略）…" + "乙".repeat(100)
        assertEquals("【司徒在回复你 今天 09:12 说的这句：「$expected」】", out)
    }

    @Test
    fun `截断处残留半截 sticker 标签时只切标签_尾段照留`() {
        // 151 个「甲」+ 20 字标签 + 200 个「乙」= 371 字：take(160) 恰好切在 `[sticker:` 之后，
        // 半截标签必须切掉，绝不能把 `[sticker:` 漏给模型。
        // **复核 R1 🟡 修订**：原图纸 §3.4 字面写「从那个 `[` 处切掉」，作用在拼好的整串上 → 连
        // 省略标记与整个尾段（100 字真内容）一起被丢。守卫的用意只是「别漏半截标签」，不是「丢掉尾段」，
        // 故改为头尾各自守卫：头段切到标签前，`…（中间略）…` 与尾段照常保留。
        val content = "甲".repeat(151) + "[sticker:abcdefghij]" + "乙".repeat(200)
        val out = line(content = content)
        assertEquals(
            "【司徒在回复你 今天 09:12 说的这句：「${"甲".repeat(151)}…（中间略）…${"乙".repeat(100)}」】",
            out,
        )
        assertFalse("半截标签绝不许漏出", out.contains("[sticker"))
    }

    @Test
    fun `标签完整时守卫不误伤`() {
        // 140 个「甲」+ 13 字完整标签 + 7 个「乙」= take(160) 的收尾，标签闭合 → 一个字都不许动。
        val content = "甲".repeat(140) + "[sticker:abc]" + "乙".repeat(231)
        val out = line(content = content)
        assertTrue("完整标签要留着（下游才转得成语义）", out.contains("[sticker:abc]"))
        assertTrue(out.contains("…（中间略）…"))
    }

    // ---- 与时间分割线的互不干扰 ----

    @Test
    fun `引用行不被时间分割线判据误判`() {
        assertFalse(HistoryTimeDivider.isDivider(line()))
        assertFalse(HistoryTimeDivider.isDivider(line(userName = "用户", ts = null)))
        assertFalse(HistoryTimeDivider.isDivider(line(senderRole = "user")))
    }
    // ---- 断标签守卫（复核 R1 🟡 修：原实现只查最后一个 [sticker:）----

    @Test
    fun `头段被腰斩的表情标签_即使尾段另有完整标签也必须切掉`() {
        // 复核实测复现场景：311 字、两个表情。头段 160 处切在第一个标签中间，而尾段 100 字里有第二个**完整**标签。
        // 原实现 lastIndexOf 找到的是尾段那个（闭合的）→ 判定「没有断标签」→ 头段那截 `[sticker:aa` 原样漏给模型。
        val content = "今天真的累坏了".repeat(21) + "呜呜" + "[sticker:aaaaaaaa-1111]" +
            "中间还有好多话要说".repeat(12) + "最后再来一个[sticker:bbbbbbbb-2222]收尾"
        assertTrue("前提：本例必须超过 300 字才会走截断", content.length > PromptQuoteLine.MAX_QUOTED_CHARS)

        val out = PromptQuoteLine.truncate(content)

        assertFalse("被腰斩的标签残片必须切干净：$out", out.contains("[sticker:aaaaaaaa"))
        assertTrue("尾段里完整的那个标签要留着（它转得成语义）", out.contains("[sticker:bbbbbbbb-2222]"))
        assertTrue(out.contains(PromptQuoteLine.JOINER))
    }

    @Test
    fun `尾段起点落在标签中间时_残片从标签闭合处切掉`() {
        // takeLast 的起点落在 `[sticker:…]` 内部 → 尾段会以 `ticker:cccccccc-3333]` 这种残片开头。
        val content = "很长的正文".repeat(45) + "[sticker:cccccccc-3333]" + "尾".repeat(80)
        assertTrue("前提：本例必须超过 300 字", content.length > PromptQuoteLine.MAX_QUOTED_CHARS)

        val out = PromptQuoteLine.truncate(content)

        assertFalse("标签残片不许漏进提示词：$out", out.contains("cccccccc"))
        assertTrue("残片之后的正文要留着", out.endsWith("尾"))
    }

    @Test
    fun `标签完整落在头尾段内时守卫不误伤`() {
        val content = "开头[sticker:dddddddd-4444]" + "中段填充".repeat(70) + "[sticker:eeeeeeee-5555]结尾"
        assertTrue(content.length > PromptQuoteLine.MAX_QUOTED_CHARS)

        val out = PromptQuoteLine.truncate(content)

        assertTrue("头段那个完整标签不该被切", out.contains("[sticker:dddddddd-4444]"))
        assertTrue("尾段那个完整标签不该被切", out.contains("[sticker:eeeeeeee-5555]"))
    }

}
