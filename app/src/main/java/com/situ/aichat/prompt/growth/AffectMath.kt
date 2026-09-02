package com.situ.aichat.prompt.growth

import com.situ.aichat.data.model.AffectField
import com.situ.aichat.data.model.RelationshipQuality
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tanh
import kotlin.random.Random

// MARK: - 场内核纯数学（活人感内核卷三 §3.6 · 零状态零 IO · 全部 internal 便于逐点单测 `AffectMathTest`）
//
// 内核路径的四道保险在这里落成纯函数：饱和 [saturate]（废保底）/ 弹簧 [springStep] + 带守卫 [guardBand] /
// 日位移预算 [scaleToBudget] / 半衰期松弛 [relaxToward]。日倾 [dailyTilt] 是全内核**唯一**的伪随机点
// （确定性种子，可重放）；`Math` 的 random 与无种子构造的 `Random` 在本包为负向禁令（总图纸 §9.5·复核 grep ②）。

/**
 * 饱和（总图纸 §3.4 保险 2）：`m = round(6·tanh(|raw|/6))`，返回 `sign(raw)·m`——**幅值取整再配号** ⇒ 正负严格对称。
 * `|raw| < 0.5 ⇒ 0`，这就是「废保底」：内核路径上的 ±1 不再保底（对比 [scaledDelta] 的「至少 ±1」）。
 * 点值表（图纸 §3.6 锁定）：`1→1 2→2 3→3 4→3 5→4 6→5 7→5 8→5 9→5 10→6 12→6 20→6`。
 */
internal fun saturate(raw: Double): Int {
    if (raw == 0.0) return 0
    val m = (RelationshipBands.SATURATE_MAX_STEP * tanh(abs(raw) / RelationshipBands.SATURATE_MAX_STEP)).roundToInt()
    return if (raw > 0) m else -m
}

/**
 * 锚点弹簧一步（保险 4）：`m = round(0.15·|x − anchor|)`，`x > anchor ⇒ −m`、`x < anchor ⇒ +m`、相等 ⇒ 0。
 * 幅值点值（锁定·两侧对称）：`|x−a| ≤ 3 → 0`、`4..9 → 1`、`10..16 → 2`、`17..23 → 3`、`24..29 → 4`、`30 → 5`。
 */
internal fun springStep(x: Int, anchor: Int): Int {
    if (x == anchor) return 0
    val m = (RelationshipBands.SPRING_K * abs(x - anchor)).roundToInt()
    return if (x > anchor) -m else m
}

/**
 * ±20 带守卫（图纸 K-5「界内钳住、界外只许往里」）：`lo = anchor−20, hi = anchor+20`；
 * `before ∈ lo..hi ⇒ after.coerceIn(lo, hi)`；`before > hi ⇒ after.coerceIn(hi, before)`；`before < lo ⇒ after.coerceIn(before, lo)`。
 * 用户把锚点拖离现值 50 时不瞬移——弹簧逐次收回（十次分析即收回约 80%）。
 */
internal fun guardBand(before: Int, after: Int, anchor: Int): Int {
    val lo = anchor - RelationshipBands.SPRING_BAND
    val hi = anchor + RelationshipBands.SPRING_BAND
    return when {
        before > hi -> after.coerceIn(hi, before)
        before < lo -> after.coerceIn(before, lo)
        else -> after.coerceIn(lo, hi)
    }
}

/**
 * 指数松弛：`dt ≤ 0 ⇒ value`；`r = target + (value − target)·0.5^(dt/halfLife)` 取整；
 * 取整后原地不动、尚未到达目标、且 `dt ≥ RELAX_SNAP_MS(1h)` ⇒ 向 target 走 1（防长尾永远差一格）。
 *
 * **修缮卷 J1**：补步只对半衰期 ≤ [RelationshipBands.RELAX_SNAP_HALF_LIFE_MAX_MS]（24h）的快场——慢场（30d）每小时补 1 格
 * 就是「一周归零」（D-1）；本卷起慢场不再调本函数（走 [slowNow] 惰性参考值），函数本身仍守这条。
 */
