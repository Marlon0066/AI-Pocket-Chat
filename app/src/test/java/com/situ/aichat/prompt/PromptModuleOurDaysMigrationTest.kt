package com.situ.aichat.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1-5（「我们的日子」卷二图纸 §7.2）：ourDays 模块一次性归位（[PromptModuleService.migrateOurDaysAfterMeetingMemory] /
 * [PromptModuleService.migratePromptModuleOurDays]）。断言从图纸 §3.2 四分支 + E50 独立反推（非照抄实现）：
 * 缺席插缝 / 缺席且见面记忆缺席追加末尾 / 在追加位归位 / 用户挪过不动 / 已在位不动 / 幂等 / 包装三态 / 坏 JSON 不抛。
 */
class PromptModuleOurDaysMigrationTest {

    private val PREFIX = PromptModulePosition.PREFIX
    private val SUFFIX = PromptModulePosition.SUFFIX

    private fun mod(type: SystemModuleType, order: Int, position: PromptModulePosition = PREFIX) = PromptModule(
        id = "id-${type.name}",
        name = type.displayName,
        sortOrder = order,
        systemModuleType = type,
        position = position,
        isSystemGenerated = true,
    )

    private fun List<PromptModule>.orderOf(type: SystemModuleType) = first { it.systemModuleType == type }.sortOrder
    private fun List<PromptModule>.of(type: SystemModuleType) = first { it.systemModuleType == type }
    private fun List<PromptModule>.assertOurDaysRightAfterMeetingMemory() {
        val sorted = sortedBy { it.sortOrder }
        val mm = sorted.indexOfFirst { it.systemModuleType == SystemModuleType.OFFLINE_MEETING_MEMORY }
        assertEquals("排序后我们的日子紧跟见面记忆", SystemModuleType.OUR_DAYS, sorted[mm + 1].systemModuleType)
        val orders = map { it.sortOrder }
        assertEquals("无并列 sortOrder", orders.size, orders.toSet().size)
    }

    // ── 分支 ①：缺席 + 见面记忆在 PREFIX → 插缝到正后，其余 >= target 整体 +1 ──

    @Test fun absent_insertsAfterMeetingMemory_shiftsRest_noTies() {
        val mods = mutableListOf(
            mod(SystemModuleType.CORE_RULES, 0),
            mod(SystemModuleType.CHARACTER_MEMORY, 5),
            mod(SystemModuleType.OFFLINE_MEETING_MEMORY, 6),
            mod(SystemModuleType.CALENDAR_AWARENESS, 7),
            mod(SystemModuleType.SCHEDULE_AWARENESS, 8),
            mod(SystemModuleType.RESPONSE_STYLE, 10, SUFFIX),
        )
        assertTrue(PromptModuleService.migrateOurDaysAfterMeetingMemory(mods))
        assertEquals(7, mods.orderOf(SystemModuleType.OUR_DAYS))
        assertEquals(6, mods.orderOf(SystemModuleType.OFFLINE_MEETING_MEMORY))
        assertEquals(8, mods.orderOf(SystemModuleType.CALENDAR_AWARENESS))
        assertEquals(9, mods.orderOf(SystemModuleType.SCHEDULE_AWARENESS))
        assertEquals(11, mods.orderOf(SystemModuleType.RESPONSE_STYLE))
        assertEquals(0, mods.orderOf(SystemModuleType.CORE_RULES))
        mods.assertOurDaysRightAfterMeetingMemory()
    }

    @Test fun absent_insertedModuleHasReconcileFields() {
        val mods = mutableListOf(mod(SystemModuleType.CHARACTER_MEMORY, 5), mod(SystemModuleType.OFFLINE_MEETING_MEMORY, 6))
        assertTrue(PromptModuleService.migrateOurDaysAfterMeetingMemory(mods))
        val od = mods.of(SystemModuleType.OUR_DAYS)
        assertEquals("10000001-0000-0000-0000-000000000017", od.id)
        assertEquals("我们的日子", od.name)
        assertEquals(PREFIX, od.position)
        assertEquals(setOf(PromptScene.ONLINE_CHAT, PromptScene.VOICE_CALL), od.enabledScenes)
        assertTrue(od.isSystemGenerated)
        assertTrue(od.isEnabled)
        assertEquals("", od.content)
    }

