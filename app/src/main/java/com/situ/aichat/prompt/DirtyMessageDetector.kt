package com.situ.aichat.prompt

import com.situ.aichat.data.model.MessageKind

/**
 * 1:1 port of iOS `DirtyMessageDetector`. Identifies "LLM parroted a structured format" garbage messages
 * that entered history as plainText (offline invite formats, JSON, system-record labels, memory/schedule
 * format repeats, XML metadata). Used by PromptBuilder history filtering + UI folding.
 *
 * Design: only `PLAIN_TEXT` is checked; "prefer false-negative over false-positive" (markdown schema needs
 * BOTH tool name + field name; JSON needs `{` prefix + type signature; memory needs both section headers).
 */
object DirtyMessageDetector {

    enum class Reason(val raw: String) {
        LEGACY_NATURAL_LANGUAGE("legacy_natural_language"),
        MARKDOWN_TOOL_SCHEMA("markdown_tool_schema"),
        RAW_JSON("raw_json"),
        SYSTEM_RECORD_LABEL("system_record_label"),
        MARKER_TEXT_REPEAT("marker_text_repeat"),
        XML_METADATA_REPEAT("xml_metadata_repeat"),
        MEMORY_FORMAT_REPEAT("memory_format_repeat"),
        MEETING_MEMORY_FORMAT_REPEAT("meeting_memory_format_repeat"),
        SCHEDULE_LIST_REPEAT("schedule_list_repeat"),
        PROMISE_LEDGER_REPEAT("promise_ledger_repeat"),
        IN_SCENE_RECAP_REPEAT("in_scene_recap_repeat"),
        WORLD_CONTEXT_REPEAT("world_context_repeat"),
    }

    /** 命中返回对应 Reason；未命中或非 plainText 返回 null。检测顺序：最特定 → 最宽松。 */
    fun detect(content: String, kind: MessageKind): Reason? {
        if (kind != MessageKind.PLAIN_TEXT) return null
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return null

        if (matchesXMLMetadataRepeat(trimmed)) return Reason.XML_METADATA_REPEAT
        if (matchesSystemRecordLabel(trimmed)) return Reason.SYSTEM_RECORD_LABEL
        if (matchesMarkerTextRepeat(trimmed)) return Reason.MARKER_TEXT_REPEAT
        if (matchesMemoryFormatRepeat(trimmed)) return Reason.MEMORY_FORMAT_REPEAT
        if (matchesMeetingMemoryFormatRepeat(trimmed)) return Reason.MEETING_MEMORY_FORMAT_REPEAT
        if (matchesScheduleListRepeat(trimmed)) return Reason.SCHEDULE_LIST_REPEAT
        if (matchesPromiseLedgerRepeat(trimmed)) return Reason.PROMISE_LEDGER_REPEAT
        if (matchesInSceneRecapRepeat(trimmed)) return Reason.IN_SCENE_RECAP_REPEAT
        if (matchesWorldContextRepeat(trimmed)) return Reason.WORLD_CONTEXT_REPEAT
        if (matchesRawInviteJSON(trimmed)) return Reason.RAW_JSON
        if (matchesMarkdownToolSchema(trimmed)) return Reason.MARKDOWN_TOOL_SCHEMA
        if (matchesLegacyNaturalLanguage(trimmed)) return Reason.LEGACY_NATURAL_LANGUAGE

        return null
    }

    fun isDirty(content: String, kind: MessageKind): Boolean = detect(content, kind) != null

    // MARK: - 私有匹配

    private fun matchesLegacyNaturalLanguage(s: String): Boolean {
        val patterns = listOf(
            "发出了一张线下见面邀请",
            "线下见面邀请卡片",
            "发起了线下见面邀请",
            "向对方发起线下见面",
            "线下见面结束卡片",
            "结束见面确认卡片",
        )
        return patterns.any { s.contains(it) }
    }

    private fun matchesMarkdownToolSchema(s: String): Boolean {
        val hasToolName = s.contains("suggest_offline_meeting") || s.contains("end_offline_meeting")
        if (!hasToolName) return false
        val fieldMarkers = listOf(
            "`activity`", "`location`", "`invitation`",
            "`hidden_tension`", "`tension_hint`", "`final_mood`",
        )
        return fieldMarkers.any { s.contains(it) }
    }

