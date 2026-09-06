package com.situ.aichat.prompt.memory

/**
 * 记忆两段视图纯解析（活人感二期 M1·图纸 §3.1）：把「印象笔记」(`CharacterEntity.memorySummary`) 文本里
 * 模型按 [LONG_TERM_HEADER]/[RECENT_HEADER] 两标题维护的分区，解析成结构化视图，供资料页记忆卡分两节展示。
 *
 * **纯解析·不写库·不碰生成 / 注入**——聊天提示词里 memorySummary 原文注入路径逐字节不变（图纸 §6）。
 * 模型偶发丢标题时优雅回退：全部行入 [Sections.unparsed]、[Sections.hasSections] = false（资料页回退现状渲染·E1）。
 *
 * ⚠️ **强耦合（图纸 §6 第四个只读引用点）**：[LONG_TERM_HEADER]/[RECENT_HEADER] 两常量字面必须与
 * [MemoryService] 生成模板（`DEFAULT_EXTRACTION_PROMPT` 常量）逐字节一致；改标题须**四处同步**——
 * 生成模板 / `DirtyMessageDetector` / `pb_mem_format_ban` / 本处。本处**只读引用**，绝不反向修改前三处。
 */
object MemorySummarySections {

    const val LONG_TERM_HEADER = "【长期事实】"
    const val RECENT_HEADER = "【近期经历】"

    /**
     * 记忆文本的两段视图：
     * - [unparsed]：首个标题之前的行（含完全无标题的整段）——导语行归此（E2）。
     * - [longTermFacts]：[LONG_TERM_HEADER] 节内容行（不含标题行本身）。
     * - [recentEvents]：[RECENT_HEADER] 节内容行（不含标题行本身·生成模板保证从旧到新排列 → takeLast = 最新）。
     */
    data class Sections(
        val unparsed: List<String>,
        val longTermFacts: List<String>,
        val recentEvents: List<String>,
    ) {
        /** 至少解析出一节内容才走两节视图；否则资料页回退现状单节渲染（E1）。 */
        val hasSections: Boolean get() = longTermFacts.isNotEmpty() || recentEvents.isNotEmpty()
    }

    private enum class Current { NONE, LONG_TERM, RECENT }

    /**
     * 解析规则（图纸 §3.1 逐条）：按 '\n'/'\r' split → trim → 滤空（与资料页现状 `ProfileMemoryCard` 同口径）；
     * 逐行状态机——行**等于**某标题 → 切换当前节、该行不输出；行**以**某标题**开头**（同行带内容·E3）→ 切换节
     * 并输出剩余 trim 后非空部分；其余行归当前节（无当前节 → [Sections.unparsed]）；重复标题追加进同节。
     */
    fun parse(memorySummary: String): Sections {
        val unparsed = mutableListOf<String>()
        val longTerm = mutableListOf<String>()
        val recent = mutableListOf<String>()
        var current = Current.NONE

        fun sink(section: Current): MutableList<String> = when (section) {
            Current.NONE -> unparsed
            Current.LONG_TERM -> longTerm
            Current.RECENT -> recent
        }

        memorySummary.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            when {
                line == LONG_TERM_HEADER -> current = Current.LONG_TERM
                line == RECENT_HEADER -> current = Current.RECENT
                line.startsWith(LONG_TERM_HEADER) -> {
                    current = Current.LONG_TERM
                    val rest = line.removePrefix(LONG_TERM_HEADER).trim()
                    if (rest.isNotEmpty()) longTerm.add(rest)
                }
                line.startsWith(RECENT_HEADER) -> {
                    current = Current.RECENT
                    val rest = line.removePrefix(RECENT_HEADER).trim()
                    if (rest.isNotEmpty()) recent.add(rest)
                }
                else -> sink(current).add(line)
            }
        }
        return Sections(unparsed, longTerm, recent)
    }
}
