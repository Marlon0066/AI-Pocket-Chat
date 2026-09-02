package com.situ.aichat.prompt.growth

import com.situ.aichat.data.model.AffectField
import com.situ.aichat.data.model.CharacterIntent
import com.situ.aichat.data.model.IntentKind
import com.situ.aichat.data.model.IntentState
import com.situ.aichat.data.model.PersonaVocab
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 六种意图的三要素表（触发 / 半衰期 / 了结加成维）+ 全清词表 + 常量 + 三个只读纯函数
 * （活人感内核卷四图纸 §3.3 · **全部锁定值**·总图纸 §9.2；修缮卷 J4：层 ① 表达 / 了结两张关键词表已删——
 * 分析 LLM 每 ≤30 轮都在判 `intent_status`，本地子串只会抢跑；全清词保留但改「短消息整句」口径）。
 *
 * 意图侧常量**单源**住这里（图纸 §3.8）；`MAX_STORED` / `REVIEW_ROUNDS`（后者已停用·只作解码钳位）住 data/model 的 [IntentQueueState]
 * （那一层不依赖 prompt/·卷三 D-2 先例）。本对象零状态、零写：
 * 状态推进全在 `IntentKernel.companion` 的纯函数里，渲染层只用 [isLive] / [effectiveStrength] 只读判定（图纸 §9.4）。
 */
internal object IntentRules {

    // MARK: - 常量（§3.3 · §9.2 锁定）

    /** 队列同时最多几个 live 意图（超了淘汰 effective 最低者）。 */
    const val QUEUE_CAP = 3

    /** 同类 RESOLVED 后冷却：期内同 kind 不再萌生；RESOLVED 条目也保留到期满供判定。 */
    const val COOLDOWN_MS = 24L * 3_600_000L

    /** 任何 live 态挂满 7 天强制 FADED。 */
    const val TIMEOUT_MS = 7L * 86_400_000L

    /** FADED 的残留（「那件事没过去」）保留多久。 */
    const val RESIDUE_TTL_MS = 7L * 86_400_000L

    /**
     * 内心行换气：同 kind FADED 后冷却 3 天——期内不再萌生（治「同一批命中每周重生同一句」）；无残留的 FADED 保留到期满才清。
     * 单源住 [RelationshipBands.FADE_COOLDOWN_MS]，这里是别名。
     */
    const val FADE_COOLDOWN_MS = RelationshipBands.FADE_COOLDOWN_MS

    /** effective 低于此值 ⇒ 消退。 */
    const val FADE_MIN = 15

    /** 一次分析最多萌生几个。 */
    const val MAX_BIRTHS_PER_ANALYSIS = 2

    const val INVEST_MID = 45
    const val SECURITY_LOW = 40
    const val SECURITY_PROBE_MAX = 60
    const val VALENCE_COMFORT = -3
    const val VALENCE_SHARE = 3

    /** 层 ② 提示词「请重点看最近 N 轮」。 */
    const val RECENT_ROUNDS_HINT = 8

    /** 修缮卷 J4：全清词只对**短消息**整句生效——`trim()` 后长度 ≤ 此值才扫（长句里带「过去了」多半是在叙事）。 */
    const val CLEAR_MAX_LEN = 12

    /** 修缮卷 J5（用户拍板 ③）：消退后留残留句「那件事没过去」的意图种类 = 负向五种；**想分享不留**。 */
    val RESIDUE_KINDS: Set<IntentKind> = IntentKind.entries.toSet() - IntentKind.WANT_SHARE

    /** 萌生强度三档（K-22·与增益三档同源）：不吃这套 40 / 正常 50 / 很敏感 60。 */
    fun strengthForLevel(level: Int): Int = when (level) {
        PersonaVocab.LEVEL_NUMB -> 40
        PersonaVocab.LEVEL_SENSITIVE -> 60
        else -> 50
    }

    // MARK: - 触发（萌生）规则表（§3.3 表序锁定）

    /** 一条萌生规则（§3.3 表的一行）。[keys] = 该行提到的全部增益 key，萌生强度按其中**命中**的最高档位取。 */
    class BirthRule(
        val kind: IntentKind,
        val keys: Set<String>,
        private val predicate: (hits: Set<String>, field: AffectField) -> Boolean,
    ) {
        /** [field] = 分析通道步骤 6 落用后的场（安全感 S / 投入度 I / 效价 V）。 */
        fun matches(hits: Set<String>, field: AffectField): Boolean = predicate(hits, field)
    }

    private val COMFORT_KEYS = setOf("g05", "g06", "g08", "g13", "g14", "g18", "g24", "g27")
    private val CONFLICT_KEYS = setOf("g13", "g14")
    private val SHARE_KEYS = setOf("g07", "g10", "g11", "g16")

