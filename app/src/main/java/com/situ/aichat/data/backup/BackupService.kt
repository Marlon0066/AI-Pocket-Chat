package com.situ.aichat.data.backup

import com.situ.aichat.maintenance.FirstMessageDateBackfill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of an import, surfaced to the UI. */
sealed interface ImportResult {
    /**
     * 逐策略计数（1:1 iOS `ImportResult`）：[imported]=无冲突新导入 / [overwritten]=覆盖 / [duplicated]=创建副本 /
     * [skipped]=跳过。[messages]=实际写入的消息条数（跳过的不计）。
     *
     * [mediaFailed]=未能恢复的媒体条数（卷 A）：单条媒体坏了不再拖垮整包导入，但**必须如实告诉用户**——
     * 「导入成功」却悄悄少了几张图/几条语音，是隐瞒数据丢失。0 = 一条不缺（结果区不出现警示行）。
     */
    data class Success(
        val imported: Int,
        val overwritten: Int,
        val duplicated: Int,
        val skipped: Int,
        val messages: Int,
        val mediaFailed: Int = 0,
    ) : ImportResult {
        /** 实际落库的角色数（新导入 + 覆盖 + 副本；跳过不计）——供非预览/旧 .json 状态行速览。 */
        val characters: Int get() = imported + overwritten + duplicated
    }

    data class Error(val message: String) : ImportResult
}

/**
 * 全量备份门面（文件瘦身刀1–5 收口）：导出/导入/恢复全部委托给 [BackupExporter] / [BackupImporter] 两个单一职责
 * 协作者，自身只保留公开 API 入口与 [ImportResult] 结果类。两调用方（AutoBackupWorker 导出 / BackupViewModel
 * 导出+预览+导入）经此门面调用，签名逐字不变。导出纯只读；导入/恢复事务化全成或全回滚、💰钱还原 1:1 镜像 iOS
 * （详见各协作者类 KDoc）。
 */
@Singleton
class BackupService @Inject constructor(
    private val exporter: BackupExporter,
    private val importer: BackupImporter,
    private val firstMessageDateBackfill: FirstMessageDateBackfill,
) {
    // ════════════════════════════════ EXPORT（委托 BackupExporter） ════════════════════════════════

    /** 流式导出全量备份到 [out]（委托 [BackupExporter.exportTo]；[out] 所有权归调用方·本函数不 close）。 */
    suspend fun exportTo(
        out: OutputStream,
        includeMedia: Boolean = true,
        onProgress: ((BackupProgress) -> Unit)? = null,
    ) = exporter.exportTo(out, includeMedia, onProgress)

    /** 原子导出（cache 临时文件 → SAF 目标，失败/取消删目标；委托 [BackupExporter.exportAtomic]）。 */
    suspend fun exportAtomic(
        includeMedia: Boolean,
        onProgress: ((BackupProgress) -> Unit)? = null,
        openOut: suspend () -> OutputStream?,
        deleteTarget: () -> Unit,
    ): Boolean = exporter.exportAtomic(includeMedia, onProgress, openOut, deleteTarget)

    // ════════════════════════════════ IMPORT / RESTORE（委托 BackupImporter） ════════════════════════════════

    /**
     * 这个源现在还打得开吗（文件被移走 / SAF 授权失效 → false）。UI 侧据此给「读取失败」提示——卷 A 之后
     * 不再有「先把整包读进内存」那一步，这就是它留下的唯一探针。开流是磁盘/SAF IO，故收在这层做（VM 只编排状态）。
     */
    suspend fun canOpen(source: BackupByteSource): Boolean = withContext(Dispatchers.IO) {
        val stream = runCatching { source.open() }.getOrNull() ?: return@withContext false
        runCatching { stream.close() }
        true
    }

    /** 旧明文 .json 覆盖式导入（委托 [BackupImporter.import]）；成功后补一次「第一次聊天时间」（相识天数图纸 D-4·老包该字段为空）。 */
    suspend fun import(jsonStr: String): ImportResult =
        importer.import(jsonStr).also { if (it is ImportResult.Success) runCatching { firstMessageDateBackfill.run() } }

    /**
     * zip 备份冲突预览（不写库·委托 [BackupImporter.previewArchive]）；非 zip / 损坏 / 版本过高 → null。
     * [source] = 可重开的字节源（卷 A：预览与确认各自重开一条流，整包字节绝不驻留内存）。
     */
    suspend fun previewArchive(source: BackupByteSource): BackupPreview? = importer.previewArchive(source)

    /**
     * zip 全量恢复（两遍流式·事务化·逐角色策略·委托 [BackupImporter.importArchive]）；非 zip 回退旧 .json。
     * 成功后补一次「第一次聊天时间」（相识天数图纸 D-4）：补账抛异常吞掉，不改 [ImportResult]。
     */
    suspend fun importArchive(
        source: BackupByteSource,
        strategies: Map<String, ImportStrategy> = emptyMap(),
        onProgress: ((BackupProgress) -> Unit)? = null,
    ): ImportResult = importer.importArchive(source, strategies, onProgress)
        .also { if (it is ImportResult.Success) runCatching { firstMessageDateBackfill.run() } }
}
