package com.situ.aichat.promise

import com.situ.aichat.data.local.entity.OpenLoopEntity
import com.situ.aichat.data.local.entity.OpenLoopStatus
import com.situ.aichat.data.local.entity.OpenLoopType
import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.local.entity.PromiseSource
import com.situ.aichat.data.local.entity.PromiseStatus
import com.situ.aichat.data.local.dao.OfflineMeetingMemoryDao
import com.situ.aichat.data.repository.OpenLoopRepository
import com.situ.aichat.data.repository.PromiseRepository
import com.situ.aichat.util.StringListJson
import com.situ.aichat.work.BackgroundScheduler
import com.situ.aichat.work.OpenLoopDueWorker
import androidx.work.ExistingWorkPolicy
import java.time.Duration
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 承诺账本写入口（记忆改造一期·部件①/②·图纸 §3.2）：注册（金额守卫 + 去重 + 惦记桥）、对账结果落库、
 * 见面便车注册。纯函数（对账裁决 / 注入渲染）分别在 [PromiseReconciliation] / [PromiseInjectionRenderer]。
 *
 * **惦记桥**（[linkOrCreateLoop]·四期起：等值 open loop 恒关联，仅未来日期无等值时才新建）：只经既有
 * [OpenLoopRepository] 与 [BackgroundScheduler] API 新增调用，惦记系统表结构 / 扫描提示词 / 注入选择 /
 * 到期送达五守卫零碰（图纸 §2.3-4）。
 *
 * **日志纪律**：绝不打约定内容 / 素材内容 / 任何用户文本——只打计数 / uuid / 状态（图纸 §5·E20）。
 */
