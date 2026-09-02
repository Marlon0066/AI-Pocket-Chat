package com.situ.aichat.prompt.growth

import com.situ.aichat.data.model.PersonaVocab
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipQuality
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - 场系数表（活人感内核卷三 §3.5 · **锁定值** · 纯数据）
//
// A 投影表：事件（27 增益项 + custom 两行）→ 四场；B 扩散表：四场 → 16 维。两张表的区间 / 符号 / 上限
// 由总图纸 §3.4 锁死，具体系数由卷三图纸落值；[validateTables] 把这些约束做成机器断言（`AffectCoefficientsTest`）。
// **结构禁令**：本文件（以及 AffectKernel / AffectMath）不得存在任何以 PersonalitySpectrum / RelationshipQuality /
// RelationshipPressure 为入参、返回 AffectField / FieldVector 的函数——场 → 维单向（总图纸 §3.4 保险 1）。

/** 四个场（顺序即 [FieldVector] / [FieldDelta] 的分量序）。 */
internal enum class Field { SECURITY, INVESTMENT, VALENCE, AROUSAL }

/** 四场系数向量（单位 = 系数）。 */
internal data class FieldVector(val security: Double, val investment: Double, val valence: Double, val arousal: Double) {
    operator fun get(field: Field): Double = when (field) {
        Field.SECURITY -> security
        Field.INVESTMENT -> investment
        Field.VALENCE -> valence
        Field.AROUSAL -> arousal
    }
}

/** 四场整数位移（单位 = 场值）。 */
internal data class FieldDelta(val security: Int, val investment: Int, val valence: Int, val arousal: Int) {
    operator fun get(field: Field): Int = when (field) {
        Field.SECURITY -> security
        Field.INVESTMENT -> investment
        Field.VALENCE -> valence
        Field.AROUSAL -> arousal
    }

    companion object {
        val ZERO = FieldDelta(0, 0, 0, 0)
    }
}

internal object AffectCoefficients {

    /** A · 投影表 27×4（图纸 §3.5 锁定）：每项 1–3 个非零、∈ [−1.0, +1.0] 步长 0.1。列序 = 安全感 / 投入度 / 效价 / 激活度。 */
    val PROJECTION: Map<String, FieldVector> = mapOf(
        "g01" to FieldVector(0.3, 0.0, 0.4, 0.0),     // 被关心问候
        "g02" to FieldVector(-0.4, 0.0, -0.4, -0.2),  // 被冷落 · 已读不回
        "g03" to FieldVector(0.0, 0.0, -0.3, 0.3),    // 被黏得太紧
        "g04" to FieldVector(0.0, 0.1, 0.5, 0.2),     // 被夸奖肯定
        "g05" to FieldVector(-0.2, 0.0, -0.5, 0.3),   // 被批评否定
        "g06" to FieldVector(-0.3, 0.0, -0.4, 0.2),   // 被小瞧 · 被当空气
        "g07" to FieldVector(0.5, 0.2, 0.4, 0.0),     // 被真正听懂
        "g08" to FieldVector(-0.2, 0.0, -0.4, 0.3),   // 被误解
        "g09" to FieldVector(0.4, 0.2, 0.4, 0.0),     // 你记得她说过的小事
        "g10" to FieldVector(0.0, 0.0, 0.6, 0.4),     // 被逗笑
        "g11" to FieldVector(0.0, 0.4, 0.3, 0.2),     // 一起做点什么
        "g12" to FieldVector(0.0, -0.2, -0.2, -0.4),  // 例行公事 · 没新鲜感
        "g13" to FieldVector(-0.3, 0.0, -0.6, 0.6),   // 吵架 · 被凶
        "g14" to FieldVector(-0.5, 0.0, -0.4, -0.3),  // 冷战 · 冷暴力
        "g15" to FieldVector(0.4, 0.0, 0.5, -0.2),    // 道歉与和好
        "g16" to FieldVector(0.0, 0.3, 0.5, 0.2),     // 收到礼物
        "g17" to FieldVector(0.4, 0.2, 0.4, 0.0),     // 被照顾
        "g18" to FieldVector(-0.5, -0.2, -0.5, 0.0),  // 被爽约 · 被辜负
        "g19" to FieldVector(-0.7, 0.0, -0.5, 0.3),   // 被隐瞒欺骗
        "g20" to FieldVector(0.6, 0.2, 0.3, 0.0),     // 承诺被兑现
        "g21" to FieldVector(0.4, 0.4, 0.2, 0.0),     // 你对她坦白脆弱
        "g22" to FieldVector(0.0, 0.2, 0.3, 0.6),     // 被撩 · 暧昧试探
        "g23" to FieldVector(0.0, 0.3, 0.5, 0.5),     // 身体亲密（线下）
        "g24" to FieldVector(-0.3, 0.0, -0.5, -0.2),  // 亲密被拒绝
        "g25" to FieldVector(-0.1, 0.0, -0.2, -0.3),  // 独处 · 深夜
        "g26" to FieldVector(-0.2, 0.0, -0.1, 0.5),   // 意外与变化
        "g27" to FieldVector(-0.8, 0.0, -0.4, 0.3),   // 被抛弃的信号
    )

    /** custom · pos（专属项·让她舒服）。 */
    val CUSTOM_POS = FieldVector(0.1, 0.0, 0.4, 0.2)

    /** custom · neg（专属项·让她难受）。 */
    val CUSTOM_NEG = FieldVector(-0.2, 0.0, -0.4, 0.2)

