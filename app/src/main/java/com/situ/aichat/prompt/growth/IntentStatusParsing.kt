package com.situ.aichat.prompt.growth

import com.situ.aichat.data.model.CharacterIntent
import com.situ.aichat.data.model.IntentKind
import com.situ.aichat.data.model.IntentState
import com.situ.aichat.prompt.IntentScripts
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 层 ② 意图判定——成长分析搭车（活人感内核卷四图纸 §3.5 · 总图纸 §4.5）：**生成侧与解析侧同文件同对**。
 *
 * - [section]：分析 user 框末尾追加的「【{角色名}当前挂着的意图】」段（逐字锁定），每行末尾 `[key]` 尾标供模型回填（K-18）
 * - [parse]：`intent_status` 字段 ⇒ `key → open|expressed|resolved`，未知 key / 未知值 / 非对象一律丢弃、**绝不抛**（E37）
 *
 * ⚠️ 与 `GrowthAnalysisService.buildAnalysisPrompt` 的输出格式行 `"intent_status": {...}` 及注意行是同一对生成/解析
 * （图纸 §6）：改 key 字面 / 三值 / 段形状必须同改，格式锁 = `IntentStatusParsingTest`。层 ② **只判了结，不萌生**（K-2 / N-2）：
 * 解析出的 key 若队列里没有对应 live 条目，`IntentKernel.applyStatus` 会忽略。
 */
internal object IntentStatusParsing {

    private const val OPEN = "open"
    private const val EXPRESSED = "expressed"
    private const val RESOLVED = "resolved"

    /**
     * 段标题关键字（修缮卷 🔵-2）：段标题 = `【{角色名}SECTION_KEYWORD】`，分析提示词的注意行用同一常量互指
     * （`GrowthAnalysisService.buildAnalysisPrompt`「- intent_status 只对「{角色名}当前挂着的意图」段里…」），两处不再各抄一份字面。
     */
    const val SECTION_KEYWORD = "当前挂着的意图"

    /**
     * 取 live 条目按 effective 降序（同分取声明序）；空 ⇒ `""`。
     * `{n} 天前萌生`：`n = (now − bornAt) / 86_400_000`，`n == 0 ⇒ 今天萌生`；状态词：BUDDING/ACTIVE ⇒ 活跃中、EXPRESSED ⇒ 已表达但未了结。
     */
    fun section(intents: List<CharacterIntent>, charName: String, userName: String, now: Long): String {
        val live = intents.filter { IntentRules.isLive(it, now) }
            .sortedWith(compareByDescending<CharacterIntent> { IntentRules.effectiveStrength(it, now) }.thenBy { it.kind.ordinal })
        if (live.isEmpty()) return ""
        val lines = buildList {
            add("【$charName$SECTION_KEYWORD】")
            for (i in live) {
                val days = ((now - i.bornAt) / 86_400_000L).coerceAtLeast(0L)
                val born = if (days == 0L) "今天萌生" else "$days 天前萌生"
                val state = if (i.state == IntentState.EXPRESSED) "已表达但未了结" else "活跃中"
                add("- ${IntentScripts.thirdPerson(i.kind, charName, userName)}（$born，$state）[${i.kind.key}]")
            }
            add("请重点看最近 ${IntentRules.RECENT_ROUNDS_HINT} 轮，判断这些意图有没有了结。")
            add("了结 = 这件事在对话里被正面接住、说开了；只是提了一嘴算 expressed；什么都没发生算 open。")
        }
        return lines.joinToString("\n")
    }

    /**
     * 只认 `JsonObject`；key `trim()` 后 [IntentKind.fromKey] 非空才留（**存 `kind.key` 原样**）；
     * 值只认 JSON 字符串，`trim().lowercase()` ∈ {open, expressed, resolved}；其它 ⇒ 丢该项；非对象 / 缺席 ⇒ 空 map。
     */
    fun parse(element: JsonElement?): Map<String, String> {
        val obj = element as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((rawKey, value) in obj) {
            val kind = IntentKind.fromKey(rawKey) ?: continue
            val v = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase() ?: continue
            if (v == OPEN || v == EXPRESSED || v == RESOLVED) out[kind.key] = v
        }
        return out
    }
}
