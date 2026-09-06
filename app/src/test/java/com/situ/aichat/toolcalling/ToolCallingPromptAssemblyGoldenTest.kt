package com.situ.aichat.toolcalling

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.prompt.PromptBuilder
import com.situ.aichat.prompt.PromptStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Phase 0-1 黄金快照（双模式·①命根）：对一个代表性 [PromptBuilder.buildMessages] 装配（含日历事件 + 可主动线下 +
 * 约见面），在 `toolCallingEnabled = true / false` 两套模式下，断言**装配出的系统提示词**逐字节包含 Phase C 将要
 * 搬动的三段（日历感知 / 线下见面 / 约定未来见面），且各自的模式专属在/不在正确。
 *
 * 为何只钉这三段而非全文：这三段正是 ①（工具自包含盒子）会从 `PromptBuilder*` 搬进工具盒子的内容；用「装配输出
 * 逐字节包含冻结段」既看住「搬完字节不变」，又看住「装配在对的模式下注入对的段」（C-5 改 `buildChatToolDefinitions`/
 * `PromptBuilder` 遍历活跃工具的接线），同时不被无关模块（核心规则/时间感知等·并行 session 可能动）误伤。
 *
 * 仿 [com.situ.aichat.prompt.PromptBuilderFutureMeetingLeakTest] 的 Robolectric + buildMessages 装配法。
 * `now` 固定、`userProfile.nickname` 固定为「阿哲」（与 dump 同口径·有意区别于角色名「小雨」，使日历段的称呼无歧义）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolCallingPromptAssemblyGoldenTest {

    private val events = "[#E1] 开会（5月31日 14:00~15:00 · A会议室）"

    /** 装配出的系统消息内容列表（保留消息边界·供结构断言）。 */
    private fun assembledSystemMessages(
        toolCalling: Boolean,
        deliveryMode: PromptBuilder.AssistantDeliveryMode = PromptBuilder.AssistantDeliveryMode.TEXT,
    ): List<String> {
        val strings = PromptStrings(RuntimeEnvironment.getApplication())
        val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)
        val user = UserProfileEntity(nickname = "阿哲")
        val userMsg = MessageEntity(
            messageUUID = "u1", conversationUuid = "c1", roleRaw = "user", content = "在吗", timestamp = 1L,
        )
        val messages = PromptBuilder.buildMessages(
            character = character,
            sortedMessages = listOf(userMsg),
            userProfile = user,
            appSettings = AppSettings(), // calendarIntegrationEnabled / characterCanInitiateOfflineMeeting 默认 true
            strings = strings,
            calendarUpcomingEvents = events,
            toolCallingEnabled = toolCalling,
            now = Instant.ofEpochMilli(1_700_000_000_000L),
            assistantDeliveryMode = deliveryMode,
        )
        return messages.filter { it.role == "system" }.map { it.content.orEmpty() }
    }

    private fun assembledSystemText(toolCalling: Boolean): String =
        assembledSystemMessages(toolCalling).joinToString("\n")

    @Test fun tool_mode_assembly_freezes_tool_segments_and_drops_marker_howto() {
        val s = assembledSystemText(toolCalling = true)
        // 工具模式：日历感知段（无暗号教程）+ 线下工具版提示词，逐字节冻结。
        assertTrue("缺日历感知段(工具版)：\n$s", s.contains(GoldenResources.read("calendar_awareness_tool.txt")))
        assertTrue("缺线下工具版提示词", s.contains(GoldenResources.read("offline_tool.txt")))
        // 工具模式绝不注入暗号写入教程 / 约定未来见面文本规则（双登广告 H4·治 #1）。
        assertFalse("工具模式不该有【日历操作】", s.contains("【日历操作】"))
        assertFalse("工具模式不该有 [CALENDAR_ACTION]", s.contains("[CALENDAR_ACTION]"))
        assertFalse("工具模式不该有约定未来见面文本规则", s.contains(GoldenResources.read("future_rule.txt")))
    }

    @Test fun marker_mode_assembly_freezes_all_marker_segments() {
        val s = assembledSystemText(toolCalling = false)
        assertTrue("缺日历感知段(暗号版)：\n$s", s.contains(GoldenResources.read("calendar_awareness_marker.txt")))
        assertTrue("缺线下暗号版提示词", s.contains(GoldenResources.read("offline_fallback.txt")))
        assertTrue("缺约定未来见面文本规则", s.contains(GoldenResources.read("future_rule.txt")))
        assertTrue("缺约定记账文本规则", s.contains(GoldenResources.read("promise_rule.txt")))
    }

    // ── T1-9（图纸 2026-09-06 约定工具调用化·E6）：约定暗号规则的三态门 ──

    @Test fun promise_rule_onlyInMarkerMode_andNeverInVoiceCall() {
        val rule = GoldenResources.read("promise_rule.txt")
        // 暗号模式（文字回合）：恰一次，且排在约见面规则之后（registry 顺序 线下 → 约见 → 约定）。
        val markerMsgs = assembledSystemMessages(toolCalling = false)
        assertEquals("约定记账规则应在且仅一条系统消息里", 1, markerMsgs.count { it.contains(rule) })
        val guardCard = markerMsgs.single { it.contains(rule) }
        val future = GoldenResources.read("future_rule.txt")
        assertTrue("约定记账规则应与约见面规则同在守卫卡", guardCard.contains(future))
        assertTrue("卡内顺序：约见面在约定记账之前", guardCard.indexOf(future) < guardCard.indexOf(rule))
        // 工具模式：0 次（schema 已在 tools 数组下发·双登广告 H4）。
        assertEquals("工具模式不该有约定记账规则", 0, assembledSystemMessages(toolCalling = true).count { it.contains(rule) })
        // 语音通话回合（暗号模式）：0 次——通话侧无人解析暗号，注入只会让 JSON 被念出来。
        val voiceMsgs = assembledSystemMessages(toolCalling = false, deliveryMode = PromptBuilder.AssistantDeliveryMode.VOICE)
        assertEquals("通话回合不该有约定记账规则", 0, voiceMsgs.count { it.contains(rule) })
        // 正向锚（防「守卫卡整段没装配」的假绿）：同一通话回合里约见面规则照旧在。
        assertEquals("通话回合守卫卡本身应存在（约见面规则仍在）", 1, voiceMsgs.count { it.contains(future) })
    }

    // ── 结构看门（消息边界 + 顺序·堵 contains 看不住的「合并/改序/重复」缝·尤为 C-5 接线护栏） ──
    // 刀2 装订（2026-07-11 过审）后的边界契约：非线下时工具守卫段并入**守卫卡**（反元+风格+工具合并一条）——
    // 段内容仍逐字节冻结（contains），「独占一条」改为「在且仅一条系统消息里」+ 卡内相对序冻结。

    @Test fun marker_mode_message_structure_is_frozen() {
        val msgs = assembledSystemMessages(toolCalling = false)
        val offline = GoldenResources.read("offline_fallback.txt")
        val future = GoldenResources.read("future_rule.txt")
        assertEquals("线下暗号提示词应在且仅一条系统消息里", 1, msgs.count { it.contains(offline) })
        assertEquals("约定未来见面规则应在且仅一条系统消息里", 1, msgs.count { it.contains(future) })
        assertEquals("日历感知段(暗号版)应在且仅一条系统消息里", 1, msgs.count { it.contains(GoldenResources.read("calendar_awareness_marker.txt")) })
        // 两段同属守卫卡（含反元守卫），卡内顺序冻结：线下（step5）在 约定未来见面（step5）之前。
        val guardCard = msgs.single { it.contains(offline) }
        assertTrue("线下与约见规则应同在守卫卡", guardCard.contains(future))
        assertTrue("守卫卡应含反元守卫（刀2 装订）", guardCard.contains("绝对禁令"))
        assertTrue("卡内顺序：线下在约见之前", guardCard.indexOf(offline) < guardCard.indexOf(future))
    }

    @Test fun tool_mode_message_structure_is_frozen() {
        val msgs = assembledSystemMessages(toolCalling = true)
        assertEquals("线下工具版提示词应在且仅一条系统消息里", 1, msgs.count { it.contains(GoldenResources.read("offline_tool.txt")) })
        // 工具模式：约定未来见面规则绝不出现（独占或内嵌都不行）。
        assertEquals("工具模式不该有约定未来见面规则", 0, msgs.count { it.contains(GoldenResources.read("future_rule.txt")) })
        assertEquals("日历感知段(工具版)应在且仅一条系统消息里", 1, msgs.count { it.contains(GoldenResources.read("calendar_awareness_tool.txt")) })
    }
}