    // ── 分支 ②：缺席 + 见面记忆缺席 / 不在 PREFIX → 追加末尾 ──

    @Test fun absent_meetingMemoryAbsent_appendsToEnd() {
        val mods = mutableListOf(mod(SystemModuleType.CORE_RULES, 0), mod(SystemModuleType.CHARACTER_MEMORY, 5))
        assertTrue(PromptModuleService.migrateOurDaysAfterMeetingMemory(mods))
        assertEquals(6, mods.orderOf(SystemModuleType.OUR_DAYS))
        assertEquals(3, mods.size)
    }

    @Test fun absent_meetingMemoryInSuffix_appendsToEnd() {
        val mods = mutableListOf(
            mod(SystemModuleType.CORE_RULES, 0),
            mod(SystemModuleType.OFFLINE_MEETING_MEMORY, 3, SUFFIX), // 用户把见面记忆挪去后置 → 无 PREFIX 锚点
        )
        assertTrue(PromptModuleService.migrateOurDaysAfterMeetingMemory(mods))
        assertEquals(4, mods.orderOf(SystemModuleType.OUR_DAYS))
        assertEquals("后置的见面记忆不动", 3, mods.orderOf(SystemModuleType.OFFLINE_MEETING_MEMORY))
    }

    // ── 分支 ③：在追加位（sortOrder 全表最大）→ 归位 ──

    @Test fun present_atAppendPosition_movesAfterMeetingMemory() {
        val mods = mutableListOf(
            mod(SystemModuleType.CHARACTER_MEMORY, 5),
            mod(SystemModuleType.OFFLINE_MEETING_MEMORY, 6),
            mod(SystemModuleType.CALENDAR_AWARENESS, 7),
            mod(SystemModuleType.CURRENT_MOMENT, 21, SUFFIX),
            mod(SystemModuleType.OUR_DAYS, 22), // reconcile 追加位
        )
        assertTrue(PromptModuleService.migrateOurDaysAfterMeetingMemory(mods))
        assertEquals(7, mods.orderOf(SystemModuleType.OUR_DAYS))
        assertEquals(8, mods.orderOf(SystemModuleType.CALENDAR_AWARENESS))
        assertEquals(22, mods.orderOf(SystemModuleType.CURRENT_MOMENT))
        assertEquals(6, mods.orderOf(SystemModuleType.OFFLINE_MEETING_MEMORY))
        mods.assertOurDaysRightAfterMeetingMemory()
    }

    // ── 分支 ④：不在追加位（用户挪过 / 已归位）→ false 不动 ──

    @Test fun present_userMoved_notAtMax_skipsUnchanged() {
        val mods = mutableListOf(
            mod(SystemModuleType.CORE_RULES, 0),
            mod(SystemModuleType.OUR_DAYS, 1), // 用户挪到最前
            mod(SystemModuleType.CHARACTER_MEMORY, 5),
            mod(SystemModuleType.OFFLINE_MEETING_MEMORY, 6),
            mod(SystemModuleType.CURRENT_MOMENT, 21, SUFFIX),
        )
        val before = mods.map { it.copy() }
        assertFalse(PromptModuleService.migrateOurDaysAfterMeetingMemory(mods))
        assertEquals("跳过时列表逐字不动", before, mods)
    }

    @Test fun present_atMax_meetingMemoryAbsent_skips() {
        val mods = mutableListOf(mod(SystemModuleType.CORE_RULES, 0), mod(SystemModuleType.OUR_DAYS, 22))
        val before = mods.map { it.copy() }
        assertFalse(PromptModuleService.migrateOurDaysAfterMeetingMemory(mods))
        assertEquals(before, mods)
    }

    @Test fun present_alreadyRightAfter_andIsMax_skips() {
        // 见面记忆恰是倒数第二 → 我们的日子既在追加位又已在正后 → 不动。
        val mods = mutableListOf(mod(SystemModuleType.CHARACTER_MEMORY, 5), mod(SystemModuleType.OFFLINE_MEETING_MEMORY, 6), mod(SystemModuleType.OUR_DAYS, 7))
        assertFalse(PromptModuleService.migrateOurDaysAfterMeetingMemory(mods))
        assertEquals(7, mods.orderOf(SystemModuleType.OUR_DAYS))
    }

    // ── 幂等 ──

