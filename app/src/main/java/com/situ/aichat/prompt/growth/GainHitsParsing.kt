package com.situ.aichat.prompt.growth

import com.situ.aichat.data.model.PersonaVocab
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// MARK: - 敏感点命中的生成与解析（活人感内核卷四 §3.0 · 从 GrowthAnalysisService 只搬不改拆出）
//
// 本文件是「敏感点命中」一对生成/解析的家：提示词段 [buildSensitivitySection] + 解析 [parseGainHits] /
// [parseCustomHits]。三者原先住在 GrowthAnalysisService 里，卷四 chunk 0 按卷三 R1 交接第 4 条的强制前置动作
// **只搬不改**迁来（行为字节级不变，仅由类内 private 升为同包 internal —— 搬出类体的必然推论；
// `GAIN_HITS_MAX` 改经 `GrowthAnalysisService.GAIN_HITS_MAX` 引用）。调用点仍在 GrowthAnalysisService
// （`buildAnalysisPrompt` / `parseAnalysisResponse`），格式锁 = `GainHitsParseTest`。
// ⚠️ 修缮卷起本文件**已改**（不再是「只搬不改」）：`parseGainHits` 前缀归一 + 丢弃计数（D-13）、`parseCustomHits` tone 忽略大小写（🔵-3）。

/**
 * 提示词「### 敏感点命中规则」段（卷三 §3.3 锁定逐字）：27 行 `- gNN 标签`（[PersonaVocab.gainPromptLine]）+ 专属项一行
 * （[customGainLabels] 空则整行不出）。在 `trimIndent()` **之后**替换进 [SENSITIVITY_SECTION_MARKER] 所在行——多行插值若直接放进
 * 原始字符串会把公共缩进算成 0，整段提示词的缩进都剥不掉。
 * ⚠️ 与 [parseGainHits] / [parseCustomHits] 是同一对生成/解析（图纸 §6.1）：改键名 / 值域必须同改，格式锁 = `GainHitsParseTest`。
 */
internal fun buildSensitivitySection(customGainLabels: List<String>): String {
    val gainLines = PersonaVocab.GAIN_KEYS.joinToString("\n") { "- ${PersonaVocab.gainPromptLine(it)}" }
    val customLine = if (customGainLabels.isEmpty()) "" else "\n- 她的专属敏感点（原文照抄标签）：${customGainLabels.joinToString("、")}"
    return "### 敏感点命中规则\n" +
        "- 下面是她可能在意的事。**这段对话里确实发生过的**才算命中，没发生的不要写；一次对话通常命中 0~3 项\n" +
        gainLines + customLine + "\n" +
        "- custom_hits 只能写上面列出的专属项，label 原文照抄；tone 写 pos（让她舒服的）或 neg（让她难受的）"
}

/** [parseGainHits] 的产物：归一后 ∈ GAIN_KEYS 的命中（去重、截帽）+ 认不出的项数（上观测行·修缮卷 🔵-1）。 */
internal data class GainHitsParse(val hits: List<String>, val dropped: Int)

/** `g4` / `G04` / `g13 吵架 · 被凶` 这类前缀形状：捕获编号（1–2 位），其后只许空白接任意尾巴（`\s` 在 Android 恒 ASCII·记忆 ICU 清单）。 */
private val GAIN_KEY_PREFIX = Regex("""^[gG](\d{1,2})(?:\s.*)?$""")

/**
 * `gain_hits`：只认字符串数组；每项 `trim()` 后先按 [GAIN_KEY_PREFIX] 归一为 `gNN`（修缮卷 🔵-1：模型常把标签一起抄回来或省掉前导 0），
 * 结果 ∈ GAIN_KEYS 保留否则计丢弃；非字符串项计丢弃；去重（重复不计丢弃）、截 [GrowthAnalysisService.GAIN_HITS_MAX]；其它形状 ⇒ 空 / 0。
 */
internal fun parseGainHits(element: JsonElement?): GainHitsParse {
    val array = element as? JsonArray ?: return GainHitsParse(emptyList(), 0)
    val valid = PersonaVocab.GAIN_KEYS.toSet()
    val kept = LinkedHashSet<String>()
    var dropped = 0
    for (item in array) {
        val raw = (item as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()
        if (raw == null) {
            dropped++
            continue
        }
        val key = GAIN_KEY_PREFIX.matchEntire(raw)?.groupValues?.get(1)?.toIntOrNull()?.let { "g" + it.toString().padStart(2, '0') } ?: raw
        if (key in valid) kept += key else dropped++
    }
    return GainHitsParse(kept.take(GrowthAnalysisService.GAIN_HITS_MAX), dropped)
}

/**
 * `custom_hits`：只认 `[{"label": …, "tone": "pos"|"neg"}]`；label `trim()` 后与 [customGainLabels] **忽略大小写**逐一相等才保留
 * （回填清单里的原文标签），tone `trim().lowercase()` 后必须恰为 `pos` / `neg`，否则整条丢弃；其它形状 ⇒ 空。
 */
internal fun parseCustomHits(element: JsonElement?, customGainLabels: List<String>): List<GrowthAnalysisResult.CustomHit> {
    val array = element as? JsonArray ?: return emptyList()
    if (customGainLabels.isEmpty()) return emptyList()
    val canonicalByLower = customGainLabels.associateBy { it.trim().lowercase() }
    return array.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val label = (obj["label"] as? JsonPrimitive)?.contentOrNull?.trim() ?: return@mapNotNull null
        val canonical = canonicalByLower[label.lowercase()] ?: return@mapNotNull null
        when ((obj["tone"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()) {   // 修缮卷 🔵-3：`Neg` / `POS` 也认
            "pos" -> GrowthAnalysisResult.CustomHit(label = canonical, positive = true)
            "neg" -> GrowthAnalysisResult.CustomHit(label = canonical, positive = false)
            else -> null
        }
    }
}
