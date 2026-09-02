package com.situ.aichat.prompt

import com.situ.aichat.data.model.AffectField
import com.situ.aichat.data.model.CharacterIntent
import com.situ.aichat.data.model.IntentKind
import com.situ.aichat.data.model.PersonaOperator
import com.situ.aichat.data.model.RelationshipPressure
import com.situ.aichat.prompt.growth.IntentRules
import com.situ.aichat.prompt.growth.RelationshipBands
import com.situ.aichat.prompt.growth.scriptVariant
import java.time.ZoneId
import kotlin.math.abs

/**
 * 【此刻】末尾那一行内心状态（活人感内核卷三 §4.1 · 总图纸 §4.2「三段合一」·修缮卷 J12 修订）：
 * `此刻你心里：<句1><句2><句3>`——候选按优先级 意图（卷四 §4.4·[IntentExitRenderer.chatCandidate]）› 算子 › 场状态，每类至多 1 句，
 * ≤3 句、含前缀总长 ≤80 字、单句 >74 直接跳过；一个都没有 ⇒ 返回 `""`（整行不出·E12）。
 * **矛盾短句已从内心行去掉**（J12：Growth 段已出同维长句，再出短句是重复占位；`pressure` 形参保留、签名不动，不再读）。
 * 算子候选跳过「与选中意图同源」的 c01–c06（E20：想被哄挂着时 c02 的句子与意图句说的是同一件事）。
 *
 * **渲染层不做数值运算**（总图纸 §2.1）：阈值比较全在 [isConditionActive] / [fieldSentence] 两个纯函数里，
 * 它们只读场、不改任何状态；句子字面全部住 [InnerStateScripts]。人称：`你` = 角色，用户一律真名。
 * 调用方传入的 `field` 须是读值（`fieldForRead`·修缮卷 §3.5）——本对象不做松弛。
 *
 * **内心行换气（微图纸 2026-09-02）**：意图句 / 残留句 / 场句按 `variant = scriptVariant(now, zone)` 每 3 天换同义变体（算子句不轮换）；
 * 慢场句（安全感 / 投入度）只在跨档后 [RelationshipBands.SLOW_SENTENCE_TTL_MS] 内参与评分（`slowBandsAt == 0` 的老列不出）。
 */
internal object InnerStateRenderer {

    const val MAX_SENTENCES = 3
    const val MAX_TOTAL_CHARS = 80
    const val MAX_SENTENCE_CHARS = 74

    /**
     * @param hour 本地时（c10 用）；@param now epoch millis（命中 24h 有效期 / 意图惰性衰减用）。
     * @param intents 角色意图队列（卷四 §4.4：第 2 位候选 + 算子 c01–c06 求值）；默认空 = 卷三行为逐字节不变。
     * @param zone 算台词变体的本地日所用时区（内心行换气）；`PromptBuilderSchedule` 传日程时区（无日程即系统时区），三入口同源。
     */
    @Suppress("UNUSED_PARAMETER") // 修缮卷 J12：矛盾候选已删，`pressure` 只为保住签名（PromptBuilder 调用点零改）
    fun render(
        field: AffectField,
        pressure: RelationshipPressure,
        operators: List<PersonaOperator>,
        userName: String,
        hour: Int,
        now: Long,
        intents: List<CharacterIntent> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val variant = scriptVariant(now, zone)
        val candidates = mutableListOf<String>()
        // 1. 意图（卷四 §4.4）：最强 live 意图的句子；无 live 时 7 天内的残留句；都没有 ⇒ 无（台词按 variant 轮换）
        IntentExitRenderer.chatCandidate(intents, userName, now, variant)?.let(candidates::add)
        // 2. 算子：按列表序取第一条 enabled、条件成立、且不与选中意图同源（E20）的
        val chosenKind = IntentExitRenderer.strongestLiveKind(intents, now)
        fun sameSourceAsIntent(condition: String): Boolean = kindOfCondition(condition).let { it != null && it == chosenKind }
        operators.firstOrNull {
            it.enabled && isConditionActive(it.condition, field, hour, now, intents) && !sameSourceAsIntent(it.condition)
        }?.let { op ->
            val condition = InnerStateScripts.conditionPhrase(op.condition, userName)
            val action = InnerStateScripts.actionPhrase(op.action)
            if (condition != null && action != null) candidates.add("$condition，你$action。")
        }
        // 3. 场状态：四场偏离分最大者一句（慢场句过资格门·台词按 variant 轮换）
        fieldSentence(field, userName, now, variant)?.let(candidates::add)

        val chosen = mutableListOf<String>()
        var total = InnerStateScripts.PREFIX.length
        for (sentence in candidates) {
            if (sentence.length > MAX_SENTENCE_CHARS) continue
            if (chosen.size < MAX_SENTENCES && total + sentence.length <= MAX_TOTAL_CHARS) {
                chosen.add(sentence)
                total += sentence.length
            }
        }
        return if (chosen.isEmpty()) "" else InnerStateScripts.PREFIX + chosen.joinToString("")
    }

    /**
     * c01–c06 → 意图种类（映射照 zh 资源 K4-F3：**c01 = 道歉、c02 = 被哄**，与 [IntentKind] 声明序不同）；其它条件 ⇒ null。
     * [isConditionActive] 与 E20 同源去重共用这一张表。
     */
    internal fun kindOfCondition(condition: String): IntentKind? = when (condition) {
        "c01" -> IntentKind.WANT_APOLOGIZE
        "c02" -> IntentKind.WANT_COMFORT
        "c03" -> IntentKind.WANT_PROBE
        "c04" -> IntentKind.WANT_HIDE
        "c05" -> IntentKind.WANT_SHARE
        "c06" -> IntentKind.WANT_CONFIRM
        else -> null
    }

