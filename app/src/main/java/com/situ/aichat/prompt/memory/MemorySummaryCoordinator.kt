package com.situ.aichat.prompt.memory

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.remote.llm.ApiConfigValues
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记忆总结写回校验失败的错误类型（确定性，不重试）。对齐 iOS MemorySummaryError。
 */
sealed class MemorySummaryError(message: String) : Exception(message) {
    /** LLM 返回空内容。 */
    data object EmptyResponse : MemorySummaryError("Memory summary returned empty content.")

    /** 新记忆远短于旧记忆，疑似截断或废话。 */
    data object SuspiciouslyShort : MemorySummaryError("Memory summary was too short and may be corrupted. Existing memory preserved.")

    /**
     * 新记忆超硬上限且压缩自救/自愈/泄压阀全部用尽（批1 1-4 + 记忆护栏 G3）——游标不推进、进失败短冷却；
     * 抛出前若瘦身稿可用会先落底（旧记忆被替换为其压缩版，事实不丢），否则保旧记忆。
     */
    data object TooLong : MemorySummaryError("Memory summary exceeded the relief cap even after recompression and base slimming. Cursor not advanced.")
}

/** 手动编辑写回结果（图纸 2026-09-01 件③）。 */
sealed interface ManualEditResult {
    data object Saved : ManualEditResult

    /** 编辑期间库内已被自动整理改写；[current] = 库内当前文本（供「查看新版」重载）。 */
    data class Conflict(val current: String) : ManualEditResult

    data object CharacterGone : ManualEditResult
}

/**
 * 1:1 port of iOS `MemorySummaryCoordinator`：统一「生成 → 校验 → 写回 → 标记」，降低调用方分叉。
 *
 * [existingMemoryOverride] + [writeBack] 为见面记忆独立管线（M16）预留：传入后读/写指定字段而非
 * `character.memorySummary`，从而与主摘要分预算。当前 M16 未移植，主聊天路径都用默认写回。
 */
