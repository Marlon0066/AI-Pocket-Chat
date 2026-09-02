package com.situ.aichat.prompt.memory

import com.situ.aichat.data.local.dao.OurDayDao
import com.situ.aichat.data.local.entity.OurDayEntity
import com.situ.aichat.ourdays.OurDayKey
import kotlinx.coroutines.delay
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「我们的日子」向量索引（卷二图纸 §3.6·W-1·逐字仿 [MeetingArchiveVectorService]）：事实行（[OurDayEntity.factLine]）建向量，
 * 聊到相关话题时作为**第三路候选**与消息 / 见面档案在 [VectorMemoryService] 的同一个 TOP_K 池竞争。
 *
 * 职责边界：不碰消息表、不做合并排序（单池合并在 [VectorMemoryService]）、不调 LLM；只写 `our_days.embedding` 一列。
 * 嵌入编码 = [VectorMemoryService.serializeEmbedding]（float32 小端·与消息 / 档案同款）；回填由
 * [com.situ.aichat.work.EmbeddingBackfillWorker] 末尾调用；换模型 [clearAll] 并入 CLEAR_AND_REEMBED 三清。
 * 「别让 TA 记」/ 墓碑行：DAO 谓词挡候选与回填（E13），置位时 embedding 已被卷一 DAO 置 NULL。
 * 时区 / 今天只取注入的 [Clock]（W-9）：窗口排除与「今天永不出」都按日键比较。
 */
@Singleton
class OurDayVectorService @Inject constructor(
    private val dao: OurDayDao,
    private val embedder: TextEmbedder,
    private val clock: Clock,
) {

    /** 嵌入源文本（锁定·纯函数·T1-4）：事实行 trim。不含日期前缀（片段格式在 [VectorMemoryService.formatOurDaySnippet]）。 */
    internal fun embedSource(row: OurDayEntity): String = row.factLine.trim()

    /** 回填缺嵌入行（照 [MeetingArchiveVectorService.backfillMissing]：可用性先探 / 空批退 / 失败即停 / 批 16 / 批间让片 50ms）。 */
    suspend fun backfillMissing() {
        if (!embedder.isAvailable) return
        while (true) {
            val batch = dao.missingEmbedding(BATCH_SIZE)
            if (batch.isEmpty()) return // 空批退出
            for (row in batch) {
                val vector = embedder.embed(embedSource(row)) ?: return // 不可用/失败 → 停（避免空跑死循环）
                dao.updateEmbedding(row.uuid, VectorMemoryService.serializeEmbedding(vector))
            }
            delay(BACKFILL_YIELD_MS)
        }
    }

    /** 模型签名变更清空（由 [VectorMemoryService.detectModelChangeAndClearIfNeeded] 的 CLEAR_AND_REEMBED 分支调）。 */
    suspend fun clearAll(): Int = dao.clearAllEmbeddings()

    /** 单条第三路候选（[dayKey] 作时间标·[factLine] 原文·[similarity] 余弦分）。 */
    data class DayCandidate(val dayKey: String, val factLine: String, val similarity: Double)

    /** 第三路候选（供 [VectorMemoryService] 全部并池后统一 take(TOP_K)·不单独截断）。 */
    data class Retrieval(val candidates: List<DayCandidate>)

    /**
     * 第三路候选（锁定算法·图纸 §3.6 / W-8）：排除今天（`dayKey >= todayKey`）与原文窗口起日及之后
     * （[windowCutoffMillis] 与消息路同一 cutoff 源·按 `clock.zone` 转日键·null = 不排除）；阈值 / 维度不符 / 坏向量跳过。
     */
    suspend fun retrieval(queryEmbedding: FloatArray, characterUuid: String, threshold: Double, windowCutoffMillis: Long?): Retrieval {
        val excludeFrom = windowCutoffMillis?.let { OurDayKey.dayKey(it, clock.zone) }
        val todayKey = OurDayKey.dayKey(clock.millis(), clock.zone)
        val candidates = dao.embeddedForCharacter(characterUuid).mapNotNull { r ->
            if (r.dayKey >= todayKey) return@mapNotNull null
            if (excludeFrom != null && r.dayKey >= excludeFrom) return@mapNotNull null
            val emb = r.embedding?.let(VectorMemoryService::deserializeEmbedding) ?: return@mapNotNull null
            if (emb.size != queryEmbedding.size) return@mapNotNull null
            val sim = VectorMemoryService.cosineSimilarity(queryEmbedding, emb)
            if (sim < threshold) return@mapNotNull null
            DayCandidate(r.dayKey, r.factLine, sim)
        }
        return Retrieval(candidates)
    }

    companion object {
        /** 每批条数（§9.2 锁定 16·照 MeetingArchiveVectorService）。 */
        private const val BATCH_SIZE = 16

        /** 批间让片毫秒（§9.2 锁定 50ms）。 */
        private const val BACKFILL_YIELD_MS = 50L
    }
}
