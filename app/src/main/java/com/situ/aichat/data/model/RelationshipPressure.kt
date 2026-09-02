package com.situ.aichat.data.model

import kotlinx.serialization.Serializable
import kotlin.math.pow
import kotlin.math.roundToInt

// MARK: - 关系双压（活人感内核·卷二《正负双压》§3.1）

/**
 * 关系质感 8 维的**正压 / 负压**双记账。
 *
 * **为什么要两个数**：[RelationshipQuality] 每维只有一个净额，数据结构上就表达不了矛盾——
 * 「说了很多心里话(+3)，但有句话让她介意(-2)」被压成 `+1` 之后，那两股力就永远找不回来了；
 * 而依恋 正80/负75（净额 5，正落进静默区）恰恰是一个人正在痛苦挣扎的样子，旧结构下会被渲染成
 * 「你对ta没有依恋」。双压把这两股力**分开记**，净额只是它俩的差。
 *
 * **净额单向派生**（图纸 §9.4）：`relationshipQualityJSON` 列永不被直接赋值，只由 [toQuality] 产出；
 * 任何关系变化必经 [applyNetDelta] / [applyPressureDelta] / [shrinkPositive] 三个写口之一。
 *
 * 不变式（图纸 §3.1，全部由 [normalized] 落实）：
 * - **I-1**：`quality[i] == (pos[i] - neg[i]).coerceIn(0, 100)`
 * - **I-2**：写后若 `min(pos[i], neg[i]) > 100`，两侧同减 `d = min - 100`（**净额恒等**，只是同时泄掉
 *   两侧攒过头的部分——否则一段长关系的两个数会一路涨到域顶，之后谁也推不动谁）
 * - **I-3**：每项取值域 `0..200`
 * - **I-4**：解码得长度 <8 右侧补 0、>8 截前 8
 *
 * 另有**防漂移**（图纸 P-E3）：净额越 `[0,100]` 时把越界那一侧压回边界，使下一次增量以**钳位后的净额**
 * 为基准。不这么做的话，封顶维会攒出一笔看不见的虚高（`pos=150` 显示仍是 100），负向增量得先啃完这
 * 50 才开始掉分——那正是卷零 `KernelPullback` 要撤销的棘轮虚高。
 *
 * [pos] / [neg] 的下标顺序恒等于 [RelationshipQuality.DIMENSION_KEYS]。
 *
 * **修缮卷 J2（重叠泄放）**：I-2 只在双侧 > 100 时触发，正常量级下两股力一旦攒起来就终身不减（矛盾句永久点亮）。
 * [relaxOverlap] 是 I-2 的连续版：`min(pos, neg)` 那部分按 30 天半衰慢慢泄掉、两侧同减 ⇒ **净额恒等**、`relationshipQualityJSON`
 * 一个字节不变。**禁止**给 neg 单独衰减（那会改净额 = 改关系值）。
 * 派生本类的一切写口一律 `copy(pos = …, neg = …)` 以保留 [relaxedAt]；只有 [fromQuality] 播种裸构造（`relaxedAt = 0` 是有意的）。
 */
@Serializable
data class RelationshipPressure(
    val pos: List<Int> = List(DIM_COUNT) { 0 },   // 正压，顺序恒等于 RelationshipQuality.DIMENSION_KEYS
    val neg: List<Int> = List(DIM_COUNT) { 0 },   // 负压，同序
    /** 修缮卷 J2：上次重叠泄放时刻；0 = 从未（首次 [relaxOverlap] 只登记不泄）。老 JSON 缺键回 0。 */
    val relaxedAt: Long = 0L,
) {
    companion object {
        /** 关系质感维度数（= [RelationshipQuality.DIMENSION_KEYS] 的长度）。 */
        const val DIM_COUNT = 8

        /** 单项取值域上限（I-3）。 */
        const val MAX_PRESSURE = 200

        /** 归一化阈（I-2）：两侧同时超过它就一起泄掉超出部分，净额不变。 */
        const val NORMALIZE_THRESHOLD = 100

        /** 重叠泄放半衰期 30 天（修缮卷 §3.11 锁定值；data/model 层不依赖 prompt/，故不复用 `RelationshipBands.SLOW_HALF_LIFE_MS`）。 */
        const val OVERLAP_HALF_LIFE_MS = 30L * 86_400_000L

        /** 重叠泄放最小步长 24h：`dt < 此值` 原样返回且 [relaxedAt] 不更新（修缮卷 §3.11 锁定值）。 */
        const val OVERLAP_MIN_STEP_MS = 24L * 3_600_000L
    }
}

