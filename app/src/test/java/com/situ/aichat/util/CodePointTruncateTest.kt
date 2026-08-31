package com.situ.aichat.util

import com.situ.aichat.story.StoryChatInfluenceBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * codePoint 安全截断（图纸 2026-09-01「记忆与防污染加固批」件⑧·T1-8）。
 * 断言从规格独立反推：n ≤ 0 → 空；n ≥ codePoint 数 → 原样；截断点落在代理对中间时绝不产出孤立代理字符（E24）。
 * 「🙂」= U+1F642（一个 codePoint / 两个 UTF-16 char），故「a🙂b」的 length=4、codePointCount=3。
 */
class CodePointTruncateTest {

    private val emoji = "🙂" // 🙂 U+1F642

    @Test fun zeroOrNegative_returnsEmpty() {
        assertEquals("", "abc".takeCodePoints(0))
        assertEquals("", "abc".takeCodePoints(-1))
        assertEquals("", (emoji + emoji).takeCodePoints(0))
    }

    @Test fun limitAtOrAboveCount_returnsOriginal() {
        assertEquals("abc", "abc".takeCodePoints(3))
        assertEquals("abc", "abc".takeCodePoints(99))
        val text = "a$emoji" + "b" // 3 codePoints / 4 chars
        assertEquals(text, text.takeCodePoints(3))
        assertEquals(text, text.takeCodePoints(4))
    }

    @Test fun truncatesByCodePoint_neverSplitsSurrogatePair() {
        val text = "a$emoji" + "b"
        assertEquals("a", text.takeCodePoints(1))
        assertEquals("a$emoji", text.takeCodePoints(2)) // 按 char 截会得到「a\uD83D」半个 emoji
        assertNoLoneSurrogate(text.takeCodePoints(2))
    }

    @Test fun truncationBoundaryOnEmojiRun_hasNoLoneSurrogate() {
        val run = emoji.repeat(10) // 10 codePoints / 20 chars
        for (n in 1..9) {
            val cut = run.takeCodePoints(n)
            assertEquals(n, cut.codePointCount(0, cut.length))
            assertNoLoneSurrogate(cut)
        }
    }

    /** clipped 是 220/120/36 三处预算的共用入口（E24 真正落地点）。 */
    @Test fun storyHelpersClipped_isCodePointSafe() {
        val h = StoryChatInfluenceBuilder.Helpers
        val run = emoji.repeat(250)
        val clipped = h.clipped(run, 220)
        assertEquals(220, clipped.removeSuffix("…").codePointCount(0, clipped.length - 1))
        assertNoLoneSurrogate(clipped)
        // 恰好 220 个 codePoint（440 char）不该被误判为超限
        assertEquals(emoji.repeat(220), h.clipped(emoji.repeat(220), 220))
    }

    private fun assertNoLoneSurrogate(s: String) {
        s.forEachIndexed { i, c ->
            if (c.isHighSurrogate()) {
                assertFalse("尾部孤立高代理 @$i", i == s.lastIndex)
                assertFalse("高代理后非低代理 @$i", !s[i + 1].isLowSurrogate())
            }
            if (c.isLowSurrogate()) assertFalse("首部孤立低代理 @$i", i == 0 || !s[i - 1].isHighSurrogate())
        }
    }
}
