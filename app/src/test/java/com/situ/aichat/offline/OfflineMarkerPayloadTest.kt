package com.situ.aichat.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `OfflineMarkerStartPayload` / `OfflineMarkerEndPayload` tests (P10.2c-3a) — exact marker text
 * (byte-faithful to iOS `MessageContent.makeContent`) + `parse` round-trips / colon variants / null
 * cases, reverse-derived from iOS `MessageContent.swift`.
 */
class OfflineMarkerPayloadTest {

    // ── start: makeContent ──

    @Test fun start_make_content_without_seed() {
        val text = OfflineMarkerStartPayload("咖啡馆", "喝咖啡", "15:30").makeContent()
        assertEquals(
            "【线下见面开始 | 地点：咖啡馆 | 活动：喝咖啡 | 时间：15:30】\n从现在起你们面对面在一起，不再是手机聊天。",
            text,
        )
    }

    @Test fun start_make_content_with_seed_appends_scene_seed() {
        val text = OfflineMarkerStartPayload("江边", "散步", "18:30", "她今天有心事").makeContent()
        assertEquals(
            "【线下见面开始 | 地点：江边 | 活动：散步 | 时间：18:30】\n从现在起你们面对面在一起，不再是手机聊天。\n【今日场景种子】她今天有心事",
            text,
        )
    }

    @Test fun start_blank_seed_is_omitted() {
        val text = OfflineMarkerStartPayload("江边", "散步", "18:30", "").makeContent()
        assertEquals(
            "【线下见面开始 | 地点：江边 | 活动：散步 | 时间：18:30】\n从现在起你们面对面在一起，不再是手机聊天。",
            text,
        )
    }

    // ── start: parse ──

    @Test fun start_parse_round_trips_with_seed() {
        val original = OfflineMarkerStartPayload("咖啡馆", "喝咖啡", "15:30", "她今天有心事")
        assertEquals(original, OfflineMarkerStartPayload.parse(original.makeContent()))
    }

    @Test fun start_parse_round_trips_without_seed() {
        val original = OfflineMarkerStartPayload("公园", "看展", "9:05", null)
        assertEquals(original, OfflineMarkerStartPayload.parse(original.makeContent()))
    }

    @Test fun start_parse_accepts_half_width_colons_and_time_with_colon() {
        // 半角冒号字段 + 值里含半角冒号（15:30）不应被 | 分割误伤。
        val raw = "【线下见面开始 | 地点:咖啡馆 | 活动:喝咖啡 | 时间:15:30】\n从现在起你们面对面在一起，不再是手机聊天。"
        val p = OfflineMarkerStartPayload.parse(raw)
        assertEquals(OfflineMarkerStartPayload("咖啡馆", "喝咖啡", "15:30", null), p)
    }

    @Test fun start_parse_empty_seed_yields_null_tension() {
        val raw = "【线下见面开始 | 地点：江边 | 活动：散步 | 时间：18:30】\n从现在起你们面对面在一起，不再是手机聊天。\n【今日场景种子】"
        assertNull(OfflineMarkerStartPayload.parse(raw)!!.tensionSeed)
    }

    @Test fun start_parse_returns_null_on_bad_format() {
        assertNull(OfflineMarkerStartPayload.parse("线下见面开始 | 地点：X | 活动：Y | 时间：T")) // 无【前缀
        assertNull(OfflineMarkerStartPayload.parse("【线下见面开始 | 地点：X | 活动：Y")) // 缺时间 + 不足 4 段
        assertNull(OfflineMarkerStartPayload.parse("【线下见面开始 | 地点：X | 活动：Y | 备注：Z】")) // 4 段但无时间
        assertNull(OfflineMarkerStartPayload.parse("普通聊天文本"))
    }

    // ── end: makeContent + parse ──

    @Test fun end_make_content_exact_text() {
        val text = OfflineMarkerEndPayload("约30分钟", "16:00", "你们自然地结束了这次见面").makeContent()
        assertEquals(
            "【线下见面结束 | 时长：约30分钟 | 时间：16:00】\n你们自然地结束了这次见面。现在恢复正常线上聊天模式。\n" +
                "【重要】从现在起不要再使用 [叙述][对话][内心] 等任何标签，像平时发微信一样正常说话。你可以自然地回顾和提及刚才见面时发生的事情。",
            text,
        )
    }

    @Test fun end_parse_round_trips() {
        val original = OfflineMarkerEndPayload("约45分钟", "21:10", "用户主动结束了这次见面")
        assertEquals(original, OfflineMarkerEndPayload.parse(original.makeContent()))
    }

    @Test fun end_parse_extracts_reason_before_terminator() {
        val raw = "【线下见面结束 | 时长：不到1分钟 | 时间：8:00】\n你们匆匆道别。现在恢复正常线上聊天模式。\n【重要】别用标签。"
        val p = OfflineMarkerEndPayload.parse(raw)!!
        assertEquals("不到1分钟", p.durationText)
        assertEquals("8:00", p.timeString)
        assertEquals("你们匆匆道别", p.reasonText)
    }

    @Test fun end_parse_returns_null_without_terminator() {
        assertNull(OfflineMarkerEndPayload.parse("【线下见面结束 | 时长：约30分钟 | 时间：16:00】\n你们道别了")) // 无结束句
        assertNull(OfflineMarkerEndPayload.parse("【线下见面结束 | 时长：约30分钟】")) // 不足 3 段
        assertNull(OfflineMarkerEndPayload.parse("【线下见面开始 | 地点：X】")) // 错前缀
    }

    // ── end: llmRepresentation（留痕改造 2026-08-31·图纸 §7 T1-2）──

    @Test fun end_llm_representation_exact_text() {
        // 图纸 §3.2 锁定措辞（逐字反推·只露时长）。
        assertEquals(
            "[系统记录：线下见面结束（约40分钟），你们回到了线上聊天]",
            OfflineMarkerEndPayload("约40分钟", "16:00", "你们自然地结束了这次见面").llmRepresentation(),
        )
    }

    @Test fun end_llm_representation_drops_important_directive_and_reason() {
        // 【重要】指令段只服务见面刚结束那一轮，不该永驻历史；结束原因也不进普通聊天窗口。
        val line = OfflineMarkerEndPayload("约40分钟", "16:00", "用户主动结束了这次见面").llmRepresentation()
        assertFalse("留痕行不应带【重要】指令段，实际：$line", line.contains("【重要】"))
        assertFalse(line.contains("用户主动结束了这次见面"))
        assertTrue("留痕行须以 [系统记录： 开头且含「线下见面结束」（供复读折叠）", line.startsWith("[系统记录：") && line.contains("线下见面结束"))
    }

    // ── stripOfflineMarkerLabel ──

    @Test fun strip_label_handles_both_colon_widths() {
        assertEquals("咖啡馆", stripOfflineMarkerLabel("地点：咖啡馆", "地点")) // 全角
        assertEquals("咖啡馆", stripOfflineMarkerLabel("地点:咖啡馆", "地点")) // 半角
        assertNull(stripOfflineMarkerLabel("活动：散步", "地点"))               // 不匹配
    }
}
