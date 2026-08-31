package com.situ.aichat.prompt.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆编辑拆装纯函数（图纸 2026-09-01「记忆与防污染加固批」件③·T1-5）。
 *
 * 断言从规格独立反推：标准两节 → 分区编辑且往返规范化；无标题 / 有节前导语 → 整段退化（E15）；
 * 空文本不许保存（E13）。标题字面在此**重新打字**做双保险 pin：既钉住用户看到的文案，
 * 又与实现常量核对包含关系（段标题是提示词↔检测器强耦合面，漂一个字就静默坏）。
 */
class MemoryEditTextTest {

    private val longHeader = "【长期事实】"
    private val recentHeader = "【近期经历】"

    @Test fun headers_matchImplementationConstants() {
        assertEquals(longHeader, MemorySummarySections.LONG_TERM_HEADER)
        assertEquals(recentHeader, MemorySummarySections.RECENT_HEADER)
    }

    @Test fun standardTwoSections_splitsIntoSections() {
        val text = "$longHeader\n- 喜欢猫\n- 在学吉他\n$recentHeader\n- [2026-06-10] 去了公园"
        val mode = MemoryEditText.toMode(text)
        assertTrue("标准两节应进分区态", mode is MemoryEditMode.Sections)
        mode as MemoryEditMode.Sections
        assertEquals("- 喜欢猫\n- 在学吉他", mode.longTermText)
        assertEquals("- [2026-06-10] 去了公园", mode.recentText)
    }

    @Test fun sections_roundTrip_normalizesAndKeepsContent() {
        val text = "$longHeader\n- 喜欢猫\n$recentHeader\n- [2026-06-10] 去了公园"
        val composed = MemoryEditText.compose(MemoryEditText.toMode(text))
        // 规范化：两节之间恒一个空行；内容与标题一字不差地保留。
        assertEquals("$longHeader\n- 喜欢猫\n\n$recentHeader\n- [2026-06-10] 去了公园", composed)
        // 再拆一次必须稳定（二次往返不漂）。
        assertEquals(composed, MemoryEditText.compose(MemoryEditText.toMode(composed)))
    }

    @Test fun noHeaders_fallsBackToWhole() {
        // E15：老记忆没有标准分节 → 整段编辑，原文一字不改地端上来。
        val text = "她喜欢猫，最近在学吉他。"
        val mode = MemoryEditText.toMode(text)
        assertEquals(MemoryEditMode.Whole(text), mode)
        assertEquals(text, MemoryEditText.compose(mode))
    }

    @Test fun preambleBeforeFirstHeader_fallsBackToWhole() {
        // E15：节前有导语（unparsed 非空）→ 分区会丢掉导语，故退化整段，绝不静默吞用户的字。
        val text = "以下是我记得的事：\n$longHeader\n- 喜欢猫\n$recentHeader\n- 去了公园"
        val mode = MemoryEditText.toMode(text)
        assertTrue("有节前导语必须退化整段", mode is MemoryEditMode.Whole)
        assertTrue("导语必须还在", MemoryEditText.compose(mode).contains("以下是我记得的事："))
    }

    @Test fun onlyOneSection_stillSplits() {
        val text = "$longHeader\n- 喜欢猫"
        val mode = MemoryEditText.toMode(text)
        assertTrue(mode is MemoryEditMode.Sections)
        mode as MemoryEditMode.Sections
        assertEquals("- 喜欢猫", mode.longTermText)
        assertEquals("", mode.recentText)
        // 装配后空节只剩标题（用户随后可往里补内容）。
        assertEquals("$longHeader\n- 喜欢猫\n\n$recentHeader\n", MemoryEditText.compose(mode))
    }

    @Test fun canSave_rejectsEmptyContent() {
        // E13：清空记忆是「删除」语义，本期不提供 → 保存钮置灰，防误触抹掉全部记忆。
        assertFalse(MemoryEditText.canSave(MemoryEditMode.Whole("")))
        assertFalse(MemoryEditText.canSave(MemoryEditMode.Whole("   \n  ")))
        assertFalse(MemoryEditText.canSave(MemoryEditMode.Sections("", "")))
        assertFalse(MemoryEditText.canSave(MemoryEditMode.Sections("  ", "\n")))
        // 任一节有内容即可保存。
        assertTrue(MemoryEditText.canSave(MemoryEditMode.Sections("- 喜欢猫", "")))
        assertTrue(MemoryEditText.canSave(MemoryEditMode.Sections("", "- 去了公园")))
        assertTrue(MemoryEditText.canSave(MemoryEditMode.Whole("她喜欢猫")))
    }

    @Test fun pseudoHeaderInsideSectionText_doesNotCrashOrLoseText() {
        // 用户在正文里打了个像标题的行：拆装不崩，内容仍在（再拆时会被归进对应节，属可接受的规范化）。
        val mode = MemoryEditMode.Sections("- 喜欢猫\n$recentHeader 手打的一行", "- 去了公园")
        val composed = MemoryEditText.compose(mode)
        assertTrue(composed.contains("手打的一行"))
        assertTrue(MemoryEditText.toMode(composed) is MemoryEditMode.Sections)
    }
}