/**
 * 修缮卷 D-3：pos/neg 同减「重叠部分」的衰减量，净额恒等；`dt < 24h` 不动（含 [RelationshipPressure.relaxedAt] 不更新）。
 *
 * 调用点恰 2 处（§3.3 机制锁）：成长分析 `analyzeAndPersist` 淡化同步之后（LLM 前）、闲置淡化扫 `decayOneCharacter`。
 * 首次调用（`relaxedAt == 0`）只登记时刻不泄——老数据不知道上次是什么时候，不能凭空泄一大截。
 */
fun RelationshipPressure.relaxOverlap(nowMs: Long): RelationshipPressure {
    if (relaxedAt == 0L) return copy(relaxedAt = nowMs)
    val dt = nowMs - relaxedAt
    if (dt < RelationshipPressure.OVERLAP_MIN_STEP_MS) return this
    val factor = 0.5.pow(dt.toDouble() / RelationshipPressure.OVERLAP_HALF_LIFE_MS)
    val p = pos.toMutableList()
    val n = neg.toMutableList()
    for (i in 0 until RelationshipPressure.DIM_COUNT) {
        val overlap = minOf(p[i], n[i])
        val d = overlap - (overlap * factor).roundToInt()
        p[i] -= d
        n[i] -= d
    }
    return copy(pos = p, neg = n, relaxedAt = nowMs).normalized()
}

/** 播种：老数据 / 空列 ⇒ `pos = 当前净额`，`neg = 0`（满足 I-1）。 */
fun RelationshipPressure.Companion.fromQuality(q: RelationshipQuality): RelationshipPressure =
    RelationshipPressure(pos = q.values, neg = List(RelationshipPressure.DIM_COUNT) { 0 })

/** 单向派生净额（**唯一投影口**）。 */
fun RelationshipPressure.toQuality(): RelationshipQuality {
    var quality = RelationshipQuality()
    for (i in 0 until RelationshipPressure.DIM_COUNT) {
        quality = quality.setValue(i, pos[i] - neg[i])   // setValue 自带 coerceIn(0,100) ⇒ I-1
    }
    return quality
}

/**
 * 净额语义写者用（礼物 ②③④ / 名分校准 ⑤ / 闲置淡化 ⑥ / 成长分析的软上限校正）：把「净额增量」翻译成压强。
 *
 * `delta > 0` → 加正压；`delta < 0` → 加负压；`delta == 0` → **原样返回**（不产生任何写入差异）。
 *
 * ⚠️ 淡化走这里意味着久不聊天累积的是「疏远压」而**不是**「亲近压消失」（图纸表1 ⑥ · 有意为之）。
 */
fun RelationshipPressure.applyNetDelta(dimIndex: Int, delta: Int): RelationshipPressure {
    if (dimIndex !in 0 until RelationshipPressure.DIM_COUNT || delta == 0) return this
    return if (delta > 0) withDim(dimIndex, pos[dimIndex] + delta, neg[dimIndex])
    else withDim(dimIndex, pos[dimIndex], neg[dimIndex] - delta)
}

/**
 * 双压语义写者用（**只有成长分析 ①**）：正负分别加，各钳 `[0, PRESSURE_DELTA_MAX]`。
 *
 * 这是双压的信息源——LLM 分开报两个数，两股力在这里才第一次被分开记下来。
 */
