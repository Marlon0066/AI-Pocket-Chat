package com.situ.aichat.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.situ.aichat.prompt.memory.MeetingArchiveVectorService
import com.situ.aichat.prompt.memory.VectorMemoryService
import com.situ.aichat.world.link.WorldMemoryEmbedder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 向量记忆 embedding 的后台分批回填（12.3，1:1 iOS `VectorMemoryEmbeddingActor` 的启动回填）。
 *
 * 为何需要：导入【旧版备份】（无 embedding 字段）或嵌入器曾不可用时，历史消息 embedding 为 NULL；而每轮聊天只嵌
 * 【当轮】消息（懒路径），永不回访历史 → 这些历史永远语义检索不到（「导入备份后历史记忆失忆」缺口）。新版备份已随
 * 包带 embedding（无需回填）；本 worker 是兜底自愈层。
 *
 * 入队两路（[com.situ.aichat.ui.AppViewModel] 冷启动一次性 + [com.situ.aichat.ui.backup.BackupViewModel] 导入成功后）。
 * 纯本地 ONNX 推理 → 不需联网（requireNetwork=false）。worker 极轻：[VectorMemoryService.backfillMissingEmbeddings]
 * 先 EXISTS 秒探测，无缺失则直接返回、绝不加载 24MB 模型；分批让片不抢前台。HyperOS 永不调度后台也无妨——下次
 * 冷启动会再排一次（KEEP）。
 */
@HiltWorker
class EmbeddingBackfillWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val vectorMemory: VectorMemoryService,
    private val worldMemoryEmbedder: WorldMemoryEmbedder,
    private val meetingArchiveVector: MeetingArchiveVectorService,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            // 先做模型签名变更检测（换模型→清空旧向量），再回填，让回填用新模型重嵌（对齐 iOS runStartupTasks 次序）。
            vectorMemory.detectModelChangeAndClearIfNeeded(applicationContext)
            // 图纸 2026-09-01 件④：一次性洗白历史被瞬态失败冤枉的哨兵，让紧随其后的回填按新规则复评。
            vectorMemory.washWronglySentineledOnce(applicationContext)
            vectorMemory.backfillMissingEmbeddings()
            worldMemoryEmbedder.backfillMissing() // W5：世界记忆嵌入限流回填（同款分批让片·空批秒退）
            meetingArchiveVector.backfillMissing() // 记忆改造四期·部件⑥：见面档案向量回填（同款分批让片·空批秒退）
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "嵌入回填失败: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "EmbeddingBackfill"

        /** 冷启动 / 导入后一次性回填唯一任务名。 */
        const val UNIQUE_ENSURE = "embedding_backfill_ensure"
    }
}