@Singleton
class MemorySummaryCoordinator @Inject constructor(
    private val memoryService: MemoryService,
    private val characterDao: CharacterDao,
) {
    /**
     * 批3 3-4：每角色摘要互斥——聊天触发 / 语音通话触发 / 见面摘要重试三路各有独立防并发旗标，跨路径仍可
     * 并发「读旧记忆→生成→写回」，低概率互相覆盖丢一路合并结果。锁只在摘要路径间竞争（不用 CharacterWriteLock：
     * 那把锁服务快速 RMW，绝不能被秒级 LLM 调用长占）。
     */
    private val perCharacterLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    /**
     * 生成并写回记忆摘要，完成后执行 [markSummarized]（推进游标）。
     * @return 写回的新记忆（trim 后）。
     * @throws MemorySummaryError 空内容 / 过短（< 旧记忆 5%）→ 由调用方进入 5 分钟短冷却。
     */
    suspend fun summarizeAndPersist(
        character: CharacterEntity,
        messages: List<MessageEntity>,
        config: ApiConfigValues,
        maxLength: Int,
        customPrompt: String = "",
        progressiveCompressionEnabled: Boolean = false,
        characterName: String = "",
        userName: String = "",
        /** 消化素材（记忆改造一期·图纸 §3.6）：透传给 [MemoryService.generateMemorySummary] 拼进 {{聊天记录}}；校验链零碰。 */
        extraMaterial: String = "",
        existingMemoryOverride: String? = null,
        writeBack: (suspend (character: CharacterEntity, oldMemory: String, newMemory: String) -> Unit)? = null,
        markSummarized: suspend () -> Unit,
    ): String = perCharacterLocks.getOrPut(character.uuid) { Mutex() }.withLock {
        // 批3 3-4：锁内重读最新常规记忆——调用方传入的 character 是锁外快照，不重读则锁只保护写、读仍陈旧
        // （前一路刚合并完的结果会被本路的旧快照当底稿覆盖）。见面记忆走 override，由其自身管线保证串行。
        val existingMemory = existingMemoryOverride
            ?: (characterDao.getByUuid(character.uuid)?.memorySummary ?: character.memorySummary)

        val newMemory = memoryService.generateMemorySummary(
            existingMemory = existingMemory,
            newMessages = messages,
            config = config,
            maxLength = maxLength,
            customPrompt = customPrompt,
            characterName = characterName,
            userName = userName,
            progressiveCompressionEnabled = progressiveCompressionEnabled,
            extraMaterial = extraMaterial,
        )

        // 验证 LLM 返回内容，防止空串或垃圾覆盖已有记忆
        var trimmed = newMemory.trim()
        // 验证1：新记忆不能为空
        if (trimmed.isEmpty()) throw MemorySummaryError.EmptyResponse
        // 验证2：旧记忆有内容时，新记忆不能低于旧记忆长度的 5%（疑似截断/废话覆盖）
        if (existingMemory.isNotEmpty() &&
            MemoryService.cjkLength(trimmed) < MemoryService.cjkLength(existingMemory) / 20
        ) {
            throw MemorySummaryError.SuspiciouslyShort
        }
        // 验证3（批1 1-4·防无限增长，拍板 2026-07-02；自愈+泄压阀扩展拍板 2026-07-11·微图纸「记忆护栏」G3）：
        // maxLength 是代码层硬护栏而非仅模板话术。超软目标 1.2× 后逐级解套：
        // A 压缩自救 candidate（原路径）→ B 自愈：瘦身存量旧记忆并用同批消息重合并一次（合并压不动多半是旧底稿臃肿）
        // → C 泄压阀：仍超硬上限 1.5× 但 ≤ RELIEF_CAP 2.0× → 放行一次打破死锁（下轮总结自然压回）
        // → D 终败：瘦身稿可用则先落底（纯瘦身不含新消息，**绝不推进游标**），抛 TooLong 进失败短冷却。
        // maxLength ≤ 0 = 用户关闭上限。
        if (maxLength > 0 && MemoryService.cjkLength(trimmed) > (maxLength * OVERFLOW_TRIGGER).toInt()) {
            val hardCap = (maxLength * OVERFLOW_HARD).toInt()

            // A. 压缩自救（垃圾短输出不采纳，防压缩失败反噬记忆）。
            val recompressed = memoryService.compressMemory(
                memory = trimmed,
                config = config,
                maxLength = maxLength,
                characterName = characterName,
            )
            if (recompressed.isNotEmpty() &&
                MemoryService.cjkLength(recompressed) >= MemoryService.cjkLength(trimmed) / 20
            ) {
                trimmed = recompressed
            }

            // B. 自愈：瘦身旧底稿再合并一次。slim 采纳闸（微图纸锁定）：非空 且 ≥旧 5% 且 严格短于旧稿。
            var slim: String? = null
            if (MemoryService.cjkLength(trimmed) > hardCap && existingMemory.isNotEmpty()) {
                val compressedBase = memoryService.compressMemory(
                    memory = existingMemory,
                    config = config,
                    maxLength = maxLength,
                    characterName = characterName,
                )
                val baseLen = MemoryService.cjkLength(existingMemory)
                val slimLen = MemoryService.cjkLength(compressedBase)
                if (compressedBase.isNotEmpty() && slimLen >= baseLen / 20 && slimLen < baseLen) {
                    slim = compressedBase
                    val remerged = memoryService.generateMemorySummary(
                        existingMemory = compressedBase,
                        newMessages = messages,
                        config = config,
                        maxLength = maxLength,
                        customPrompt = customPrompt,
                        characterName = characterName,
                        userName = userName,
                        progressiveCompressionEnabled = progressiveCompressionEnabled,
                        extraMaterial = extraMaterial,
                    ).trim()
                    // 校验对照 slim 底稿（非空 + ≥5%）；比现有最优更短才采纳。
                    if (remerged.isNotEmpty() &&
                        MemoryService.cjkLength(remerged) >= slimLen / 20 &&
                        MemoryService.cjkLength(remerged) < MemoryService.cjkLength(trimmed)
                    ) {
                        trimmed = remerged
                    }
                }
            }

            // C/D. 泄压阀与终败。
            if (MemoryService.cjkLength(trimmed) > hardCap &&
                MemoryService.cjkLength(trimmed) > (maxLength * RELIEF_CAP).toInt()
            ) {
                // D. 终败：瘦身稿落底（不含新消息 → 不推进游标，下轮以更瘦底稿重试），保旧语义仅在 slim 不可用时成立。
                slim?.let { slimBase ->
                    if (writeBack != null) {
                        writeBack(character, existingMemory, slimBase)
                    } else {
                        characterDao.updateMemorySummary(character.uuid, existingMemory, slimBase)
                    }
                }
                throw MemorySummaryError.TooLong
            }
            // ≤ hardCap 正常放行；(hardCap, RELIEF_CAP] 为泄压阀放行一次（拍板 2026-07-11），继续走下方写回。
        }

        if (writeBack != null) {
            writeBack(character, existingMemory, trimmed)
        } else {
            // 定向两列 UPDATE（非整行 @Upsert）：避免用陈旧快照覆盖并发写入的其它列——尤其退出见面后常规摘要与
            // 见面摘要重试链可能并发，整行写会抹掉刚写的 offlineMeetingMemorySummary（D1 数据丢失）。见 CharacterDao.updateMemorySummary。
            characterDao.updateMemorySummary(character.uuid, existingMemory, trimmed)
        }
        markSummarized()

        return trimmed
    }

    /**
     * 手动编辑写回（图纸 2026-09-01 件③）：与自动摘要**同一把 per-角色锁**——否则用户点保存的同时后台整理
     * 正好写回，两份内容互相覆盖且谁也不知道。锁内重读库内现值与 [baseline] 比对（[force]=false 时），
     * 不一致返 [ManualEditResult.Conflict] 绝不静默覆盖；写走既有定向两列 UPDATE（旧值入 previousMemorySummary）。
     *
     * **只写正文**——游标 / 冷却 / 触发判定 / 护栏解套链一概不碰（手动编辑不是一次「整理」）。
     */
    suspend fun applyManualEdit(
        characterUuid: String,
        baseline: String,
        newMemory: String,
        force: Boolean = false,
    ): ManualEditResult = perCharacterLocks.getOrPut(characterUuid) { Mutex() }.withLock {
        val current = characterDao.getByUuid(characterUuid)?.memorySummary ?: return@withLock ManualEditResult.CharacterGone
        if (!force && current != baseline) return@withLock ManualEditResult.Conflict(current)
        characterDao.updateMemorySummary(characterUuid, current, newMemory)
        ManualEditResult.Saved
    }

    private companion object {
        /** 软目标溢出容忍（避免 3050/3000 这类轻微超标白烧一次压缩调用）。 */
        const val OVERFLOW_TRIGGER = 1.2

        /** 常态硬上限倍数：压缩自救/自愈后仍超此线 → 进泄压阀判定（拍板 2026-07-11：此线不放宽）。 */
        const val OVERFLOW_HARD = 1.5

        /** 泄压阀上限倍数（拍板 2026-07-11·微图纸锁定）：(1.5×, 2.0×] 放行一次打破死锁，超 2.0× 终败。 */
        const val RELIEF_CAP = 2.0
    }
}