fun RelationshipPressure.applyPressureDelta(dimIndex: Int, posDelta: Int, negDelta: Int): RelationshipPressure {
    if (dimIndex !in 0 until RelationshipPressure.DIM_COUNT) return this
    val p = posDelta.coerceIn(0, PRESSURE_DELTA_MAX)
    val n = negDelta.coerceIn(0, PRESSURE_DELTA_MAX)
    if (p == 0 && n == 0) return this
    return withDim(dimIndex, pos[dimIndex] + p, neg[dimIndex] + n)
}

/**
 * ⑧ `KernelPullback` 专用（图纸 P-1）：把某维净额降到 [targetNet]，**只减 pos，neg 一个字节不动**。
 *
 * 绝不可用 [applyNetDelta] 代替——系统撤销棘轮虚高 ≠ 角色身上多了一股负向力，后者会把「拉回」
 * 伪造成「又想又不敢」，直接污染矛盾判定（一个被拉回的老角色会立刻凑出 `pos≈97 / neg≈19`）。
 *
 * 推导：要让 `pos - neg == targetNet` 而 `neg` 不动，`pos` 只能取 `neg + targetNet`；
 * 越 [RelationshipPressure.MAX_PRESSURE] 则钳位，净额取钳后实际值（**不假装成功**）。
 */
fun RelationshipPressure.shrinkPositive(dimIndex: Int, targetNet: Int): RelationshipPressure =
    setNetKeepingNeg(dimIndex, targetNet)

/**
 * 系统侧数值调整的通用口径（R1 复核 O-1 · 与 P-1 同一条原则）：把某维净额设为 [targetNet]，
 * **只动 pos，neg 一个字节不动**——可升可降。
 *
 * 用在两处：⑧ 拉回（[shrinkPositive]，只降）与 ① 成长分析的 `scaledDelta` 软上限校正（可升可降）。
 * 后者原走 `applyNetDelta`，高分段「涨得慢」被打掉的那 2~3 点会**记成负压**——LLM 明明报 `neg=0`，
 * 一段长期高分的温暖关系每次分析白攒几点负压，攒够 55 就凭空跨过矛盾阈。系统调整不是角色身上的力，
 * 不许进 neg。
 */
fun RelationshipPressure.setNetKeepingNeg(dimIndex: Int, targetNet: Int): RelationshipPressure {
    if (dimIndex !in 0 until RelationshipPressure.DIM_COUNT) return this
    return withDim(dimIndex, neg[dimIndex] + targetNet, neg[dimIndex])
}

/**
 * ⑦ 用户手拖滑杆专用（图纸表1 ⑦ / §3.2）：把 [from] → [to] 之间**真变了的维**双压重置为
 * `pos = 目标值, neg = 0`；**没变的维一个字节不动**。
 *
 * 用户手调 = 圣旨，清空该维的历史压强是有意的——她说「就是这个数」，之前攒的那两股力就不作数了。
 * 反过来，[from] == [to]（只改了性格）时本函数原样返回，**绝不误触重置**（P-E15：⑦ 的写口同时管
 * 性格与关系两列，只改性格时关系侧是透传，那一刻若顺手重置双压就是静默清空用户的相处史）。
 */
fun RelationshipPressure.resetChangedDims(from: RelationshipQuality, to: RelationshipQuality): RelationshipPressure {
    if (from == to) return this
    val newPos = pos.toMutableList()
    val newNeg = neg.toMutableList()
    for (i in 0 until RelationshipPressure.DIM_COUNT) {
        if (from.values[i] == to.values[i]) continue
        newPos[i] = to.values[i].coerceIn(0, RelationshipPressure.MAX_PRESSURE)
        newNeg[i] = 0
    }
    return copy(pos = newPos, neg = newNeg)   // copy 保留 relaxedAt（修缮卷 J2）
}

