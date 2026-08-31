package com.situ.aichat.prompt.memory

/**
 * 记忆手动编辑的拆装纯函数（图纸 2026-09-01「记忆与防污染加固批」件③）。
 *
 * 拆：`memorySummary` → 编辑模式（标准两节 → 分区编辑；否则整段退化编辑）。
 * 装：编辑模式 → 回写文本。标题一律取 [MemorySummarySections] 常量，**绝不手写字面量**——
 * 段标题是提示词↔检测器强耦合面（REDLINES §1），这里是只读引用点。
 *
 * round-trip 会把空行与行首尾空白规范化掉（解析本就 trim+滤空）：保存即规范化，可接受。
 */
sealed interface MemoryEditMode {
    /** 分区编辑：现文本恰为「两标题分节且无节前导语」。 */
    data class Sections(val longTermText: String, val recentText: String) : MemoryEditMode

    /** 整段退化编辑（无标准分节 / 有节前 unparsed 行）。 */
    data class Whole(val text: String) : MemoryEditMode
}

object MemoryEditText {

    /** 拆：能分区就分区（各节行以换行 join），否则整段原文。 */
    fun toMode(memorySummary: String): MemoryEditMode {
        val sections = MemorySummarySections.parse(memorySummary)
        return if (sections.hasSections && sections.unparsed.isEmpty()) {
            MemoryEditMode.Sections(
                longTermText = sections.longTermFacts.joinToString("\n"),
                recentText = sections.recentEvents.joinToString("\n"),
            )
        } else {
            MemoryEditMode.Whole(memorySummary)
        }
    }

    /** 装：分区态按两标题重组；整段态原样（两者都 trim）。 */
    fun compose(mode: MemoryEditMode): String = when (mode) {
        is MemoryEditMode.Sections ->
            "${MemorySummarySections.LONG_TERM_HEADER}\n${mode.longTermText.trim()}\n\n" +
                "${MemorySummarySections.RECENT_HEADER}\n${mode.recentText.trim()}"
        is MemoryEditMode.Whole -> mode.text.trim()
    }

    /**
     * 保存资格：装配结果去掉两个标题后仍有内容。
     * 清空记忆是「删除」语义、应走显式动作，本期不提供 → 保存钮置灰防误触抹掉全部记忆。
     */
    fun canSave(mode: MemoryEditMode): Boolean = when (mode) {
        is MemoryEditMode.Sections -> mode.longTermText.isNotBlank() || mode.recentText.isNotBlank()
        is MemoryEditMode.Whole -> mode.text.isNotBlank()
    }
}
