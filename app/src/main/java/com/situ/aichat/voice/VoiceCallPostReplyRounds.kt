package com.situ.aichat.voice

import android.util.Log
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.GrowthEventType
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.StructuredMemoryMetadata
import com.situ.aichat.data.model.growthMetadata
import com.situ.aichat.data.model.relationshipQuality
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.prompt.growth.AnalysisPacing
import com.situ.aichat.prompt.growth.AffectKernel
import com.situ.aichat.prompt.growth.GrowthAnalysisCoordinator
import com.situ.aichat.prompt.growth.GrowthAnalysisError
import com.situ.aichat.prompt.growth.GrowthAnalysisResult
import com.situ.aichat.prompt.growth.IntentKernel
import com.situ.aichat.prompt.growth.RelationshipAnalysisCoordinator
import com.situ.aichat.prompt.growth.RelationshipAnalysisError
import com.situ.aichat.prompt.growth.RelationshipBands
import com.situ.aichat.prompt.memory.InSceneRecapCoordinator
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.prompt.memory.MemoryDigestCoordinator
import com.situ.aichat.prompt.memory.MemorySummaryError
import com.situ.aichat.prompt.memory.StructuredMemoryCoordinator
import com.situ.aichat.prompt.memory.StructuredMemoryError
import com.situ.aichat.prompt.memory.SummaryTriggerDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fires the four post-reply analysis rounds after a voice-call AI message is saved — the same set
 * iOS triggers from `VoiceCallManager+Logging.saveAIMessage`: memory rolling summary + structured
 * memory (10-point) + growth analysis + relationship analysis.
 *
 * Per the user's 2026-06-03 decision, this is an ISOLATED voice-side helper that reuses the existing
 * `@Singleton` coordinators (rather than refactoring the device-verified chat reply path in
 * `ChatViewModel`): the orchestration here is a faithful port of `ChatViewModel`'s
 * `checkAndTriggerMemorySummary` / `incrementStructuredMemoryRoundAndCheck` /
 * `incrementGrowthRoundAndCheck` / `incrementRelationshipRoundAndCheck` (and the chained
 * growth → relationship + fallback triggers), so behavior matches iOS exactly. Some logic is
 * deliberately duplicated from `ChatViewModel`; the pure trigger predicates are `internal` + unit-tested.
 *
 * Concurrency mirrors `ChatViewModel`: the shared per-character [CharacterWriteLock] serializes the
 * round-counter read-modify-write (P12.6 D1), and the `is…` guards are confined to this `@Singleton`'s `Main.immediate` scope.
 * Because the guards live on the singleton (not a per-call VM), chat and voice can never double-fire.
 */
