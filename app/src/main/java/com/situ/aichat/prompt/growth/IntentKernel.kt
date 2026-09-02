package com.situ.aichat.prompt.growth

import android.util.Log
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.model.AffectField
import com.situ.aichat.data.model.CharacterIntent
import com.situ.aichat.data.model.GrowthEventType
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.GrowthLogEntry
import com.situ.aichat.data.model.IntentKind
import com.situ.aichat.data.model.IntentQueueState
import com.situ.aichat.data.model.IntentState
import com.situ.aichat.data.model.PersonaGains
import com.situ.aichat.data.model.PersonaVocab
import com.situ.aichat.prompt.IntentScripts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log2

/**
 * 意图内核（活人感统一内核·卷四图纸 §3.4 / §3.8 / §3.9 · 照 [AffectKernel] 形状）：`intentQueueJSON` 一列的**唯一持有者**。
 *
 * 两条写路都在 per-uuid [Mutex] 内：
 * - [tick]：每轮回合尾（聊天 / 语音各一处钩子）——消退 / 清理 / 晋升 + 层 ① 全清词（修缮卷 J4：表达 / 了结表已删），
 *   **队列不变不写**（0 或 1 次·K-15）；
 * - [withQueueLocked]：成长分析通道（层 ② 判定 + 萌生 + 记账 + 清理）——恒 1 次列级写（性格复盘已砍·修缮卷）。
 *
 * **锁序（K-16 锁定）**：`CharacterWriteLock → AffectKernel.Mutex → IntentKernel.Mutex`，永不反向；tick 只拿本锁、不进
 * `CharacterWriteLock`；两个内核 tick 顺序执行不嵌套。列集与其它写者零重叠（I-3）⇒ 列级 UPDATE 即原子。
 *
 * **零 LLM、零真随机**（总图纸 §2.1）：萌生是确定性规则（[birth]·K-2），层 ② 的 LLM 判表达 / 了结（[applyStatus]·**唯一入口**），
 * 层 ① 只跑生命周期 + 用户全清（[advance]）。`UUID.randomUUID` 只作主键，不参与任何数值。
 */