/**
 * 把一份「已按净额算出来的目标值」同步进压强：逐维补一笔 [applyNetDelta]，使 [toQuality] 恒等于 [target]。
 *
 * 净额语义写者（礼物 ②③④ / 名分校准 ⑤ / 闲置淡化 ⑥ / 成长分析后段的联动与泄压）的统一入口——
 * 它们的算法都是**直接算出目标净额**的，这个函数把「目标」翻译回「增量」，从而让净额始终**单向派生**
 * （图纸 §9.4：`relationshipQualityJSON` 永不被直接赋值），而不是绕过写口硬塞一个数。
 *
 * 不是第四个写口：内部只调 [applyNetDelta]，所有钳位 / 归一化 / 防漂移照旧在那里发生。
 *
 * （每维只动自己那一格，故 [toQuality] 只需在循环前算一次——其余维的当前净额不会被别维的写入影响。）
 */
fun RelationshipPressure.syncedTo(target: RelationshipQuality): RelationshipPressure {
    val current = toQuality()
    var result = this
    for (i in 0 until RelationshipPressure.DIM_COUNT) {
        result = result.applyNetDelta(i, target.values[i] - current.values[i])
    }
    return result
}

/**
 * 补齐 / 截断到 8 维（I-4）并逐维跑 [normalizeDim]（I-2 / I-3 / 防漂移）。**解码后必调**。
 */
fun RelationshipPressure.normalized(): RelationshipPressure {
    val p = pos.fitToDimCount()
    val n = neg.fitToDimCount()
    val outPos = ArrayList<Int>(RelationshipPressure.DIM_COUNT)
    val outNeg = ArrayList<Int>(RelationshipPressure.DIM_COUNT)
    for (i in 0 until RelationshipPressure.DIM_COUNT) {
        val (np, nn) = normalizeDim(p[i], n[i])
        outPos.add(np)
        outNeg.add(nn)
    }
    return copy(pos = outPos, neg = outNeg)   // copy 保留 relaxedAt（修缮卷 J2）
}

/** 单次压强增量上限（与既有 `relationship_changes` 的 ±5 同量级；图纸 P-6）。 */
const val PRESSURE_DELTA_MAX = 5

/** 写单维并就地归一化——三个写口的共用出口，保证不变式没有旁路。 */
private fun RelationshipPressure.withDim(dimIndex: Int, rawPos: Int, rawNeg: Int): RelationshipPressure {
    val (p, n) = normalizeDim(rawPos, rawNeg)
    return copy(   // copy 保留 relaxedAt（修缮卷 J2）
        pos = pos.toMutableList().also { it[dimIndex] = p },
        neg = neg.toMutableList().also { it[dimIndex] = n },
    )
}

/**
 * 单维归一化：I-3 钳域 → I-2 双高同减 → 防漂移把净额压回 `[0,100]`（图纸 P-E3）。
 *
 * 三步都只**减**不增，故先后跑不会互相破坏：I-2 之后 `min ≤ 100`，防漂移的 `pos = neg + 100` 分支
 * 只在 `pos > neg`（即 `neg ≤ 100`）时触发 ⇒ 结果恒 `≤ 200`，不会回头违反 I-3。
 */
private fun normalizeDim(rawPos: Int, rawNeg: Int): Pair<Int, Int> {
    var p = rawPos.coerceIn(0, RelationshipPressure.MAX_PRESSURE)
    var n = rawNeg.coerceIn(0, RelationshipPressure.MAX_PRESSURE)
    val overflow = minOf(p, n) - RelationshipPressure.NORMALIZE_THRESHOLD
    if (overflow > 0) {
        p -= overflow
        n -= overflow
    }
    val net = p - n
    if (net < 0) n = p                                                    // 净额下溢 ⇒ 归零，下次以 0 为基准
    else if (net > NET_MAX) p = n + NET_MAX                               // 净额上溢 ⇒ 压回 100，不留虚高
    return p to n
}

/** I-4：右侧补 0 / 截前 8。 */
private fun List<Int>.fitToDimCount(): List<Int> = when {
    size == RelationshipPressure.DIM_COUNT -> this
    size > RelationshipPressure.DIM_COUNT -> take(RelationshipPressure.DIM_COUNT)
    else -> this + List(RelationshipPressure.DIM_COUNT - size) { 0 }
}

/** 净额取值域上限（与 [RelationshipQuality.setValue] 的 `coerceIn(0,100)` 同源）。 */
private const val NET_MAX = 100
