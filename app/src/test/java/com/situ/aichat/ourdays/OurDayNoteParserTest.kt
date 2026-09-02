package com.situ.aichat.ourdays

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1-5（卷一图纸 §7.2）：手记 JSON 解析 + 校验。断言从总图纸 §3.4 锁定口径独立反推：
 * note 40..600 / factLine 8..120 不含换行 / 日期前缀剥除（E30）/ 两候选（围栏·前后有话·E31）/ 非 string 原语视为缺失 /
 * `isLikelyValid` 黑名单命中失败。
 */
class OurDayNoteParserTest {

    private fun note(n: Int) = "记".repeat(n)
    private fun fact(n: Int) = "事".repeat(n)
    private fun json(note: String, factLine: String) = """{"note": "$note", "factLine": "$factLine"}"""

    private fun success(raw: String): NoteResult {
        val p = OurDayNoteParser.parse(raw)
        assertTrue("应解析成功，实际：$p", p is NoteParse.Success)
        return (p as NoteParse.Success).result
    }
    private fun failure(raw: String): String {
        val p = OurDayNoteParser.parse(raw)
        assertTrue("应解析失败，实际：$p", p is NoteParse.Failure)
        return (p as NoteParse.Failure).reason
    }

    @Test
    fun 纯JSON成功() {
        val r = success(json(note(80), "林晚和小明聊了考试"))
        assertEquals(note(80), r.note)
        assertEquals("林晚和小明聊了考试", r.factLine)
    }

    @Test
    fun json围栏成功_E31() {
        val r = success("```json\n" + json(note(80), "林晚和小明聊了考试") + "\n```")
        assertEquals("林晚和小明聊了考试", r.factLine)
    }

    @Test
    fun 裸围栏成功_E31() {
        success("```\n" + json(note(80), "林晚和小明聊了考试") + "\n```")
    }

    @Test
    fun 前后有话成功_E31() {
        val r = success("好的，这是今天的记录：\n" + json(note(80), "林晚和小明聊了考试") + "\n希望你喜欢。")
        assertEquals(note(80), r.note)
    }

    @Test
    fun 思考标签剥除后成功() {
        success("<think>先想想这一天</think>" + json(note(80), "林晚和小明聊了考试"))
    }

    @Test
    fun 缺字段失败() {
        failure("""{"note": "${note(80)}"}""")
        failure("""{"factLine": "林晚和小明聊了考试"}""")
        failure("""{}""")
    }

    @Test
    fun 非JSON失败() {
        failure("今天过得很平淡。")
        failure("")
        failure("[1, 2, 3]")
    }

    @Test
    fun note长度边界_39失败_40成功_600成功_601失败() {
        failure(json(note(39), fact(10)))
        assertEquals(40, success(json(note(40), fact(10))).note.codePointCount(0, 40))
        success(json(note(600), fact(10)))
        failure(json(note(601), fact(10)))
    }

    @Test
    fun factLine长度边界_7失败_8成功_120成功_121失败() {
        failure(json(note(80), fact(7)))
        success(json(note(80), fact(8)))
        success(json(note(80), fact(120)))
        failure(json(note(80), fact(121)))
    }

    @Test
    fun factLine含换行失败() {
        failure(json(note(80), "林晚和小明聊了考试\\n第二行"))
    }

    @Test
    fun 日期前缀剥除后再校验_E30() {
        assertEquals("林晚和小明聊了考试", success(json(note(80), "[2026-08-22] 林晚和小明聊了考试")).factLine)
        assertEquals("林晚和小明聊了考试", success(json(note(80), "2026-8-22 林晚和小明聊了考试")).factLine)
        assertEquals("林晚和小明聊了考试", success(json(note(80), "[2026-08-22]林晚和小明聊了考试")).factLine)
        // 剥完只剩 7 字 ⇒ 按剥后长度判太短
        failure(json(note(80), "[2026-08-22] " + fact(7)))
        // R1 🟡-1：卷二注入行形态「[yyyy-MM-dd 周X] 」原样回写也剥净（O-5）；裸日期后跟非空白后缀一并吃掉
        assertEquals("林晚和小明聊了考试", success(json(note(80), "[2026-08-22 周六] 林晚和小明聊了考试")).factLine)
        assertEquals("林晚和小明聊了考试", success(json(note(80), "[2026-08-22 · 日子] 林晚和小明聊了考试")).factLine)
        assertEquals("林晚和小明聊了考试", success(json(note(80), "2026-08-22， 林晚和小明聊了考试")).factLine)
        // 不以日期起头的正文一字不动
        assertEquals("八月的事：林晚和小明聊了考试", success(json(note(80), "八月的事：林晚和小明聊了考试")).factLine)
    }

    @Test
    fun 非string原语字段视为缺失() {
        failure("""{"note": 12345678901234567890123456789012345678901234, "factLine": "林晚和小明聊了考试"}""")
        failure("""{"note": "${note(80)}", "factLine": ["林晚和小明聊了考试"]}""")
        failure("""{"note": "${note(80)}", "factLine": null}""")
    }

    @Test
    fun isLikelyValid黑名单命中失败() {
        // 前缀黑名单：以 error: 起手；片段黑名单：含 {"error"
        failure(json("error: rate limit exceeded " + note(40), fact(10)))
        failure(json(note(40) + " {\\\"error\\\": \\\"x\\\"} " + note(10), fact(10)))
        // 纯符号（无字母 / 汉字）
        failure(json("……".repeat(30), fact(10)))
    }

    @Test
    fun 前后空白被trim() {
        val r = success(json("  " + note(50) + "  ", "  林晚和小明聊了考试  "))
        assertEquals(note(50), r.note)
        assertEquals("林晚和小明聊了考试", r.factLine)
    }
}