@Singleton
class IntentKernel @Inject constructor(
    private val characterDao: CharacterDao,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(uuid: String): Mutex = locks.computeIfAbsent(uuid) { Mutex() }

    /**
     * 回合尾 tick（§3.4 锁定序）：1 次 [CharacterDao.getIntentQueueJson] → [advance] → 队列有变化才 1 次 [CharacterDao.updateIntentQueue]。
     * 语音回合传空文本（N-5）：只跑消退 / 清理 / 晋升。异常吞掉打 `Log.w`（回合尾不因它中断）；协程取消照常上抛。
     */
    suspend fun tick(uuid: String, now: Long, userText: String) {
        try {
            lockFor(uuid).withLock {
                val q0 = load(uuid)
                val q1 = advance(q0, now, userText)
                if (q1 != q0) characterDao.updateIntentQueue(uuid, GrowthJson.encode(q1))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "tick 失败（吞掉·不影响回合尾其余段）：${e.javaClass.simpleName}")
        }
    }

    /**
     * 分析通道（§3.4）：Mutex 内 `q0 = advance(load(uuid), now, "")` → `block(q0)` → **恒 1 次**列级写。
     * 调用方须已按锁序持有外层锁（分析通道：`CharacterWriteLock → AffectKernel.Mutex` 内）。
     * 块内异常原样上抛（本轮队列不落库）。
     */
    suspend fun withQueueLocked(uuid: String, now: Long, block: suspend (IntentQueueState) -> IntentQueueState) {
        lockFor(uuid).withLock {
            val q0 = advance(load(uuid), now, "")
            val out = block(q0)
            characterDao.updateIntentQueue(uuid, GrowthJson.encode(out))
        }
    }

    /** 列单读 + 解码（空 / 坏 JSON 同路默认·E36）。行不存在也回默认——随后的 UPDATE 命中零行，无副作用。 */
    private suspend fun load(uuid: String): IntentQueueState =
        characterDao.getIntentQueueJson(uuid)?.let { GrowthJson.decodeIntentQueueOrNull(it) } ?: IntentQueueState()

    companion object {
        private const val TAG = "IntentKernel"

        private fun isLiveState(state: IntentState): Boolean =
            state == IntentState.BUDDING || state == IntentState.ACTIVE || state == IntentState.EXPRESSED

        /** EXPRESSED 时强度砍半不归零：`ceil(effective / 2)` 且 ≥ 1。 */
        private fun halved(i: CharacterIntent, now: Long): Int =
            ((IntentRules.effectiveStrength(i, now) + 1) / 2).coerceAtLeast(1)

        /**
         * 生命周期推进 + 层 ① 全清（修缮卷 §3.4 `advance` · 纯函数 · **四步顺序锁定**）：
         * 0 钳未来时间（🔵-13）→ 1 消退 → 2 清理（残留 7 天；无残留 3 天·内心行换气 [IntentRules.FADE_COOLDOWN_MS]；RESOLVED 不在此清·K-8）
         * → 3 晋升（`bornAt < now`·E51）
         * → 4 全清（userText 短消息整句）。原第 5 了结 / 第 6 表达已删（J4：层 ② 是唯一入口）。
         *
         * 消退口径（J5·用户拍板 ③）：`residue = kind ∈ RESIDUE_KINDS`（想分享不留）；`lastChangeAt` 记**可算出的消退时刻**
         * `fadedAt = min(now, 超时时刻, 衰减到 FADE_MIN 的时刻)`（久别 30 天回来的残留当场因 TTL 过期清理、不补挂）。
         */
        internal fun advance(q: IntentQueueState, now: Long, userText: String): IntentQueueState {
            // 0. 钳未来时间：时钟回拨留下的 bornAt / lastChangeAt 在未来 ⇒ 钳到 now（下一 tick 才晋升·E12）
            var list = q.intents.map { i ->
                if (i.bornAt > now || i.lastChangeAt > now) i.copy(bornAt = minOf(i.bornAt, now), lastChangeAt = minOf(i.lastChangeAt, now)) else i
            }
            // 1. 消退：live 态且（effective < FADE_MIN ∨ 挂满 7 天）⇒ FADED；强度定格在当刻 effective；lastChangeAt = fadedAt
            list = list.map { i ->
                if (isLiveState(i.state) &&
                    (IntentRules.effectiveStrength(i, now) < IntentRules.FADE_MIN || now - i.bornAt >= IntentRules.TIMEOUT_MS)
                ) {
                    val timeoutAt = i.bornAt + IntentRules.TIMEOUT_MS
                    val decayAt = if (i.strength >= IntentRules.FADE_MIN) {
                        i.lastChangeAt + (IntentRules.halfLifeOf(i.kind) * log2(i.strength.toDouble() / IntentRules.FADE_MIN)).toLong()
                    } else {
                        i.lastChangeAt
                    }
                    val fadedAt = minOf(now, timeoutAt, decayAt).coerceAtLeast(i.lastChangeAt)
                    i.copy(
                        state = IntentState.FADED, residue = i.kind in IntentRules.RESIDUE_KINDS,
                        strength = IntentRules.effectiveStrength(i, now), lastChangeAt = fadedAt,
                    )
                } else {
                    i
                }
            }
            // 2. 清理：残留过 7 天清；无残留的 FADED 保留 FADE_COOLDOWN_MS（3 天·内心行换气：供 birth 同类冷却判定）后清
            list = list.filterNot {
                it.state == IntentState.FADED &&
                    now - it.lastChangeAt >= (if (it.residue) IntentRules.RESIDUE_TTL_MS else IntentRules.FADE_COOLDOWN_MS)
            }
            // 3. 晋升：萌生后的第一个回合尾转活跃（K-4）；bornAt 在未来不晋升（E51）
            list = list.map { i -> if (i.state == IntentState.BUDDING && i.bornAt < now) i.copy(state = IntentState.ACTIVE) else i }
            // 4. 全清（E18·J4 短消息口径）：trim 后 ≤ CLEAR_MAX_LEN 字且含全清词 ⇒ 移除全部 live 态；RESOLVED / FADED 留
            val text = userText.trim()
            if (text.isNotEmpty() && text.length <= IntentRules.CLEAR_MAX_LEN && IntentRules.CLEAR_ALL_WORDS.any { text.contains(it) }) {
                list = list.filterNot { isLiveState(it.state) }
            }
            return q.copy(intents = list)
        }

        /**
         * 层 ②（§3.4 `applyStatus` · 纯函数）：对每个 `(key, value)` 找该 kind 的 live 条目——`resolved` ⇒ RESOLVED / 强度 0；
         * `expressed` ⇒ 仅 BUDDING / ACTIVE 砍半转 EXPRESSED（已 EXPRESSED 不动·E55）；`open` / 其它不动。未知 key / 无 live ⇒ 跳过（E37 / E38）。
         */
        internal fun applyStatus(q: IntentQueueState, status: Map<String, String>, now: Long): IntentQueueState {
            if (status.isEmpty()) return q
            val list = q.intents.toMutableList()
            for ((key, value) in status) {
                val kind = IntentKind.fromKey(key) ?: continue
                val idx = list.indexOfFirst { it.kind == kind && IntentRules.isLive(it, now) }
                if (idx < 0) continue
                val i = list[idx]
                list[idx] = when (value) {
                    "resolved" -> i.copy(state = IntentState.RESOLVED, strength = 0, lastChangeAt = now)
                    "expressed" -> if (i.state == IntentState.BUDDING || i.state == IntentState.ACTIVE) {
                        i.copy(state = IntentState.EXPRESSED, strength = halved(i, now), lastChangeAt = now)
                    } else {
                        i
                    }
                    else -> i
                }
            }
            return q.copy(intents = list)
        }

        /**
         * 萌生（§3.3 / §3.4 `birth` · 纯函数 · 表序）：本次 [hits] + 落用后的 [field] 逐条判规则，一次最多 [IntentRules.MAX_BIRTHS_PER_ANALYSIS] 个；
         * 同 kind RESOLVED 后 24h 冷却（E40）/ 同 kind FADED 后 3 天冷却（内心行换气·治同一批命中每周重生同一句）；
         * 同 kind 已 live ⇒ 刷新不重复不计数（E39）；队列满 3 时新来者须比最弱者强才顶替（E16）。
         * 强度按命中集合里的最高档位取 40 / 50 / 60（K-22）。
         */
        internal fun birth(q: IntentQueueState, hits: List<String>, gains: PersonaGains, field: AffectField, now: Long): IntentQueueState {
            val hitSet = hits.toSet()
            var list = q.intents
            var births = 0
            for (rule in IntentRules.BIRTH_RULES) {
                if (births >= IntentRules.MAX_BIRTHS_PER_ANALYSIS) break
                if (!rule.matches(hitSet, field)) continue
                val kind = rule.kind
                val s0 = (hitSet intersect rule.keys)
                    .maxOfOrNull { IntentRules.strengthForLevel(gains.system[it] ?: PersonaVocab.LEVEL_NORMAL) }
                    ?: IntentRules.strengthForLevel(PersonaVocab.LEVEL_NORMAL)
                if (list.any {
                        it.kind == kind && (
                            (it.state == IntentState.RESOLVED && now - it.lastChangeAt < IntentRules.COOLDOWN_MS) ||
                                (it.state == IntentState.FADED && now - it.lastChangeAt < IntentRules.FADE_COOLDOWN_MS)
                            )
                    }
                ) {
                    continue
                }
                val liveIdx = list.indexOfFirst { it.kind == kind && IntentRules.isLive(it, now) }
                if (liveIdx >= 0) {
                    val i = list[liveIdx]
                    list = list.toMutableList().also {
                        it[liveIdx] = i.copy(strength = maxOf(IntentRules.effectiveStrength(i, now), s0), lastChangeAt = now)
                    }
                    continue
                }
                val fresh = CharacterIntent(
                    id = UUID.randomUUID().toString(), kind = kind, state = IntentState.BUDDING,
                    strength = s0, bornAt = now, lastChangeAt = now,
                )
                val live = list.filter { IntentRules.isLive(it, now) }
                if (live.size >= IntentRules.QUEUE_CAP) {
                    val weakest = live.minWith(compareBy<CharacterIntent> { IntentRules.effectiveStrength(it, now) }.thenBy { it.bornAt })
                    if (s0 <= IntentRules.effectiveStrength(weakest, now)) continue
                    list = list.filterNot { it.id == weakest.id } + fresh
                } else {
                    list = list + fresh
                }
                births++
            }
            return q.copy(intents = list)
        }

        /** 分析通道末（K-8）：移除 RESOLVED 且已过 24h 冷却的条目——记账已在前一步完成。 */
        internal fun pruneResolved(q: IntentQueueState, now: Long): IntentQueueState =
            q.copy(intents = q.intents.filterNot { it.state == IntentState.RESOLVED && now - it.lastChangeAt >= IntentRules.COOLDOWN_MS })

        /**
         * growthLog 行（K-21 · 只在分析通道）：萌生 = after 有 before 无（按 id）；了结 / 消退 = after 里 RESOLVED / FADED 且
         * `lastChangeAt > lastAnalysisDate`（水位·恰记一次）。每行 = `{第三人称短句}（萌生|了结|消退）`，type = RELATIONSHIP_CHANGE。
         */
        internal fun logLines(
            before: IntentQueueState,
            after: IntentQueueState,
            lastAnalysisDate: Long?,
            charName: String,
            userName: String,
            now: Long,
        ): List<GrowthLogEntry> {
            val floor = lastAnalysisDate ?: 0L
            val beforeIds = before.intents.map { it.id }.toSet()
            val lines = mutableListOf<String>()
            for (i in after.intents) {
                val third = IntentScripts.thirdPerson(i.kind, charName, userName)
                when {
                    i.id !in beforeIds -> lines += "$third（萌生）"
                    i.state == IntentState.RESOLVED && i.lastChangeAt > floor -> lines += "$third（了结）"
                    i.state == IntentState.FADED && i.lastChangeAt > floor -> lines += "$third（消退）"
                }
            }
            return lines.map { GrowthLogEntry(timestamp = now, type = GrowthEventType.RELATIONSHIP_CHANGE, summary = it) }
        }
    }
}
