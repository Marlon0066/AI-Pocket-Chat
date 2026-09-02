package com.situ.aichat.ui.chat

import android.util.Log
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.GrowthEventType
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.growthMetadata
import com.situ.aichat.data.model.relationshipQuality
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.prompt.growth.AffectKernel
import com.situ.aichat.prompt.growth.AnalysisPacing
import com.situ.aichat.prompt.growth.GrowthAnalysisCoordinator
import com.situ.aichat.prompt.growth.GrowthAnalysisError
import com.situ.aichat.prompt.growth.GrowthAnalysisResult
import com.situ.aichat.prompt.growth.IntentKernel
import com.situ.aichat.prompt.growth.RelationshipAnalysisCoordinator
import com.situ.aichat.prompt.growth.RelationshipAnalysisError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 「回合后台分析触发」之成长+关系簇协作者——从 ChatViewModel 抽出（对齐 iOS ChatViewModel+Growth.swift），方法体字节级不变。
 * 成长命脉：AI 回复完成后递增成长轮次 → 达阈值触发成长分析；成长分析完成后**单向链式**触发关系评估
 * （[performGrowthAnalysis] → [checkGrowthDrivenRelationshipTrigger]），故成长 + 关系两段必须同处一协作者。
 * 另设关系保底触发（长聊后链式条件长期 false 时强制定期评估）。错误全静默、不碰 UI / _error。
 * [scope] = VM 的 viewModelScope；两个防并发标志随之搬入（仅 Main 调度协程读写，无需同步）。
 */
