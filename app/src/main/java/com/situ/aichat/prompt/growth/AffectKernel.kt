package com.situ.aichat.prompt.growth

import android.util.Log
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.model.AffectField
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.PersonaGains
import com.situ.aichat.data.model.PersonaVocab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 场内核（活人感统一内核·卷三 §3.4 / §3.6 / §3.8）：`affectFieldJSON` 一列的**唯一持有者**。
 *
 * 两条写路：
 * - [tick]：每轮回合尾（聊天 / 语音各一处钩子）——松弛到 now + 激活脉冲 + 跨日归零预算与日倾，**1 次**列级写；
 * - [withFieldLocked]：成长分析通道——松弛（无脉冲）后把场交给协调器做投影 / 扩散 / 预算，块返回新场，**1 次**列级写。
 *
 * **锁纪律（J-1/J-2）**：per-uuid [Mutex]，与 `CharacterWriteLock` 是不同对象不同类、**禁止复用**；
 * 锁序恒为 `CharacterWriteLock → 内核 Mutex`（分析通道在角色写锁内再进本锁），tick **不进** `CharacterWriteLock`
 * ⇒ 无环。列集与其它写者零重叠（I-3）⇒ 列级 UPDATE 即原子。
 *
 * **零 LLM、零真随机**（总图纸 §2.1）：事件只经分析 LLM 报的封闭 key 进场（[project]），日倾走确定性种子（[dailyTilt]）。
 * 场 → 维单向（[diffuse]）；本类不存在任何以性格/关系为入参、返回场的函数（保险 1·`grep` 断言）。
 */
