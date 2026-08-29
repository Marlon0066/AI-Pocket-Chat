package com.situ.aichat.offline

import android.util.Log
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.ResponseFormatDto
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.OfflineMeetingMemoryRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.promise.PromiseLedgerService
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.util.DateFormatters
import com.situ.aichat.util.StringListJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 线下见面摘要的**重试协调器**（10.2d，统一前台即时层①/重进对话层②/全局扫描③④/24h 自愈⑤ 的核心，对齐 iOS
 * `OfflineSummaryRetryCoordinator` + `ChatViewModel+ToolCalling` 的 extractOfflineMeetingMemory/applyFallback）。
 *
 * **安卓地道做法（比 iOS 少一份重复）**：iOS 把 extractOfflineMeetingMemory（VM @MainActor，绑单会话）与 retryOne
 * （Coordinator，全局扫描）写成两份共享 applyFallback；安卓注入 @Singleton 即可被 ViewModel（前台）与 Worker（后台）
 * 共用一份 [retryOne]。
 *
 * **退避状态持久化在 Room**（[OfflineSummaryRetryPolicy] 读 ConversationEntity 的 failCount/lastAttemptAt），不靠
 * WorkManager BackoffPolicy（spec §3.3 / 坑 §4#2）。`lastAttemptAt` 无论成功失败都更新（退避窗口判断）。
 *
 * 本块（d-2）含 [retryOne] + [applyFallback] + 软上限合并 + 见面 prompt；全局 [scanAndRetry]/24h 自愈/Worker 入队
 * 在 d-3。
 */
