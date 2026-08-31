package com.situ.aichat.offline

import com.situ.aichat.data.calendar.CalendarAction
import com.situ.aichat.data.calendar.CalendarActionType
import com.situ.aichat.data.remote.llm.ToolDefinitionDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structured tool-definition schema tests (S1) — `OfflineMeetingAction.toolDefinitions` /
 * `CalendarAction.toolDefinitions`, reverse-derived from iOS `OfflineMeetingActionTests` +
 * `CalendarActionTests`, plus a wire-serialization check (explicitNulls/encodeDefaults = production).
 */
class OfflineToolDefinitionsTest {

    // 与 NetworkModule.provideJson 同配置：null 字段省略、默认值不编码。
    private val wireJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

    private fun tool(defs: List<ToolDefinitionDto>, name: String) = defs.first { it.function.name == name }

    // ── OfflineMeetingAction.toolDefinitions ──

    @Test fun offline_exposes_suggest_and_end_tools() {
        val defs = OfflineMeetingAction.toolDefinitions
        assertEquals(2, defs.size)

        val suggest = tool(defs, "suggest_offline_meeting")
        assertEquals("function", suggest.type)
        assertEquals(
            listOf("activity", "hidden_tension", "invitation", "location", "tension_hint"),
            suggest.function.parameters.required?.sorted(),
        )
        for (key in listOf("location", "activity", "invitation", "hidden_tension", "tension_hint")) {
            assertEquals("string", suggest.function.parameters.properties[key]?.type)
        }

        val end = tool(defs, "end_offline_meeting")
        assertEquals("function", end.type)
        assertEquals(listOf("final_mood"), end.function.parameters.required)
        assertEquals("string", end.function.parameters.properties["final_mood"]?.type)
        assertEquals(
            listOf("warm", "sweet", "melancholic", "awkward", "neutral"),
            end.function.parameters.properties["final_mood"]?.enumValues,
        )
        // farewell 不再是工具参数（iOS 已废弃）
        assertNull(end.function.parameters.properties["farewell"])
    }

    @Test fun offline_canInitiate_false_drops_suggest_keeps_end() {
        val defs = OfflineMeetingAction.toolDefinitions(canInitiate = false)
        assertEquals(1, defs.size)
        assertEquals("end_offline_meeting", defs[0].function.name)
        // 默认 / canInitiate=true 仍是完整两件套
        assertEquals(2, OfflineMeetingAction.toolDefinitions(canInitiate = true).size)
    }

    // ── 知情邀约规则（留痕改造 2026-08-31·图纸 §7 T1-5）──

    @Test fun both_guard_prompts_end_with_informed_invite_rules_and_keep_existing_content() {
        // 双模式共用同一段规则，且**拼在末尾**（声明序错位会静默拼进空值 → endsWith 直接红）。
        assertTrue(OfflineMeetingAction.TOOL_CALLING_PROMPT.endsWith(OfflineMeetingAction.INFORMED_INVITE_RULES))
        assertTrue(OfflineMeetingAction.FALLBACK_PROMPT.endsWith(OfflineMeetingAction.INFORMED_INVITE_RULES))
        // 规则本体逐字（双保险 pin：字面 + 与实现常量的包含关系）。
        assertTrue(OfflineMeetingAction.INFORMED_INVITE_RULES.startsWith("【邀约的分寸】"))
        // 首段引用句 2026-08-31 随留痕行改双名第三人称同步（原「你向…」制式已出局）：规则必须告诉模型
        // 「记录里一律用名字相称，你的名字指的就是你自己」，否则第三人称留痕行会被读成别人做的事。
        assertTrue(
            "首段引用句缺失，实际：${OfflineMeetingAction.INFORMED_INVITE_RULES}",
            OfflineMeetingAction.INFORMED_INVITE_RULES.contains("记录里一律用名字相称，你的名字指的就是你自己"),
        )
        assertFalse(
            "规则不得残留「你向…」制式引用句",
            OfflineMeetingAction.INFORMED_INVITE_RULES.contains("[系统记录：你向"),
        )
        for (rule in listOf(
            "上一次邀约的状态还是「还没回应」时，不要再发起新的邀约",
            "但邀约台词必须体现出你记得这件事",
            "对方连续两次婉拒之后，不要再发起邀约",
        )) {
            assertTrue("规则缺失：$rule", OfflineMeetingAction.INFORMED_INVITE_RULES.contains(rule))
        }
        // 既有段落原样健在（各抽一独有句）——追加不得吃掉原文。
        assertTrue(OfflineMeetingAction.TOOL_CALLING_PROMPT.contains("必须调用 suggest_offline_meeting 工具"))
        assertTrue(OfflineMeetingAction.FALLBACK_PROMPT.contains("[offline_invite|附近的咖啡店|喝咖啡聊天|走吧，我知道一家不错的咖啡厅~]"))
    }

    @Test fun suggest_description_narrows_situation_two_and_points_at_stand_in_records() {
        val suggest = tool(OfflineMeetingAction.toolDefinitions, "suggest_offline_meeting")
        val description = suggest.function.description
        // 情形 2 收窄为「用户刚刚明确说要现在见」（原「用户邀请你见面而你同意」过宽）。
        assertTrue("实际：$description", description.contains("JUST clearly said"))
        assertFalse(description.contains("The user invites you to meet and you agree"))
        // 调工具前先看自己的留痕记录（与聊天规则互指）。
        assertTrue("实际：$description", description.contains("still unanswered or was just declined"))
        assertTrue(description.contains("线下见面邀约"))
    }

    // ── CalendarAction.toolDefinitions ──

    @Test fun calendar_exposes_single_action_tool_with_full_enum() {
        val defs = CalendarAction.toolDefinitions
        assertEquals(1, defs.size)
        val t = defs[0]
        assertEquals("function", t.type)
        assertEquals("calendar_action", t.function.name)
        assertEquals(listOf("action", "title"), t.function.parameters.required)
        // action 枚举 = 全部 CalendarActionType raw
        assertEquals(
            CalendarActionType.entries.map { it.raw },
            t.function.parameters.properties["action"]?.enumValues,
        )
    }

    // ── wire serialization (null omission / enum key / type present) ──

    @Test fun serialization_omits_nulls_and_keeps_enum_and_type() {
        val json = wireJson.encodeToString(ListSerializer(ToolDefinitionDto.serializer()), OfflineMeetingAction.toolDefinitions)

        assertTrue(json.contains("\"type\":\"function\""))
        assertTrue(json.contains("\"type\":\"object\""))
        assertTrue(json.contains("\"name\":\"suggest_offline_meeting\""))
        assertTrue(json.contains("\"name\":\"end_offline_meeting\""))
        // enum 仅在有值的属性上出现
        assertTrue(json.contains("\"enum\":[\"warm\",\"sweet\",\"melancholic\",\"awkward\",\"neutral\"]"))
        // null 字段省略：不出现裸 null（required/enum/description 为 null 时不应写出）
        assertFalse(json.contains(":null"))
        assertFalse(json.contains("\"enum\":null"))
        assertFalse(json.contains("\"required\":null"))
    }
}
