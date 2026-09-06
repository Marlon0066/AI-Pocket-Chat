package com.situ.aichat.prompt.diary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1（图纸 §7 T1-5·E10/E11）：写作规则取值单源 [DiaryRuleOverrides]。
 * 断言从图纸 §3.2 规格独立反推——字面替换永不抛异常、`{角色名}` 只在 TA 的信侧替换、
 * 字数恒传、三个文本项空则不进 map。
 */
class DiaryRuleOverridesTest {

    private fun values(
        wordCount: Int = 1000,
        narrativePerson: String = "",
        styleHint: String = "",
        extraRules: String = "",
    ) = DiaryRuleValues(wordCount, narrativePerson, styleHint, extraRules)

    @Test fun `含百分号的自定义文本原样进 map、不抛异常`() {
        // 裸 % 走 String.format 必抛 UnknownFormatConversionException；字面 replace 永不抛（J-4）。
        val out = DiaryRuleOverrides.toOverrides(
            values(styleHint = "把握 100% 的真诚，别写成 %s 那种模板腔"),
            userName = "小明",
            characterName = "小满",
        )
        assertEquals("把握 100% 的真诚，别写成 %s 那种模板腔", out[DiaryPromptField.STYLE_HINT.raw])
    }

    @Test fun `TA 的信侧两种占位都替换`() {
        val out = DiaryRuleOverrides.toOverrides(
            values(narrativePerson = "用「我」写{角色名}自己", styleHint = "多写对{用户名}的在意"),
            userName = "小明",
            characterName = "小满",
        )
        assertEquals("用「我」写小满自己", out[DiaryPromptField.NARRATIVE_PERSON.raw])
        assertEquals("多写对小明的在意", out[DiaryPromptField.STYLE_HINT.raw])
    }

    @Test fun `我的日记侧只替换用户名，角色名保持字面量`() {
        val out = DiaryRuleOverrides.toOverrides(
            values(styleHint = "{用户名}的口吻，别提{角色名}"),
            userName = "小明",
            characterName = null,
        )
        assertEquals("小明的口吻，别提{角色名}", out[DiaryPromptField.STYLE_HINT.raw])
    }

    @Test fun `字数恒传；三个文本项为空或纯空白时不进 map`() {
        val out = DiaryRuleOverrides.toOverrides(values(wordCount = 1500, styleHint = "   "), "小明", "小满")
        assertEquals("1500", out[DiaryPromptField.WORD_COUNT_RANGE.raw])
        assertFalse("纯空白不算自定义", out.containsKey(DiaryPromptField.STYLE_HINT.raw))
        assertFalse(out.containsKey(DiaryPromptField.NARRATIVE_PERSON.raw))
        assertFalse(out.containsKey(DiaryPromptField.EXTRA_RULES.raw))
        assertEquals("全默认时 map 里只有字数一项", 1, out.size)
    }

    @Test fun `补充规则多行原样保留（拆行与前缀由消费端做）`() {
        val out = DiaryRuleOverrides.toOverrides(values(extraRules = "别写天气\n\n多写手上的动作"), "小明", "小满")
        assertEquals("别写天气\n\n多写手上的动作", out[DiaryPromptField.EXTRA_RULES.raw])
    }

    @Test fun `占位替换本身 - 空文本与无占位文本原样返回`() {
        assertEquals("", DiaryRuleOverrides.applyRulePlaceholders("", "小明", "小满"))
        assertEquals("普通一句话", DiaryRuleOverrides.applyRulePlaceholders("普通一句话", "小明", "小满"))
        assertTrue(DiaryRuleOverrides.applyRulePlaceholders("{用户名}", "", null).isEmpty())
    }
}