@Singleton
class OfflineSummaryRetryCoordinator @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val messageRepo: MessageRepository,
    private val characterRepo: CharacterRepository,
    private val offlineMeetingMemoryRepo: OfflineMeetingMemoryRepository,
    private val contextLog: ContextLogService,
    private val apiConfigRepo: ApiConfigRepository,
    private val sessionExtractor: OfflineMeetingSessionExtractor,
    private val healStore: OfflineSummaryHealStore,
    private val promiseLedgerService: PromiseLedgerService,
    private val userProfileDao: UserProfileDao,
) {

    /** 手动重试进行中的 sessionId 集合（驱动「简版」徽章转圈态；见 [manuallyRetry] 守卫注释）。 */
    private val _retryingSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val retryingSessionIds: StateFlow<Set<String>> = _retryingSessionIds.asStateFlow()

    /** 单次重试结果。前台调用方据 [FELL_BACK] 决定是否弹 Toast（全局扫描不弹）。 */
    enum class RetryOutcome {
        /** LLM 成功，已写回见面记忆 + 清 pending。 */
        SUCCESS,

        /** LLM 失败，failCount+1，未达兜底阈值（下次退避后再试）。 */
        RETRIED_FAIL,

        /** LLM 失败且达阈值（5 次），已应用规则兜底。 */
        FELL_BACK,

        /** 本次未尝试（无 pending / 仍在线下 / 缺角色或 API / 退避窗口内 / 无有效消息）。 */
        SKIPPED,
    }

    /**
     * 对单个会话执行一次见面摘要重试（1:1 iOS retryOne / extractOfflineMeetingMemory）：
     * 守卫 → 退避判断 → 收集见面消息 → 跑 LLM → 成功清 pending+移 fallback+markSummarizedUpToWindow /
     * 失败 failCount++ 达 5 兜底。
     *
     * @param bypassBackoff true=绕过退避强制跑（24h 自愈手动重试用，d-3）。
     */
    suspend fun retryOne(
        conversationUuid: String,
        now: Long = System.currentTimeMillis(),
        bypassBackoff: Boolean = false,
    ): RetryOutcome {
        val convo = conversationRepo.get(conversationUuid) ?: run {
            Log.d(TAG, "见面摘要重试：会话不存在，跳过 conv=$conversationUuid")
            return RetryOutcome.SKIPPED
        }
        val sessionId = convo.pendingOfflineSummarySessionId?.takeIf { it.isNotEmpty() } ?: run {
            Log.d(TAG, "见面摘要重试：无 pending session，跳过 conv=$conversationUuid")
            return RetryOutcome.SKIPPED
        }
        if (convo.isInOfflineMode) {
            Log.d(TAG, "见面摘要重试：见面尚未结束，跳过 session=$sessionId")
            return RetryOutcome.SKIPPED // 本次见面还没结束
        }

        // R5#3：per-session 在途守卫**上提到 retryOne**——三入口（前台 ChatViewModel.onChatAppear / 全局
        // scanAndRetry / 手动 manuallyRetry）此前只有 manuallyRetry 自带守卫，前台与扫描并发可对同一 session 双跑
        // LLM、last-wins 双写见面记忆。这里按 sessionId 占坑（也驱动「简版」徽章转圈），把退避判定 + lastAttemptAt
        // 写入 + LLM 全收进同一临界区，消除 TOCTOU。manuallyRetry 已去掉自带守卫，避免双重占坑自锁。
        val alreadyRunning = _retryingSessionIds.getAndUpdate { it + sessionId }.contains(sessionId)
        if (alreadyRunning) {
            Log.d(TAG, "见面摘要重试：该 session 已在重试中，跳过 session=$sessionId")
            return RetryOutcome.SKIPPED
        }
        try {
            val character = characterRepo.get(convo.characterUuid) ?: run {
                Log.w(TAG, "见面摘要重试：角色已删除，跳过 conv=$conversationUuid char=${convo.characterUuid}")
                return RetryOutcome.SKIPPED
            }
            val config = apiConfigRepo.resolveConfigValues(ApiFunction.MEMORY_SUMMARY) ?: run {
                Log.w(TAG, "见面摘要重试：未配置 API，跳过 conv=$conversationUuid session=$sessionId")
                return RetryOutcome.SKIPPED
            }

            // 退避判断（手动自愈绕过）。
            if (!bypassBackoff && !OfflineSummaryRetryPolicy.shouldAttempt(
                    convo.pendingOfflineSummaryFailCount, convo.pendingOfflineSummaryLastAttemptAt, now,
                )
            ) {
                Log.d(TAG, "见面摘要重试：退避窗口内，跳过 session=$sessionId fail=${convo.pendingOfflineSummaryFailCount}")
                return RetryOutcome.SKIPPED
            }

            // 收集见面消息：全部 session 消息（含 marker，用于算时长）+ 喂 LLM 的非 marker 非空消息。
            val allSessionMessages = messageRepo.offlineSessionMessages(conversationUuid, sessionId)
            val llmMessages = allSessionMessages.filter {
                it.messageKindRaw != MessageKind.OFFLINE_MARKER_START.raw &&
                    it.messageKindRaw != MessageKind.OFFLINE_MARKER_END.raw &&
                    it.content.isNotEmpty()
            }
            if (llmMessages.isEmpty()) {
                // 消息被删光 → 清 pending 避免死锁（1:1 iOS）。
                Log.i(TAG, "见面摘要重试：消息已全部删除，清除 pending session=$sessionId")
                conversationRepo.clearPendingOfflineSummary(conversationUuid)
                return RetryOutcome.SKIPPED
            }

            // 时长用全部 session 消息（入场标记→离场标记，= 真实见面时长）；durationText 含「约」（兜底正文直接用·
            // v2 schema 事实行的「（约…）」在 runSchemaLLM 内去前缀「约」防双「约」）。
            val durationText = durationFromMessages(allSessionMessages)

            // 本次尝试时间（无论成功/失败都更新，退避窗口判断）。
            conversationRepo.updateOfflineSummaryLastAttemptAt(conversationUuid, now)
            Log.d(TAG, "见面摘要提取触发 conv=$conversationUuid session=$sessionId fail=${convo.pendingOfflineSummaryFailCount}")

            return try {
            val meta = sessionExtractor.extractFallbackMetadata(conversationUuid, sessionId, now)
            val userName = userProfileDao.get()?.nickname.orEmpty()
            val userRecordLabel = userName.trim().takeIf { it.isNotEmpty() && it != "用户" } ?: "对方"
            val record = MemoryService.formatMessages(
                llmMessages, userLabel = userRecordLabel, charLabel = character.name,
            )
            val draft = runSchemaLLM(
                character.name, meta, allSessionMessages, durationText, llmMessages.size, record, userName, config,
            )
                ?: throw IllegalStateException("见面摘要 v2 生成失败（网络/schema 全败）")
            offlineMeetingMemoryRepo.upsertMeeting(
                buildMeetingRow(
                    characterUuid = character.uuid, conversationUuid = conversationUuid, sessionId = sessionId,
                    meta = meta, endMillis = allSessionMessages.lastOrNull()?.timestamp ?: meta.startMillis,
                    messageCount = llmMessages.size, summary = draft.summary, mood = draft.mood,
                    highlights = draft.highlights, promises = draft.promises, source = "llm", now = now,
                ),
            )
            // 见面便车（记忆改造一期·部件②·图纸 §3.10）：把本次见面提取的约定注册进承诺账本（sourceRaw=meeting·
            // 去重在注册端·自愈/手动重跑幂等）。失败仅计数日志、不影响摘要成败；绝不打约定内容（§5·E20）。
            runCatching {
                promiseLedgerService.registerFromMeeting(character.uuid, conversationUuid, sessionId, draft.promises, now)
            }.onFailure { Log.w(TAG, "见面约定注册失败（不影响摘要）count=${draft.promises.size}") }
            // 成功清理：清 pending + 从 fallback 列表移除本 session（read A·自愈升级同路径）。摘要以**行存**为单源
            // （blob 冻结只读·只在懒播种时读一次），Repository 落行即生效。
            conversationRepo.clearPendingOfflineSummary(conversationUuid)
            conversationRepo.removeFallbackSessionId(conversationUuid, sessionId)
            Log.d(TAG, "见面摘要 v2 提取成功 session=$sessionId")
            RetryOutcome.SUCCESS
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // LLM 失败（含沉浸守卫/网络/schema 全败）→ failCount+1，判是否兜底。
            conversationRepo.incrementOfflineSummaryFailCount(conversationUuid)
            val newFailCount = convo.pendingOfflineSummaryFailCount + 1
            Log.w(TAG, "见面摘要提取失败（第 $newFailCount 次）session=$sessionId: ${e.message}")
            if (OfflineSummaryRetryPolicy.shouldFallbackNow(newFailCount)) {
                applyFallback(conversationUuid, character.uuid, sessionId, durationText, llmMessages.size, now)
                Log.w(TAG, "见面摘要达阈值，已应用规则兜底 session=$sessionId")
                RetryOutcome.FELL_BACK
            } else {
                RetryOutcome.RETRIED_FAIL
            }
        }
        } finally {
            // R5#3：无论成功/失败/取消/早退，释放 per-session 占坑（也复位徽章转圈态）。
            _retryingSessionIds.update { it - sessionId }
        }
    }

    /**
     * 全局后台扫描重试（③冷启动 / ④回前台层，1:1 iOS scanAndRetry）：扫所有 pendingOfflineSummarySessionId 非空的
     * 会话，逐个 [retryOne]（退避判断在内）。**全局扫描触发的兜底不弹 Toast**（用户可能不在聊天页）。
     */
    suspend fun scanAndRetry(now: Long = System.currentTimeMillis()) {
        val pending = conversationRepo.conversationsWithPendingOfflineSummary()
        if (pending.isEmpty()) return
        Log.d(TAG, "发现 ${pending.size} 个待重试的线下见面摘要")
        for (conv in pending) {
            retryOne(conv.uuid, now) // retryOne 内重新读会话取最新 failCount/lastAttemptAt；串行避免 API 并发。
        }
    }

    /**
     * 低频自愈（⑤层，1:1 iOS healOneFallbackIfDue）：每 24h 最多一次，把已兜底的「简版」摘要尝试升级回 LLM 完整版。
     * **无感**（成功静默升级、失败保持原样，不弹 Toast）；**轮询公平**（[healStore] triedIds + [pickNextHealCandidate]
     * 避免某永久失败 session 占位阻塞其他 fallback）。无候选时也更新时间戳，避免每次冷启动扫表。
     */
    suspend fun healOneFallbackIfDue(now: Long = System.currentTimeMillis()) {
        val lastHealAt = healStore.lastHealAt()
        if (lastHealAt != null && now - lastHealAt < HEAL_MIN_INTERVAL_MS) return

        val convs = conversationRepo.conversationsWithOfflineFallback()
        if (convs.isEmpty()) {
            healStore.setLastHealAt(now)
            return
        }

        // 收集所有 fallback sessionId（去重，保持扫描顺序）。
        val seen = mutableSetOf<String>()
        val candidates = mutableListOf<String>()
        for (conv in convs) {
            for (id in conv.offlineSummaryFallbackSessionIds.split(",").filter { it.isNotEmpty() }) {
                if (seen.add(id)) candidates.add(id)
            }
        }
        if (candidates.isEmpty()) {
            healStore.setLastHealAt(now)
            return
        }

        val result = pickNextHealCandidate(candidates, healStore.triedIds())
        val pick = result.pick
        if (pick == null) {
            healStore.setLastHealAt(now)
            return
        }
        // 写回状态：已尝试列表 + 自愈时间。
        healStore.setTriedIds(result.updatedTriedIds)
        healStore.setLastHealAt(now)
        Log.i(TAG, "低频自愈：尝试升级 fallback 摘要 [$pick]，本轮已试 ${result.updatedTriedIds.size}/${candidates.size}")
        manuallyRetry(pick, now)
    }

    /**
     * 手动/自愈重试（绕过退避强制跑一次，1:1 iOS manuallyRetry）：经入场标记反查会话 → 把 sessionId 塞回 pending +
     * 清零计数/时间戳 → [retryOne] bypassBackoff。成功升级完整摘要 + 移出 fallback；失败重新进入退避循环。
     */
    suspend fun manuallyRetry(sessionId: String, now: Long = System.currentTimeMillis()): RetryOutcome {
        // R5#3：per-session in-flight 守卫已下沉到 [retryOne]（三入口共用，含本手动路），故此处不再自带守卫
        // ——否则与 retryOne 的占坑双重 add 会让手动路恒 SKIPPED 自锁。徽章转圈态仍由 [retryingSessionIds] 驱动
        // （retryOne restore 后随即占坑、finally 复位，根治旧「失败后转圈卡死」），状态提升出组合不随回收重置。
        val conversationUuid = messageRepo.conversationUuidForOfflineSession(sessionId) ?: run {
            Log.w(TAG, "手动重试找不到 conversation [$sessionId]")
            return RetryOutcome.SKIPPED
        }
        conversationRepo.restorePendingOfflineSummary(conversationUuid, sessionId)
        return retryOne(conversationUuid, now, bypassBackoff = true)
    }

    /**
     * 应用规则兜底：写入骨架摘要 + 登记 fallback 列表 + 清 pending（1:1 iOS applyFallback，前台/后台共享）。
     *
     * **去重保护**：sessionId 已在 fallback 列表 → 跳过追加，只清 pending（避免重复简版段落）。
     * **软上限保护**：追加前 summary 已超 80%（且有旧 fallback）→ 把最老的 fallback 段落合并成一行，腾空间到 50%。
     */
    suspend fun applyFallback(
        conversationUuid: String,
        characterUuid: String,
        sessionId: String,
        durationText: String,
        messageCount: Int,
        now: Long = System.currentTimeMillis(),
    ) {
        val convo = conversationRepo.get(conversationUuid) ?: return
        val existingFallbackIds = convo.offlineSummaryFallbackSessionIds.split(",").filter { it.isNotEmpty() }
        val alreadyFallback = sessionId in existingFallbackIds

        if (!alreadyFallback) {
            // 兜底行：硬事实 + buildFallbackBody（无标题行·durationText 含「约」直接用）。upsert 按 sessionId 幂等
            // （E6·Repository 落**行**·行存单源，blob 冻结只读）；软上限压缩改由注入端 [OfflineMeetingMemoryRenderer] 承担（不再手动 blob 合并）。
            val meta = sessionExtractor.extractFallbackMetadata(conversationUuid, sessionId, now)
            val allSessionMessages = messageRepo.offlineSessionMessages(conversationUuid, sessionId)
            val body = OfflineSummaryRegenerator.buildFallbackBody(
                durationText = durationText,
                activity = meta.activity,
                messageCount = messageCount,
                finalMood = meta.finalMood,
                initiatedByUser = meta.initiatedByUser,
            )
            offlineMeetingMemoryRepo.upsertMeeting(
                buildMeetingRow(
                    characterUuid = characterUuid, conversationUuid = conversationUuid, sessionId = sessionId,
                    meta = meta, endMillis = allSessionMessages.lastOrNull()?.timestamp ?: meta.startMillis,
                    messageCount = messageCount, summary = body, mood = meta.finalMood.orEmpty(),
                    highlights = emptyList(), promises = emptyList(), source = "fallback", now = now,
                ),
            )
            // read A：会话级 fallback 列表仍作 24h 自愈候选（骨架不动）。
            conversationRepo.appendFallbackSessionId(conversationUuid, sessionId)
        }

        conversationRepo.clearPendingOfflineSummary(conversationUuid)
    }

    /**
     * 即时要点（卷二 G1·契约 §1-G1）：见面结束瞬间先落一行「简版」记忆（复用 [applyFallback] 的骨架配方·
     * `source=`[SOURCE_INSTANT]），顶住「LLM 摘要未落前线上完全失忆」的空窗；[retryOne] 成功后 upsertMeeting 按
     * sessionId 原位覆盖为 "llm" 行（=「替换」，见 [OfflineMeetingMemoryRepository.upsertMeeting] E6 幂等）。
     *
     * **绝不碰 pending / fallback 列表**——重试链语义原样（[retryOne] 只认 pendingOfflineSummarySessionId，
     * 5 败仍走 [applyFallback] 覆写本行 + 登记列表 + 进 24h 自愈）。行已存在（重入 / 旧会话）→ 零写早退。
     */
    suspend fun applyInstantGist(
        conversationUuid: String,
        sessionId: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val convo = conversationRepo.get(conversationUuid) ?: return
        if (offlineMeetingMemoryRepo.bySessionId(sessionId) != null) return // 已有行（llm/fallback/instant/manual）→ 不覆盖
        val meta = sessionExtractor.extractFallbackMetadata(conversationUuid, sessionId, now)
        val msgs = messageRepo.offlineSessionMessages(conversationUuid, sessionId)
        // 复核 R1·D-1 裁决：「共 N 轮对话」的 N 与 applyFallback 同源（retryOne 同款谓词剔 marker/空消息）——
        // 即时行就是「提前的兜底行」，同一句骨架在两种来源下必须同一个 N。endMillis 仍取全量末条（离场 marker=真实结束时刻）。
        val llmMessages = msgs.filter {
            it.messageKindRaw != MessageKind.OFFLINE_MARKER_START.raw &&
                it.messageKindRaw != MessageKind.OFFLINE_MARKER_END.raw &&
                it.content.isNotEmpty()
        }
        if (llmMessages.isEmpty()) return // 材料尽失（照 retryOne 同判据；此处不动 pending）：没有材料就不造行
        val endMillis = msgs.lastOrNull()?.timestamp ?: meta.startMillis
        val body = OfflineSummaryRegenerator.buildFallbackBody(
            durationText = OfflineMeetingService.durationText(meta.startMillis, endMillis),
            activity = meta.activity,
            messageCount = llmMessages.size,
            finalMood = meta.finalMood,
            initiatedByUser = meta.initiatedByUser,
        )
        offlineMeetingMemoryRepo.upsertMeeting(
            buildMeetingRow(
                characterUuid = convo.characterUuid, conversationUuid = conversationUuid, sessionId = sessionId,
                meta = meta, endMillis = endMillis, messageCount = llmMessages.size, summary = body,
                mood = meta.finalMood.orEmpty(), highlights = emptyList(), promises = emptyList(),
                source = SOURCE_INSTANT, now = now,
            ),
        )
        Log.d(TAG, "见面即时要点已落行 session=$sessionId")
    }

    /** 自愈队列的选择决策结果（1:1 iOS HealPickResult）。 */
    data class HealPickResult(
        /** 本次要尝试的 sessionId；null 表示没有候选。 */
        val pick: String?,
        /** 更新后的「本轮已尝试」列表（调用方写回 [OfflineSummaryHealStore]）。 */
        val updatedTriedIds: List<String>,
    )

    /**
     * v2 摘要 LLM 链（图纸 §3.5·范式 [com.situ.aichat.gift.ProactiveGiftLLMService]）：schema 失败带错反馈重试
     * ≤[SCHEMA_RETRIES]、网络失败退避 1s/2s/4s ×[NETWORK_RETRIES]。全败返回 null（调用方 throw → failCount++ 退避链）。
     * [record] = [MemoryService.formatMessages] 产出（已脱敏·带时间戳→沉浸守卫）；事实行「（约…）」传裸时长（去前缀「约」防双「约」）。
     */
    private suspend fun runSchemaLLM(
        characterName: String,
        meta: OfflineMeetingSessionExtractor.FallbackMetadata,
        allSessionMessages: List<com.situ.aichat.data.local.entity.MessageEntity>,
        durationText: String,
        messageCount: Int,
        record: String,
        userName: String,
        config: com.situ.aichat.data.remote.llm.ApiConfigValues,
    ): OfflineMeetingSummarySchema.MeetingSummaryDraft? {
        val startText = DateFormatters.yearMonthDayHourMinute(meta.startMillis)
        val endMillis = allSessionMessages.lastOrNull()?.timestamp ?: meta.startMillis
        val endText = HHMM_FORMATTER.format(Instant.ofEpochMilli(endMillis).atZone(ZoneId.systemDefault()))
        val bareDuration = durationText.removePrefix("约")
        // 日记体（图纸 §4）：userName（昵称原值）由调用方上提单读传入；空白/恰为「用户」由 schema 内落无名变体。
        var previousError: String? = null
        repeat(SCHEMA_RETRIES + 1) {
            val user = OfflineMeetingSummarySchema.buildUserPrompt(
                characterName = characterName, startText = startText, endText = endText, durationText = bareDuration,
                location = meta.location, activity = meta.activity, messageCount = messageCount,
                conversationRecord = record, userName = userName, previousError = previousError,
            )
            val response = callWithBackoff(user, characterName, config) ?: return null
            when (val r = OfflineMeetingSummarySchema.parseAndValidate(response)) {
                is OfflineMeetingSummarySchema.ParseResult.Success -> return r.draft
                is OfflineMeetingSummarySchema.ParseResult.Failure -> {
                    previousError = r.error
                    Log.i(TAG, "见面摘要 v2 schema 校验失败：${r.error}")
                }
            }
        }
        return null
    }

    /** 网络指数退避 1s→2s→4s（[NETWORK_RETRIES] 次）；json_object 强格式、temp 0.4。全失败 null。 */
    private suspend fun callWithBackoff(
        user: String,
        characterName: String,
        config: com.situ.aichat.data.remote.llm.ApiConfigValues,
    ): String? {
        repeat(NETWORK_RETRIES) { attempt ->
            try {
                return contextLog.completion(
                    source = LogSource.OFFLINE_MEETING_MEMORY,
                    characterName = characterName,
                    config = config,
                    messages = listOf(ChatMessageDto(role = "user", content = user)),
                    temperature = 0.4,
                    responseFormat = ResponseFormatDto(type = "json_object"),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "见面摘要 v2 LLM 调用失败（第 ${attempt + 1} 次）：${e.message}")
                if (attempt < NETWORK_RETRIES - 1) delay((1L shl attempt) * 1000L)
            }
        }
        return null
    }

    /**
     * 构造见面回忆行（llm/fallback 共用·图纸 §3.2 字段）。uuid 新建随机——[OfflineMeetingMemoryRepository.upsertMeeting]
     * 按 sessionId 命中旧行会保留其 uuid/createdAt（E6 幂等），故此处随机 uuid 只在真·新行生效。
     */
    private fun buildMeetingRow(
        characterUuid: String,
        conversationUuid: String,
        sessionId: String,
        meta: OfflineMeetingSessionExtractor.FallbackMetadata,
        endMillis: Long,
        messageCount: Int,
        summary: String,
        mood: String,
        highlights: List<String>,
        promises: List<String>,
        source: String,
        now: Long,
    ): OfflineMeetingMemoryEntity = OfflineMeetingMemoryEntity(
        uuid = UUID.randomUUID().toString(),
        characterUuid = characterUuid,
        conversationUuid = conversationUuid,
        sessionId = sessionId,
        kindRaw = "meeting",
        startedAtMillis = meta.startMillis,
        endedAtMillis = endMillis,
        location = meta.location,
        activity = meta.activity,
        moodRaw = mood,
        initiatedByUser = meta.initiatedByUser,
        messageCount = messageCount,
        summary = summary,
        highlightsJson = StringListJson.encode(highlights),
        promisesJson = StringListJson.encode(promises),
        sourceRaw = source,
        createdAtMillis = now,
        updatedAtMillis = now,
    )

    companion object {
        private const val TAG = "OfflineSummaryRetry"

        /**
         * 即时要点行的 `sourceRaw` 值（卷二 M1·**锁定字面量**）——全工程唯一定义点：余温守卫④ / 朋友圈呼应守卫⑥ /
         * 「简版」徽章两站一律引它，绝不再各写一遍字符串。
         */
        internal const val SOURCE_INSTANT = "instant"

        /**
         * 「摘要还没熟」统一谓词（卷二 J3·余温与朋友圈呼应**共用勿复制**·单源防漂移）：
         * 无行 或 行还是即时要点骨架 → 还得等；`fallback`/`manual`/`llm` 均算「熟」
         * （fallback 出现即重试链已尽力，再等它无意义）。
         */
        internal fun summaryStillPending(row: OfflineMeetingMemoryEntity?): Boolean =
            row == null || row.sourceRaw == SOURCE_INSTANT

        /** 低频自愈最小间隔（24h，1:1 iOS healMinInterval）。 */
        internal const val HEAL_MIN_INTERVAL_MS = 24L * 3600L * 1000L

        /**
         * **纯函数**（1:1 iOS pickNextHealCandidate）：从候选 + 已尝试列表挑下一个要自愈的 sessionId。
         * 轮询语义：本轮已试的跳过；全试过 → 清空已试列表开新一轮；稳定取 pending[0]，行为可预测便于单测。
         * 同时清理失效的 triedIds（候选里已不存在的，如成功重试后从 fallback 列表移除的）。
         */
        internal fun pickNextHealCandidate(candidates: List<String>, triedIds: List<String>): HealPickResult {
            if (candidates.isEmpty()) return HealPickResult(null, triedIds)
            val triedSet = triedIds.toSet()
            var pending = candidates.filter { it !in triedSet }
            val nextTried: MutableList<String>
            if (pending.isEmpty()) {
                // 本轮所有候选都已试过 → 重置队列，开始新一轮。
                pending = candidates
                nextTried = mutableListOf()
            } else {
                // 只保留与候选有交集的 triedIds（清理失效历史条目）。
                nextTried = triedIds.filter { it in triedSet && it in candidates }.toMutableList()
            }
            val pick = pending[0]
            nextTried.add(pick)
            return HealPickResult(pick, nextTried)
        }

        /**
         * 见面时长文案：首尾消息时间戳推算（重试时入场时间已丢失，1:1 iOS calculateOfflineDuration/calculateDuration）。
         * 空 → 「一段时间」；否则复用 [OfflineMeetingService.durationText]（同一阈值公式，单一真相源）。
         */
        internal fun durationFromMessages(messages: List<com.situ.aichat.data.local.entity.MessageEntity>): String {
            val first = messages.firstOrNull() ?: return "一段时间"
            val last = messages.last()
            return OfflineMeetingService.durationText(first.timestamp, last.timestamp)
        }

        /** v2 schema 校验失败带反馈重试次数（范式 ProactiveGiftLLMService.MAX_RETRIES_ON_SCHEMA_FAILURE）。 */
        private const val SCHEMA_RETRIES = 2

        /** 网络失败指数退避次数（1s→2s→4s）。 */
        private const val NETWORK_RETRIES = 3

        /** 见面结束时刻「HH:mm」（v2 事实行·系统时区）。 */
        private val HHMM_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
