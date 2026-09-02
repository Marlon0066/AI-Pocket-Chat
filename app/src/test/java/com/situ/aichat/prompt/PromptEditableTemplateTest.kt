package com.situ.aichat.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 提示词模块编辑重设计 · Phase 1c：[PromptBuilder.defaultEditableTemplate] 的 Robolectric 单测（解析真实
 * `pb_*` 资源，默认 locale = en）。
 *
 * 守"可编辑 8 模块默认装配零漂移"红线——现有单测不覆盖可编辑模块输出，故这里专门锁：
 * - **纯文案模板不含任何宏** → 装配时 `resolveLazy` 是 no-op → 与旧"直连 builder 无宏解析"逐字节一致；
 * - 核心规则模板带 `{{char}}`/`{{user}}` 且能解析回真名（≡ 旧的直接传名字）；
 * - 受解析器强耦合的情绪格式行、忙碌场景宏原样保留。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptEditableTemplateTest {

    private fun strings() = PromptStrings(RuntimeEnvironment.getApplication())

    private val editableTypes = listOf(
        SystemModuleType.CORE_RULES,
        SystemModuleType.CHAT_FORMAT,
        SystemModuleType.RESPONSE_STYLE,
        SystemModuleType.QUALITY_CONTROL,
        SystemModuleType.MOOD_EXPRESSION,
        SystemModuleType.GENERAL_INSTRUCTIONS,
        SystemModuleType.BUSY_REPLY_INSTRUCTION,
        SystemModuleType.SCENARIO,
    )

    /** 纯文案模板（无名字/场景宏）：装配 resolveLazy 必须是 no-op，等价才成立。 */
    private val stringOnlyTypes = listOf(
        SystemModuleType.CHAT_FORMAT,
        SystemModuleType.RESPONSE_STYLE,
        SystemModuleType.QUALITY_CONTROL,
        SystemModuleType.MOOD_EXPRESSION,
        SystemModuleType.GENERAL_INSTRUCTIONS,
    )

    @Test fun `editable types yield a template, scenario empty and others non-blank`() {
        val s = strings()
        for (type in editableTypes) {
            val t = PromptBuilder.defaultEditableTemplate(type, s)
            assertTrue("$type 应有模板", t != null)
            if (type == SystemModuleType.SCENARIO) {
                assertEquals("互动场景默认空", "", t)
            } else {
                assertTrue("$type 模板不应空白", t!!.isNotBlank())
            }
        }
    }

    @Test fun `data modules have no editable template`() {
        val s = strings()
        val dataTypes = SystemModuleType.entries.filter { it !in editableTypes }
        assertEquals(15, dataTypes.size) // 「我们的日子」卷二 +OUR_DAYS（数据类）
        for (type in dataTypes) {
            assertNull("$type 走整块宏，不应有可编辑模板", PromptBuilder.defaultEditableTemplate(type, s))
        }
    }

    @Test fun `string-only templates carry no macros (assembly resolve is a no-op == old output)`() {
        val s = strings()
        for (type in stringOnlyTypes) {
            val tpl = PromptBuilder.defaultEditableTemplate(type, s)!!
            assertFalse("$type 纯文案模板不应含宏（含则装配会改写、破坏零漂移）", tpl.contains("{{"))
        }
    }

    @Test fun `core rules template carries char and user macros and resolves to real names`() {
        val tpl = PromptBuilder.defaultEditableTemplate(SystemModuleType.CORE_RULES, strings())!!
        assertTrue("应带 {{char}}", tpl.contains("{{char}}"))
        assertTrue("应带 {{user}}", tpl.contains("{{user}}"))
        val resolved = PromptMacros.resolveLazy(
            tpl,
            mapOf(PromptMacros.CHAR to { "凛" }, PromptMacros.USER to { "小柚" }),
        )
        assertTrue("解析后含角色名", resolved.contains("凛"))
        assertTrue("解析后含用户名", resolved.contains("小柚"))
        assertFalse("解析后不应残留任何宏", resolved.contains("{{"))
    }

    @Test fun `mood template preserves the parser-coupled format line`() {
        val tpl = PromptBuilder.defaultEditableTemplate(SystemModuleType.MOOD_EXPRESSION, strings())!!
        assertTrue("情绪标注格式行必须原样保留（解析器强耦合）", tpl.contains("[mood:"))
    }

    @Test fun `busy reply template carries its scene macros`() {
        val tpl = PromptBuilder.defaultEditableTemplate(SystemModuleType.BUSY_REPLY_INSTRUCTION, strings())!!
        assertTrue(tpl.contains("{{busy_activity}}"))
        assertTrue(tpl.contains("{{user_pending_messages}}"))
        assertTrue(tpl.contains("{{user}}"))
    }
}