@Singleton
class AffectKernel @Inject constructor(
    private val characterDao: CharacterDao,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(uuid: String): Mutex = locks.computeIfAbsent(uuid) { Mutex() }

    /**
     * 每轮 tick（图纸 §3.6 锁定序）：读列 → 解码/默认 → [rollDay] → **快场** [relaxToward]（dt = now − updatedAt；
     * `updatedAt == 0 ⇒ dt = 0`；修缮卷 J1：慢场原样不动、`slowRefAt == 0` 的旧列置 `now`）→ 激活脉冲
     * （**仅当** `arousal + 2 ≤ arousalBaseline(hour) + 30`·上限恰 74·F16）→ 慢场带档跟踪 [trackSlowBands]（内心行换气·用读值判档）
     * → `updatedAt = now` → 1 次 [CharacterDao.updateAffectField]。**不读整行、不进 CharacterWriteLock**。
     * 异常吞掉打 `Log.w`（回合尾四段不因它中断·外部行为清单 9）；协程取消照常上抛。
     */
    suspend fun tick(uuid: String, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()) {
        try {
            lockFor(uuid).withLock {
                val relaxed = relax(rollDay(load(uuid), nowMs, zone, uuid), nowMs, zone)
                    .let { if (it.slowRefAt == 0L) it.copy(slowRefAt = nowMs) else it }
                val hour = hourOf(nowMs, zone)
                val pulsed = if (relaxed.arousal + RelationshipBands.AROUSAL_PULSE <= arousalBaseline(hour) + PULSE_HEADROOM) {
                    relaxed.copy(arousal = (relaxed.arousal + RelationshipBands.AROUSAL_PULSE).coerceIn(0, 100))
                } else {
                    relaxed
                }
                // 内心行换气：慢场带档跟踪搭在这一次写里（用读值 slowNow 判档；参考值本身不动）
                val tracked = trackSlowBands(
                    pulsed,
                    slowNow(pulsed.security, BASELINE.security, pulsed.slowRefAt, nowMs),
                    slowNow(pulsed.investment, BASELINE.investment, pulsed.slowRefAt, nowMs),
                    nowMs,
                )
                characterDao.updateAffectField(uuid, GrowthJson.encode(tracked.copy(updatedAt = nowMs)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "tick 失败（吞掉·不影响回合尾其余段）：${e.javaClass.simpleName}")
        }
    }

    /**
     * 分析通道（图纸 §3.4 步骤 2–11 的外壳）：内核 Mutex 内：读列 → 解码/默认 → [rollDay] → 快场松弛（**无脉冲**）→
     * 慢场取读值 [slowNow] 并把 `slowRefAt` 重设为 now（修缮卷 J1：块里落用的事件位移就写在新参考值上）→
     * `block(field0)` 返回新场 → 慢场带档跟踪 [trackSlowBands]（块返回值已是读值）→ 1 次 [CharacterDao.updateAffectField]。
     * 调用方必须已持有 `CharacterWriteLock`（锁序）。
     * 块内异常原样上抛（分析失败 = 本轮场不落库，与 16 维同命运）。
     */
    suspend fun withFieldLocked(
        uuid: String,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        block: suspend (AffectField) -> AffectField,
    ) {
        lockFor(uuid).withLock {
            val relaxed = relax(rollDay(load(uuid), nowMs, zone, uuid), nowMs, zone)
            val field0 = relaxed.copy(
                security = slowNow(relaxed.security, BASELINE.security, relaxed.slowRefAt, nowMs),
                investment = slowNow(relaxed.investment, BASELINE.investment, relaxed.slowRefAt, nowMs),
                slowRefAt = nowMs,
            )
            val out = block(field0)
            // 内心行换气：块返回的 security / investment 已是新参考值（= 读值），直接判档
            val tracked = trackSlowBands(out, out.security, out.investment, nowMs)
            characterDao.updateAffectField(uuid, GrowthJson.encode(tracked.copy(updatedAt = nowMs)))
        }
    }

    /** 场列单读 + 解码（空 / 坏 JSON 同路默认·K-11）。行不存在（角色已删）也回默认——随后的 UPDATE 命中零行，无副作用。 */
    private suspend fun load(uuid: String): AffectField =
        characterDao.getAffectFieldJson(uuid)?.let { GrowthJson.decodeAffectFieldOrNull(it) } ?: AffectField()

    /**
     * 快场半衰期松弛：效价 → 0（24h）；激活 → 昼夜基线（4h）。**慢场原样**（修缮卷 J1：安全感 / 投入度是参考值，
     * 读时经 [slowNow] 惰性算，tick 不碰；只有 [withFieldLocked] 在分析通道把读值重设为新参考值）。
     */
    private fun relax(f: AffectField, nowMs: Long, zone: ZoneId): AffectField {
        val dt = if (f.updatedAt == 0L) 0L else nowMs - f.updatedAt
        return f.copy(
            valence = relaxToward(f.valence, BASELINE.valence, dt, RelationshipBands.VALENCE_HALF_LIFE_MS),
            arousal = relaxToward(f.arousal, arousalBaseline(hourOf(nowMs, zone)), dt, RelationshipBands.AROUSAL_HALF_LIFE_MS),
        )
    }

    private fun hourOf(ms: Long, zone: ZoneId): Int = Instant.ofEpochMilli(ms).atZone(zone).hour

    companion object {
        private const val TAG = "AffectKernel"

        /** 脉冲上限余量：`arousal + 2 ≤ baseline + 30` 才 +2（修缮卷 F16 修正后上限恰 74 < 句子阈 75 ⇒「劲头收不住」只能由事件推上去）。 */
        private const val PULSE_HEADROOM = 30

        /** 慢场基线与效价松弛目标 = 出厂默认场（安全感 50 / 投入度 30 / 效价 0）。 */
        private val BASELINE = AffectField()

        /**
         * §3.5 A 投影（纯函数·零状态）：逐命中各自饱和再求和——
         * `fΔ[F] = (Σ_hit saturate(coef[hit][F] × BASE_HIT × gainFactor(level))).coerceIn(−12, 12)`；
         * `level = gains.system[key] ?: LEVEL_NORMAL`（custom 用该项自己的 level，按标签忽略大小写对上）。
         * 例：g10 效价正常档 `0.6×10=6 ⇒ saturate=5`；很敏感 `×1.8=10.8 ⇒ 6`；不吃这套 `×0.4=2.4 ⇒ 2`。
         * 不认识的 key（不在投影表）直接跳过；空命中 ⇒ [FieldDelta.ZERO]（E29）。
         */
        internal fun project(
            gainHits: List<String>,
            customHits: List<GrowthAnalysisResult.CustomHit>,
            gains: PersonaGains,
        ): FieldDelta {
            val sums = IntArray(Field.entries.size)
            fun add(vec: FieldVector, level: Int) {
                val factor = PersonaVocab.gainFactor(level).toDouble()
                Field.entries.forEachIndexed { i, f ->
                    sums[i] += saturate(vec[f] * RelationshipBands.BASE_HIT * factor)
                }
            }
            for (key in gainHits) {
                val vec = AffectCoefficients.PROJECTION[key] ?: continue
                add(vec, gains.system[key] ?: PersonaVocab.LEVEL_NORMAL)
            }
            if (customHits.isNotEmpty()) {
                val levelByLabel = gains.custom.associate { it.label.trim().lowercase() to it.level }
                for (hit in customHits) {
                    val level = levelByLabel[hit.label.trim().lowercase()] ?: PersonaVocab.LEVEL_NORMAL
                    add(if (hit.positive) AffectCoefficients.CUSTOM_POS else AffectCoefficients.CUSTOM_NEG, level)
                }
            }
            val cap = RelationshipBands.FIELD_STEP_CAP
            return FieldDelta(
                security = sums[0].coerceIn(-cap, cap),
                investment = sums[1].coerceIn(-cap, cap),
                valence = sums[2].coerceIn(-cap, cap),
                arousal = sums[3].coerceIn(-cap, cap),
            )
        }

        /**
         * §3.5 B 扩散（纯函数·**场 → 维单向**）：`dRaw[d] = Σ_F DIFFUSION[F][d] × fΔ[F]`；`dΔ[d] = saturate(dRaw[d])`。
         * 返回 16 维（性格 8 按 `PersonalitySpectrum.DIMENSION_KEYS` 序 + 关系 8 按 `RelationshipQuality.DIMENSION_KEYS` 序），
         * 未被任何场触及的维为 0。
         */
        internal fun diffuse(delta: FieldDelta): List<Int> = AffectCoefficients.DIM_KEYS.map { dim ->
            var raw = 0.0
            for ((field, row) in AffectCoefficients.DIFFUSION) {
                val coef = row[dim] ?: continue
                raw += coef * delta[field]
            }
            saturate(raw)
        }
    }
}