internal class RelationshipAnalysisTrigger(
    private val scope: CoroutineScope,
    private val characterRepo: CharacterRepository,
    private val characterWriteLock: CharacterWriteLock,
    private val apiConfigRepo: ApiConfigRepository,
    private val growthCoordinator: GrowthAnalysisCoordinator,
    private val relationshipCoordinator: RelationshipAnalysisCoordinator,
    /** 卷三：每轮回合尾先 tick 场内核（松弛 + 脉冲 + 跨日），失败吞掉不影响其余段。 */
    private val affectKernel: AffectKernel,
    /** 卷四：紧跟场 tick 再 tick 意图内核（层 ① 关键词 + 消退 / 清理 / 晋升），两把内核锁顺序拿、不嵌套（K-16）。 */
    private val intentKernel: IntentKernel,
) {
    /** 防止并发成长分析（对齐 iOS isAnalyzingGrowth）。 */
    private var isAnalyzingGrowth = false

    /** 防止并发关系评估（对齐 iOS isAnalyzingRelationship）。 */
    private var isAnalyzingRelationship = false

    // MARK: - 成长分析触发（对齐 iOS ChatViewModel+Growth.swift）

    /**
     * AI 回复完成后：递增 roundsSinceLastAnalysis，再按「轮次达标 + 距上次分析 ≥1h」双条件触发成长分析。
     * 递增无条件进行（对齐 iOS）；触发受 [isAnalyzingGrowth] 防并发。
     */
    fun incrementGrowthRoundAndCheck(
        characterUuid: String,
        config: ApiConfigValues,
        settings: AppSettings,
        userName: String,
        /** 卷四层 ①（K-5）：本轮用户消息正文（扫全清词·修缮卷 J4 只剩全清）；空 = 跳过该层（语音回合·N-5）。 */
        userText: String = "",
    ) {
        if (!settings.growthSystemEnabled) return
        val interval = settings.growthAnalysisInterval
        if (interval <= 0) return

        scope.launch {
            // 卷三 §3.4 表1：每轮 tick 场（内核自有 Mutex·不进 CharacterWriteLock）；tick 内部已吞异常，这里再兜一层（外部行为清单 9）。
            runCatching { affectKernel.tick(characterUuid, System.currentTimeMillis()) }
            // 卷四 §3.4：紧跟着 tick 意图（自有 Mutex·与场锁顺序拿不嵌套·K-16）；队列不变则 0 写（K-15）。
            runCatching { intentKernel.tick(characterUuid, System.currentTimeMillis(), userText) }
            // P12.6 D1：每角色写锁内「重读最新→+1→列级写回」（见 [CharacterWriteLock]）。
            val incremented = characterWriteLock.withCharacterLock(characterUuid) {
                val character = characterRepo.get(characterUuid) ?: return@withCharacterLock null
                val metadata = character.growthMetadata
                val inc = metadata.copy(roundsSinceLastAnalysis = metadata.roundsSinceLastAnalysis + 1)
                characterRepo.updateGrowthMetadata(characterUuid, GrowthJson.encode(inc))
                inc
            } ?: return@launch

            // 触发判定
            if (isAnalyzingGrowth) return@launch
            // 活人感一期 P3：首次 10 轮、第二次 25 轮、之后回用户设置值（userInterval 为硬上限）。
            if (incremented.roundsSinceLastAnalysis < AnalysisPacing.growthInterval(incremented.totalAnalysisCount, interval)) return@launch
            // 距上次分析至少 1 小时（防频繁触发）
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

    /** 执行成长分析（失败静默；无 VM 级重试——空响应重试在 GrowthAnalysisService 内，对齐 iOS）。 */
    private suspend fun performGrowthAnalysis(
        characterUuid: String,
        config: ApiConfigValues,
        settings: AppSettings,
        userName: String,
    ) {
        Log.d("GrowthAnalysis", "触发后台成长分析…")
        try {
            // 关系质感快照（成长分析前），用于检测维度跨阶段线 → 链式触发关系评估
            val before = characterRepo.get(characterUuid)?.relationshipQuality ?: RelationshipQuality()
            val result = growthCoordinator.analyzeAndPersist(characterUuid, config, userName, settings)
            // 成长分析完成 → 检查是否链式触发关系评估
            checkGrowthDrivenRelationshipTrigger(characterUuid, result, before, settings, userName)
        } catch (e: GrowthAnalysisError) {
            // 确定性错误（无消息 / 解析失败）：不重试，只留一条观测行（修缮卷 D-13：此前零日志 = 解析失败静默吞）
            Log.w("GrowthAnalysis", "成长分析确定性失败：${e.javaClass.simpleName} ${e.message}")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // 瞬态错误（网络等）：不重试，只留一条观测行
            Log.w("GrowthAnalysis", "成长分析瞬态失败：${e.javaClass.simpleName}")
        }
    }

    // MARK: - 关系评估触发（对齐 iOS ChatViewModel+Growth.swift 关系段）

    /** AI 回复完成后：递增 relationshipMessageCount，再检查关系评估保底触发。 */
    fun incrementRelationshipRoundAndCheck(characterUuid: String, settings: AppSettings, userName: String) {
        if (!settings.relationshipAutoAdvanceEnabled) return
        scope.launch {
            // relationshipMessageCount 每条消息写 → 每角色写锁内重读最新+1 列级写回，防与成长/结构化递增及分析回写互相覆盖。
            val character = characterWriteLock.withCharacterLock(characterUuid) {
                val c = characterRepo.get(characterUuid) ?: return@withCharacterLock null
                val newCount = c.relationshipMessageCount + 1
                characterRepo.updateRelationshipMessageCount(characterUuid, newCount)
                c.copy(relationshipMessageCount = newCount)
            } ?: return@launch
            checkRelationshipFallbackTrigger(characterUuid, character, settings, userName)
        }
    }

    /** 关系评估保底触发：长聊后链式条件长期 false 时强制定期评估（≥7 天 或 ≥100 轮且 ≥24h）。 */
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
        if (isAnalyzingRelationship) return // 原子复检：resolveConfigValues 挂起后再确认，与下一行间无挂起点
        isAnalyzingRelationship = true
        try {
            performRelationshipAnalysis(characterUuid, relConfig, "aiAutomatic", userName)
        } finally {
            isAnalyzingRelationship = false
        }
    }

    /**
     * 成长分析完成后链式触发关系评估：本次有 relationshipChange/majorEvent 事件 OR 关系维度跨阶段线，
     * 且距上次评估已聊 ≥30 轮。
     */
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
            // 活人感一期 P3：首次评估门槛 10 轮（从未评估过），之后回 30（现状值）。
            if (character.relationshipMessageCount < AnalysisPacing.relationshipChainThreshold(character.lastRelationshipAnalysisDate)) return@launch
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

    /** 执行关系评估（自动触发，失败静默，无 VM 级重试）。手动推进 + 结果 toast 延后（需 UI/toast 基建）。 */
    private suspend fun performRelationshipAnalysis(
        characterUuid: String,
        config: ApiConfigValues,
        triggerTypeRaw: String,
        userName: String,
    ) {
        Log.d("RelationshipAnalysis", "触发后台关系评估…（$triggerTypeRaw）")
        try {
            relationshipCoordinator.analyzeAndPersist(characterUuid, config, userName, triggerTypeRaw)
        } catch (_: RelationshipAnalysisError) {
            // 确定性错误（无消息 / 解析失败）：静默
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // 瞬态错误：静默
        }
    }

    companion object {
        /** 检测是否有关系维度跨过非均匀阶段线 [10,20,30,50,70,85,95,100]（末尾 100 防 96+ 饱和死锁）。 */
        internal fun detectRelationshipBandCrossing(before: RelationshipQuality, after: RelationshipQuality): Boolean {
            val boundaries = intArrayOf(10, 20, 30, 50, 70, 85, 95, 100)
            val oldValues = before.values
            val newValues = after.values
            for (i in 0 until minOf(oldValues.size, newValues.size)) {
                if (relationshipBand(oldValues[i], boundaries) != relationshipBand(newValues[i], boundaries)) return true
            }
            return false
        }

        internal fun relationshipBand(value: Int, boundaries: IntArray): Int {
            for ((index, boundary) in boundaries.withIndex()) {
                if (value <= boundary) return index
            }
            return boundaries.size
        }

        /**
         * 关系评估保底触发判定（纯函数）：① 距上次评估（或创建）≥7 天 → true；② ≥100 轮且 ≥24h → true。
         * 24h 最小冷却防 -15 计数语义引发「每 ~15 轮反复触发」循环。对齐 iOS shouldTriggerRelationshipFallback。
         */
        internal fun shouldTriggerRelationshipFallback(messageCount: Int, lastAnalysisDate: Long?, creationDate: Long, now: Long): Boolean {
            val referenceDate = lastAnalysisDate ?: creationDate
            val elapsed = now - referenceDate
            val oneDay = 86_400_000L
            if (elapsed >= 7 * oneDay) return true
            if (messageCount >= 100 && elapsed >= oneDay) return true
            return false
        }
    }
}