    private fun matchesRawInviteJSON(s: String): Boolean {
        if (!s.startsWith("{")) return false
        val signatures = listOf(
            "\"type\":\"offline_invite\"",
            "\"type\": \"offline_invite\"",
            "\"type\":\"offline_end\"",
            "\"type\": \"offline_end\"",
        )
        return signatures.any { s.contains(it) }
    }

    /**
     * `[系统记录：…]` 标签复读（强耦合·改任一侧必须同步）：markers 与各留痕行的产出方单源对齐——
     * 「线下见面邀约」← [com.situ.aichat.data.model.OfflineInviteData.llmRepresentation]（留痕改造 2026-08-31 新增，
     * 该行**永不含「邀约卡片」连写**，故必须单列此 marker）；「线下见面结束」← 既有离场留痕
     * [com.situ.aichat.offline.OfflineMarkerEndPayload.llmRepresentation] 与老措辞共用。
     */
    private fun matchesSystemRecordLabel(s: String): Boolean {
        if (!s.startsWith("[系统记录")) return false
        val markers = listOf("线下见面邀约卡片", "线下见面邀约", "结束见面确认卡片", "线下见面结束", "线下见面 |")
        return markers.any { s.contains(it) }
    }

    private fun matchesMarkerTextRepeat(s: String): Boolean {
        if (s.contains("【线下见面开始 |")) return true
        if (s.contains("【线下见面结束 |")) return true
        if (s.contains("【今日场景种子】")) return true
        return false
    }

    private fun matchesMemoryFormatRepeat(s: String): Boolean =
        s.contains("【长期事实】") && s.contains("【近期经历】")

    private fun matchesMeetingMemoryFormatRepeat(s: String): Boolean =
        s.contains("【见面 · ") || s.contains("【见面·")

    private fun matchesScheduleListRepeat(s: String): Boolean =
        s.contains("【你今天完整的日程】")

    /**
     * 【我们的约定】注入块复读（记忆改造一期·部件①·§3.3-D 强耦合）：AI 把注入的约定清单块头逐字吐回历史 = 脏。
     * 标题字面 `【我们的约定】` 与 [com.situ.aichat.promise.PromiseInjectionRenderer] 的标题行**单一真源同步**——
     * 改注入标题必须同步改此匹配（以及 `pb_mem_format_ban` 两语言枚举）。
     */
    private fun matchesPromiseLedgerRepeat(s: String): Boolean =
        s.contains("【我们的约定】")

    /**
     * 【前情提要】场内前情提要块复读（记忆改造二期·部件⑤·§3.2-D 强耦合）：AI 把注入的场内前情提要块头逐字吐回历史 = 脏。
     * 标题字面 `【前情提要】` 与 [com.situ.aichat.prompt.memory.InSceneRecapCoordinator.RECAP_HEADER]（PromptBuilder 2.15
     * 注入端）**单一真源同步**——改注入标题必须同步改此匹配。
     */
    private fun matchesInSceneRecapRepeat(s: String): Boolean =
        s.contains("【前情提要】")

    /**
     * W5 世界联动上下文提炼行复读（§6 强耦合·与 [com.situ.aichat.world.link.WorldRelationshipDigest] §4.1
     * 提炼行 `【与{对端名}｜{types}】` 双侧同步）：AI 把注入的关系提炼头逐字吐回历史 = 脏。正则纯字符类、无
     * look-behind → ICU（Android）/JVM 行为一致（避 [reference_android_icu_regex_pitfalls] 变长回顾坑）。
     * **今后改注入格式必须同步改此正则**（本图纸即登记处）。
     */
    private fun matchesWorldContextRepeat(s: String): Boolean =
        WORLD_CONTEXT_REPEAT_REGEX.containsMatchIn(s)

    private val WORLD_CONTEXT_REPEAT_REGEX = Regex("【与[^】\n]{1,12}｜[^】\n]{1,16}】")

    private fun matchesXMLMetadataRepeat(s: String): Boolean {
        val tags = listOf(
            "<recent_offline_events",
            "</recent_offline_events>",
            "<current_state",
            "</current_state>",
            "<pending_invite",
            "<in_offline_meeting",
            "<event time=",
            "<event kind=",
        )
        return tags.any { s.contains(it) }
    }
}
