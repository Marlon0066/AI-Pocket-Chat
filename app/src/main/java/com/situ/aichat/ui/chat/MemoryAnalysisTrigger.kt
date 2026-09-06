package com.situ.aichat.ui.chat

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.StructuredMemoryMetadata
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.prompt.growth.AnalysisPacing
import com.situ.aichat.prompt.memory.MemoryDigestCoordinator
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.prompt.memory.MemorySummaryError
import com.situ.aichat.prompt.memory.StructuredMemoryCoordinator
import com.situ.aichat.prompt.memory.StructuredMemoryError
import com.situ.aichat.prompt.memory.SummaryTriggerDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「回合后台分析触发」之记忆簇协作者——从 ChatViewModel 抽出（对齐 iOS ChatViewModel+Memory/+Growth），方法体字节级不变。
 * 两条完全独立的 fire-and-forget 后台流：滚动记忆摘要 + 结构化记忆(10 要点)抽取；错误全静默、不碰 UI / _error。
 * [scope] = 应用级 `@ChatTurnScope`（`Dispatchers.Main.immediate`·退出会话不取消·ChatViewModel 2-5b）；两个防并发旗标仅在该 Main 作用域内读写，无需同步。
 */
internal class MemoryAnalysisTrigger(
    private val scope: CoroutineScope,
    private val conversationUuid: String,
    private val characterRepo: CharacterRepository,
    private val conversationRepo: ConversationRepository,
    private val characterWriteLock: CharacterWriteLock,
    private val memoryService: MemoryService,
    private val digestCoordinator: MemoryDigestCoordinator,
    private val structuredCoordinator: StructuredMemoryCoordinator,
    private val apiConfigRepo: ApiConfigRepository,
    private val settingsRepo: SettingsRepository,
    private val userProfileDao: UserProfileDao,
    /**
     * 结构化记忆抽取**成功写回后**的回调（活人感二期 M3·重烤钩子·图纸 §3.3）：用来触发通知文案重烤，
     * 让日常推送随最新称呼 / 内部梗 / 共同喜欢个性化。**仅成功路径调**（失败 / 静默不调·E10）。
     */
    private val onStructuredMemoryExtracted: (characterUuid: String) -> Unit,
) {
    /** 退出线下后补一次常规记忆摘要（对齐 iOS finalizeOfflineMode 末尾 checkAndTriggerMemorySummary）。审计 S3 自 VM 搬入。 */
    suspend fun checkAndTriggerAfterOffline() {
        val convo = conversationRepo.get(conversationUuid) ?: return
        val character = characterRepo.get(convo.characterUuid) ?: return
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.MEMORY_SUMMARY)
            ?: apiConfigRepo.resolveConfigValues(ApiFunction.CHAT) ?: return
        val settings = settingsRepo.getAppSettings()
        val userName = userProfileDao.get()?.nickname ?: ""
        checkAndTriggerMemorySummary(character.uuid, config, settings, userName)
    }

    /** 防止并发记忆总结（对齐 iOS isSummarizing）。仅在 Main 调度的协程里读写，无需同步。 */
    private var isSummarizing = false

    /** 防止并发结构化记忆提取（对齐 iOS isExtractingStructuredMemory）。 */
    private var isExtractingStructuredMemory = false

    /**
     * AI 回复完成后检查是否需要滚动总结。收集窗口外未总结消息 → 触发判定（失败冷却/下限/双轨）→ 通过则后台总结。
     * [isSummarizing] 同步置位防并发；查库与总结在 fire-and-forget 协程里进行，不阻塞输入。
     */
    fun checkAndTriggerMemorySummary(
        characterUuid: String,
        config: ApiConfigValues,
        settings: AppSettings,
        userName: String,
    ) {
        if (isSummarizing) return
        val interval = settings.autoSummarizeInterval
        if (interval <= 0) return

        isSummarizing = true
        scope.launch {
            try {
                val character = characterRepo.get(characterUuid) ?: return@launch
                val conversation = conversationRepo.get(conversationUuid) ?: return@launch
                // 复核 MED#2：见面进行中跳过常规记忆摘要（1:1 iOS Memory.swift:13 !isInOfflineMode）——叙事标签会污染
                // 通用摘要模板 + 推进水位线越过线下消息；线下叙事改由退出见面后 extractOfflineMeetingMemory 专项压缩。
                if (conversation.isInOfflineMode) return@launch
                val shortTermLength = settings.shortTermMemoryLength

                val outsideWindowMessages = memoryService.collectMessagesOutsideWindow(
                    characterUuid = characterUuid,
                    currentConversationUuid = conversationUuid,
                    shortTermLength = shortTermLength,
                )
                val outsideRoundCount = MemoryService.countRounds(outsideWindowMessages)

                val decision = MemoryService.summaryTriggerDecision(
                    outsideRoundCount = outsideRoundCount,
                    interval = interval,
                    successCooldownMinutes = settings.memorySummaryCooldownMinutes,
                    lastSuccessDate = conversation.lastMemorySummarySuccessDate,
                    lastFailureDate = conversation.lastMemorySummaryFailureDate,
                    now = System.currentTimeMillis(),
                )
                if (decision !is SummaryTriggerDecision.Trigger) return@launch

                performMemorySummary(
                    character = character,
                    config = config,
                    messages = outsideWindowMessages,
                    settings = settings,
                    userName = userName,
                    maxRetries = 2,
                )
            } finally {
                isSummarizing = false
            }
        }
    }

    /**
     * 执行一次记忆摘要：summarizeAndPersist（含写回 + 推进游标）→ 记录成功/失败时间戳。
     * 瞬态错误间隔 2s 重试（最多 [maxRetries] 次）；[MemorySummaryError]（空/过短）确定性失败不重试。
     * 失败写入失败短冷却起点（5 分钟），对齐 iOS performMemorySummary。
     */
    private suspend fun performMemorySummary(
        character: CharacterEntity,
        config: ApiConfigValues,
        messages: List<MessageEntity>,
        settings: AppSettings,
        userName: String,
        maxRetries: Int,
    ) {
        for (attempt in 1..maxRetries) {
            try {
                // 记忆改造一期（图纸 §3.6）：消化班车编排（素材收集 → 摘要写回带素材 → 标记 → 约定对账 → 落库）。
                // 触发判定 / 重试 / 冷却记录一字不动（下方仍归本 Trigger）；maxLength/customPrompt/游标推进/校验链下沉进班车。
                digestCoordinator.digestAndReconcile(
                    character = character,
                    conversationUuid = conversationUuid,
                    messages = messages,
                    config = config,
                    settings = settings,
                    userName = userName,
                )
                conversationRepo.recordMemorySummaryResult(conversationUuid, success = true, now = System.currentTimeMillis())
                return
            } catch (_: MemorySummaryError) {
                // 验证失败（空/过短）是确定性的，重试结果不会变，直接跳出进入失败兜底
                break
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (attempt < maxRetries) delay(2_000)
            }
        }

        // 失败兜底：写失败时间戳进入 5 分钟短冷却。背景任务静默（TODO(M03/M04): 失败 toast）。
        conversationRepo.recordMemorySummaryResult(conversationUuid, success = false, now = System.currentTimeMillis())
    }

    /**
     * AI 回复完成后：递增 roundsSinceLastExtraction，再按「用户下限 + 双轨节奏」判定是否触发 10 要点抽取。
     * 递增无条件进行（对齐 iOS）；触发受 [isExtractingStructuredMemory] 防并发。
     */
    fun incrementStructuredMemoryRoundAndCheck(
        characterUuid: String,
        config: ApiConfigValues,
        settings: AppSettings,
        userName: String,
    ) {
        if (!settings.growthSystemEnabled) return
        val interval = settings.structuredMemoryInterval
        if (interval <= 0) return

        scope.launch {
            // P12.6 D1：每角色写锁内「重读最新→+1→列级写回」，与成长/关系递增及各分析回写互不覆盖。
            val incremented = characterWriteLock.withCharacterLock(characterUuid) {
                val character = characterRepo.get(characterUuid) ?: return@withCharacterLock null
                val metadata = StructuredMemoryMetadata.decode(character.structuredMemoryMetadataJSON)
                val inc = metadata.copy(roundsSinceLastExtraction = metadata.roundsSinceLastExtraction + 1)
                characterRepo.updateStructuredMemoryMetadata(characterUuid, inc.encode())
                inc
            } ?: return@launch

            // 触发判定
            if (isExtractingStructuredMemory) return@launch
            val rounds = incremented.roundsSinceLastExtraction
            // 活人感一期 P3：首次抽取门槛 min(10, interval)（从未抽取过），之后回用户下限（默认 30 轮）。双轨守卫仅 lastDate!=null 生效、不受影响。
            if (rounds < AnalysisPacing.structuredInterval(incremented.lastExtractionDate, interval)) return@launch
            // 双轨：有上次提取时间时，距上次 ≥ 30 分钟 OR 累计 ≥ 50 轮 任一满足
            val lastDate = incremented.lastExtractionDate
            if (lastDate != null) {
                val elapsed = System.currentTimeMillis() - lastDate
                val timeReady = elapsed >= 1_800_000L // 30 分钟
                val countReady = rounds >= 50          // 50 轮
                if (!timeReady && !countReady) return@launch
            }

            isExtractingStructuredMemory = true
            try {
                performStructuredMemoryExtraction(characterUuid, config, userName)
            } finally {
                isExtractingStructuredMemory = false
            }
        }
    }

    /** 执行结构化记忆提取：确定性错误([StructuredMemoryError])不重试；瞬态错误 2s 后重试一次，仍失败静默。 */
    private suspend fun performStructuredMemoryExtraction(
        characterUuid: String,
        config: ApiConfigValues,
        userName: String,
    ) {
        suspend fun attempt() = structuredCoordinator.extractAndPersist(
            characterUuid = characterUuid,
            config = config,
            userName = userName,
        )
        try {
            attempt()
            // 成功写回 → 触发重烤钩子（活人感二期 M3·E10：仅成功路径）。
            onStructuredMemoryExtracted(characterUuid)
        } catch (_: StructuredMemoryError) {
            // 解析失败 / 无消息：确定性错误，重试结果不会变
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // 瞬态错误（网络超时/限流等）：延迟 2s 重试一次
            delay(2_000)
            try {
                attempt()
                // 重试成功写回 → 同样触发重烤钩子（E10）。
                onStructuredMemoryExtracted(characterUuid)
            } catch (e2: Exception) {
                if (e2 is CancellationException) throw e2
                // 重试仍失败：静默
            }
        }
    }
}