@Singleton
class PromiseLedgerService @Inject constructor(
    private val promiseRepository: PromiseRepository,
    private val openLoopRepository: OpenLoopRepository,
    private val backgroundScheduler: BackgroundScheduler,
    private val offlineMeetingMemoryDao: OfflineMeetingMemoryDao,
) {
    /**
     * 惦记桥（记忆改造四期·§3.5-①·体逐字来自旧 register ⑤ 新建分支·只搬不改）：找该角色等值 open loop →
     * 返其 uuid（关联）；没有且 [dueAtMillis] 在未来 → 新建 + 排到点 worker → 返新 uuid；否则 null。
     *
     * 与旧 register ⑤ 的**唯一行为差异**：等值 open loop 查找不再被「仅未来日期」前置门拦——无日期 / 过期约定
     * 现在也能链接到既存等值 loop（了结时联动 resolve·§3.5-②治理第三刀）；不新建 loop（惦记 14 天短线语义不变）。
     */
    private suspend fun linkOrCreateLoop(
        characterUuid: String,
        conversationUuid: String,
        content: String,
        dueAtMillis: Long?,
        now: Long,
    ): String? {
        val normalized = normalize(content)
        val existing = openLoopRepository.openLoopsForCharacter(characterUuid)
            .firstOrNull { normalize(it.content) == normalized }
        if (existing != null) return existing.uuid
        if (dueAtMillis == null || dueAtMillis <= now) return null
        val loop = OpenLoopEntity(
            uuid = UUID.randomUUID().toString(),
            conversationUuid = conversationUuid,
            characterUuid = characterUuid,
            content = content,
            typeRaw = OpenLoopType.PROMISE_CHAR,
            dueAt = dueAtMillis,
            statusRaw = OpenLoopStatus.OPEN,
            createdAt = now,
        )
        openLoopRepository.upsert(loop)
        backgroundScheduler.scheduleOneShot(
            uniqueName = OpenLoopDueWorker.uniqueName(loop.uuid),
            workerClass = OpenLoopDueWorker::class.java,
            initialDelay = Duration.ofMillis(dueAtMillis - now),
            requireNetwork = true,
            existingPolicy = ExistingWorkPolicy.KEEP,
            inputData = OpenLoopDueWorker.inputData(loop.uuid),
        )
        return loop.uuid
    }

    /**
     * 注册一条约定（图纸 §3.2）。守卫顺序：① 空 → null；② 金额守卫命中 → null；③ 与该角色全部 open 行去空白等值
     * → null（去重）；④ 落库；⑤ 惦记桥（[linkOrCreateLoop]·四期起无日期也链接等值 loop·§3.5-②）。返回落库的实体，被守卫拦下 → null。
     */
    suspend fun register(
        characterUuid: String,
        conversationUuid: String,
        content: String,
        dueAtMillis: Long?,
        sourceRaw: String,
        sourceSessionId: String,
        now: Long,
    ): PromiseEntity? {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return null // ① 空
        if (AMOUNT_GUARD.containsMatchIn(trimmed)) return null // ② 金额守卫
        val open = promiseRepository.openByCharacter(characterUuid)
        val normalizedNew = normalize(trimmed)
        if (open.any { normalize(it.content) == normalizedNew }) return null // ③ 去重（去空白等值）

        // ⑤ 惦记桥（四期·§3.5-②·抽为 linkOrCreateLoop）：等值 loop → 关联；无且未来 → 新建 + 排 worker；否则 null。
        val openLoopUuid = linkOrCreateLoop(characterUuid, conversationUuid, trimmed, dueAtMillis, now)

        // ④ 落库（openLoopUuid 已在⑤确定 → 单次 upsert 写入最终值）。
        val promise = PromiseEntity(
            uuid = UUID.randomUUID().toString(),
            characterUuid = characterUuid,
            conversationUuid = conversationUuid,
            content = trimmed,
            statusRaw = PromiseStatus.OPEN,
            dueAtMillis = dueAtMillis,
            sourceRaw = sourceRaw,
            sourceSessionId = sourceSessionId,
            openLoopUuid = openLoopUuid,
            resolvedAtMillis = null,
            resolutionEvidence = "",
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        promiseRepository.upsert(promise)
        return promise
    }

    /**
     * 见面便车注册（图纸 §3.2）：逐条调 [register]（sourceRaw = meeting·dueAtMillis = null——见面 schema 无日期字段，
     * 为保 schema 零碰一期不解析日期）。去重在注册端。
     */
    suspend fun registerFromMeeting(
        characterUuid: String,
        conversationUuid: String,
        sessionId: String,
        promises: List<String>,
        now: Long,
    ) {
        for (p in promises) {
            register(
                characterUuid = characterUuid,
                conversationUuid = conversationUuid,
                content = p,
                dueAtMillis = null,
                sourceRaw = PromiseSource.MEETING,
                sourceSessionId = sessionId,
                now = now,
            )
        }
    }

    /**
     * 对账结果落库（图纸 §3.2）。每条 change：byUuid 重读 → 仅当仍为 open 才写状态 / 了结时间 / 证据；关联 loop
     * 仍为 open 才 markResolved（非 open → no-op·E16）。每条 new：调 [register]（sourceRaw = chat）。
     */
    suspend fun applyReconciliation(
        characterUuid: String,
        conversationUuid: String,
        verified: PromiseReconciliation.Verified,
        now: Long,
    ) {
        for (change in verified.changes) applyChange(change, now)
        // dates（记忆改造四期·§3.5-③·补日期第三路）：每条 byUuid 重读 → 仍 open 且 due 仍空才写（重读双守卫·陈旧/并发防护）。
        // 已有 openLoopUuid 的不重桥、不改 loop 的 dueAt（loop 短线自治，容忍）；无 loop 且新 due 在未来 → linkOrCreateLoop 建。
        // **绝不写 resolutionEvidence**——三期「证据空=手动」推断不变量继续成立；dates 证据只做闸门不落库（§3.5-③）。
        for (d in verified.dates) {
            val current = promiseRepository.byUuid(d.promiseUuid) ?: continue
            if (current.statusRaw != PromiseStatus.OPEN || current.dueAtMillis != null) continue // 重读双守卫
            val loopUuid = current.openLoopUuid ?: linkOrCreateLoop(characterUuid, conversationUuid, current.content, d.dueAtMillis, now)
            promiseRepository.upsert(
                current.copy(dueAtMillis = d.dueAtMillis, openLoopUuid = loopUuid, updatedAtMillis = now),
            )
        }
        for (n in verified.newPromises) {
            register(
                characterUuid = characterUuid,
                conversationUuid = conversationUuid,
                content = n.content,
                dueAtMillis = n.dueAtMillis,
                sourceRaw = PromiseSource.CHAT,
                sourceSessionId = "",
                now = now,
            )
        }
    }

    /**
     * 单条状态变更落库（体 = 原 [applyReconciliation] changes 循环体·**只搬不改**）：byUuid 重读 → 仅当仍为 open
     * 才写状态 / 了结时间 / 证据；关联 loop 仍 open 才 markResolved（非 open → no-op·E16）。
     *
     * 2026-09-06 约定工具调用化抽出：聊天内 `resolve_promise` 工具路与攒批对账走**同一条落库路**（编号过期 /
     * 已被并发了结 → 重读非 open → 零写返回 false）。
     * @return true = 本次写入生效；false = 目标不存在 / 已非 open（全部零写返回）。
     */
    suspend fun applyChange(change: PromiseReconciliation.VerifiedChange, now: Long): Boolean {
        val current = promiseRepository.byUuid(change.promiseUuid) ?: return false
        if (current.statusRaw != PromiseStatus.OPEN) return false // 仍 open 才写（陈旧防护）
        promiseRepository.upsert(
            current.copy(
                statusRaw = change.status,
                resolvedAtMillis = now,
                resolutionEvidence = change.evidence,
                updatedAtMillis = now,
            ),
        )
        current.openLoopUuid?.let { loopUuid ->
            val loop = openLoopRepository.byUuid(loopUuid) ?: return@let
            if (loop.statusRaw == OpenLoopStatus.OPEN) openLoopRepository.markResolved(loop, now) // 非 open → no-op
        }
        return true
    }

    /**
     * 手动兜底（记忆改造三期·D-5·对账第四道闸）：用户在约定页把一条进行中约定标为已兑现 / 已取消。
     * 照 [applyReconciliation] 先例：byUuid 重读 → 仍 open 才写 → 关联 loop 仍 open 才 markResolved（E16 no-op）。
     * [PromiseEntity.resolutionEvidence] **恒不写（保持 ""）**——「证据空 = 手动标记」是三期 UI 判定方式推断的闭环不变量
     * （对账路径闸二保证证据 ≥6 字·见 [PromiseReconciliation]），新增第三个非 open 状态写者须重审此不变量。
     * @return true = 本次写入生效；false = 目标不存在 / 已非 open / statusRaw 非法（全部零写返回）。
     */
    suspend fun resolveManually(promiseUuid: String, statusRaw: String, now: Long): Boolean {
        if (statusRaw != PromiseStatus.FULFILLED && statusRaw != PromiseStatus.CANCELLED) return false
        val current = promiseRepository.byUuid(promiseUuid) ?: return false
        if (current.statusRaw != PromiseStatus.OPEN) return false // 仍 open 才写（陈旧防护·与对账并发安全）
        promiseRepository.upsert(
            current.copy(statusRaw = statusRaw, resolvedAtMillis = now, updatedAtMillis = now),
        )
        current.openLoopUuid?.let { loopUuid ->
            val loop = openLoopRepository.byUuid(loopUuid) ?: return@let
            if (loop.statusRaw == OpenLoopStatus.OPEN) openLoopRepository.markResolved(loop, now) // 非 open → no-op
        }
        return true
    }

    /**
     * 历史回填（一次性·图纸 §3.2）：把存量见面档案 `promisesJson` 里的约定注册进账本。按 startedAtMillis 升序，
     * 取 promisesJson 非空数组行，逐条 register（sourceRaw = meeting_backfill·createdAtMillis = 行 endedAtMillis
     * [为 0 用 startedAtMillis]·dueAtMillis = null·sourceSessionId = 行 sessionId）；返回注册成功计数。不做 ensureSeeded
     * （legacy 行 promisesJson 恒 []，播种不产约定）。重跑靠注册端去重挡住（幂等）。[now] 由 Worker 传入（签名一致·未直接消费）。
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun backfillFromMeetingRows(now: Long): Int {
        var count = 0
        val rows = offlineMeetingMemoryDao.getAll().sortedBy { it.startedAtMillis }
        for (row in rows) {
            val promises = StringListJson.decode(row.promisesJson)
            if (promises.isEmpty()) continue
            val rowTime = if (row.endedAtMillis != 0L) row.endedAtMillis else row.startedAtMillis
            for (content in promises) {
                val registered = register(
                    characterUuid = row.characterUuid,
                    conversationUuid = row.conversationUuid,
                    content = content,
                    dueAtMillis = null,
                    sourceRaw = PromiseSource.MEETING_BACKFILL,
                    sourceSessionId = row.sessionId,
                    now = rowTime,
                )
                if (registered != null) count++
            }
        }
        return count
    }

    companion object {
        /**
         * 金额守卫正则（图纸 §3.2-A·锁定）：命中即拒绝注册。无 look-behind、无裸 Emoji 类、不依赖 `\s` 匹配 CJK
         * （ICU 陷阱清单合规）。
         */
        val AMOUNT_GUARD = Regex("红包|转账|\\d+(\\.\\d+)?\\s*(元|块钱|金币)|[¥￥]")

        /** 去重归一化（图纸 §3.2）：去全部空白。 */
        fun normalize(s: String): String = s.replace(Regex("\\s+"), "")
    }
}