internal fun relaxToward(value: Int, target: Int, dtMs: Long, halfLifeMs: Long): Int {
    if (dtMs <= 0L) return value
    val r = target + (value - target) * 0.5.pow(dtMs.toDouble() / halfLifeMs.toDouble())
    val out = r.roundToInt()
    if (out == value && value != target && dtMs >= RelationshipBands.RELAX_SNAP_MS &&
        halfLifeMs <= RelationshipBands.RELAX_SNAP_HALF_LIFE_MAX_MS
    ) {
        return if (target > value) value + 1 else value - 1
    }
    return out
}

/**
 * 慢场惰性参考值（修缮卷 J1·照卷四意图强度 K-3 范式）：列里存「参考值 [ref] + 参考时刻 [refAt]」，读时按 30 天半衰期算：
 * `refAt == 0（旧数据）∨ now ≤ refAt ⇒ ref`；否则 `round(baseline + (ref − baseline) × 0.5^((now − refAt) / SLOW_HALF_LIFE_MS))`（Double 算）。
 * 只有分析通道的事件才改参考值（[AffectKernel.withFieldLocked] 把读值重设为新参考值）；tick 不碰。
 */
internal fun slowNow(ref: Int, baseline: Int, refAt: Long, nowMs: Long): Int {
    if (refAt == 0L || nowMs <= refAt) return ref
    val dt = (nowMs - refAt).toDouble()
    return (baseline + (ref - baseline) * 0.5.pow(dt / RelationshipBands.SLOW_HALF_LIFE_MS)).roundToInt()
}

/**
 * 场的**只读视图**（修缮卷 §3.2·渲染层 / 观测台 / 意图萌生入参统一走它）：不 rollDay、不日倾、不脉冲、不写。
 * 慢场按 [slowNow]（基线 50 / 30）、快场按 [relaxToward]（`dt = now − updatedAt`；`updatedAt == 0 ⇒ 0`）；其余字段原样
 * （`slowRefAt / updatedAt` 都不因读而变）。
 */
internal fun fieldForRead(field: AffectField, nowMs: Long, zone: ZoneId): AffectField {
    val dt = if (field.updatedAt == 0L) 0L else nowMs - field.updatedAt
    val baseline = AffectField()
    return field.copy(
        security = slowNow(field.security, baseline.security, field.slowRefAt, nowMs),
        investment = slowNow(field.investment, baseline.investment, field.slowRefAt, nowMs),
        valence = relaxToward(field.valence, baseline.valence, dt, RelationshipBands.VALENCE_HALF_LIFE_MS),
        arousal = relaxToward(field.arousal, arousalBaseline(Instant.ofEpochMilli(nowMs).atZone(zone).hour), dt, RelationshipBands.AROUSAL_HALF_LIFE_MS),
    )
}

// MARK: - 内心行换气（微图纸 2026-09-02 §4 · 纯函数 · 逐点单测 `AffectMathTest`）

/**
 * 慢场档：`value ≤ lowMax ⇒ 0（低）`、`value ≥ highMin ⇒ 2（高）`、否则 1（中）。
 * 档界由调用方传入（[RelationshipBands.SLOW_SECURITY_LOW_MAX] 等·与场句阈值同源）。
 */
internal fun slowBand(value: Int, lowMax: Int, highMin: Int): Int = when {
    value <= lowMax -> 0
    value >= highMin -> 2
    else -> 1
}

/**
 * 带档跟踪（锁定规则）：对 k ∈ {0 安全感, 1 投入度}，`b = slowBand(eff_k)`：
 * `slowBands[k] == −1 ⇒ slowBands[k] = b，slowBandsAt[k] 不动`（老列：仍 0 = 未知历史，不出慢场句）；
 * `slowBands[k] != b ⇒ slowBands[k] = b，slowBandsAt[k] = nowMs`；相等 ⇒ 不动。
 * [effSecurity] / [effInvestment] 必须是**读值**（tick 用 [slowNow]；`withFieldLocked` 用块返回的新参考值 = 读值）。
 * 搭在既有那一次列级写里，不额外写；其余字段原样。
 */
