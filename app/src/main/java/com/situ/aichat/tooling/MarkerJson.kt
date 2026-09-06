package com.situ.aichat.tooling

/**
 * 回复末尾「暗号标记 + JSON」的配平花括号扫描（单源）。
 *
 * 原生于 [com.situ.aichat.meeting.FutureMeetingTool]，2026-09-06 约定工具调用化时抽出共用（`[future_meeting]{…}`
 * 与 `[promise]{…}` 两族暗号解析同一算法）——**只搬不改**，行为与原实现字节级一致
 * （回归钉 = `FutureMeetingToolTest.marker_embeddedBraceInValue_fullyParsedAndErased`）。
 */
internal object MarkerJson {

    /**
     * 从 [open]（须指向 `{`）起找**配平**的 `}`，跳过 JSON 字符串字面量内的花括号与 `\` 转义；找不到（不配平）返回 -1。
     * 让 `{"activity":"吃饭}聊天"}` 这类值内含 `}` 的合法 JSON 被完整提取，而非在串内 `}` 处误截断。
     */
    fun matchingBraceEnd(s: String, open: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in open until s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return -1
    }
}