    /**
     * 算子条件求值（§4.1 锁定表）：c07/c08/c09/c12 看最近一次分析的命中（24h 内有效·K-12），c10 看深夜 + 激活 ≤ 25，
     * c11 看效价 ≤ −40；c01–c06 = 队列里有该 kind 的 live 意图（卷四 §4.4·只读判据 [IntentRules.isLive]·映射见 [kindOfCondition]）；
     * 未知 key ⇒ false。
     */
    internal fun isConditionActive(
        condition: String,
        field: AffectField,
        hour: Int,
        now: Long,
        intents: List<CharacterIntent> = emptyList(),
    ): Boolean {
        val hitsFresh = field.hitsAt > 0L && now - field.hitsAt <= RelationshipBands.HITS_TTL_MS
        kindOfCondition(condition)?.let { kind -> return intents.any { it.kind == kind && IntentRules.isLive(it, now) } }
        return when (condition) {
            "c07" -> hitsFresh && "g04" in field.hits
            "c08" -> hitsFresh && "g05" in field.hits
            "c09" -> hitsFresh && "g02" in field.hits
            "c10" -> (hour == 23 || hour in 0..5) && field.arousal <= RelationshipBands.AROUSAL_LOW
            "c11" -> field.valence <= RelationshipBands.VALENCE_BAD
            "c12" -> hitsFresh && AffectField.BAND_UP in field.hits
            else -> false
        }
    }

    /**
     * 场状态句（§4.1 第 4 项锁定）：偏离分
     * `s_v = |v|≥20 ? |v|/100 : 0`、`s_a = a≤10 ? (10−a)/10 : a≥75 ? (a−75)/25 : 0`、
     * `s_s = s≤30 ? (30−s)/30 : s≥80 ? (s−80)/20 : 0`、`s_i = i≥80 ? (i−80)/20 : i≤10 ? (10−i)/10 : 0`；
     * 全 0 ⇒ null；否则取最大者（同分序 v > a > s > i）的句子。
     *
     * **慢场句资格门（内心行换气微图纸 §4 锁定）**：`s`、`i` 两个候选只在 `slowBandsAt[k] > 0 ∧ now − slowBandsAt[k] ≤ SLOW_SENTENCE_TTL_MS`
     * 时参与评分（跨档 3 天内）；`v`、`a` 照旧；评分公式与同分序不变。慢场档界与 `AffectMath.trackSlowBands` 同源
     * （[RelationshipBands.SLOW_SECURITY_LOW_MAX] 等）。[variant] = 台词变体（0 = 原文）。
     */
    internal fun fieldSentence(field: AffectField, userName: String, now: Long, variant: Int): String? {
        val v = field.valence
        val a = field.arousal
        val s = field.security
        val i = field.investment
        fun slowEligible(k: Int): Boolean {
            val at = field.slowBandsAt.getOrNull(k) ?: 0L
            // R1 A-3：未来时刻（时钟回拨 / 坏列）不算「刚跨档」——否则踏实句会一直挂到时钟追上
            return at in 1..now && now - at <= RelationshipBands.SLOW_SENTENCE_TTL_MS
        }
        val scoreV = if (abs(v) >= 20) abs(v) / 100.0 else 0.0
        val scoreA = if (a <= 10) (10 - a) / 10.0 else if (a >= 75) (a - 75) / 25.0 else 0.0
        val scoreS = when {
            !slowEligible(0) -> 0.0
            s <= RelationshipBands.SLOW_SECURITY_LOW_MAX -> (RelationshipBands.SLOW_SECURITY_LOW_MAX - s) / RelationshipBands.SLOW_SECURITY_LOW_MAX.toDouble()
            s >= RelationshipBands.SLOW_SECURITY_HIGH_MIN -> (s - RelationshipBands.SLOW_SECURITY_HIGH_MIN) / (100 - RelationshipBands.SLOW_SECURITY_HIGH_MIN).toDouble()
            else -> 0.0
        }
        val scoreI = when {
            !slowEligible(1) -> 0.0
            i >= RelationshipBands.SLOW_INVESTMENT_HIGH_MIN -> (i - RelationshipBands.SLOW_INVESTMENT_HIGH_MIN) / (100 - RelationshipBands.SLOW_INVESTMENT_HIGH_MIN).toDouble()
            i <= RelationshipBands.SLOW_INVESTMENT_LOW_MAX -> (RelationshipBands.SLOW_INVESTMENT_LOW_MAX - i) / RelationshipBands.SLOW_INVESTMENT_LOW_MAX.toDouble()
            else -> 0.0
        }
        // maxByOrNull 取首个最大值 ⇒ 同分按列表序 v > a > s > i
        val best = listOf('v' to scoreV, 'a' to scoreA, 's' to scoreS, 'i' to scoreI).maxByOrNull { it.second }
        if (best == null || best.second <= 0.0) return null
        return when (best.first) {
            'v' -> when {
                v >= 45 -> InnerStateScripts.valenceHigh(variant)
                v >= 20 -> InnerStateScripts.valenceGood(variant)
                v <= -45 -> InnerStateScripts.valenceBad(variant)
                else -> InnerStateScripts.valenceLow(variant)
            }
            'a' -> if (a <= 10) InnerStateScripts.arousalLow(variant) else InnerStateScripts.arousalHigh(variant)
            's' -> if (s <= RelationshipBands.SLOW_SECURITY_LOW_MAX) InnerStateScripts.securityLow(userName, variant) else InnerStateScripts.securityHigh(userName, variant)
            else -> if (i >= RelationshipBands.SLOW_INVESTMENT_HIGH_MIN) InnerStateScripts.investmentHigh(userName, variant) else InnerStateScripts.investmentLow(userName, variant)
        }
    }
}
