package com.situ.aichat.worldbook

import com.situ.aichat.data.local.entity.WorldBookEntryEntity
import com.situ.aichat.data.worldbook.decodeStringList

/**
 * 关键词匹配器（WB3·八步算法的第 1/3 步·契约 §4.2）。
 * - 扫描缓冲 = 最近 N 条消息（include_names 加「名字: 」前缀，与 ST 一致）；
 * - 主键任一命中 + 次键四逻辑（AND ANY / NOT ALL / NOT ANY / AND ALL·契约 §2.1）；
 * - `/正则/flags` 形态关键词按 D3 支持（i/m/s 生效，JS 专属 flag 忽略）；**解析失败降级普通子串**并记入
 *   [badRegexKeys]（调用方写日志，引擎不打日志）；
 * - 中文 = 纯子串包含；整词匹配用 Unicode 字母数字边界（只对拉丁系有意义，默认关）。
 */
internal class WorldInfoMatcher(
    private val settings: WorldInfoSettings,
    private val characterName: String = "",
    private val userName: String = "",
    /** 批3 3-7：单个正则键单次匹配的时间预算（测试注入 0 以确定性触发降级；生产走默认值）。 */
    private val regexMatchBudgetNanos: Long = REGEX_MATCH_BUDGET_NANOS,
) {

    /** 解析失败、已降级为普通子串的正则关键词（诊断用，去重）。 */
    val badRegexKeys = mutableListOf<String>()

    /** 关键词 → 编译结果缓存；null = 非正则形态或编译失败（走子串）。 */
    private val regexCache = HashMap<String, Regex?>()

    data class KeyMatch(val matched: Boolean, val matchCount: Int)

    /** 扫描缓冲：最近 [depth] 条消息（depth ≤ 0 = 空缓冲，即 ST「深度 0 只认递归」语义）。 */
    fun buildBuffer(messages: List<ScanMessage>, depth: Int): String {
        if (depth <= 0) return ""
        val slice = messages.takeLast(depth)
        val base = slice.joinToString("\n") { m ->
            if (settings.includeNames && m.senderName.isNotBlank()) "${m.senderName}: ${m.text}" else m.text
        }
        // 首轮尚未产生 assistant 回复时，若开启了 includeNames 且给了角色名，补角色名行对齐第 2 轮起的行为。
        return if (
            settings.includeNames &&
            characterName.isNotBlank() &&
            slice.isNotEmpty() &&
            slice.none { it.senderName == characterName }
        ) {
            "$characterName\n$base"
        } else {
            base
        }
    }

    /** 绿灯条目判定：主键命中 + 次键逻辑；[KeyMatch.matchCount] 供分组评分。 */
    fun matchEntry(entry: WorldBookEntryEntity, buffer: String): KeyMatch {
        val caseSensitive = entry.caseSensitive ?: settings.caseSensitive
        val wholeWords = entry.matchWholeWords ?: settings.matchWholeWords
        val primary = decodeStringList(entry.keysJson).map(::expandKeyMacros).filter { it.isNotBlank() }
        if (primary.isEmpty() || buffer.isEmpty()) return KeyMatch(false, 0)

        val primaryHits = primary.count { matchKey(it, buffer, caseSensitive, wholeWords) }
        if (primaryHits == 0) return KeyMatch(false, 0)

        val secondary = decodeStringList(entry.secondaryKeysJson).map(::expandKeyMacros).filter { it.isNotBlank() }
        if (!entry.selective || secondary.isEmpty()) return KeyMatch(true, primaryHits)

        val secondaryHits = secondary.count { matchKey(it, buffer, caseSensitive, wholeWords) }
        val pass = when (entry.selectiveLogic) {
            0 -> secondaryHits > 0                    // AND ANY
            1 -> secondaryHits < secondary.size       // NOT ALL
            2 -> secondaryHits == 0                   // NOT ANY
            3 -> secondaryHits == secondary.size      // AND ALL
            else -> secondaryHits > 0                 // 未知值宽容按 AND ANY
        }
        return KeyMatch(pass, primaryHits + secondaryHits)
    }

    fun matchKey(key: String, buffer: String, caseSensitive: Boolean, wholeWords: Boolean): Boolean {
        if (isRegexForm(key)) {
            val regex = regexCache.getOrPut(key) { compileRegexKey(key) }
            if (regex != null) {
                // 批3 3-7：灾难性回溯护栏——匹配经 deadline CharSequence（引擎回溯时每次取字符都过预算检查），
                // 超时抛出 → 与编译失败同 D3 降级（记诊断 + 缓存置 null 永久转子串）。激活在发送关键路径上，
                // 绝不容一条社区书坏正则挂死整个回合。
                try {
                    return regex.containsMatchIn(DeadlineCharSequence(buffer, System.nanoTime() + regexMatchBudgetNanos))
                } catch (_: MatchDeadlineExceeded) {
                    if (key !in badRegexKeys) badRegexKeys.add(key)
                    regexCache[key] = null
                }
            }
            // 编译失败/匹配超时 → D3 降级：整个原始文本当普通子串
        }
        if (wholeWords) {
            val pattern = "(?<![\\p{L}\\p{N}_])${Regex.escape(key)}(?![\\p{L}\\p{N}_])"
            val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            return Regex(pattern, options).containsMatchIn(buffer)
        }
        return if (caseSensitive) buffer.contains(key) else buffer.lowercase().contains(key.lowercase())
    }

    /** ST 判据：以 `/` 开头且还有第二个 `/`（其后是 flags）才算正则形态。 */
    private fun isRegexForm(key: String): Boolean =
        key.length > 2 && key.startsWith("/") && key.lastIndexOf('/') > 0

    /**
     * 展开关键词内的 `{{char}}` / `{{user}}`（大小写无关）：正则键对替换文本做 `Regex.escape` 防角色名含元字符崩坏，
     * 普通键按字面替换。无 `{{` 快速短路零分配。
     */
    private fun expandKeyMacros(rawKey: String): String {
        if (!rawKey.contains("{{")) return rawKey
        val regexKey = isRegexForm(rawKey)
        var resolved = rawKey
        if (characterName.isNotEmpty()) {
            val replacement = if (regexKey) Regex.escape(characterName) else characterName
            resolved = resolved.replace("{{char}}", replacement, ignoreCase = true)
        }
        if (userName.isNotEmpty()) {
            val replacement = if (regexKey) Regex.escape(userName) else userName
            resolved = resolved.replace("{{user}}", replacement, ignoreCase = true)
        }
        return resolved
    }

    private fun compileRegexKey(key: String): Regex? {
        val lastSlash = key.lastIndexOf('/')
        val pattern = key.substring(1, lastSlash)
        val flags = key.substring(lastSlash + 1)
        val options = buildSet {
            if ('i' in flags) add(RegexOption.IGNORE_CASE)
            if ('m' in flags) add(RegexOption.MULTILINE)
            if ('s' in flags) add(RegexOption.DOT_MATCHES_ALL)
            // g/u/y 等 JS 专属 flag 对「有没有命中」无影响，忽略
        }
        return try {
            Regex(pattern, options)
        } catch (_: Exception) {
            if (key !in badRegexKeys) badRegexKeys.add(key)
            null
        }
    }

    /** 匹配超时信号（批3 3-7）。 */
    private class MatchDeadlineExceeded : RuntimeException()

    /**
     * 批3 3-7：带截止时间的字符视图——正则引擎回溯时高频调 [get]，超预算即抛 [MatchDeadlineExceeded]。
     * 这是唯一能中断 `java.util.regex` 灾难性回溯的通用手段（try/catch 拦不住一直不返回的匹配）。
     */
    private class DeadlineCharSequence(
        private val cs: CharSequence,
        private val deadlineNanos: Long,
    ) : CharSequence {
        override val length: Int get() = cs.length
        override fun get(index: Int): Char {
            if (System.nanoTime() > deadlineNanos) throw MatchDeadlineExceeded()
            return cs[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            DeadlineCharSequence(cs.subSequence(startIndex, endIndex), deadlineNanos)
    }

    private companion object {
        /** 单个正则键单次匹配的时间预算（正常匹配微秒级，50ms 已是灾难回溯的铁证）。 */
        const val REGEX_MATCH_BUDGET_NANOS = 50_000_000L
    }
}