    /** 表序（锁定）：想被哄 → 想道歉 → 想躲 → 想确认 → 想试探 → 想分享。每条至多萌生 1 个。 */
    val BIRTH_RULES: List<BirthRule> = listOf(
        BirthRule(IntentKind.WANT_COMFORT, COMFORT_KEYS) { hits, f ->
            hits.any { it in COMFORT_KEYS } && f.valence <= VALENCE_COMFORT
        },
        BirthRule(IntentKind.WANT_APOLOGIZE, CONFLICT_KEYS) { hits, f ->
            hits.any { it in CONFLICT_KEYS } && f.investment >= INVEST_MID
        },
        BirthRule(IntentKind.WANT_HIDE, setOf("g03", "g13", "g14", "g08")) { hits, f ->
            "g03" in hits ||
                (hits.any { it in CONFLICT_KEYS } && f.investment < INVEST_MID) ||
                ("g08" in hits && f.security <= SECURITY_LOW)
        },
        BirthRule(IntentKind.WANT_CONFIRM, setOf("g27", "g25", "g18")) { hits, f ->
            "g27" in hits ||
                ("g25" in hits && f.security <= SECURITY_LOW) ||
                ("g18" in hits && f.investment >= INVEST_MID)
        },
        BirthRule(IntentKind.WANT_PROBE, setOf("g22", "g19", "g02")) { hits, f ->
            ("g22" in hits && f.security <= SECURITY_PROBE_MAX) ||
                "g19" in hits ||
                ("g02" in hits && f.investment >= INVEST_MID)
        },
        BirthRule(IntentKind.WANT_SHARE, SHARE_KEYS + "g26") { hits, f ->
            (hits.any { it in SHARE_KEYS } && f.valence >= VALENCE_SHARE) || "g26" in hits
        },
    )

    // MARK: - 半衰期 / 了结正压（§3.3 表末两列）

    /** 半衰期（锁定·全部落在 `[12h, 7d]` 域内）。 */
    fun halfLifeOf(kind: IntentKind): Long = when (kind) {
        IntentKind.WANT_COMFORT -> 24L * 3_600_000L
        IntentKind.WANT_APOLOGIZE -> 72L * 3_600_000L
        IntentKind.WANT_PROBE -> 48L * 3_600_000L
        IntentKind.WANT_HIDE -> 12L * 3_600_000L
        IntentKind.WANT_SHARE -> 24L * 3_600_000L
        IntentKind.WANT_CONFIRM -> 72L * 3_600_000L
    }

    /** 了结正压：(关系维 key, 净额 +N)。只在分析通道进 rΔ 池（K-6 / K-7）。 */
    fun resolveBonus(kind: IntentKind): Pair<String, Int> = when (kind) {
        IntentKind.WANT_COMFORT -> "closeness" to 3
        IntentKind.WANT_APOLOGIZE -> "trust" to 3
        IntentKind.WANT_PROBE -> "familiarity" to 2
        IntentKind.WANT_HIDE -> "respect" to 2
        IntentKind.WANT_SHARE -> "rapport" to 3
        IntentKind.WANT_CONFIRM -> "attachment" to 3
    }

    // MARK: - 只读纯函数（K-3 惰性衰减 · 渲染层唯一判据）

    /** `round(strength × 2^(−max(0, now − lastChangeAt) / 半衰期))`（Double 算·E51 时钟回拨不反向增长）。 */
    fun effectiveStrength(i: CharacterIntent, now: Long): Int {
        val dt = (now - i.lastChangeAt).coerceAtLeast(0L).toDouble()
        return (i.strength * 2.0.pow(-dt / halfLifeOf(i.kind))).roundToInt()
    }

    /** live = 三个 live 态之一 ∧ effective ≥ [FADE_MIN] ∧ 未超时（`now − bornAt < TIMEOUT_MS`·为负不超时·E51）。**永不改状态**。 */
    fun isLive(i: CharacterIntent, now: Long): Boolean =
        (i.state == IntentState.BUDDING || i.state == IntentState.ACTIVE || i.state == IntentState.EXPRESSED) &&
            effectiveStrength(i, now) >= FADE_MIN &&
            now - i.bornAt < TIMEOUT_MS

    // MARK: - 层 ① 全清词（§3.3 锁定逐字 · 匹配 = 短消息整句 contains 子串·修缮卷 J4）

    /** 全清词（E18）：扫用户**短消息**（≤ [CLEAR_MAX_LEN] 字），命中任一 ⇒ 移除全部 live 条目。表达 / 了结只经层 ②（`IntentKernel.applyStatus`）。 */
    val CLEAR_ALL_WORDS: List<String> = listOf("没事了", "过去了", "不提了", "翻篇", "别放在心上")
}