internal fun trackSlowBands(field: AffectField, effSecurity: Int, effInvestment: Int, nowMs: Long): AffectField {
    val bands = MutableList(2) { field.slowBands.getOrNull(it) ?: -1 }
    // R1 A-3：未来时刻（时钟回拨 / 坏列）钳到 now，资格窗从此刻起算而不是永不过期
    val at = MutableList(2) { minOf(field.slowBandsAt.getOrNull(it) ?: 0L, nowMs) }
    val now = listOf(
        slowBand(effSecurity, RelationshipBands.SLOW_SECURITY_LOW_MAX, RelationshipBands.SLOW_SECURITY_HIGH_MIN),
        slowBand(effInvestment, RelationshipBands.SLOW_INVESTMENT_LOW_MAX, RelationshipBands.SLOW_INVESTMENT_HIGH_MIN),
    )
    for (k in 0..1) {
        val b = now[k]
        when {
            bands[k] == -1 -> bands[k] = b
            bands[k] != b -> { bands[k] = b; at[k] = nowMs }
        }
    }
    return field.copy(slowBands = bands, slowBandsAt = at)
}

/**
 * 台词变体（锁定）：`((localDay(now, zone).toEpochDay() / SCRIPT_ROTATE_DAYS) % SCRIPT_VARIANTS).toInt()`——按本地日每 3 天轮换、
 * 确定性、无种子（`toEpochDay()` 非负）。只作用于聊天出口的意图句 / 残留句 / 场句；算子句与四个非聊天出口恒变体 0。
 */
internal fun scriptVariant(nowMs: Long, zone: ZoneId): Int =
    ((localDay(nowMs, zone).toEpochDay() / RelationshipBands.SCRIPT_ROTATE_DAYS) % RelationshipBands.SCRIPT_VARIANTS).toInt()

/** 激活度昼夜基线（图纸 §3.6 锁定表）：`0..5→12 · 6..8→28 · 9..11→42 · 12..13→36 · 14..17→44 · 18..21→40 · 22→28 · 23→18`。 */
internal fun arousalBaseline(hour: Int): Int = when (hour) {
    in 0..5 -> 12
    in 6..8 -> 28
    in 9..11 -> 42
    in 12..13 -> 36
    in 14..17 -> 44
    in 18..21 -> 40
    22 -> 28
    else -> 18
}

/** 日倾幅度（[dailyTilt] 产物）：效价 ∈ [−6, 6]、激活 ∈ [−4, 4]。 */
internal data class DailyTilt(val valence: Int, val arousal: Int)

/**
 * 日倾（图纸 K-9·内核唯一的伪随机）：`seed = (uuid + "|" + 本地日 + "|0").hashCode()`，`kotlin.random.Random(seed)`
 * ⇒ 同角色同日恒同值、可重放；「今天就是不太想说话」由此而来。
 */
internal fun dailyTilt(uuid: String, localDay: LocalDate): DailyTilt {
    val seed = (uuid + "|" + localDay.toString() + "|0").hashCode()
    val rnd = Random(seed)
    return DailyTilt(
        valence = rnd.nextInt(-RelationshipBands.TILT_VALENCE, RelationshipBands.TILT_VALENCE + 1),
        arousal = rnd.nextInt(-RelationshipBands.TILT_AROUSAL, RelationshipBands.TILT_AROUSAL + 1),
    )
}

