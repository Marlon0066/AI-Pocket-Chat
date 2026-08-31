package com.situ.aichat.prompt.memory

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 面向 LLM 的时间戳格式锁定（图纸 2026-09-01「记忆与防污染加固批」件⑤·T1-7·PITFALLS §1c）。
 * 断言从规格独立反推：无论系统默认 Locale 是中文还是英文，输出恒为 "yyyy-MM-dd HH:mm"（E23）——
 * 旧实现走 `DateFormat.getBestDateTimePattern(Locale.getDefault(), …)`，切英文/繁体即漂成别的骨架。
 * 纯 JVM（不再依赖 android.text.format.DateFormat）。
 */
class MemoryServiceTimestampTest {

    private val original: Locale = Locale.getDefault()

    @After fun restore() = Locale.setDefault(original)

    @Test fun format_isStableAcrossDefaultLocales() {
        val millis = 1_700_000_000_000L
        Locale.setDefault(Locale.CHINA)
        val zh = MemoryService.formatTimestamp(millis)
        Locale.setDefault(Locale.US)
        val en = MemoryService.formatTimestamp(millis)
        Locale.setDefault(Locale.TAIWAN)
        val tw = MemoryService.formatTimestamp(millis)
        assertEquals(zh, en)
        assertEquals(zh, tw)
    }

    @Test fun format_matchesLockedPattern() {
        val pattern = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$""")
        for (locale in listOf(Locale.CHINA, Locale.US, Locale.JAPAN, Locale.forLanguageTag("ar-EG"))) {
            Locale.setDefault(locale)
            val text = MemoryService.formatTimestamp(1_700_000_000_000L)
            assertTrue("locale=$locale 输出=$text", pattern.matches(text))
        }
    }

    /** 阿拉伯语等 locale 会把数字渲染成本地数字（٢٠٢٣）——Locale.ROOT 必须挡住这一层。 */
    @Test fun format_usesAsciiDigits() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        val text = MemoryService.formatTimestamp(1_700_000_000_000L)
        assertTrue("输出=$text", text.all { it.isDigit() && it in '0'..'9' || it == '-' || it == ':' || it == ' ' })
    }
}
