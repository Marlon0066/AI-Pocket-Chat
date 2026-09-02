package com.situ.aichat.prompt.diary

import android.content.Context
import android.util.Log
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.MilestoneDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.DiaryVisibility
import com.situ.aichat.data.model.MomentTriggerType
import com.situ.aichat.data.model.growthMetadata
import com.situ.aichat.data.model.intentQueue
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.DiaryRepository
import com.situ.aichat.data.repository.OpenLoopRepository
import com.situ.aichat.data.repository.PromiseRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.openloop.OpenLoopScanService
import com.situ.aichat.prompt.GeneratedContentValidator
import com.situ.aichat.prompt.IntentExitRenderer
import com.situ.aichat.prompt.PromptStrings
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.promise.PromiseInjectionRenderer
import com.situ.aichat.util.DateFormatters
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 交换日记（R4·契约 §2 F1·O1 锁定）：角色每天也写一篇**TA 自己**的日记；用户发布当日日记才可拆信。
 *
 * - **笔友**：设置固定 uuid 优先（固定不轮换·当日没聊过则不可解锁·角色已删回落自动）；
 *   自动 = 当日消息最多的角色（平手取最近消息者）。
 * - **懒生成**：拆信时才调 LLM（每日至多 1 次调用·不发布不生成）；同日已有信 → 幂等直接取回。
 * - **独立视角**：TA 不读用户日记正文（提示词明令「不偷看」）——交换的乐趣是两个视角对照。
 * - **素材**：与该角色当日聊天摘要（复用用户日记的脱敏收口·钱路口径不变；「我/对方」翻转为 TA 执笔视角）
 *   + TA 当日日程 + 会话当前情绪。
 */