/** 某时刻所在的本地日。 */
internal fun localDay(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

/** 本地日零点 millis。 */
internal fun localDayStart(ms: Long, zone: ZoneId): Long =
    localDay(ms, zone).atStartOfDay(zone).toInstant().toEpochMilli()

/**
 * 跨本地日（图纸 §3.6 `rollDay`）：`localDay(now) != localDay(budgetDayStart)`（`budgetDayStart == 0` 视为不同日）⇒
 * 预算归零 + `budgetDayStart` = 今日零点 + 施加日倾（各钳域）；同日 ⇒ 原样返回（E10：同日重复调用不再倾）。
 */
internal fun rollDay(field: AffectField, nowMs: Long, zone: ZoneId, uuid: String): AffectField {
    val today = localDay(nowMs, zone)
    if (field.budgetDayStart != 0L && localDay(field.budgetDayStart, zone) == today) return field
    val tilt = dailyTilt(uuid, today)
    return field.copy(
        budgetDayStart = today.atStartOfDay(zone).toInstant().toEpochMilli(),
        budgetUsed = 0,
        slowDayUsed = listOf(0, 0),   // 修缮卷 J3：慢场日帽随日归零
        valence = (field.valence + tilt.valence).coerceIn(-100, 100),
        arousal = (field.arousal + tilt.arousal).coerceIn(0, 100),
    )
}

/** [scaleToBudget] 的产物：三组缩放后的整数位移 + 本次实际用量（= 缩放后绝对值之和）。 */
internal data class BudgetedDeltas(
    val personality: List<Int>,
    val relationship: List<Int>,
    val diffusion: List<Int>,
    val used: Int,
)

/**
 * 日位移预算（保险 3·图纸 K-3 收窄口径：**只管分析通道 16 维**，四场不进池）：
 * `remaining = max(0, 40 − budgetUsed)`；`pool = Σ|Δ|`；`pool ≤ remaining ⇒ 原样`；
 * 否则每笔 `Δ' = sign(Δ) × round(|Δ| × remaining/pool)`（**修缮卷 F9：取整不再向零截断**——截断让小位移整批归零；
 * 幅值取整再配号 ⇒ 正负严格对称，与 [saturate] 同口径·R1 🟡-2），
 * 取整后若 `Σ|Δ'| > remaining` ⇒ 循环把 |Δ'| 最大者向零走 1（并列取序 personality › relationship › diffusion、同组下标小者）
 * 直到 `Σ|Δ'| ≤ remaining`；`used = Σ|Δ'|`。剩 0 ⇒ 全 0（E9 不变）。
 */
internal fun scaleToBudget(
    personality: List<Int>,
    relationship: List<Int>,
    diffusion: List<Int>,
    budgetUsed: Int,
): BudgetedDeltas {
    val remaining = maxOf(0, RelationshipBands.DAILY_BUDGET - budgetUsed)
    val pool = personality.sumOf { abs(it) } + relationship.sumOf { abs(it) } + diffusion.sumOf { abs(it) }
    if (pool <= remaining) return BudgetedDeltas(personality, relationship, diffusion, pool)
    val factor = remaining.toDouble() / pool.toDouble()
    fun scale(a: List<Int>) = a.map { v -> val m = (abs(v) * factor).roundToInt(); if (v < 0) -m else m }.toMutableList()
    val groups = listOf(scale(personality), scale(relationship), scale(diffusion))
    fun total() = groups.sumOf { g -> g.sumOf { abs(it) } }
    while (total() > remaining) {
        var best: Pair<Int, Int>? = null
        for ((gi, g) in groups.withIndex()) {
            for ((i, v) in g.withIndex()) {
                if (best == null || abs(v) > abs(groups[best.first][best.second])) best = gi to i
            }
        }
        val (gi, i) = best ?: break
        val v = groups[gi][i]
        groups[gi][i] = if (v > 0) v - 1 else v + 1
    }
    return BudgetedDeltas(groups[0], groups[1], groups[2], total())
}

/**
 * 关系档（图纸 §3.6 `anyDimBandRoseUp`）：与 `RelationshipAnalysisTrigger.relationshipBand` **同一分档**——边界
 * [RelationshipBands.CROSSING_BOUNDARIES]`[10,20,30,50,70,85,95,100]`，`value <= boundary` 即落该档。
 * 没有直接复用 ui/chat 里那个 companion 函数是为了不让 prompt/growth 反向依赖 ui 层；
 * `GrowthKernelIntegrationTest` 逐值（0..100）钉两者相等。
 */
internal fun relationshipBandOf(value: Int): Int {
    val boundaries = RelationshipBands.CROSSING_BOUNDARIES
    for ((index, boundary) in boundaries.withIndex()) {
        if (value <= boundary) return index
    }
    return boundaries.size
}

/** 存在某关系维 `band(after) > band(before)` ⇒ true（命中字面 `bandUp` 的判据·K-12 / c12）。 */
internal fun anyDimBandRoseUp(before: RelationshipQuality, after: RelationshipQuality): Boolean =
    before.values.indices.any { relationshipBandOf(after.values[it]) > relationshipBandOf(before.values[it]) }