@Singleton
class VoiceCallPostReplyRounds @Inject constructor(
    private val characterRepo: CharacterRepository,
    private val conversationRepo: ConversationRepository,
    private val apiConfigRepo: ApiConfigRepository,
    private val memoryService: MemoryService,
    private val digestCoordinator: MemoryDigestCoordinator,
    private val structuredCoordinator: StructuredMemoryCoordinator,
    private val growthCoordinator: GrowthAnalysisCoordinator,
    private val relationshipCoordinator: RelationshipAnalysisCoordinator,
    private val characterWriteLock: CharacterWriteLock,
    private val inSceneRecapCoordinator: InSceneRecapCoordinator,
    /** 卷三：语音回合尾同样 tick 场内核（K-F13 第二处钩子）。 */
    private val affectKernel: AffectKernel,
    /** 卷四：语音回合尾 tick 意图内核（无文本可用·N-5：只跑消退 / 清理 / 晋升）。 */
    private val intentKernel: IntentKernel,
) {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    // Per-feature concurrency guards (Main-confined, = iOS isSummarizing / isExtracting… / isAnalyzing…).
    private var isSummarizing = false
    private var isExtractingStructuredMemory = false
    private var isAnalyzingGrowth = false
    private var isAnalyzingRelationship = false


    /**
     * Entry point: after a voice-call AI message is persisted, fire the four rounds (= iOS saveAIMessage
     * tail). Resolves the per-function API configs (falling back to the active VOICE_CALL config, like
     * ChatViewModel falls back to the active chat config); a missing VOICE_CALL config = no-op.
     */
    fun onAssistantMessagePersisted(characterUuid: String, conversationUuid: String, settings: AppSettings, userName: String) {
        scope.launch {
            val activeConfig = apiConfigRepo.resolveConfigValues(ApiFunction.VOICE_CALL) ?: return@launch
            val memoryConfig = apiConfigRepo.resolveConfigValues(ApiFunction.MEMORY_SUMMARY) ?: activeConfig
            checkAndTriggerMemorySummary(characterUuid, conversationUuid, memoryConfig, settings, userName)
            incrementStructuredMemoryRoundAndCheck(characterUuid, memoryConfig, settings, userName)
            val growthConfig = apiConfigRepo.resolveConfigValues(ApiFunction.GROWTH_ANALYSIS) ?: activeConfig
            incrementGrowthRoundAndCheck(characterUuid, growthConfig, settings, userName)
            incrementRelationshipRoundAndCheck(characterUuid, settings, userName)
            // 场内滚动压缩·前情提要（记忆改造二期·部件⑤·§3.2-B）：通话回合尾后台把本场早期被丢弃部分压缩成前情提要
            // （内部尾块推导 + 单飞 + 冷却守卫；无尾部通话段则守卫返回）。
            inSceneRecapCoordinator.checkCallRecap(conversationUuid)
        }
    }

    // MARK: - 记忆摘要触发（1:1 iOS ChatViewModel+Memory）

    private fun checkAndTriggerMemorySummary(
        characterUuid: String,
        conversationUuid: String,
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
                    conversationUuid = conversationUuid,
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

    private suspend fun performMemorySummary(
        character: CharacterEntity,
        conversationUuid: String,
        config: ApiConfigValues,
        messages: List<MessageEntity>,
        settings: AppSettings,
        userName: String,
        maxRetries: Int,
    ) {
        for (attempt in 1..maxRetries) {
            try {
                // 记忆改造一期（图纸 §3.6）：消化班车编排（与聊天侧 MemoryAnalysisTrigger 对称·校验链/触发/冷却语义零碰）。
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
                break
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (attempt < maxRetries) delay(2_000)
            }
        }
        conversationRepo.recordMemorySummaryResult(conversationUuid, success = false, now = System.currentTimeMillis())
    }

    // MARK: - 结构化记忆触发（1:1 iOS ChatViewModel+Growth）

    private fun incrementStructuredMemoryRoundAndCheck(
        characterUuid: String,
        config: ApiConfigValues,
        settings: AppSettings,
        userName: String,
    ) {
        if (!settings.growthSystemEnabled) return
        val interval = settings.structuredMemoryInterval
        if (interval <= 0) return

        scope.launch {
            val incremented = characterWriteLock.withCharacterLock(characterUuid) {
                val character = characterRepo.get(characterUuid) ?: return@withCharacterLock null
                val metadata = StructuredMemoryMetadata.decode(character.structuredMemoryMetadataJSON)
                val inc = metadata.copy(roundsSinceLastExtraction = metadata.roundsSinceLastExtraction + 1)
                characterRepo.updateStructuredMemoryMetadata(characterUuid, inc.encode())
                inc
            } ?: return@launch

            if (isExtractingStructuredMemory) return@launch
            val rounds = incremented.roundsSinceLastExtraction
            if (rounds < interval) return@launch
            val lastDate = incremented.lastExtractionDate
            if (lastDate != null) {
                val elapsed = System.currentTimeMillis() - lastDate
                val timeReady = elapsed >= 1_800_000L
                val countReady = rounds >= 50
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
        } catch (_: StructuredMemoryError) {
            // 解析失败 / 无消息：确定性错误，重试结果不会变
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // 瞬态错误（网络超时/限流等）：延迟 2s 重试一次
            delay(2_000)
            try {
                attempt()
            } catch (e2: Exception) {
                if (e2 is CancellationException) throw e2
                // 重试仍失败：静默
            }
        }
    }

    // MARK: - 成长分析触发（1:1 iOS ChatViewModel+Growth）

    private fun incrementGrowthRoundAndCheck(
        characterUuid: String,
        config: ApiConfigValues,
        settings: AppSettings,
        userName: String,
    ) {
        if (!settings.growthSystemEnabled) return
        val interval = settings.growthAnalysisInterval
        if (interval <= 0) return

        scope.launch {
            runCatching { affectKernel.tick(characterUuid, System.currentTimeMillis()) } // 卷三：每轮 tick 场（失败吞掉）
            runCatching { intentKernel.tick(characterUuid, System.currentTimeMillis(), "") } // 卷四：语音回合无文本（N-5）·只跑消退 / 清理 / 晋升
            val incremented = characterWriteLock.withCharacterLock(characterUuid) {
                val character = characterRepo.get(characterUuid) ?: return@withCharacterLock null
                val metadata = character.growthMetadata
                val inc = metadata.copy(roundsSinceLastAnalysis = metadata.roundsSinceLastAnalysis + 1)
                characterRepo.updateGrowthMetadata(characterUuid, GrowthJson.encode(inc))
                inc
            } ?: return@launch

            if (isAnalyzingGrowth) return@launch
            // 活人感一期 P3：首次 10 轮、第二次 25 轮、之后回用户设置值（与 RelationshipAnalysisTrigger 同款阶梯）。
            if (incremented.roundsSinceLastAnalysis < AnalysisPacing.growthInterval(incremented.totalAnalysisCount, interval)) return@launch
            val lastDate = incremented.lastAnalysisDate
            if (lastDate != null && System.currentTimeMillis() - lastDate < 3_600_000L) return@launch

            isAnalyzingGrowth = true
            try {
                performGrowthAnalysis(characterUuid, config, settings, userName)
            } finally {
                isAnalyzingGrowth = false
            }
        }
    }

    private suspend fun performGrowthAnalysis(
        characterUuid: String,
        config: ApiConfigValues,
        settings: AppSettings,
        userName: String,
    ) {
        Log.d(TAG, "voice call → growth analysis…")
        try {
            val before = characterRepo.get(characterUuid)?.relationshipQuality ?: RelationshipQuality()
            val result = growthCoordinator.analyzeAndPersist(characterUuid, config, userName, settings)
            checkGrowthDrivenRelationshipTrigger(characterUuid, result, before, settings, userName)
        } catch (e: GrowthAnalysisError) {
            // 解析失败 / 无消息：确定性错误，重试结果不会变；只留一条观测行（修缮卷 D-13）
            Log.w(TAG, "成长分析确定性失败：${e.javaClass.simpleName} ${e.message}")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // 瞬态失败：只留一条观测行
            Log.w(TAG, "成长分析瞬态失败：${e.javaClass.simpleName}")
        }
    }

    // MARK: - 关系评估触发（1:1 iOS ChatViewModel+Growth 关系段）

    private fun incrementRelationshipRoundAndCheck(characterUuid: String, settings: AppSettings, userName: String) {
        if (!settings.relationshipAutoAdvanceEnabled) return
        scope.launch {
            val character = characterWriteLock.withCharacterLock(characterUuid) {
                val c = characterRepo.get(characterUuid) ?: return@withCharacterLock null
                val newCount = c.relationshipMessageCount + 1
                characterRepo.updateRelationshipMessageCount(characterUuid, newCount)
                c.copy(relationshipMessageCount = newCount)
            } ?: return@launch
            checkRelationshipFallbackTrigger(characterUuid, character, settings, userName)
        }
    }

    private suspend fun checkRelationshipFallbackTrigger(
        characterUuid: String,
        character: CharacterEntity,
        settings: AppSettings,
        userName: String,
    ) {
        if (isAnalyzingRelationship) return
        val relConfig = apiConfigRepo.resolveConfigValues(ApiFunction.RELATIONSHIP_ANALYSIS) ?: return
        if (!shouldTriggerRelationshipFallback(
                messageCount = character.relationshipMessageCount,
                lastAnalysisDate = character.lastRelationshipAnalysisDate,
                creationDate = character.creationDate,
                now = System.currentTimeMillis(),
            )
        ) {
            return
        }
        if (isAnalyzingRelationship) return
        isAnalyzingRelationship = true
        try {
            performRelationshipAnalysis(characterUuid, relConfig, "aiAutomatic", userName)
        } finally {
            isAnalyzingRelationship = false
        }
    }

    private fun checkGrowthDrivenRelationshipTrigger(
        characterUuid: String,
        result: GrowthAnalysisResult,
        before: RelationshipQuality,
        settings: AppSettings,
        userName: String,
    ) {
        if (!settings.relationshipAutoAdvanceEnabled) return
        if (isAnalyzingRelationship) return
        scope.launch {
            val relConfig = apiConfigRepo.resolveConfigValues(ApiFunction.RELATIONSHIP_ANALYSIS) ?: return@launch
            val character = characterRepo.get(characterUuid) ?: return@launch
            if (character.relationshipMessageCount < 30) return@launch
            val hasSignificantEvent = result.events.any {
                it.type == GrowthEventType.RELATIONSHIP_CHANGE || it.type == GrowthEventType.MAJOR_EVENT
            }
            val hasBandCrossing = detectRelationshipBandCrossing(before, character.relationshipQuality)
            if (!hasSignificantEvent && !hasBandCrossing) return@launch
            if (isAnalyzingRelationship) return@launch
            isAnalyzingRelationship = true
            try {
                performRelationshipAnalysis(characterUuid, relConfig, "aiAutomatic", userName)
            } finally {
                isAnalyzingRelationship = false
            }
        }
    }

    private fun detectRelationshipBandCrossing(before: RelationshipQuality, after: RelationshipQuality): Boolean {
        val oldValues = before.values
        val newValues = after.values
        for (i in 0 until minOf(oldValues.size, newValues.size)) {
            if (relationshipBand(oldValues[i]) != relationshipBand(newValues[i])) return true
        }
        return false
    }

    private fun relationshipBand(value: Int): Int {
        for ((index, boundary) in RELATIONSHIP_BANDS.withIndex()) {
            if (value <= boundary) return index
        }
        return RELATIONSHIP_BANDS.size
    }

    private suspend fun performRelationshipAnalysis(
        characterUuid: String,
        config: ApiConfigValues,
        triggerTypeRaw: String,
        userName: String,
    ) {
        Log.d(TAG, "voice call → relationship analysis… ($triggerTypeRaw)")
        try {
            relationshipCoordinator.analyzeAndPersist(characterUuid, config, userName, triggerTypeRaw)
        } catch (_: RelationshipAnalysisError) {
            // 解析失败 / 无消息：确定性错误，重试结果不会变
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // 重试仍失败：静默
        }
    }

    internal companion object {
        private const val TAG = "VoiceCallRounds"

        /** Non-uniform relationship-dimension band edges (定义点住 [RelationshipBands.CROSSING_BOUNDARIES]). */
        private val RELATIONSHIP_BANDS = RelationshipBands.CROSSING_BOUNDARIES

        /**
         * Relationship fallback trigger (pure, 1:1 iOS `shouldTriggerRelationshipFallback`):
         * ① ≥7 days since last analysis (or creation) → true; ② ≥100 rounds AND ≥24h → true.
         */
        internal fun shouldTriggerRelationshipFallback(messageCount: Int, lastAnalysisDate: Long?, creationDate: Long, now: Long): Boolean {
            val referenceDate = lastAnalysisDate ?: creationDate
            val elapsed = now - referenceDate
            val oneDay = 86_400_000L
            if (elapsed >= 7 * oneDay) return true
            if (messageCount >= 100 && elapsed >= oneDay) return true
            return false
        }

        /** Whether two relationship values fall in different bands (pure; mirrors [detectRelationshipBandCrossing]). */
        internal fun crossesRelationshipBand(oldValue: Int, newValue: Int): Boolean {
            fun band(value: Int): Int {
                for ((index, boundary) in RELATIONSHIP_BANDS.withIndex()) {
                    if (value <= boundary) return index
                }
                return RELATIONSHIP_BANDS.size
            }
            return band(oldValue) != band(newValue)
        }
    }
}