@Singleton
class DiaryExchangeService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contextLog: ContextLogService,
    private val apiConfigRepo: ApiConfigRepository,
    private val diaryRepository: DiaryRepository,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val characterDao: CharacterDao,
    private val userProfileDao: UserProfileDao,
    private val settingsRepo: SettingsRepository,
    private val scheduleDao: ScheduleDao,
    // 角色日记丰富化（2026-07-13·以角色为中心）：里程碑 / 约定 / 惦记（记忆·关系其余数据挂在 penpal.character 上·零新依赖）。
    private val milestoneDao: MilestoneDao,
    private val promiseRepository: PromiseRepository,
    private val openLoopRepository: OpenLoopRepository,
) {
    private val unlockMutex = Mutex()

    /** 信封位状态（时间线当日置顶·S5）。 */
    sealed interface State {
        /** 一个角色都没有 → 不显示信封位。 */
        data object Hidden : State

        /** 今天（和笔友）还没聊过 → 无米之炊不硬写。 */
        data object NoChatToday : State

        /** 有聊天但今日尚未发布用户日记 → 引导去写。 */
        data class NeedPublish(val characterName: String) : State

        /** 已发布 → 可拆信。 */
        data class ReadyToUnlock(val characterName: String) : State

        /** 今日的信已生成（时间线卡片自然展示 → 信封位隐藏）。 */
        data object Unlocked : State
    }

    /** 拆信结果。 */
    sealed interface UnlockResult {
        data class Success(val entry: DiaryEntryEntity) : UnlockResult
        data object NoApi : UnlockResult

        /** 状态已变（无聊天/未发布）→ UI 重新取状态。 */
        data object NotReady : UnlockResult
        data object Failed : UnlockResult
    }

    private data class Penpal(val character: CharacterEntity, val conversationUuids: Set<String>)

    /** 今日信封位状态（列表 VM 进页/数据变化时取）。 */
    suspend fun stateForToday(nowMillis: Long = System.currentTimeMillis()): State {
        val zone = ZoneId.systemDefault()
        val start = DateFormatters.startOfDayMillis(nowMillis, zone)
        val end = start + DiaryGenerationService.DAY_MILLIS
        if (diaryRepository.exchangeEntryInRange(start, end) != null) return State.Unlocked
        val penpal = resolvePenpal(start, end)
            ?: return if (characterDao.getAll().isEmpty()) State.Hidden else State.NoChatToday
        return if (diaryRepository.hasPublishedUserDiaryInRange(start, end)) {
            State.ReadyToUnlock(penpal.character.name)
        } else {
            State.NeedPublish(penpal.character.name)
        }
    }

    /**
     * 拆开今天的信（懒生成）：幂等（已有 → 直接返回）→ 解锁门（已发布）→ 笔友 → LLM（temp 0.8·
     * 空响应重试 1 次）→ MOOD 尾行解析（TA 的心情驱动信笺色）→ 脏数据门 → 落库（已发布·openToAI）。
     */
    suspend fun unlockToday(): UnlockResult = unlockMutex.withLock {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val start = DateFormatters.startOfDayMillis(now, zone)
        val end = start + DiaryGenerationService.DAY_MILLIS
        diaryRepository.exchangeEntryInRange(start, end)?.let { return UnlockResult.Success(it) }
        if (!diaryRepository.hasPublishedUserDiaryInRange(start, end)) return UnlockResult.NotReady
        val penpal = resolvePenpal(start, end) ?: return UnlockResult.NotReady
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.DIARY_GENERATION) ?: return UnlockResult.NoApi

        val ps = PromptStrings(context)
        val promptStrings = DiaryPromptStrings.from(ps)
        val exStrings = DiaryExchangePromptStrings.from(ps)
        val profile = userProfileDao.get()
        val userName = profile?.nickname?.trim()?.takeIf { it.isNotEmpty() } ?: exStrings.userFallback

        // 素材（TA 视角）：仅与笔友的当日聊天（每角色各自取·跨该角色全部会话·不被别的角色挤占；沿用脱敏单源收口）
        // + TA 当日日程 + 会话当前情绪。标注按 TA 执笔：角色自己=「我」、用户=用户名（作者恒「我」·防第三人称漂移·2026-07-13 拍板）。
        val chatRaw = messageDao.messagesForCharacterInRange(
            penpal.character.uuid, start, end, DiaryGenerationService.CHAT_TAKE,
        )
        val chatSummary = DiaryGenerationService.summarizeChatMessages(
            chatRaw, zone, promptStrings,
            userLabel = userName,
            characterLabel = promptStrings.roleMe,
        )
        val scheduleSummary = buildScheduleSummary(penpal.character.uuid, start, zone)
        val moodLine = penpal.conversationUuids
            .firstNotNullOfOrNull { conversationDao.getByUuid(it) }
            ?.let { c ->
                listOfNotNull(
                    c.moodEmoji.takeIf { it.isNotEmpty() },
                    c.moodText.takeIf { it.isNotEmpty() },
                ).joinToString(" ")
            }
            .orEmpty()

        // 角色日记丰富化（2026-07-13·以角色为中心·各空 → 该段自动省略）：A记忆(memorySummary·直接字段)、
        // B关系(阶段+最近里程碑)、C约定/惦记(复用现成渲染器·双语)、D用户bio、③人设框定。记忆/关系数据挂在 character 上。
        val ch = penpal.character
        val relationship = formatRelationship(ch.growthMetadata.currentPhase, milestoneDao.getForCharacter(ch.uuid), exStrings, zone)
        val promiseBlock = PromiseInjectionRenderer.render(promiseRepository.injectableForCharacter(ch.uuid, now), now, zone)
        val loopBlock = OpenLoopScanService.formatInjectionBlock(
            OpenLoopScanService.selectLoopsForInjection(
                openLoopRepository.openLoopsForCharacter(ch.uuid), null, Instant.ofEpochMilli(now), zone,
            ),
            Instant.ofEpochMilli(now), ps,
        )
        val enrichment = DiaryExchangeEnrichment(
            personaFrame = exStrings.personaFrame,
            aboutUser = profile?.bio?.trim().orEmpty(),
            relationship = relationship,
            memory = ch.memorySummary.trim(),
            promiseBlock = promiseBlock,
            loopBlock = loopBlock,
            intentBlock = IntentExitRenderer.diaryBlock(ch.intentQueue.intents, userName, now), // 卷四 §4.5 ④：心里挂着的事
        )

        val system = DiaryExchangePromptBuilder.build(
            strings = exStrings,
            characterName = ch.name,
            personality = ch.personalityDescription,
            systemPrompt = ch.systemPrompt,
            userName = userName,
            nowMillis = now,
            zone = zone,
            moodLine = moodLine,
            chatSummary = chatSummary,
            scheduleSummary = scheduleSummary,
            enrichment = enrichment,
        )
        val messages = listOf(
            ChatMessageDto(role = "system", content = system),
            ChatMessageDto(role = "user", content = exStrings.userMessage),
        )

        // 空响应等 200ms 重试 1 次（对齐用户日记生成对 DeepSeek 空响应的处理）。
        var result = ""
        for (attempt in 1..2) {
            val buffer = try {
                contextLog.completion(
                    source = LogSource.DIARY_GENERATION,
                    characterName = penpal.character.name,
                    config = config,
                    messages = messages,
                    temperature = 0.8,
                )
            } catch (e: Exception) {
                Log.w(TAG, "交换日记生成失败: ${e.message}")
                return UnlockResult.Failed
            }
            val candidate = MemoryService.strippingThinkingTags(buffer)
            if (candidate.isNotEmpty()) {
                result = candidate
                break
            }
            if (attempt < 2) delay(200)
        }
        val draft = DiaryMoodLineParser.extract(result)
        if (!GeneratedContentValidator.isLikelyValid(draft.content)) return UnlockResult.Failed

        val entry = DiaryEntryEntity(
            uuid = UUID.randomUUID().toString(),
            content = draft.content,
            timestamp = now,
            moodEmoji = draft.moodEmoji,
            isAutoGenerated = false,
            isDraft = false,
            visibilityRaw = DiaryVisibility.OPEN_TO_AI.raw,
            triggerTypeRaw = MomentTriggerType.EXCHANGE.raw,
            authorCharacterUuid = penpal.character.uuid,
            authorNameSnapshot = penpal.character.name,   // R6-3① 孤儿信 A：落作者名快照，角色被删后仍可署名
        )
        diaryRepository.upsert(entry)
        Log.d(TAG, "交换日记已生成")
        return UnlockResult.Success(entry)
    }

    /** 今日笔友：固定 uuid 优先（当日没聊过 → null；角色已删 → 回落自动）；自动 = 当日消息最多。 */
    private suspend fun resolvePenpal(start: Long, end: Long): Penpal? {
        val messages = messageDao.messagesInRange(start, end, PENPAL_SCAN_LIMIT)
        if (messages.isEmpty()) return null
        val convToChar = mutableMapOf<String, String>()
        for (cid in messages.map { it.conversationUuid }.distinct()) {
            conversationDao.getByUuid(cid)?.characterUuid?.let { convToChar[cid] = it }
        }
        val fixed = settingsRepo.getAppSettings().diaryExchangePartnerUuid.trim().takeIf { it.isNotEmpty() }
        val chosenUuid = when {
            fixed == null -> pickAutoPenpalUuid(messages, convToChar)
            characterDao.getByUuid(fixed) == null -> pickAutoPenpalUuid(messages, convToChar)
            else -> fixed.takeIf { f -> messages.any { convToChar[it.conversationUuid] == f } }
        }
        val character = chosenUuid?.let { characterDao.getByUuid(it) } ?: return null
        val conversationUuids = convToChar.filterValues { it == character.uuid }.keys
        if (conversationUuids.isEmpty()) return null
        return Penpal(character, conversationUuids)
    }

    /** TA 当日日程 → "HH:mm-HH:mm 活动（地点）" 行（无日程 → ""，section 省略）。 */
    private suspend fun buildScheduleSummary(characterUuid: String, startOfDay: Long, zone: ZoneId): String {
        val schedule = scheduleDao.scheduleFor(characterUuid, startOfDay) ?: return ""
        val events = scheduleDao.eventsForSchedule(schedule.uuid).sortedBy { it.startTime }
        if (events.isEmpty()) return ""
        return events.joinToString("\n") { e ->
            val s = ZonedDateTime.ofInstant(Instant.ofEpochMilli(e.startTime), zone)
            val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(e.endTime), zone)
            val place = e.location.takeIf { it.isNotEmpty() }?.let { "（$it）" }.orEmpty()
            "%02d:%02d-%02d:%02d %s%s".format(s.hour, s.minute, t.hour, t.minute, e.activity, place)
        }
    }

    internal companion object {
        private const val TAG = "DiaryExchange"

        /** 笔友判定的当日消息扫描上限（远大于日常量·只为封顶）。 */
        const val PENPAL_SCAN_LIMIT = 500

        /** B 关系正文里程碑注入条数（升序取最近·takeLast）。 */
        private const val REL_MILESTONE_TAKE = 3

        /** 阶段字符串固定序 → [DiaryExchangePromptStrings.phaseNames] 的 `|` 分隔本地化名索引。 */
        private val PHASE_ORDER = listOf("honeymoon", "adjustment", "stability", "fatigue", "breakthrough")

        /**
         * B 关系正文（纯函数·T1·丰富化）：阶段行（phase→本地化阶段名·未知/空 phase 则省阶段行）+ 最近
         * [REL_MILESTONE_TAKE] 条里程碑「{yyyy/M/d} 起，你们成为{关系名}」。两者皆空 → ""（调用方据此省略整段）。
         * 里程碑列表须升序（DAO getForCharacter 契约）；日期走 Locale.ROOT 的 dateYMD（防非拉丁数字设备）。
         */
        internal fun formatRelationship(
            phase: String?,
            milestones: List<MilestoneEntity>,
            strings: DiaryExchangePromptStrings,
            zone: ZoneId,
        ): String {
            val lines = mutableListOf<String>()
            val idx = PHASE_ORDER.indexOf(phase)
            if (idx >= 0) {
                strings.phaseNames.split("|").getOrNull(idx)?.let { lines.add(strings.phaseLine.format(it)) }
            }
            milestones.takeLast(REL_MILESTONE_TAKE).forEach { m ->
                lines.add(strings.milestoneLine.format(DateFormatters.dateYMD(m.establishedDate, zone), m.relationshipName))
            }
            return lines.joinToString("\n")
        }

        /**
         * 自动笔友（纯函数·T1）：当日消息最多的角色；平手 → 最近一条消息更晚者。
         * [convToChar] = 会话 → 角色（解析不到的会话忽略）。
         */
        internal fun pickAutoPenpalUuid(
            messages: List<MessageEntity>,
            convToChar: Map<String, String>,
        ): String? {
            val byChar = messages.mapNotNull { m -> convToChar[m.conversationUuid]?.let { it to m.timestamp } }
            if (byChar.isEmpty()) return null
            return byChar.groupBy({ it.first }, { it.second })
                .entries
                .maxWithOrNull(compareBy({ it.value.size }, { it.value.max() }))
                ?.key
        }
    }
}
