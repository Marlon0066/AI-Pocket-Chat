package com.situ.aichat.voice

import android.content.Context
import com.situ.aichat.R
import com.situ.aichat.data.calendar.CalendarReader
import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.StructuredMemory
import com.situ.aichat.data.remote.llm.LlmClient
import com.situ.aichat.data.remote.llm.StreamToken
import com.situ.aichat.data.remote.llm.UsageDto
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.PetRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.data.repository.StickerRepository
import com.situ.aichat.economy.CharacterEconomicStateService
import com.situ.aichat.gift.GiftHistoryPromptService
import com.situ.aichat.moments.MomentChatContextService
import com.situ.aichat.pet.OtherPetInfo
import com.situ.aichat.pet.PetInventoryPromptService
import com.situ.aichat.pet.growthStage
import com.situ.aichat.pet.species
import com.situ.aichat.prompt.PromptBuilder
import com.situ.aichat.prompt.PromptBuilder.AssistantDeliveryMode
import com.situ.aichat.prompt.PromptScene
import com.situ.aichat.prompt.PromptStrings
import com.situ.aichat.prompt.ReplyParser
import com.situ.aichat.prompt.memory.InSceneRecapCoordinator
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.prompt.memory.VectorMemoryService
import com.situ.aichat.worldbook.WorldBookPromptService
import com.situ.aichat.worldbook.toWorldInfoSettings
import com.situ.aichat.sticker.DisabledBuiltInStickerStore
import com.situ.aichat.tts.TtsConfigurationRepository
import com.situ.aichat.tts.TtsService
import com.situ.aichat.tts.TtsVoiceProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and streams the LLM turn for a voice call — the Android home of iOS `VoiceCallManager+TTS`'s
 * `streamLLMResponse`, decoupled from the UI exactly like [com.situ.aichat.busyreply.BusyReplyService] is
 * (the call runs from a `@Singleton` controller, not a `ChatViewModel`). Two responsibilities:
 *
 *  1. [resolveSynthesizer] — resolve the character's TTS provider/voice **once per turn, with NO moodEmoji**
 *     (1:1 iOS §1.2: the call uses only the character's fixed `ttsEmotionRaw`, never per-sentence emoji
 *     auto-mapping — the load-bearing difference from the chat-message TTS path), and hand the pipeline a
 *     bound `synthesize(text)` closure.
 *  2. [streamResponse] — assemble the full conversation context (same pieces as
 *     `ChatViewModel.runAssistantTurn`: history + vector memory + schedule + calendar + moments + economy +
 *     gifts + stickers + pet), build messages with `scene = VOICE_CALL`, `deliveryMode = VOICE`, **tools and
 *     vision off**, then `streamChat` token-by-token. `Content` tokens feed the pipeline; `Reasoning` is
 *     ignored (= iOS ignoring `.thinking` / `.toolCallDelta`). Returns the sanitized full text.
 *
 * The current user utterance is persisted by the controller (10.1i, before this turn starts), so the
 * history fetch here already includes it — no synthetic trailing message (= iOS streamLLMResponse).
 */
