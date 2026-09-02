package com.situ.aichat.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词模块编辑重设计 · Phase 1 的纯函数单测（[PromptBuilder.defaultModuleTemplate]）。
 *
 * 锁定"数据类模块默认模板 = 单个整块宏"的映射 + 数据/可编辑分类（15 / 8）。配合 [PromptMacrosTest] 已证明的
 * 「单宏替换 ≡ 该宏 producer」，即得数据类装配「输出按构造与旧 buildXxx 逐字节等价、零漂移」（契约 §3 红线）。
 */
class PromptModuleTemplateTest {

    /** 数据类 15 个 → 其整块宏（与 buildModuleContent 旧 when 派发的 builder 一一对应；卷二 +OUR_DAYS）。 */
    private val dataExpected = mapOf(
        SystemModuleType.CHARACTER_IDENTITY to PromptMacros.CHAR_PROFILE,
        SystemModuleType.CHARACTER_GROWTH to PromptMacros.CHAR_GROWTH,
        SystemModuleType.USER_PERSONA to PromptMacros.USER_PERSONA,
        SystemModuleType.CHARACTER_MEMORY to PromptMacros.CHAR_MEMORY,
        SystemModuleType.TIME_AWARENESS to PromptMacros.TIME_CONTEXT,
        SystemModuleType.CALENDAR_AWARENESS to PromptMacros.USER_CALENDAR,
        SystemModuleType.SCHEDULE_AWARENESS to PromptMacros.SCHEDULE_TODAY,
        SystemModuleType.CURRENT_MOMENT to PromptMacros.CURRENT_MOMENT,
        SystemModuleType.MOMENTS_CONTEXT to PromptMacros.MOMENTS_CONTEXT,
        SystemModuleType.STICKER_LIBRARY to PromptMacros.STICKER_LIBRARY,
        SystemModuleType.PET_STATUS to PromptMacros.PET_STATUS,
        SystemModuleType.OFFLINE_MEETING_MEMORY to PromptMacros.MEETING_MEMORY,
        SystemModuleType.OUR_DAYS to PromptMacros.OUR_DAYS, // 「我们的日子」卷二
        SystemModuleType.GIFT_HISTORY to PromptMacros.GIFT_HISTORY,
        SystemModuleType.CHARACTER_ECONOMIC_STATE to PromptMacros.ECONOMIC_STATE,
    )

    /** 可编辑 / 纯用户模块 8 个 → 无块宏默认（字面模板留 Phase 2）。 */
    private val editableTypes = listOf(
        SystemModuleType.CORE_RULES,
        SystemModuleType.SCENARIO,
        SystemModuleType.RESPONSE_STYLE,
        SystemModuleType.CHAT_FORMAT,
        SystemModuleType.QUALITY_CONTROL,
        SystemModuleType.MOOD_EXPRESSION,
        SystemModuleType.GENERAL_INSTRUCTIONS,
        SystemModuleType.BUSY_REPLY_INSTRUCTION,
    )

    @Test fun `data modules map to their expected block macro`() {
        for ((type, macro) in dataExpected) {
            assertEquals("数据类 $type 默认模板应为其整块宏", macro, PromptBuilder.defaultModuleTemplate(type))
        }
    }

    @Test fun `editable modules have no block-macro default`() {
        for (type in editableTypes) {
            assertNull("$type 应留 null（字面默认模板待 Phase 2）", PromptBuilder.defaultModuleTemplate(type))
        }
    }

    @Test fun `every data default is exactly one well-formed macro`() {
        for ((type, _) in dataExpected) {
            val t = PromptBuilder.defaultModuleTemplate(type)!!
            assertTrue("以 {{ 开头：$type→$t", t.startsWith("{{"))
            assertTrue("以 }} 结尾：$type→$t", t.endsWith("}}"))
            // 单宏：只出现一处 "{{"
            assertEquals("$type 默认模板须为单个宏", 1, Regex("\\{\\{").findAll(t).count())
        }
    }

    @Test fun `classification covers all module types with no overlap`() {
        // 数据(15) ∪ 可编辑(8) = 全部 23；无遗漏、无交叠（「我们的日子」卷二 +1 数据类）
        assertEquals(15, dataExpected.size)
        assertEquals(8, editableTypes.size)
        assertEquals(SystemModuleType.entries.size, dataExpected.size + editableTypes.size)
        val classified = dataExpected.keys + editableTypes.toSet()
        assertEquals("每个 SystemModuleType 都被分类", SystemModuleType.entries.toSet(), classified)
    }

    @Test fun `data types resolve via defaultModuleTemplate and editable via null`() {
        // 与 buildModuleContent 的分流条件对账：恰好数据类非 null、可编辑类 null
        val nonNull = SystemModuleType.entries.filter { PromptBuilder.defaultModuleTemplate(it) != null }.toSet()
        assertEquals(dataExpected.keys, nonNull)
    }
}
