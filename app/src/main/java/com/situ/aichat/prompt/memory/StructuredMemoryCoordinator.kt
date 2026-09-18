package com.situ.aichat.prompt.memory

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.data.model.StructuredMemory
import com.situ.aichat.data.model.StructuredMemoryMetadata
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.prompt.growth.GrowthAnalysisService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 1:1 port of iOS `StructuredMemoryCoordinator`：调用 [StructuredMemoryService] 抽取 → 校验 → 字段级合并写回
 * `structuredMemoryJSON` + 更新元数据。消息收集复用「最近 200 条」(对齐 iOS GrowthAnalysisService.collectMessagesForAnalysis)。
 *
 * 丢弃路径（0 字段，多半 API 错误/响应损坏）：不重置 roundsSinceLastExtraction、不增 totalExtractionCount，
 * 但仍写 lastExtractionDate 让 30 分钟时间冷却接管，防瞬时抖动下反复触发形成调用风暴；返回 false（记忆没变，
 * 调用方不做「记忆变了」的后续）。
 */
@Singleton
class StructuredMemoryCoordinator @Inject constructor(
    private val structuredMemoryService: StructuredMemoryService,
    private val growthAnalysisService: GrowthAnalysisService,
    private val characterDao: CharacterDao,
    private val characterWriteLock: CharacterWriteLock,
) {
    /**
     * 执行结构化记忆提取并写回。[characterUuid] 实时取最新角色（含触发处刚递增的轮次计数）。
     * **P12.6 D1**：整个「读-LLM-写」在 [CharacterWriteLock] 内串行（锁内重读最新角色），回写改用列级 UPDATE
     * （成功写 previous+current+元数据 3 列；丢弃路径仅写元数据 1 列），消除与计数器递增 / 其它分析的并发覆盖。
     * @return true = 真写进了新的结构化记忆；false = 没写（角色已不在 / 0 字段丢弃）。调用方只在 true 时做
     *   「记忆变了」的后续（如通知文案重烤·2026-09-18 起丢弃路径不再触发，对齐触发端 E10 契约）。
     * @throws StructuredMemoryError 无消息 / 解析失败（确定性，调用方不重试）。
     */
    suspend fun extractAndPersist(
        characterUuid: String,
        config: ApiConfigValues,
        userName: String,
    ): Boolean = characterWriteLock.withCharacterLock(characterUuid) {
        val character = characterDao.getByUuid(characterUuid) ?: return@withCharacterLock false

        // 复用成长分析的消息收集（最近 200 条；GrowthAnalysisService 为规范所有者）
        val messages = growthAnalysisService.collectMessagesForAnalysis(characterUuid)
        if (messages.isEmpty()) throw StructuredMemoryError.NoMessages

        val existing = StructuredMemory.decode(character.structuredMemoryJSON)
        val metadata = StructuredMemoryMetadata.decode(character.structuredMemoryMetadataJSON)

        // 抽取（确定性 StructuredMemoryError 直接上抛由调用方处理；其它瞬态错误同样上抛）
        val result = structuredMemoryService.extractMemory(
            messages = messages,
            existing = existing,
            characterName = character.name,
            config = config,
            userName = userName,
        )

        // 验证：0 字段填充 → 丢弃（仅写 lastExtractionDate，保留轮数、不计 count）
        val validated = validateExtraction(result)
        if (validated == null) {
            characterDao.updateStructuredMemoryMetadata(
                characterUuid,
                metadata.copy(lastExtractionDate = System.currentTimeMillis()).encode(),
            )
            return@withCharacterLock false
        }

        // 应用提取结果（新值非空才覆盖；firstConflict write-once）+ 元数据更新
        val merged = applyExtraction(validated, existing)
        val newMetadata = metadata.copy(
            lastExtractionDate = System.currentTimeMillis(),
            roundsSinceLastExtraction = 0,
            totalExtractionCount = metadata.totalExtractionCount + 1,
        )
        characterDao.updateStructuredMemory(
            uuid = characterUuid,
            previous = character.structuredMemoryJSON,
            current = merged.encode(),
            metadata = newMetadata.encode(),
        )
        true
    }

    // MARK: - 应用 / 校验

    /** 新值非空才覆盖旧值；firstConflict 仅在旧值为空时写入（write-once）。对齐 iOS applyExtraction。 */
    private fun applyExtraction(new: StructuredMemory, old: StructuredMemory): StructuredMemory = old.copy(
        nicknameFromChar = new.nicknameFromChar.ifEmpty { old.nicknameFromChar },
        nicknameToChar = new.nicknameToChar.ifEmpty { old.nicknameToChar },
        insideJoke = new.insideJoke.ifEmpty { old.insideJoke },
        deepestChat = new.deepestChat.ifEmpty { old.deepestChat },
        impressionOfUser = new.impressionOfUser.ifEmpty { old.impressionOfUser },
        sharedLikes = new.sharedLikes.ifEmpty { old.sharedLikes },
        learnedPhrase = new.learnedPhrase.ifEmpty { old.learnedPhrase },
        importantPromise = new.importantPromise.ifEmpty { old.importantPromise },
        firstConflict = if (old.firstConflict.isEmpty() && new.firstConflict.isNotEmpty()) new.firstConflict else old.firstConflict,
        comfortStyle = new.comfortStyle.ifEmpty { old.comfortStyle },
    )

    /**
     * 极端异常防御 + 字段截断。0 字段填充 → 返回 null（丢弃）；否则每字段截断到 [MAX_FIELD_LENGTH]
     * （prompt 要求 20 字，50 为宽松兜底）。对齐 iOS validateExtraction。
     */
    private fun validateExtraction(new: StructuredMemory): StructuredMemory? {
        if (countFilledFields(new) == 0) return null
        return new.copy(
            nicknameFromChar = new.nicknameFromChar.take(MAX_FIELD_LENGTH),
            nicknameToChar = new.nicknameToChar.take(MAX_FIELD_LENGTH),
            insideJoke = new.insideJoke.take(MAX_FIELD_LENGTH),
            deepestChat = new.deepestChat.take(MAX_FIELD_LENGTH),
            impressionOfUser = new.impressionOfUser.take(MAX_FIELD_LENGTH),
            sharedLikes = new.sharedLikes.take(MAX_FIELD_LENGTH),
            learnedPhrase = new.learnedPhrase.take(MAX_FIELD_LENGTH),
            importantPromise = new.importantPromise.take(MAX_FIELD_LENGTH),
            firstConflict = new.firstConflict.take(MAX_FIELD_LENGTH),
            comfortStyle = new.comfortStyle.take(MAX_FIELD_LENGTH),
        )
    }

    private fun countFilledFields(m: StructuredMemory): Int {
        var count = 0
        if (m.nicknameFromChar.isNotEmpty()) count++
        if (m.nicknameToChar.isNotEmpty()) count++
        if (m.insideJoke.isNotEmpty()) count++
        if (m.deepestChat.isNotEmpty()) count++
        if (m.impressionOfUser.isNotEmpty()) count++
        if (m.sharedLikes.isNotEmpty()) count++
        if (m.learnedPhrase.isNotEmpty()) count++
        if (m.importantPromise.isNotEmpty()) count++
        if (m.firstConflict.isNotEmpty()) count++
        if (m.comfortStyle.isNotEmpty()) count++
        return count
    }

    private companion object {
        const val MAX_FIELD_LENGTH = 50
    }
}