    @Test fun idempotent_secondRunIsNoOp() {
        val mods = mutableListOf(
            mod(SystemModuleType.CHARACTER_MEMORY, 5),
            mod(SystemModuleType.OFFLINE_MEETING_MEMORY, 6),
            mod(SystemModuleType.CALENDAR_AWARENESS, 7),
            mod(SystemModuleType.OUR_DAYS, 22),
        )
        assertTrue(PromptModuleService.migrateOurDaysAfterMeetingMemory(mods))
        val after = mods.map { it.copy() }
        assertFalse("归位后不在追加位 → 再跑必 false", PromptModuleService.migrateOurDaysAfterMeetingMemory(mods))
        assertEquals(after, mods)
    }

    @Test fun idempotent_absentPathThenSecondRun() {
        val mods = mutableListOf(mod(SystemModuleType.CHARACTER_MEMORY, 5), mod(SystemModuleType.OFFLINE_MEETING_MEMORY, 6), mod(SystemModuleType.CALENDAR_AWARENESS, 7))
        assertTrue(PromptModuleService.migrateOurDaysAfterMeetingMemory(mods))
        assertFalse(PromptModuleService.migrateOurDaysAfterMeetingMemory(mods))
    }

    // ── 包装三态 ──

    @Test fun wrapper_defaultModules_alreadyInPlace_returnsNull() {
        val globalJson = PromptModuleService.encodeModules(PromptModuleService.defaultModules())
        assertNull(PromptModuleService.migratePromptModuleOurDays(globalJson, ""))
    }

    /** 卷二之前的持久化 JSON：22 个模块（无 ourDays）·sortOrder = 0..21。 */
    private fun legacyModules(): List<PromptModule> =
        PromptModuleService.defaultModules()
            .filter { it.systemModuleType != SystemModuleType.OUR_DAYS }
            .mapIndexed { i, m -> m.copy(sortOrder = i) }

    @Test fun wrapper_staleGlobal_emptyChar_reordersGlobalOnly() {
        val legacy = legacyModules()
        assertEquals(22, legacy.size)
        val result = PromptModuleService.migratePromptModuleOurDays(PromptModuleService.encodeModules(legacy), "")
        assertNotNull(result)
        assertNotNull("全局有改动", result!!.first)
        assertNull("无角色覆盖", result.second)
        val reordered = PromptModuleService.loadGlobalModules(result.first!!)
        assertEquals(23, reordered.size)
        reordered.assertOurDaysRightAfterMeetingMemory()
        // 老默认序：见面记忆 6 → 我们的日子 7 → 日历感知 8（原 7 +1）。
        assertEquals(7, reordered.orderOf(SystemModuleType.OUR_DAYS))
        assertEquals(8, reordered.orderOf(SystemModuleType.CALENDAR_AWARENESS))
    }

    @Test fun wrapper_emptyGlobal_staleChar_reordersCharOnly() {
        val stale = listOf(mod(SystemModuleType.CHARACTER_MEMORY, 5), mod(SystemModuleType.OFFLINE_MEETING_MEMORY, 6), mod(SystemModuleType.CALENDAR_AWARENESS, 7))
        val custom = listOf(mod(SystemModuleType.OUR_DAYS, 0), mod(SystemModuleType.OFFLINE_MEETING_MEMORY, 6)) // 已挪过
        var dict = PromptModuleService.setCharacterModules("cA", stale, "")
        dict = PromptModuleService.setCharacterModules("cB", custom, dict)

        val result = PromptModuleService.migratePromptModuleOurDays(globalJson = "", characterJson = dict)
        assertNotNull(result)
        assertNull("全局空 → 不写回", result!!.first)
        assertNotNull("角色 dict 有改动 → 写回", result.second)
        val cA = PromptModuleService.loadCharacterModules("cA", result.second!!)!!
        assertEquals(7, cA.orderOf(SystemModuleType.OUR_DAYS))
        assertEquals(8, cA.orderOf(SystemModuleType.CALENDAR_AWARENESS))
        val cB = PromptModuleService.loadCharacterModules("cB", result.second!!)!!
        assertEquals("已自定义角色原样不动", 0, cB.orderOf(SystemModuleType.OUR_DAYS))
    }

    @Test fun wrapper_badJson_doesNotThrow_returnsNull() {
        assertNull(PromptModuleService.migratePromptModuleOurDays("not json at all", "{bad"))
    }
}
