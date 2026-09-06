package com.situ.aichat.promise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 「我们的约定」聊天内工具 / 暗号解码纯逻辑（图纸 2026-09-06 约定工具调用化 §3.2/§3.3-A·T1-1/2/3）。
 * 断言从图纸规格独立反推：空壳 / 非法一律 null 且**不抛**（E1）；暗号一律擦净、值内含 `}` 完整解析（E16）；
 * due 补当地 09:00（E18·与对账同规则）。
 */
class PromiseChatToolTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai") // UTC+8 无 DST·确定性

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    // ── T1-1：fromToolCall ──

    @Test fun fromToolCall_record_trimsContent_parsesDueAt0900_keepsEvidenceVerbatim() {
        val a = PromiseChatTool.fromToolCall(
            "record_promise",
            """{"content":"  周六小满和阿川一起去看展  ","due":"2026-09-13","evidence":"那就周六去看展吧"}""",
            zone,
        )
        val r = a as PromiseToolAction.Record
        assertEquals("周六小满和阿川一起去看展", r.content)
        assertEquals(at(2026, 9, 13, 9, 0), r.dueAtMillis) // 纯日期补当地 09:00
        assertEquals("那就周六去看展吧", r.evidence) // 证据原样（不 trim·闸门在 handler）
    }

    @Test fun fromToolCall_record_dueEmptyOrNullLiteralOrMissing_givesNullDue() {
        fun due(json: String) = (PromiseChatTool.fromToolCall("record_promise", json, zone) as PromiseToolAction.Record).dueAtMillis
        assertNull(due("""{"content":"一起去看展","due":"","evidence":"好"}"""))
        assertNull(due("""{"content":"一起去看展","due":"null","evidence":"好"}"""))
        assertNull(due("""{"content":"一起去看展","evidence":"好"}"""))
        assertNull(due("""{"content":"一起去看展","due":"下周六","evidence":"好"}""")) // 非 yyyy-MM-dd → null
    }

    @Test fun fromToolCall_record_blankContent_isNull() {
        assertNull(PromiseChatTool.fromToolCall("record_promise", """{"content":"   ","evidence":"好"}""", zone))
        assertNull(PromiseChatTool.fromToolCall("record_promise", """{"evidence":"好"}""", zone))
    }

    @Test fun fromToolCall_resolve_acceptsQuotedNumber_lowercasesStatus() {
        val a = PromiseChatTool.fromToolCall(
            "resolve_promise",
            """{"no":"2","status":" Fulfilled ","evidence":"展看完啦"}""",
            zone,
        )
        val r = a as PromiseToolAction.Resolve
        assertEquals(2, r.no)
        assertEquals("fulfilled", r.status)
        assertEquals("展看完啦", r.evidence)
    }

    @Test fun fromToolCall_resolve_noZeroOrNegative_isNull() {
        assertNull(PromiseChatTool.fromToolCall("resolve_promise", """{"no":0,"status":"fulfilled","evidence":"看完啦"}""", zone))
        assertNull(PromiseChatTool.fromToolCall("resolve_promise", """{"no":-1,"status":"fulfilled","evidence":"看完啦"}""", zone))
        assertNull(PromiseChatTool.fromToolCall("resolve_promise", """{"status":"fulfilled","evidence":"看完啦"}""", zone))
    }

    @Test fun fromToolCall_unknownToolOrBrokenJson_isNullNotThrow() {
        assertNull(PromiseChatTool.fromToolCall("calendar_action", """{"content":"一起去看展","evidence":"好"}""", zone))
        assertNull(PromiseChatTool.fromToolCall("record_promise", """{"content":"一起去看展",""", zone))
        assertNull(PromiseChatTool.fromToolCall("record_promise", "", zone))
    }

    // ── T1-2：parseMarkers 正常路径 ──

    @Test fun parseMarkers_twoMarkers_extractedAndFullyErased() {
        val raw = "好呀，那就说定了～\n" +
            """[promise]{"action":"record","content":"周六小满和阿川一起去看展","due":"2026-09-13","evidence":"那就周六去看展吧"}""" + "\n" +
            """[promise]{"action":"resolve","no":1,"status":"fulfilled","evidence":"简历我已经改好发你了"}"""
        val (text, actions) = PromiseChatTool.parseMarkers(raw, zone)
        assertEquals("好呀，那就说定了～", text)
        assertTrue("正文绝不含标记", !text.contains("[promise]"))
        assertEquals(2, actions.size)
        val rec = actions[0] as PromiseToolAction.Record
        assertEquals("周六小满和阿川一起去看展", rec.content)
        assertEquals(at(2026, 9, 13, 9, 0), rec.dueAtMillis)
        val res = actions[1] as PromiseToolAction.Resolve
        assertEquals(1, res.no)
        assertEquals("fulfilled", res.status)
        assertEquals("简历我已经改好发你了", res.evidence)
    }

    @Test fun parseMarkers_braceInsideValue_parsedWholeAndErased() {
        val raw = "记下啦～" +
            """[promise]{"action":"record","content":"阿川请小满吃饭}顺便聊天","evidence":"吃饭}顺便聊天，说定了"}"""
        val (text, actions) = PromiseChatTool.parseMarkers(raw, zone)
        assertEquals("记下啦～", text) // 串内 } 不截断 → 尾巴不泄露
        assertEquals(1, actions.size)
        assertEquals("阿川请小满吃饭}顺便聊天", (actions[0] as PromiseToolAction.Record).content)
    }

    // ── T1-3：parseMarkers 异常路径（一律擦除、零动作） ──

    @Test fun parseMarkers_bareMarker_erasedWithNoAction() {
        val (text, actions) = PromiseChatTool.parseMarkers("说定了[promise]", zone)
        assertEquals("说定了", text)
        assertTrue(actions.isEmpty())
    }

    @Test fun parseMarkers_malformedJson_erasedWithNoAction() {
        val (text, actions) = PromiseChatTool.parseMarkers("""说定了[promise]{"action":"record",""", zone)
        assertTrue("非法 JSON 的裸标记也擦", !text.contains("[promise]"))
        assertTrue(actions.isEmpty())
    }

    @Test fun parseMarkers_unknownAction_erasedWithNoAction() {
        val raw = """好的[promise]{"action":"delete","content":"一起去看展","evidence":"那就周六去看展吧"}"""
        val (text, actions) = PromiseChatTool.parseMarkers(raw, zone)
        assertEquals("好的", text)
        assertTrue(actions.isEmpty())
    }

    @Test fun parseMarkers_emptyShellRecord_erasedWithNoAction() {
        val raw = """好的[promise]{"action":"record","content":"","evidence":"那就周六去看展吧"}"""
        val (text, actions) = PromiseChatTool.parseMarkers(raw, zone)
        assertEquals("好的", text)
        assertTrue(actions.isEmpty())
    }

    @Test fun parseMarkers_noMarker_textUnchangedExceptTrim() {
        val (text, actions) = PromiseChatTool.parseMarkers("今天天气真好呀", zone)
        assertEquals("今天天气真好呀", text)
        assertTrue(actions.isEmpty())
    }

    // ── 强耦合钉：规则文本 ↔ 解析器（图纸 §6·改任一侧必须同步另一侧） ──

    @Test fun fallbackRule_pinsMarkerAndActionVocabulary() {
        val rule = PromiseChatTool.FALLBACK_MARKER_RULE
        assertTrue(rule.startsWith("【约定记账】"))
        assertTrue(rule.contains("""[promise]{"action":"record","""))
        assertTrue(rule.contains("""[promise]{"action":"resolve","""))
        assertTrue(rule.contains("⑤ 清单里已有的事（哪怕措辞不同）不要再记；"))
        // 规则里示范的两行标记，解析器必须真能吃下（同文件强耦合的自证）。
        val sample = "正文\n" +
            """[promise]{"action":"record","content":"阿川周六陪小满去看展","due":"2026-09-13","evidence":"那就周六去看展吧"}"""
        assertEquals(1, PromiseChatTool.parseMarkers(sample, zone).second.size)
    }

    @Test fun toolNamesAndCaps_areLocked() {
        assertEquals("record_promise", PromiseChatTool.TOOL_RECORD)
        assertEquals("resolve_promise", PromiseChatTool.TOOL_RESOLVE)
        assertEquals("[promise]", PromiseChatTool.MARKER)
        assertEquals(2, PromiseChatTool.RECORD_CAP)
        assertEquals(3, PromiseChatTool.RESOLVE_CAP)
        assertTrue(PromiseChatTool.isPromiseTool("record_promise"))
        assertTrue(PromiseChatTool.isPromiseTool("resolve_promise"))
        assertTrue(!PromiseChatTool.isPromiseTool("propose_future_meeting"))
    }
}