    /**
     * 修缮卷 D-7：「负向命中」键集 = 投影表里效价列 < 0 的系统项（派生·不另抄一张表）。
     * 协调器用它判「本次分析有没有负向命中」：有 ⇒ 张力**不**恒定泄压 −2（否则吵完架下一次分析张力照掉，联动被泄压抵消）。
     */
    val NEGATIVE_HIT_KEYS: Set<String> = PROJECTION.filterValues { it.valence < 0.0 }.keys

    /**
     * B · 扩散表 4×16（图纸 §3.5 锁定）：场 → 维 key → 系数；∈ [0, 0.5] 步长 0.05、每场 ≤ 6 维。
     * **负系数只许 [NEGATIVE_DIFFUSION] 登记的那几处**，各带理由（方向反转靠维度语义，不靠场的符号）。
     */
    val DIFFUSION: Map<Field, Map<String, Double>> = mapOf(
        Field.SECURITY to mapOf(
            "trust" to 0.30, "closeness" to 0.20,
            "tension" to -0.25,      // 安全感低 ⇒ 张力起（方向靠维度语义反转）
            "openness" to 0.15, "attachment" to 0.10, "warmth" to 0.10,
        ),
        Field.INVESTMENT to mapOf(
            "attachment" to 0.30, "closeness" to 0.20,
            "independence" to -0.15, // 越投入越不独立
            "respect" to 0.10, "curiosity" to 0.10, "familiarity" to 0.10,
        ),
        Field.VALENCE to mapOf(
            "fun" to 0.30, "warmth" to 0.20, "humor" to 0.15,
            "tension" to -0.20,      // 心情差 ⇒ 张力起
            "closeness" to 0.10, "extroversion" to 0.10,
        ),
        Field.AROUSAL to mapOf(
            "extroversion" to 0.20, "adventurousness" to 0.15, "fun" to 0.15, "tension" to 0.15,
            "emotionality" to 0.10, "curiosity" to 0.10,
        ),
    )

    /** 扩散表里登记在册的负系数（场, 维）。表里出现未登记的负值 = [validateTables] 违规。 */
    val NEGATIVE_DIFFUSION: Set<Pair<Field, String>> = setOf(
        Field.SECURITY to "tension",
        Field.INVESTMENT to "independence",
        Field.VALENCE to "tension",
    )

    /** 扩散的 16 维 key（性格 8 + 关系 8，各按自身 DIMENSION_KEYS 序）。 */
    val DIM_KEYS: List<String> = PersonalitySpectrum.DIMENSION_KEYS + RelationshipQuality.DIMENSION_KEYS

    /** 每项至少投影到几个场 / 至多几个场；每场至多影响几个维（总图纸 §9.2 锁定）。 */
    const val PROJECTION_MIN_NONZERO = 1
    const val PROJECTION_MAX_NONZERO = 3
    const val DIFFUSION_MAX_DIMS = 6

    /**
     * 校验 helper（图纸 §3.5 · T1-3 机器断言用）：返回违规描述列表，正常为空。
     * 校验项：键集 == `GAIN_KEYS`；每项非零 1..3、∈ [−1, 1]、步长 0.1；扩散每场 ≤ 6 维、维 key 合法、
     * ∈ [0, 0.5] ∪ 登记负值、步长 0.05；登记负值必须真在表里为负。
     */
    fun validateTables(): List<String> {
        val problems = mutableListOf<String>()
        if (PROJECTION.keys != PersonaVocab.GAIN_KEYS.toSet()) problems += "投影表键集 != GAIN_KEYS"
        fun stepOk(v: Double, step: Double) = abs((v / step).roundToInt() * step - v) < 1e-9
        for ((key, vec) in PROJECTION + mapOf("custom·pos" to CUSTOM_POS, "custom·neg" to CUSTOM_NEG)) {
            val values = Field.entries.map { vec[it] }
            val nonZero = values.count { it != 0.0 }
            if (nonZero !in PROJECTION_MIN_NONZERO..PROJECTION_MAX_NONZERO) problems += "$key 非零场数 $nonZero 不在 1..3"
            for (v in values) {
                if (v < -1.0 || v > 1.0) problems += "$key 系数 $v 越界 [-1,1]"
                if (!stepOk(v, 0.1)) problems += "$key 系数 $v 不是 0.1 步长"
            }
        }
        val dimKeys = DIM_KEYS.toSet()
        for ((field, row) in DIFFUSION) {
            if (row.size > DIFFUSION_MAX_DIMS) problems += "$field 扩散到 ${row.size} 维 > 6"
            for ((dim, v) in row) {
                if (dim !in dimKeys) problems += "$field → $dim 不是 16 维之一"
                if (!stepOk(v, 0.05)) problems += "$field → $dim 系数 $v 不是 0.05 步长"
                if (v < 0.0 && (field to dim) !in NEGATIVE_DIFFUSION) problems += "$field → $dim 负系数 $v 未登记"
                if (abs(v) > 0.5) problems += "$field → $dim 系数 $v 越界 [0,0.5]"
            }
        }
        for ((field, dim) in NEGATIVE_DIFFUSION) {
            val v = DIFFUSION[field]?.get(dim)
            if (v == null || v >= 0.0) problems += "登记负值 $field → $dim 在表里不是负数（$v）"
        }
        if (NEGATIVE_HIT_KEYS.isEmpty()) problems += "负向命中键集为空（投影表效价列没有负值）"
        return problems
    }
}