@Singleton
class VoiceCallTurnService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageRepo: MessageRepository,
    private val conversationRepo: ConversationRepository,
    private val characterRepo: CharacterRepository,
    private val offlineMeetingMemoryRepository: com.situ.aichat.data.repository.OfflineMeetingMemoryRepository,
    private val ourDayRepository: com.situ.aichat.data.repository.OurDayRepository,
    private val apiConfigRepo: ApiConfigRepository,
    private val settingsRepo: SettingsRepository,
    private val stickerRepo: StickerRepository,
    private val petRepo: PetRepository,
    private val petInventoryPromptService: PetInventoryPromptService,
    private val userProfileDao: UserProfileDao,
    private val scheduleDao: ScheduleDao,
    private val calendarReader: CalendarReader,
    private val vectorMemory: VectorMemoryService,
    private val memoryService: MemoryService,
    private val momentChatContextService: MomentChatContextService,
    private val economicStateService: CharacterEconomicStateService,
    private val giftDao: GiftDao,
    private val ttsService: TtsService,
    private val ttsConfigRepo: TtsConfigurationRepository,
    private val llmClient: LlmClient,
    private val contextLog: ContextLogService,
    private val worldBookPromptService: WorldBookPromptService,
) {
    /** Raised when the turn cannot start (no config / key / character) — the controller falls back to listening. */
    class TurnUnavailable(message: String) : Exception(message)

    /**
     * Resolve the per-turn TTS synthesizer (once, no moodEmoji). Returns a closure the pipeline calls per
     * sentence. If the character is gone, the closure always returns null (every sentence discards → the
     * pipeline reports failure → the controller returns to listening, 1:1 iOS no-voice behavior).
     */
    suspend fun resolveSynthesizer(characterUuid: String): suspend (String) -> ByteArray? {
        val character = characterRepo.get(characterUuid)
            ?: return { _ -> null }
        val config = ttsConfigRepo.getConfiguration()
        val apiKey = ttsConfigRepo.getApiKey()
        val profile = TtsVoiceProfile(
            voiceIdentifier = character.voiceIdentifier,
            remoteVoiceID = character.remoteVoiceID,
            ttsEmotionRaw = character.ttsEmotionRaw,
            ttsSpeed = character.ttsSpeed,
            ttsPitch = character.ttsPitch,
        )
        return { text -> ttsService.synthesize(text, profile, config, apiKey, moodEmoji = null) }
    }

    /**
     * Stream one voice-call LLM turn, feeding visible content to [onContentToken] as it arrives, and return
     * the sanitized full response. Throws [TurnUnavailable] if the conversation/character/config/key is
     * missing (= iOS early-return-to-listening), and propagates network/stream errors to the controller's
     * fallback. [userText] is the just-recognized utterance, already persisted by the controller before this
     * call (so the history fetch includes it); here it is used only as the vector-memory query (= iOS).
     */
    suspend fun streamResponse(
        conversationUuid: String,
        characterUuid: String,
        userText: String,
        onContentToken: suspend (String) -> Unit,
    ): String {
        val convo = conversationRepo.get(conversationUuid)
            ?: throw TurnUnavailable("conversation $conversationUuid not found")
        val character = characterRepo.get(characterUuid)
            ?: throw TurnUnavailable("character $characterUuid not found")
        val resolvedConfig = apiConfigRepo.resolveConfigValues(ApiFunction.VOICE_CALL)
            ?: throw TurnUnavailable("no API config for voice call")
        if (resolvedConfig.apiKey.isEmpty()) throw TurnUnavailable("API key missing for voice call")
        // C3 通话响应预算：思考强度钳到 LOW（OFF 保持）——通话里长考数十秒是体感灾难，压首字延迟。
        val config = resolvedConfig.copy(
            thinkingBudgetLevel = VoiceCallTurnBudget.clampThinkingForCall(resolvedConfig.thinkingBudgetLevel),
        )

        val settings = settingsRepo.getAppSettings()
        val userProfile = userProfileDao.get()
        val userName = userProfile?.nickname ?: ""
        val promptStrings = PromptStrings(context)
        val nowInstant = Instant.now()
        val zone = ZoneId.systemDefault()
        val todayStartMillis =
            nowInstant.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

        // History = persisted chat. The current user utterance was persisted by the controller (10.1i,
        // VoiceCallPersistence.saveUserMessage) BEFORE this turn started, so the fetch already includes it
        // — 1:1 iOS streamLLMResponse (saveUserMessage runs first, then the context fetch). No synthetic msg.
        val history = messageRepo.recentChronological(conversationUuid, HISTORY_FETCH_LIMIT)
        // 记忆改造二期·部件⑤ 前情提要门控（§3.2-E 通话）：本场通话块 key = 尾部连续通话段首条 timestamp（无通话段 → null）。
        val callKey = InSceneRecapCoordinator.currentCallBlockKey(history)

        // M05 vector retrieval — query = the current utterance (= iOS latest user message).
        val retrievedSnippets = if (settings.vectorSearchThreshold > 0 && userText.isNotBlank()) {
            vectorMemory.searchRelevantMemories(
                query = userText,
                characterUuid = character.uuid,
                currentConversationUuid = conversationUuid,
                userName = userName.ifEmpty { promptStrings.s(R.string.pb_user_fallback) },
                characterName = character.name,
                shortTermLength = settings.shortTermMemoryLength,
                thresholdPercent = settings.vectorSearchThreshold,
            )
        } else {
            emptyList()
        }
        val unsummarizedRounds = memoryService.countUnsummarizedRoundsOutsideBaseWindow(
            currentConversation = convo,
            baseShortTermLength = settings.shortTermMemoryLength,
        )
        val structuredMemory = StructuredMemory.decode(character.structuredMemoryJSON)
        val offlineMeetingMemoryText = offlineMeetingMemoryRepository.renderedForInjection(character.uuid)
        val ourDays = ourDayRepository.injectableForCharacter(character.uuid) // 我们的日子·卷二：语音路同样预取（图纸 W-10）
        val milestones = characterRepo.getMilestones(character.uuid)
        val todaySchedule = scheduleDao.scheduleFor(character.uuid, todayStartMillis)
        val todayScheduleEvents = todaySchedule?.let { scheduleDao.eventsForSchedule(it.uuid) } ?: emptyList()
        val calendarUpcomingEvents = if (settings.calendarIntegrationEnabled) {
            calendarReader.upcomingEvents(nowInstant.toEpochMilli())?.text
        } else {
            null
        }
        val momentChatContext = momentChatContextService.buildMomentContext(
            character = character,
            userNickname = userName.ifEmpty { promptStrings.s(R.string.pb_user_fallback) },
            scheduleSystemEnabled = settings.scheduleSystemEnabled,
            nowMillis = nowInstant.toEpochMilli(),
        )
        val customStickers = stickerRepo.getAllForPrompt()
        val disabledStickers = DisabledBuiltInStickerStore.disabledIds(context)
        val pet = if (settings.petSystemEnabled) petRepo.getForCharacter(character.uuid) else null
        val otherPets = if (pet != null) resolveOtherPets(character.uuid) else emptyList()
        val petRecentPurchaseNames =
            if (pet != null) petInventoryPromptService.recentPetShopItemNames(nowInstant.toEpochMilli()) else emptyList()
        val economicState = economicStateService.resolveChatState(character.uuid, nowInstant.toEpochMilli())
        val giftHistory = GiftHistoryPromptService.buildContent(character.uuid, giftDao, nowInstant.toEpochMilli(), userName.ifEmpty { promptStrings.s(R.string.pb_user_fallback) })

        // 批 D 上下文日志：语音通话也走聊天模块系统 → buildMessagesWithSegments 顺带出结构化分段（scene=VOICE_CALL）。
        // 批W W-1：语音通话管道接世界书（与主聊天引擎同激活口径·每回合一次）——重设定角色进语音不再「失忆」。
        val worldInfo = worldBookPromptService.activateForTurn(
            characterUuid = character.uuid,
            conversationUuid = conversationUuid,
            sortedMessages = history,
            characterName = character.name,
            userName = userName.ifEmpty { promptStrings.s(R.string.pb_user_fallback) },
            vectorThresholdPercent = settings.vectorSearchThreshold,
            settings = settings.toWorldInfoSettings(),
        )
        val buildResult = PromptBuilder.buildMessagesWithSegments(
            character = character,
            conversation = convo,
            sortedMessages = history,
            userProfile = userProfile,
            appSettings = settings,
            strings = promptStrings,
            structuredMemory = structuredMemory,
            milestones = milestones,
            todaySchedule = todaySchedule,
            todayScheduleEvents = todayScheduleEvents,
            calendarUpcomingEvents = calendarUpcomingEvents,
            momentChatContext = momentChatContext,
            economicState = economicState,
            giftHistory = giftHistory,
            customStickers = customStickers,
            disabledStickers = disabledStickers,
            pet = pet,
            otherPets = otherPets,
            petRecentPurchaseNames = petRecentPurchaseNames,
            retrievedMemorySnippets = retrievedSnippets,
            offlineMeetingMemoryText = offlineMeetingMemoryText,
            ourDays = ourDays,
            assistantDeliveryMode = AssistantDeliveryMode.VOICE,
            toolCallingEnabled = false,
            // No voice-tag teaching: the call strips MiniMax voice tags before TTS anyway (= iOS).
            miniMaxVoiceTagsCapability = null,
            scene = PromptScene.VOICE_CALL,
            unsummarizedRoundsOutsideBaseWindow = unsummarizedRounds,
            worldInfo = worldInfo,
            now = nowInstant,
            // 记忆改造二期·部件⑤ 前情提要门控（§3.2-E 通话）：本场（key==callKey）且提要非空才注入。
            inSceneRecap = convo.inSceneRecapText.takeIf {
                callKey != null && convo.inSceneRecapSessionKey == callKey && it.isNotBlank()
            },
        )
        val messages = buildResult.messages

        // Stream: feed visible content to the pipeline; ignore reasoning (= iOS ignoring .thinking/.toolCall).
        // The collect runs in the caller's (main) context, so onContentToken hops to the pipeline on main.
        // 批 D 上下文日志：捕获末帧 usage + 计时，收流后落一条（source=VOICE_CALL）；失败原样重抛前先记。fire-and-forget。
        val full = StringBuilder()
        val turnStart = System.currentTimeMillis()
        var usage: UsageDto? = null
        try {
            // C3 通话响应预算：20s 无任何 SSE 行（keep-alive 注释行也算活性）才判死 → FirstStreamEventTimeout →
            // 下面的错误路径记日志、controller 走失败兜底——不再陪全局 60s readTimeout 干等。
            VoiceCallTurnBudget.collectWithFirstEventBudget(
                budgetMs = VoiceCallTurnBudget.FIRST_EVENT_BUDGET_MS,
                streamFactory = { onLiveness ->
                    llmClient.streamChat(
                        messages = messages,
                        config = config,
                        temperature = settings.sanitizedLlmTemperature,
                        onUsage = { usage = it },
                        onSseLine = onLiveness,
                    )
                },
            ) { token ->
                if (token is StreamToken.Content) {
                    full.append(token.text)
                    onContentToken(token.text)
                }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            contextLog.recordError(LogSource.VOICE_CALL, character.name, config.modelName, messages, e, buildResult.segments)
            throw e
        }
        contextLog.recordSuccess(
            LogSource.VOICE_CALL, character.name, config.modelName, messages, full.toString(),
            System.currentTimeMillis() - turnStart, usage, buildResult.segments,
        )
        return ReplyParser.sanitizeAssistantResponse(full.toString(), characterName = character.name)
    }

    /** Other characters' pets (first 5) for the PET_STATUS social block — 1:1 iOS / ChatViewModel.resolveOtherPets. */
    private suspend fun resolveOtherPets(currentCharacterUuid: String): List<OtherPetInfo> =
        petRepo.getAll()
            .filter { it.characterUuid != currentCharacterUuid }
            .take(5)
            .mapNotNull { p ->
                characterRepo.get(p.characterUuid)?.let { c -> OtherPetInfo(c.name, p.name, p.species, p.growthStage) }
            }

    private companion object {
        const val HISTORY_FETCH_LIMIT = 500
    }
}
