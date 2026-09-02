package com.situ.aichat.offline

import android.content.Context
import com.situ.aichat.R
import com.situ.aichat.data.calendar.CalendarReader
import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.model.StructuredMemory
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.OfflineMeetingMemoryRepository
import com.situ.aichat.data.repository.StickerRepository
import com.situ.aichat.economy.CharacterEconomicStateService
import com.situ.aichat.gift.GiftHistoryPromptService
import com.situ.aichat.moments.MomentChatContextService
import com.situ.aichat.prompt.PromptBuilder
import com.situ.aichat.prompt.PromptScene
import com.situ.aichat.prompt.PromptStrings
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.prompt.memory.VectorMemoryService
import com.situ.aichat.sticker.DisabledBuiltInStickerStore
import com.situ.aichat.worldbook.WorldBookPromptService
import com.situ.aichat.worldbook.toWorldInfoSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 见面「余温消息」的无头上下文装配器（梦剧场 B 部·§3.10）——**逐字复刻**
 * [com.situ.aichat.recovery.RecoveryReplyGenerator] 的全量上下文 fan-out + [PromptBuilder.buildMessages] 调用方式与
 * 配置路由（scene=ONLINE_CHAT），产出「基础消息列表」。§3.10 的 system 指令追加 + 生成/校验/落库/通知归
 * [OfflineAfterglowService]（单一职责拆分：本类=拼上下文，服务=生成与落库·也便于服务逻辑单测）。
 */
@Singleton
class OfflineAfterglowPromptAssembler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val characterRepo: CharacterRepository,
    private val offlineMeetingMemoryRepository: OfflineMeetingMemoryRepository,
    private val userProfileDao: UserProfileDao,
    private val messageRepo: MessageRepository,
    private val vectorMemory: VectorMemoryService,
    private val memoryService: MemoryService,
    private val scheduleDao: ScheduleDao,
    private val calendarReader: CalendarReader,
    private val momentChatContextService: MomentChatContextService,
    private val stickerRepo: StickerRepository,
    private val economicStateService: CharacterEconomicStateService,
    private val giftDao: GiftDao,
    private val worldBookPromptService: WorldBookPromptService,
) {

    /** 装配基础消息列表（全量上下文·同 RecoveryReplyGenerator 的加载点接线·含 offlineMeetingMemoryText）。 */
    suspend fun assemble(
        convo: ConversationEntity,
        character: CharacterEntity,
        settings: AppSettings,
        nowInstant: Instant,
    ): List<ChatMessageDto> {
        val zone = ZoneId.systemDefault()
        val nowMillis = nowInstant.toEpochMilli()
        val todayStart = nowInstant.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val userProfile = userProfileDao.get()
        val userName = (userProfile?.nickname ?: "").ifEmpty { context.getString(R.string.pb_user_fallback) }
        val history = messageRepo.recentChronological(convo.uuid, HISTORY_FETCH_LIMIT)
        val queryMessage = history.lastOrNull {
            val kind = MessageKind.fromRaw(it.messageKindRaw)
            it.roleRaw == "user" && kind != MessageKind.SYSTEM_HINT && !kind.isStructuredCard
        }
        val query = queryMessage?.let {
            MemoryService.renderMemoryContent(it.content, it.mediaMemorySummary, it.imageRelativePath != null)
        }.orEmpty()
        val retrievedSnippets = if (settings.vectorSearchThreshold > 0 && query.isNotBlank()) {
            vectorMemory.searchRelevantMemories(
                query = query, characterUuid = character.uuid, currentConversationUuid = convo.uuid,
                userName = userName, characterName = character.name,
                shortTermLength = settings.shortTermMemoryLength, thresholdPercent = settings.vectorSearchThreshold,
            )
        } else {
            emptyList()
        }
        val unsummarizedRounds = memoryService.countUnsummarizedRoundsOutsideBaseWindow(
            currentConversation = convo, baseShortTermLength = settings.shortTermMemoryLength,
        )
        val todaySchedule = scheduleDao.scheduleFor(character.uuid, todayStart)
        val todayScheduleEvents = todaySchedule?.let { scheduleDao.eventsForSchedule(it.uuid) } ?: emptyList()
        val calendarUpcoming = if (settings.calendarIntegrationEnabled) calendarReader.upcomingEvents(nowMillis)?.text else null
        val momentChatContext = momentChatContextService.buildMomentContext(
            character = character, userNickname = userName,
            scheduleSystemEnabled = settings.scheduleSystemEnabled, nowMillis = nowMillis,
        )
        val worldInfo = worldBookPromptService.activateForTurn(
            characterUuid = character.uuid, conversationUuid = convo.uuid, sortedMessages = history,
            characterName = character.name, userName = userName,
            vectorThresholdPercent = settings.vectorSearchThreshold, settings = settings.toWorldInfoSettings(),
        )
        return PromptBuilder.buildMessages(
            character = character,
            conversation = convo,
            sortedMessages = history,
            userProfile = userProfile,
            appSettings = settings,
            strings = PromptStrings(context),
            structuredMemory = StructuredMemory.decode(character.structuredMemoryJSON),
            milestones = characterRepo.getMilestones(character.uuid),
            todaySchedule = todaySchedule,
            todayScheduleEvents = todayScheduleEvents,
            calendarUpcomingEvents = calendarUpcoming,
            momentChatContext = momentChatContext,
            economicState = economicStateService.resolveChatState(character.uuid, nowMillis),
            giftHistory = GiftHistoryPromptService.buildContent(character.uuid, giftDao, nowMillis, userName),
            customStickers = stickerRepo.getAllForPrompt(),
            disabledStickers = DisabledBuiltInStickerStore.disabledIds(context),
            retrievedMemorySnippets = retrievedSnippets,
            offlineMeetingMemoryText = offlineMeetingMemoryRepository.renderedForInjection(character.uuid),
            unsummarizedRoundsOutsideBaseWindow = unsummarizedRounds,
            scene = PromptScene.ONLINE_CHAT,
            // 余温消息是 TA **主动**发起（见 [OfflineAfterglowService] KDoc），不是在「回」用户——
            // 方向化间隔行「…隔了约 X 才回你」在这条路上事实即错，须走中性措辞
            // （与 [com.situ.aichat.recovery.RecoveryReplyGenerator] 同款：T5 复核🟡④「那段延迟是系统的，
            // 方向化会把锅甩给用户」）。本组装器自称复刻 Recovery 全量 fan-out，当初漏抄了这一个参数；
            // 相识天数 §13 把该行从「对方」换成真名后，这句错话变得会被模型顺口接出来，故一并补上。
            delayedGeneration = true,
            worldInfo = worldInfo,
            now = nowInstant,
        )
    }

    private companion object {
        private const val HISTORY_FETCH_LIMIT = 500
    }
}
