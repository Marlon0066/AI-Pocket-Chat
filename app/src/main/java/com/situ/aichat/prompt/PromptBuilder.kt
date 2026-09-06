package com.situ.aichat.prompt

import com.situ.aichat.R
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.CharacterEconomicChatState
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.model.MomentChatContext
import com.situ.aichat.data.model.StructuredMemory
import com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.CustomStickerEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.MeetingStatus
import com.situ.aichat.data.model.MeetingTimeGranularity
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.meeting.MeetingDisplayFormatter
import com.situ.aichat.offline.NarrativeDirectiveService
import com.situ.aichat.offline.OfflineNarrativePreset
import com.situ.aichat.pet.OtherPetInfo
import com.situ.aichat.prompt.memory.InSceneRecapCoordinator
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.tooling.ChatToolContext
import com.situ.aichat.tooling.PendingCalendarFailure
import com.situ.aichat.tooling.chatToolRegistry
import com.situ.aichat.tts.provider.MiniMaxVoiceTagsCapability
import com.situ.aichat.util.DateFormatters
import com.situ.aichat.worldbook.WorldInfoActivationResult
import java.time.Instant
import java.time.ZoneId
import java.util.Base64

/**
 * 1:1 port of iOS `PromptBuilder` (核心装配 + 模块内容). Stateless. Builds the full `[ChatMessage]` sent to
 * the LLM following the exact 9-step physical assembly order, and (via [ReplyParser]) parses replies.
 *
 * Coupled-but-not-yet-built features (offline meeting M16, voice M15, calendar/schedule M12, moments M06,
 * sticker M17, pet M11, growth M14, gift M09, economy M10) have their module builders return "" and their
 * assembly steps no-op — exactly what iOS does when those systems are disabled / their data is absent.
 * The active online-chat modules produce an iOS-faithful prompt.
 */
object PromptBuilder {

    /**
     * 默认记忆**注入**提示词模板（14.5b·1:1 iOS `PromptBuilder.defaultInjectionPrompt`，含 `{{记忆内容}}` 宏）。
     *
     * ⚠️ 仅供「记忆提示词」设置编辑器作展示/恢复默认/「等于默认即存空」比对之用——**运行时默认注入路径并不读它**
     * （buildCharacterMemoryContent 在 memoryInjectionPrompt 为空时走本地化的「过往互动记忆」字符串资源，与 iOS
     * `## Past interaction memories` 默认分支 1:1）。这与 iOS 完全一致：iOS 的 defaultInjectionPrompt 同样只被
     * MemoryPromptSettingsView 引用，运行时默认分支用的是 String(localized:)。
     */
    val DEFAULT_INJECTION_PROMPT: String = """
        [{{char}}的记忆]
        以下是你对过往互动的记忆。使用指南：
        1. 只在话题自然相关时引用记忆，不要主动罗列或复述
        2. 用自然的方式体现你记得（如“你之前不是说喜欢…”），不要说“根据我的记忆”
        3. 如果{{user}}的话和记忆矛盾，以{{user}}当前说法为准，可以自然确认“咦，你不是说…换了吗？”
        4. 较早的经历可以表现得模糊些，近期的应该记得清楚
        5. 一次回复最多自然带出1-2条记忆，不要堆砌
        {{记忆内容}}
        """.trimIndent()

    enum class AssistantDeliveryMode { TEXT, VOICE }

    /** [buildMessagesWithSegments] 的返回：发送给大模型的消息 + 各模块/历史的结构化分段（批 D 上下文日志）。 */
    data class PromptBuildResult(
        val messages: List<ChatMessageDto>,
        val segments: List<ContextSegment>,
    )

    /** 短期记忆窗口的时间快照（方案 G2，timeAwareness 模块用）。 */
    data class ConversationTimeSnapshot(
        val lastAssistantTime: Instant?,
    ) {
        companion object {
            val EMPTY = ConversationTimeSnapshot(null)

            /**
             * 从窗口内消息（升序）抽取。lastAssistantTime 排除 pet 消息——"距离你上条回复"指角色本人。
             * （窗口起止已不再需要——「对话窗口跨度」行已废弃，整段时间骨架改由 [HistoryTimeDivider] 逐条表达。）
             */
            fun from(messages: List<MessageEntity>): ConversationTimeSnapshot {
                if (messages.isEmpty()) return EMPTY
                val lastAssistant = messages.lastOrNull {
                    it.roleRaw == ROLE_ASSISTANT && !it.isPetMessage
                }?.timestamp
                return ConversationTimeSnapshot(
                    lastAssistantTime = lastAssistant?.let { Instant.ofEpochMilli(it) },
                )
            }
        }
    }

    /** 构建上下文参数（对齐 iOS BuildContext，仅含已实现部分 + 共享 now）。 */
    data class BuildContext(
        val character: CharacterEntity,
        /** 关系里程碑（升序 = iOS `sortedMilestones`），独立表加载后传入，characterGrowth 模块用。 */
        val milestones: List<MilestoneEntity>,
        /** 今日日程 + 其事件（P5.2，独立表加载后传入；scheduleAwareness / currentMoment 模块用）。 */
        val todaySchedule: CharacterDailyScheduleEntity? = null,
        val todayScheduleEvents: List<ScheduleEventEntity> = emptyList(),
        /** 今天之前 3 天的日程事件（时间感知三期）：渲染【你最近几天的日子】；空=整段不出。 */
        val recentDaysScheduleEvents: List<ScheduleEventEntity> = emptyList(),
        /** 设备日历近期事件的 `[#E1]` 提示词块（P5.3a，读侧已格式化后传入；calendarAwareness 模块用）。 */
        val calendarUpcomingEvents: String? = null,
        /** 近 7 天朋友圈互动摘要（M06 7.2.6，MomentChatContextService 装配后传入；MOMENTS_CONTEXT 模块用）。 */
        val momentChatContext: MomentChatContext? = null,
        /** 角色经济状态（M10 9.1a，CharacterEconomicStateService 预计算后传入；CHARACTER_ECONOMIC_STATE 模块用，null=月薪0/无钱包→跳过）。 */
        val economicState: CharacterEconomicChatState? = null,
        /** 礼物历史 `<gift_history>` 块（M09 9.2b，GiftHistoryPromptService 渲染后传入；GIFT_HISTORY 模块用，null/""=双向无礼物→跳过）。 */
        val giftHistory: String? = null,
        /** 自定义表情包（createdAt 升序；M17，调用方查库后传入；STICKER_LIBRARY 模块 + 历史别名转换用）。 */
        val customStickers: List<CustomStickerEntity> = emptyList(),
        /** 被隐藏的内置表情 ID（M17，DisabledBuiltInStickerStore 读后传入；只影响 enabled 清单/总数）。 */
        val disabledStickers: Set<String> = emptySet(),
        /** 当前角色的宠物（M11，调用方查 PetRepository 后传入；PET_STATUS 模块用，null=无宠物→模块空）。 */
        val pet: CharacterPetEntity? = null,
        /** 其他角色的宠物社交信息（M11，宠物状态段「其他角色也养了宠物」用）。 */
        val otherPets: List<OtherPetInfo> = emptyList(),
        /** 最近 24h 用户给宠物买的物品名（M11 P9.3c，PetInventoryPromptService 预查 + 去重后传入；宠物状态「最近买的东西」行用）。 */
        val petRecentPurchaseNames: List<String> = emptyList(),
        val userProfile: UserProfileEntity?,
        val appSettings: AppSettings,
        val structuredMemory: StructuredMemory,
        val retrievedMemorySnippets: List<String>,
        /** 线下见面【总结】注入文本（梦剧场 B 部·[com.situ.aichat.data.repository.OfflineMeetingMemoryRepository.renderedForInjection]
         *  从结构化行渲染·§3.6）：{{见面记忆}} 宏唯一来源；调用方预取后传入。默认 "" = 不注入（additive·向后兼容）。
         *  注入时经相框包装（[buildOfflineMeetingMemoryContent]·2026-07-11 前置改造·空→空）。 */
        val offlineMeetingMemoryText: String = "",
        /** W5 世界联动上下文块（提炼 + 世界记忆·[com.situ.aichat.world.link.WorldChatContextProvider] 装配后传入；
         *  null/空 = 不注入）：作为【角色记忆】模块第四层（§9 联动闭环·additive 零改既有段）。 */
        val worldContext: String? = null,
        /** 活人感一期 P2 该角色 open 状态惦记的事（调用方经 [com.situ.aichat.data.repository.OpenLoopRepository] 预取传入；
         *  空 = 不注入）：注入选择 + 格式化在 [buildCharacterMemoryContent] 内用 ctx.timeSnapshot/now/strings 完成（§4.3·additive）。 */
        val openLoops: List<com.situ.aichat.data.local.entity.OpenLoopEntity> = emptyList(),
        /** 记忆改造一期·部件① 该角色注入候选约定（open 全量 + 近 7 天已结·调用方经
         *  [com.situ.aichat.data.repository.PromiseRepository.injectableForCharacter] 预取传入；空 = 不注入）：
         *  选择 / 排序 / 软上限 / 渲染在 [buildCharacterMemoryContent] 内经 [com.situ.aichat.promise.PromiseInjectionRenderer]
         *  完成（§3.3·additive·照 openLoops 透传路径）。 */
        val promises: List<com.situ.aichat.data.local.entity.PromiseEntity> = emptyList(),
        /** 「我们的日子」卷二（图纸 §3.3）：注入候选行（调用方 `OurDayRepository.injectableForCharacter` 预取·空 = 不注入·照 promises 透传路径）/ 用户当前消息组文本（`OurDaysTurnText.from`·buildMessages 从窗口派生）/ 原文窗口最早消息时刻（null = 窗口空）。 */
        val ourDays: List<com.situ.aichat.data.local.entity.OurDayEntity> = emptyList(),
        val ourDaysTurnText: String = "",
        val windowEarliestMillis: Long? = null,
        val assistantDeliveryMode: AssistantDeliveryMode,
        val toolCallingEnabled: Boolean,
        /** MiniMax 语气标签前门判定（P10.1c）。null = 不注入；只有 shouldInjectTagsHint 时才追加教学。 */
        val miniMaxVoiceTagsCapability: MiniMaxVoiceTagsCapability? = null,
        val macros: Map<String, String>,
        val resolvedUserName: String,
        val resolvedCharacterName: String,
        val timeSnapshot: ConversationTimeSnapshot,
        val scene: PromptScene,
        /** 延迟生成路（进程恢复补生成）标记：间隔行退回中性措辞「距离你上条回复：约X」——延迟是系统的,
         *  方向化「对方隔了X才回你」会把锅甩给用户(T5 复核🟡④)。即时聊天/语音恒 false。 */
        val delayedGeneration: Boolean = false,
        val extraMacros: Map<String, String>,
        /** 方案 G3：本次构建的"现在"，所有模块共用，避免毫秒漂移。 */
        val now: Instant,
        val strings: PromptStrings,
        /** 卷三 D2：最近 3 轮 · 3h 内的角色在线发言（`AttentionJudge.recentCharacterLines`），【此刻】睡眠/分心裁决取材。 */
        val recentCharacterLines: List<String> = emptyList(),
    )

    internal const val ROLE_USER = "user"
    internal const val ROLE_ASSISTANT = "assistant"
    internal const val ROLE_SYSTEM = "system"

    /**
     * 后置区**末尾连续**的 时间感知/此刻状态 段 = 现在卡成员（2026-07-11 拍板语义：排序对用户自由,
     * 钉末位只对默认序生效——被挪进中间就按用户顺序原地发射）。纯函数 internal 可测。
     */
    internal fun splitTrailingNowCard(entries: List<SuffixModuleEntry>): List<SuffixModuleEntry> =
        entries.takeLastWhile {
            it.systemModuleType == SystemModuleType.TIME_AWARENESS ||
                it.systemModuleType == SystemModuleType.CURRENT_MOMENT
        }

    /** 规则卡成员（刀2 装订过审名单）：非线下时这五个规则系统模块（+ module==null 的 MiniMax 教学）拼成一条。 */
    private val RULE_CARD_TYPES = setOf(
        SystemModuleType.RESPONSE_STYLE,
        SystemModuleType.CHAT_FORMAT,
        SystemModuleType.QUALITY_CONTROL,
        SystemModuleType.MOOD_EXPRESSION,
        SystemModuleType.GENERAL_INSTRUCTIONS,
    )

    // MARK: - P13.4b 语音消息音频段

    /** 音频段固定格式（录音器产出 16kHz/mono/PCM16 WAV，1:1 iOS audioFormat:"wav"）。 */
    internal const val AUDIO_FORMAT_WAV = "wav"

    /** 语音消息占位转写（STT 未完成/失败时 content 兜底，必须与录音侧逐字一致，1:1 iOS `[语音消息]`）。 */
    internal const val VOICE_MESSAGE_PLACEHOLDER = "[语音消息]"
    private const val VOICE_MESSAGE_PLACEHOLDER_EN = "[Voice Message]"

    /**
     * 语音消息音频段的「文字段」包装文案（1:1 iOS `PromptBuilder.buildAudioPrompt`，:715-723）：引导模型优先听音频、
     * 把转写当参考；转写为空/占位 → 替换为「未提供转写」标记。[promptPrefix]/[noTranscriptMarker] 由调用方经
     * PromptStrings 本地化注入（iOS 同样 `String(localized:)`，中文设备发中文包装；保持纯函数便于单测）。
     */
    internal fun buildAudioPrompt(transcript: String, promptPrefix: String, noTranscriptMarker: String): String {
        val trimmed = transcript.trim()
        val reference = if (trimmed.isEmpty() || isVoicePlaceholderTranscript(trimmed)) noTranscriptMarker else trimmed
        return promptPrefix + reference
    }

    /**
     * 这段「转写」是不是 STT 未完成 / 失败时写下的占位（中英任一）——**占位不是内容**，没有任何信息量。
     * 判据单源：`buildAudioPrompt` 的降级分支与「语音消息能不能被引用」
     * （`ui/chat/ChatImmersiveMenu.messageCanBeQuoted`）都问这一个函数，两处口径永不漂移。
     * 两个常量的**值与录音侧逐字耦合，一个字都不许改**（REDLINES）。
     */
    internal fun isVoicePlaceholderTranscript(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed == VOICE_MESSAGE_PLACEHOLDER || trimmed == VOICE_MESSAGE_PLACEHOLDER_EN
    }

    /** WAV 字节 → base64（裸 base64、无 data: 前缀，1:1 iOS 音频 `Data.base64EncodedString()`）。 */
    internal fun encodeWavBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    // MARK: - 消息构建

    /**
     * 构建发给 LLM 的完整消息数组（系统提示 + 最近 N 轮对话 + 后置模块），物理顺序对齐 iOS 9 步。
     *
     * @param unsummarizedRoundsOutsideBaseWindow 由 M05 记忆系统提供（未建时传 0）。
     */
    fun buildMessages(
        character: CharacterEntity,
        /** 当前会话（M16 线下模式判定源）。null=非线下（busy/voice 等场景或测试默认）。 */
        conversation: ConversationEntity? = null,
        sortedMessages: List<MessageEntity>,
        userProfile: UserProfileEntity?,
        appSettings: AppSettings,
        strings: PromptStrings,
        structuredMemory: StructuredMemory = StructuredMemory.EMPTY,
        milestones: List<MilestoneEntity> = emptyList(),
        todaySchedule: CharacterDailyScheduleEntity? = null,
        todayScheduleEvents: List<ScheduleEventEntity> = emptyList(),
        recentDaysScheduleEvents: List<ScheduleEventEntity> = emptyList(),
        calendarUpcomingEvents: String? = null,
        momentChatContext: MomentChatContext? = null,
        economicState: CharacterEconomicChatState? = null,
        giftHistory: String? = null,
        customStickers: List<CustomStickerEntity> = emptyList(),
        disabledStickers: Set<String> = emptySet(),
        pet: CharacterPetEntity? = null,
        otherPets: List<OtherPetInfo> = emptyList(),
        petRecentPurchaseNames: List<String> = emptyList(),
        retrievedMemorySnippets: List<String> = emptyList(),
        /** 线下见面【总结】注入文本（梦剧场 B 部·§3.6）：调用方经 `OfflineMeetingMemoryRepository.renderedForInjection`
         *  预取后传入 → {{见面记忆}} 宏（注入时经相框包装 [buildOfflineMeetingMemoryContent]·2026-07-11 前置改造）。
         *  默认 "" = 不注入（additive·向后兼容·各主聊天路径须显式接线）。
         *  **新调用点必须传 `OfflineMeetingMemoryRepository.renderedForInjection`，漏传=该场景丢见面记忆。** */
        offlineMeetingMemoryText: String = "",
        /** W5 世界联动上下文（[com.situ.aichat.world.link.WorldChatContextProvider] 装配·null=不注入·照 retrievedMemorySnippets 透传路径）。 */
        worldContext: String? = null,
        /** 活人感一期 P2 该角色 open 惦记的事（调用方 [com.situ.aichat.data.repository.OpenLoopRepository] 预取·空=不注入·照 worldContext 透传路径）。 */
        openLoops: List<com.situ.aichat.data.local.entity.OpenLoopEntity> = emptyList(),
        /** 记忆改造一期·部件① 该角色注入候选约定（调用方 [com.situ.aichat.data.repository.PromiseRepository.injectableForCharacter] 预取·空=不注入·照 openLoops 透传路径）。 */
        promises: List<com.situ.aichat.data.local.entity.PromiseEntity> = emptyList(),
        /** 「我们的日子」卷二 该角色注入候选行（调用方 [com.situ.aichat.data.repository.OurDayRepository.injectableForCharacter] 预取·空=不注入·照 promises 透传路径）。**新调用点必须传 `OurDayRepository.injectableForCharacter`，漏传=该场景丢日子注入。** */
        ourDays: List<com.situ.aichat.data.local.entity.OurDayEntity> = emptyList(),
        assistantDeliveryMode: AssistantDeliveryMode = AssistantDeliveryMode.TEXT,
        toolCallingEnabled: Boolean = false,
        miniMaxVoiceTagsCapability: MiniMaxVoiceTagsCapability? = null,
        scene: PromptScene = PromptScene.ONLINE_CHAT,
        extraMacros: Map<String, String> = emptyMap(),
        unsummarizedRoundsOutsideBaseWindow: Int = 0,
        /** P13.4b：路由配置是否支持音频输入（= config.audioInputEnabled）。false → 语音消息按转写纯文本发。 */
        audioInputEnabled: Boolean = false,
        /**
         * P13.4b：窗口内用户语音消息的音频（messageUUID → **已预编码的裸 base64**），由调用方在 IO 线程
         * 预读并编码；空=不挂音频段。（曾是 ByteArray 在此路径内编码——那会把 base64 压在主线程上。）
         */
        audioAttachments: Map<String, String> = emptyMap(),
        /** 图片多模态：路由配置是否支持视觉（= config.visionEnabled）。false → 图片走语义占位文本。 */
        visionEnabled: Boolean = false,
        /**
         * 图片多模态：窗口内用户图片消息的 **data URI**（messageUUID → `data:image/jpeg;base64,…`），
         * 由调用方在 IO 线程预读编码，且**只取最近若干张**（拍板①：更早的图退化为语义占位）。
         */
        imageAttachments: Map<String, String> = emptyMap(),
        /** 等待期（Phase 9）：该角色「下一个已确认未来约定」（调用方预查 nextUpcomingForCharacter）；非空 + 非线下 → 注入【待见约定】。 */
        nextMeetingAppointment: MeetingAppointmentEntity? = null,
        /**
         * ②：本会话「未消费的日历真失败」（调用方经 [com.situ.aichat.ui.chat.ChatCalendarActionHandler.consumePendingFailure]
         * 一次性 + TTL 过滤后传入）；非空 + 非线下 → 注入陪伴口吻【有件小事没办成】系统提示。默认 null=不注入（additive）。
         */
        calendarFailure: PendingCalendarFailure? = null,
        /** WB4 世界书激活结果（调用方经 WorldBookPromptService 每回合预取一次）；null = 无注入（additive 零回归）。 */
        worldInfo: WorldInfoActivationResult? = null,
        now: Instant = Instant.now(),
        /** 记忆改造二期·部件④ 见面时间线注记（图纸 §3.1）：调用方经 `OfflineMeetingMemoryRepository.byCharacter` 预取
         *  该角色全部见面档案行（注记端自筛跨度 + 上限 5）；空 = 不注记·仅普通在线聊天（now 非空）时生效·照 promises 透传路径。 */
        meetingTimeline: List<OfflineMeetingMemoryEntity> = emptyList(),
        /** 引用一期（图纸 §3.1）：调用方经 `MessageRepository.quotedRefs(history)` 预取的
         *  quotedMessageUUID → 被引用消息时间戳 + 原始正文；空 = 引用行走无锚形态 + 回退落库快照（行为同接线前）。 */
        quotedRefs: Map<String, com.situ.aichat.data.repository.QuotedMessageRef> = emptyMap(),
        /** 记忆改造二期·部件⑤ 场内前情提要（图纸 §3.2-D/E）：调用方**门控式**传本场提要正文（见面/通话 key 校验通过才传·
         *  否则 null）；非空 → 注入在截断提示之后（2.15）。普通聊天 / 忙碌回复永不注入（缺省 null）。 */
        inSceneRecap: String? = null,
        /** 延迟生成路标记（仅进程恢复补生成传 true）：时间锚间隔行用中性措辞。 */
        delayedGeneration: Boolean = false,
        /**
         * 批 D 上下文日志（内部管线，调用方一般经 [buildMessagesWithSegments]）：非 null 时本次构建顺带把各启用模块 +
         * 对话历史的结构化分段收集进来（仅聊天管线产生，1:1 iOS `buildMessagesWithSegments`）。收集完按
         * 前置→历史→后置就地排序。不影响返回的消息本身。
         */
        segmentSink: MutableList<ContextSegment>? = null,
    ): List<ChatMessageDto> {
        val chatMessages = mutableListOf<ChatMessageDto>()

        // M16 线下模式判定（P1-1 脏状态兜底，1:1 iOS isOfflineModeHealthy）：仅 isInOfflineMode=true **且**
        // currentOfflineSessionId 去空白后非空时才按线下处理。不做 DB 修复（那是 OfflineStateGuard 的职责，10.2c）。
        val isCurrentlyInOfflineMode = isOfflineModeHealthy(conversation)

        // 模式感知单一仲裁点（2026-07-12 图纸 v2）：聊天双场景由健康判定单源改写——调用方裸 flag
        // （AssistantTurnEngine:312）与硬编码（RecoveryReplyGenerator:186）的口径差在此吸收；
        // VOICE_CALL/BUSY_REPLY 透传（线下时通话入口隐藏 = ChatTopBar:175）。
        val effectiveScene = when (scene) {
            PromptScene.ONLINE_CHAT, PromptScene.OFFLINE_MEETING ->
                if (isCurrentlyInOfflineMode) PromptScene.OFFLINE_MEETING else PromptScene.ONLINE_CHAT
            else -> scene
        }
        // 模块语境二值化（两语境模型）：模块过滤与内容分版只问「是否线下」——语音/忙碌等一切
        // 非线下场景按在线聊天位走，勾选 UI（两行开关）与运行行为恒一致。时间分割线等场景语义仍用 effectiveScene。
        val moduleScene = if (isCurrentlyInOfflineMode) PromptScene.OFFLINE_MEETING else PromptScene.ONLINE_CHAT

        // 0. 短期窗口预计算（方案 G2）
        val (filteredMessages, _, truncationNotes) = prepareFilteredRecentMessages(
            sortedMessages = sortedMessages,
            appSettings = appSettings,
            isCurrentlyInOfflineMode = isCurrentlyInOfflineMode,
            currentOfflineSessionId = conversation?.currentOfflineSessionId,
            unsummarizedRoundsOutsideBaseWindow = unsummarizedRoundsOutsideBaseWindow,
            now = now,
        )
        val timeSnapshot = ConversationTimeSnapshot.from(filteredMessages)
        val ourDaysTurnText = com.situ.aichat.prompt.ourdays.OurDaysTurnText.from(filteredMessages) // 卷二 W-4：只有这里知道真实窗口
        val windowEarliestMillis = filteredMessages.firstOrNull()?.timestamp

        // WB4 世界书（契约 §4.3）：宏解析注入内容（{{user}}/{{char}}/{{now}}·未知宏原样输出=D6）。
        // 四锚点全部**只增新段、零改既有段**（CLAUDE.md §5 提示词强耦合红线）。
        val activeWorldInfo = worldInfo?.takeIf { !it.isEmpty }
        // 批2 2-6：补 {{now}} 宏（契约 D6/§4.5 承诺三宏，旧实现只产 char/user → 条目里的 {{now}} 原样漏进 prompt）；
        // 时间格式与 macroProducers 的 PromptMacros.NOW 同源。extraMacros 靠后 = 调用方可覆写。
        val worldMacros =
            if (activeWorldInfo != null) {
                promptMacros(character, userProfile, strings) +
                    mapOf(PromptMacros.NOW to DateFormatters.yearMonthDayHourMinute(now.toEpochMilli())) +
                    extraMacros
            } else {
                emptyMap()
            }
        fun resolveWorld(text: String) = applyPromptMacros(text, worldMacros)

        // 1. 系统提示词（前置区模块）+ 收集后置区模块条目（发射顺序在第 4/5 步决定）
        val recentCharacterLines = com.situ.aichat.prompt.growth.AttentionJudge.recentCharacterLines(filteredMessages, now.toEpochMilli()) // 卷三 D2
        val (systemPrompt, suffixEntries) = buildSystemPromptWithSuffixes(
            character = character,
            milestones = milestones,
            todaySchedule = todaySchedule,
            todayScheduleEvents = todayScheduleEvents,
            recentDaysScheduleEvents = recentDaysScheduleEvents,
            calendarUpcomingEvents = calendarUpcomingEvents,
            userProfile = userProfile,
            appSettings = appSettings,
            structuredMemory = structuredMemory,
            retrievedMemorySnippets = retrievedMemorySnippets,
            offlineMeetingMemoryText = offlineMeetingMemoryText,
            worldContext = worldContext,
            openLoops = openLoops,
            promises = promises,
            ourDays = ourDays, ourDaysTurnText = ourDaysTurnText, windowEarliestMillis = windowEarliestMillis, // 卷二
            assistantDeliveryMode = assistantDeliveryMode,
            toolCallingEnabled = toolCallingEnabled,
            miniMaxVoiceTagsCapability = miniMaxVoiceTagsCapability,
            momentChatContext = momentChatContext,
            economicState = economicState,
            giftHistory = giftHistory,
            customStickers = customStickers,
            disabledStickers = disabledStickers,
            pet = pet,
            otherPets = otherPets,
            petRecentPurchaseNames = petRecentPurchaseNames,
            timeSnapshot = timeSnapshot,
            scene = moduleScene,
            delayedGeneration = delayedGeneration,
            extraMacros = extraMacros,
            now = now,
            strings = strings,
            segmentSink = segmentSink,
            worldInfoBefore = activeWorldInfo?.before?.takeIf { it.isNotBlank() }?.let(::resolveWorld) ?: "",
            worldInfoAfter = activeWorldInfo?.after?.takeIf { it.isNotBlank() }?.let(::resolveWorld) ?: "",
            recentCharacterLines = recentCharacterLines,
        )
        if (systemPrompt.isNotEmpty()) {
            chatMessages.add(ChatMessageDto(role = ROLE_SYSTEM, content = systemPrompt))
        }

        // 2.05 线下事件元数据注入 — TODO(M16) OfflineEventSummarizer；当前无线下消息，恒空。

        // 2.1 截断提示
        for (note in truncationNotes) {
            chatMessages.add(ChatMessageDto(role = ROLE_SYSTEM, content = note))
        }

        // 2.15 场内前情提要（记忆改造二期·部件⑤·§3.2-D）：截断提示之后、历史回顾语义提示之前。标题
        // [InSceneRecapCoordinator.RECAP_HEADER]（=「【前情提要】」）单源，与 DirtyMessageDetector 硬编码字面互指。
        if (!inSceneRecap.isNullOrBlank()) {
            chatMessages.add(
                ChatMessageDto(
                    role = ROLE_SYSTEM,
                    content = "${InSceneRecapCoordinator.RECAP_HEADER}（本场更早部分的浓缩，下面的正文只保留了最近的对话）\n$inSceneRecap",
                ),
            )
        }

        // 2.2 历史回顾语义提示
        val isCurrentlyInVoiceCall = assistantDeliveryMode == AssistantDeliveryMode.VOICE
        // （原「历史含线下见面 → OFFLINE_HISTORY_HINT」分支已随「见面去重」后线下消息不再进在线窗口成死代码，
        //   2026-07-13 移除；线下叙事不再渗进在线历史，普通聊天由核心规则 r4 常驻兜底。）
        if (!isCurrentlyInVoiceCall && filteredMessages.any { it.isPartOfVoiceCall }) {
            chatMessages.add(ChatMessageDto(role = ROLE_SYSTEM, content = buildVoiceCallHistoryHint(userProfile?.nickname)))
        }

        // 3. 对话历史
        val historyStart = chatMessages.size
        appendConversationMessages(
            recentMessages = filteredMessages,
            chatMessages = chatMessages,
            character = character,
            petName = pet?.name,
            userProfile = userProfile,
            isCurrentlyInOfflineMode = isCurrentlyInOfflineMode,
            strings = strings,
            customStickers = customStickers,
            allowStickers = appSettings.characterCanSendStickersEnabled,
            audioInputEnabled = audioInputEnabled,
            audioAttachments = audioAttachments,
            visionEnabled = visionEnabled,
            imageAttachments = imageAttachments,
            // 历史时间分割线门控（A5）：仅【普通在线聊天】(effectiveScene==ONLINE_CHAT) 时注入。effectiveScene 已把
            // 「脏状态传 OFFLINE_MEETING→回落 ONLINE_CHAT」（V-3 分割线恢复）与「健康线下→OFFLINE_MEETING」单源收敛；
            // 忙碌回复 / 语音通话等其它场景不插（与「仅在线聊天」一致）；线下叙事刻意不打时间戳线（时间感知模块照常）。
            now = if (effectiveScene == PromptScene.ONLINE_CHAT) now else null,
            // 记忆改造二期·部件④ 见面时间线注记（§3.1）：与分割线共用上面的 now 门控（now=null 时零注记）。
            meetingTimeline = meetingTimeline,
            // 引用一期：被引用消息的预取对照表（时间锚 + 原始正文）原样透传。
            quotedRefs = quotedRefs,
        )
        val historyEnd = chatMessages.size

        // WB4 世界书 @depth 桶（position 4）：对话历史倒数第 depth 条处按 role 插入（0=system/1=user/2=assistant）；
        // 深度从大到小依次插，已插条数补偿下标位移；depth 超出历史长度钳到历史顶端。
        if (activeWorldInfo != null && activeWorldInfo.atDepth.isNotEmpty()) {
            var inserted = 0
            for (injection in activeWorldInfo.atDepth.sortedByDescending { it.depth }) {
                if (injection.content.isBlank()) continue
                val role = when (injection.role) {
                    1 -> ROLE_USER
                    2 -> ROLE_ASSISTANT
                    else -> ROLE_SYSTEM
                }
                val index = (historyEnd - injection.depth).coerceIn(historyStart, historyEnd) + inserted
                chatMessages.add(index, ChatMessageDto(role = role, content = resolveWorld(injection.content)))
                inserted++
            }
        }

        // 4. 后置区模块（聊天历史之后，利用近因偏差）。
        // 布局审计刀1+刀2（2026-07-11 过审）：**非线下**时后置区装订为三张卡——
        //   规则卡 = 回复风格+聊天格式+防重复+情绪表达+通用指令(+MiniMax 语气教学)，空行拼接、内容零改；
        //   守卫卡 = 反元 + 风格守卫 + 工具守卫（第 5 步合并成一条）；
        //   现在卡 = <time_context> 时间锚 + 【此刻】合并一条，钉物理最末位（刀1 席位）——旧布局时间锚后压
        //   1.1k–1.7k 字说明书稀释近因（13:39 答成清晨的事故根源之一）。
        // 半事实卡（日历失败/待见约定）、世界书后置桶、自定义模块不参与装订、各自独立成条（见面记忆 2026-07-11 起默认前置，仅手动挪后置才独立发射）。
        // 线下分支保持旧布局逐字节不变（逐模块独立成条 + 沉浸提示占最末位）。
        fun emitSuffix(entry: SuffixModuleEntry) {
            chatMessages.add(ChatMessageDto(role = ROLE_SYSTEM, content = entry.content))
            entry.module?.let { m ->
                segmentSink?.add(moduleSegment(m, entry.content, ContextSegment.POSITION_SUFFIX))
            }
        }
        /** 装订发射：多条目合一条消息，分段仍逐模块记（上下文日志统计口径不变）。 */
        fun emitCard(entries: List<SuffixModuleEntry>) {
            if (entries.isEmpty()) return
            chatMessages.add(
                ChatMessageDto(role = ROLE_SYSTEM, content = entries.joinToString("\n\n") { it.content }),
            )
            entries.forEach { e ->
                e.module?.let { m -> segmentSink?.add(moduleSegment(m, e.content, ContextSegment.POSITION_SUFFIX)) }
            }
        }
        // 末位现在卡语义修订（2026-07-11 用户拍板）：模块页排序对用户**完全自由**,「时间感知/此刻状态」
        // 只是**默认**排在后置区末尾——排序尊重用户;仅当二者仍处于后置区**末尾连续段**时(=默认序/未被挪走),
        // 才享受"钉在守卫卡之后收官"的近因席位;用户把它们挪进中间,就按用户的顺序原地发射、守卫卡收尾。
        val tailPinnedEntries = if (isCurrentlyInOfflineMode) emptyList() else splitTrailingNowCard(suffixEntries)
        val bodyEntries = suffixEntries.dropLast(tailPinnedEntries.size)
        if (isCurrentlyInOfflineMode) {
            bodyEntries.forEach(::emitSuffix)
        } else {
            // 规则卡装订：连续的规则类条目（五个规则系统模块 + module==null 的 MiniMax 教学）合并;
            // 遇到非规则条目（自定义模块，或被用户手动挪进后置的见面记忆）先落卡再单发，保持用户自定义顺序的相对语义。
            val ruleBuffer = mutableListOf<SuffixModuleEntry>()
            for (entry in bodyEntries) {
                val isRuleType = entry.module == null || entry.systemModuleType in RULE_CARD_TYPES
                if (isRuleType) {
                    ruleBuffer.add(entry)
                } else {
                    emitCard(ruleBuffer.toList()); ruleBuffer.clear(); emitSuffix(entry)
                }
            }
            emitCard(ruleBuffer)
        }

        // WB4 世界书后置桶（position 2/3 归并·近因注入，守卫段之前）。
        activeWorldInfo?.suffix?.takeIf { it.isNotBlank() }?.let {
            chatMessages.add(ChatMessageDto(role = ROLE_SYSTEM, content = resolveWorld(it)))
        }

        // 5. 模式末尾强约束（统一用 P1-1 兜底后的 isCurrentlyInOfflineMode，防脏 flag 下构造错乱预设）。
        // 非线下座次（第一招）：半事实卡（日历失败/待见约定）→ 守卫类（反元/风格/工具）→ 时间锚 → 【此刻】。
        if (isCurrentlyInOfflineMode) {
            // 4.5 反元层思考守卫（线下：位置保持旧序——世界书后置桶之后、沉浸 prompt 之前）
            chatMessages.add(
                ChatMessageDto(
                    role = ROLE_SYSTEM,
                    content = buildAntiMetaCognitiveGuard(character.name, userProfile?.nickname),
                ),
            )
            // 线下叙事预设（按用户档位解析）+ 导演指令轮换（分析块分布抽指令）+ 完整沉浸 prompt 注入末尾。
            // 用 filteredMessages（不含邀约卡）分析：目标块都在 plainText 叙事里，不受卡片剥离影响。
            val preset = resolveOfflinePreset(appSettings)
            val narrativeDirective = NarrativeDirectiveService.generateDirective(
                NarrativeDirectiveService.analyzeRecentBlockUsage(filteredMessages),
                preset,
            )
            // 入场标记 payload（地点 / 活动 / 心事种子）：从全量历史取当前 session 的标记——窗口截断后
            // filteredMessages 里可能已无标记（默认 30 轮 = 本场 120 条后即丢），末位说明书的地点必须每轮都在。
            val startPayload = currentOfflineSessionStartPayload(sortedMessages, conversation?.currentOfflineSessionId)
            val offlineUserName = promptMacros(character, userProfile, strings)[PromptMacros.USER]
                ?: strings.s(R.string.pb_user_fallback)
            val offlinePrompt = OfflineNarrativePreset.buildPrompt(
                currentTimeText = MemoryService.formatTimestamp(now.toEpochMilli()),
                userName = offlineUserName,
                meetingLocation = startPayload?.location,
                meetingActivity = startPayload?.activity,
                tensionSeed = startPayload?.tensionSeed?.takeIf { it.isNotEmpty() },
                perTurnDirective = narrativeDirective,
                preset = preset,
            )
            chatMessages.add(ChatMessageDto(role = ROLE_SYSTEM, content = offlinePrompt))
        } else {
            // ── 半事实卡（内容性提示，排守卫类之前）──
            // ② 执行失败回流（陪伴改良版·仅非线下）：有未消费的日历真失败 → 注入陪伴口吻【有件小事没办成】，让角色据实
            // 自然找补/再问（措辞经用户过审·见 buildCalendarFailureNudgePrompt）。调用方已 TTL + 一次性消费，此处只生成并注入。
            calendarFailure?.let { failure ->
                chatMessages.add(
                    ChatMessageDto(
                        role = ROLE_SYSTEM,
                        content = buildCalendarFailureNudgePrompt(userProfile?.nickname, failure.verb, failure.title, failure.reason),
                    ),
                )
            }
            // 等待期（Phase 9·§7：仅非线下）：有「已确认未来约定」时注入【待见约定】，让 AI 自然提起/期待，别生硬复述。
            // 段标题【待见约定】刻意避开 DirtyMessageDetector 保留的【见面 · 】【长期事实】【你今天完整的日程】。
            nextMeetingAppointment?.let { appt ->
                buildWaitingMeetingPrompt(appt, userProfile, now)?.let {
                    chatMessages.add(ChatMessageDto(role = ROLE_SYSTEM, content = it))
                }
            }
            // ── 守卫卡（刀2 装订）：反元 → 工具，簇内相对序沿用旧布局，空行拼接为一条。
            // 工具守卫提示词（①·C-5·遍历活跃工具盒子 [chatToolRegistry] 取各自 step5 守卫段·≡旧硬编码）：
            // - 线下见面（仅「角色可主动发起」时）：工具路→工具版规则、暗号路→[offline_invite|…] 降级指令（ReplyParser 剥标记·未建 M16 不破坏 UI）。
            // - 约定未来见面（仅暗号路）：附 [future_meeting]{...} 标记规则（preprocess 剥除·不泄露成气泡）；工具路工具定义已在
            //   tools 数组下发、无需 prompt。不受「可主动发起见面」开关约束——未来约定双方都可提。
            // 日历不在此（经其感知模块注入）。线下见面期间（非本 else 分支）整段不注入。遍历顺序=registry 顺序，与旧注入顺序一致。
            val toolGuardCtx = ChatToolContext(
                toolCallingEnabled = toolCallingEnabled,
                includeCalendarTool = appSettings.calendarIntegrationEnabled,
                canInitiateOffline = appSettings.characterCanInitiateOfflineMeeting,
                voiceCall = isCurrentlyInVoiceCall, // 通话回合无人解析暗号 → 约定暗号规则不注入（图纸 2026-09-06 §0.②-7）
            )
            val guardParts = mutableListOf(buildAntiMetaCognitiveGuard(character.name, userProfile?.nickname))
            for (tool in chatToolRegistry) {
                tool.stepFiveGuardPrompt(toolGuardCtx)?.let { guardParts.add(it) }
            }
            chatMessages.add(ChatMessageDto(role = ROLE_SYSTEM, content = guardParts.joinToString("\n\n")))
            // ── 现在卡（刀1 席位 + 刀2 合并）：时间锚（<time_context>）+ 【此刻】拼成一条、占物理最末位——
            // 模型生成前最后读到的永远是"现在几点/角色在干嘛"的易变事实。条目间保持模块序（timeAwareness 在前）。
            emitCard(tailPinnedEntries)
        }

        // 批 D 上下文日志：补对话历史段并把分段按 前置→历史→后置 就地重排（纯函数 finalizeSegments）。
        segmentSink?.let { finalizeSegments(it, chatMessages) }

        return chatMessages
    }

    /**
     * 等待期【待见约定】段（Phase 9·纯函数·可单测·`internal` 便于测）。仅 confirmed + 未来时返回；否则 null（防御过点/非确认）。
     * 段标题刻意避开 [DirtyMessageDetector] 保留的【见面 · 】【长期事实】【你今天完整的日程】。倒计时人话复用
     * [MeetingDisplayFormatter.countdownText]（今天 HH:mm / 明天 / N天后 / 绝对日期）。
     */
    internal fun buildWaitingMeetingPrompt(
        appt: MeetingAppointmentEntity,
        userProfile: UserProfileEntity?,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        if (MeetingStatus.fromRaw(appt.status) != MeetingStatus.CONFIRMED) return null
        val nowMillis = now.toEpochMilli()
        if (appt.scheduledAt <= nowMillis) return null // 已过点 → 交 Phase 10/11，不再「待见」
        val countdown = MeetingDisplayFormatter.countdownText(
            appt.scheduledAt, MeetingTimeGranularity.fromRaw(appt.timeGranularity), nowMillis, zone,
        )
        val userName = userProfile?.nickname?.takeIf { it.isNotEmpty() } ?: "对方"
        val what = listOfNotNull(
            appt.activity.takeIf { it.isNotBlank() },
            appt.location.takeIf { it.isNotBlank() }?.let { "在$it" },
        ).joinToString("").ifEmpty { "见面" }
        return "【待见约定】\n你和${userName}约好了「$countdown」$what。这是你们之间真实的约定，你心里记着、会自然期待。" +
            "聊到合适时可以自然提起或表达期待，但不要每次都生硬复述——像真人一样偶尔想起即可。"
    }

    /** 对话历史分段的显示名（批 D 上下文日志，1:1 iOS `String(localized: "对话历史")`，仅日志详情页展示）。 */
    private const val HISTORY_SEGMENT_NAME = "对话历史"

    /**
     * 收尾分段（批 D·纯函数·可单测）：前置模块段由 [buildSystemPromptWithSuffixes] 按构建序收进 [sink]，
     * 后置模块段由 [buildMessages] 在发射点收进（=物理序，第一招后 timeAwareness/currentMoment 段随实体钉尾），本函数
     * 据最终 [messages] 补一条对话历史段并把整表就地重排为 **前置 → 历史 → 后置**。对话历史段的字符/token 统计自
     * 非 system 消息正文拼接——与 iOS `collectContextSegments` 同口径：多模态消息 `content` 为 null 按空算
     * （1:1 [com.situ.aichat.diagnostics.LogContextFormat.plainText] 的取舍）；历史为空则不产历史段。
     */
    internal fun finalizeSegments(sink: MutableList<ContextSegment>, messages: List<ChatMessageDto>) {
        val historyText = messages
            .filter { it.role != ROLE_SYSTEM }
            .joinToString(separator = "") { it.content.orEmpty() }
        val prefix = sink.filter { it.position == ContextSegment.POSITION_PREFIX }
        val suffix = sink.filter { it.position == ContextSegment.POSITION_SUFFIX }
        sink.clear()
        sink.addAll(prefix)
        if (historyText.isNotEmpty()) {
            sink.add(
                ContextSegment(
                    name = HISTORY_SEGMENT_NAME,
                    systemModuleType = null,
                    charCount = historyText.length,
                    estimatedTokens = TokenEstimator.estimate(historyText),
                    position = ContextSegment.POSITION_HISTORY,
                ),
            )
        }
        sink.addAll(suffix)
    }

    /**
     * 构建消息数组**同时**返回各模块 + 对话历史的结构化分段（批 D 上下文日志「结构化展示」的数据源，1:1 iOS
     * `PromptBuilder.buildMessagesWithSegments`）。消息构建逻辑完全复用 [buildMessages]（不另写一份），分段由内部
     * [segmentSink] 顺带收集。**仅聊天管线**（[PromptScene] 四态）有意义——后台生成类任务不经模块系统、分段恒空。
     */
    fun buildMessagesWithSegments(
        character: CharacterEntity,
        conversation: ConversationEntity? = null,
        sortedMessages: List<MessageEntity>,
        userProfile: UserProfileEntity?,
        appSettings: AppSettings,
        strings: PromptStrings,
        structuredMemory: StructuredMemory = StructuredMemory.EMPTY,
        milestones: List<MilestoneEntity> = emptyList(),
        todaySchedule: CharacterDailyScheduleEntity? = null,
        todayScheduleEvents: List<ScheduleEventEntity> = emptyList(),
        recentDaysScheduleEvents: List<ScheduleEventEntity> = emptyList(),
        calendarUpcomingEvents: String? = null,
        momentChatContext: MomentChatContext? = null,
        economicState: CharacterEconomicChatState? = null,
        giftHistory: String? = null,
        customStickers: List<CustomStickerEntity> = emptyList(),
        disabledStickers: Set<String> = emptySet(),
        pet: CharacterPetEntity? = null,
        otherPets: List<OtherPetInfo> = emptyList(),
        petRecentPurchaseNames: List<String> = emptyList(),
        retrievedMemorySnippets: List<String> = emptyList(),
        offlineMeetingMemoryText: String = "",
        assistantDeliveryMode: AssistantDeliveryMode = AssistantDeliveryMode.TEXT,
        toolCallingEnabled: Boolean = false,
        miniMaxVoiceTagsCapability: MiniMaxVoiceTagsCapability? = null,
        scene: PromptScene = PromptScene.ONLINE_CHAT,
        extraMacros: Map<String, String> = emptyMap(),
        unsummarizedRoundsOutsideBaseWindow: Int = 0,
        audioInputEnabled: Boolean = false,
        audioAttachments: Map<String, String> = emptyMap(),
        visionEnabled: Boolean = false,
        imageAttachments: Map<String, String> = emptyMap(),
        worldInfo: WorldInfoActivationResult? = null,
        now: Instant = Instant.now(),
        /** 记忆改造二期·部件④ 见面时间线注记（图纸 §3.1）：透传给 [buildMessages]（见其同名参数）。 */
        meetingTimeline: List<OfflineMeetingMemoryEntity> = emptyList(),
        /** 引用一期：被引用消息预取对照表，透传给 [buildMessages]（见其同名参数）。 */
        quotedRefs: Map<String, com.situ.aichat.data.repository.QuotedMessageRef> = emptyMap(),
        /** 记忆改造二期·部件⑤ 场内前情提要（图纸 §3.2-D/E）：门控式透传给 [buildMessages]（见其同名参数）。 */
        inSceneRecap: String? = null,
        /** 「我们的日子」卷二：透传给 [buildMessages]（见其同名参数·语音路走本包装·W-10 只加此一参）。 */
        ourDays: List<com.situ.aichat.data.local.entity.OurDayEntity> = emptyList(),
    ): PromptBuildResult {
        val segments = mutableListOf<ContextSegment>()
        val messages = buildMessages(
            character = character,
            conversation = conversation,
            sortedMessages = sortedMessages,
            userProfile = userProfile,
            appSettings = appSettings,
            strings = strings,
            structuredMemory = structuredMemory,
            milestones = milestones,
            todaySchedule = todaySchedule,
            todayScheduleEvents = todayScheduleEvents,
            recentDaysScheduleEvents = recentDaysScheduleEvents,
            calendarUpcomingEvents = calendarUpcomingEvents,
            momentChatContext = momentChatContext,
            economicState = economicState,
            giftHistory = giftHistory,
            customStickers = customStickers,
            disabledStickers = disabledStickers,
            pet = pet,
            otherPets = otherPets,
            petRecentPurchaseNames = petRecentPurchaseNames,
            retrievedMemorySnippets = retrievedMemorySnippets,
            offlineMeetingMemoryText = offlineMeetingMemoryText,
            assistantDeliveryMode = assistantDeliveryMode,
            toolCallingEnabled = toolCallingEnabled,
            miniMaxVoiceTagsCapability = miniMaxVoiceTagsCapability,
            scene = scene,
            extraMacros = extraMacros,
            unsummarizedRoundsOutsideBaseWindow = unsummarizedRoundsOutsideBaseWindow,
            audioInputEnabled = audioInputEnabled,
            audioAttachments = audioAttachments,
            visionEnabled = visionEnabled,
            imageAttachments = imageAttachments,
            worldInfo = worldInfo,
            now = now,
            meetingTimeline = meetingTimeline,
            quotedRefs = quotedRefs,
            inSceneRecap = inSceneRecap,
            ourDays = ourDays,
            segmentSink = segments,
        )
        return PromptBuildResult(messages, segments)
    }

    // MARK: - 系统提示词构建（模块驱动）

    /**
     * "你可以在回复里自然地加 (laughs)/(sighs)/(breath)/<#0.5#> 标签" 的教学（1:1 iOS
     * `buildMiniMaxVoiceTagsHint`）。仅在 [MiniMaxVoiceTagsCapability.shouldInjectTagsHint] 为真时注入。
     * 文案走 [PromptStrings]（values/values-en），与 iOS `String(localized:)` 的中英行为一致；标签清单与
     * 暂停示例两行各 locale 相同，故同 iOS 硬编码。
     */
    internal fun buildMiniMaxVoiceTagsHint(strings: PromptStrings): String {
        val parts = listOf(
            strings.s(R.string.pb_voice_tags_header),
            strings.s(R.string.pb_voice_tags_intro),
            strings.s(R.string.pb_voice_tags_usage),
            "",
            strings.s(R.string.pb_voice_tags_vocal_label),
            "(laughs) (sighs) (breath) (gasps) (sniffs) (groans) (inhale) (exhale) (humming) (clear-throat)",
            "",
            strings.s(R.string.pb_voice_tags_pause_label),
            "<#0.3#>  <#0.5#>  <#1#>",
            "",
            strings.s(R.string.pb_voice_tags_rules_label),
            strings.s(R.string.pb_voice_tags_rule_tokens),
            strings.s(R.string.pb_voice_tags_rule_chinese),
            strings.s(R.string.pb_voice_tags_rule_start),
            strings.s(R.string.pb_voice_tags_rule_doubt),
            "",
            strings.s(R.string.pb_voice_tags_good_example),
            strings.s(R.string.pb_voice_tags_bad_example),
        )
        return parts.joinToString("\n")
    }

    // MARK: - 宏替换

    fun promptMacros(
        character: CharacterEntity,
        userProfile: UserProfileEntity?,
        strings: PromptStrings,
    ): Map<String, String> {
        val nickname = userProfile?.nickname?.trim().orEmpty()
        val resolvedUserName = nickname.ifEmpty { strings.s(R.string.pb_user_fallback) }
        return mapOf("{{char}}" to character.name, "{{user}}" to resolvedUserName)
    }

    fun applyPromptMacros(content: String, macros: Map<String, String>): String {
        var resolved = content
        for ((macro, value) in macros) resolved = resolved.replace(macro, value)
        return resolved
    }

    // MARK: - 模块内容派发

    /**
     * 把一个已生成内容的模块折成上下文日志分段（批 D）：[ContextSegment.name] 取模块显示名，[systemModuleType]
     * 取系统模块 rawValue（自定义模块=null），字符数/估算 token 据 [content] 现算。
     */
    internal fun moduleSegment(module: PromptModule, content: String, position: String): ContextSegment =
        ContextSegment(
            name = module.name,
            systemModuleType = module.systemModuleType?.rawValue,
            charCount = content.length,
            estimatedTokens = TokenEstimator.estimate(content),
            position = position,
        )

    internal fun buildModuleContent(module: PromptModule, ctx: BuildContext): String {
        val type = module.systemModuleType
        // 用户模板与默认模板共用同一份惰性 producer 目录（契约 D1/D2：用户内容也支持全部数据宏，而非仅 {{char}}/{{user}}）。
        // 对仅含 {{char}}/{{user}} 的既有自定义内容，输出与旧 applyPromptMacros 一致（惰性只命中出现的宏）。
        val producers = macroProducers(ctx)

        // 自定义模块
        if (type == null) {
            return if (module.content.isEmpty()) "" else PromptMacros.resolveLazy(module.content, producers)
        }

        // 线下见面：核心规则分版（图纸 v2 §3-B2）。ctx.scene 已是入口 moduleScene（单源二值）；
        // 用户线下自定义（offlineContent）优先，空则内置线下专版；主 content 只管普通聊天。
        if (type == SystemModuleType.CORE_RULES && ctx.scene == PromptScene.OFFLINE_MEETING) {
            val body = module.offlineContent.ifEmpty {
                buildOfflineCoreRulesContent(ctx.strings, PromptMacros.CHAR, PromptMacros.USER)
            }
            return PromptMacros.resolveLazy(body, producers)
        }

        // chatFormat 特殊：基础内容始终追加条数指令
        if (type == SystemModuleType.CHAT_FORMAT) {
            val base = if (module.content.isNotEmpty()) {
                PromptMacros.resolveLazy(module.content, producers)
            } else {
                buildChatFormatContent(ctx.strings)
            }
            return appendReplySegmentInstruction(base, ctx)
        }

        // 可编辑系统模块 + 用户内容非空 → 用用户内容
        if (type.isContentEditable && module.content.isNotEmpty()) {
            return PromptMacros.resolveLazy(module.content, producers)
        }

        // 默认（content 空）：数据类→整块宏；可编辑/纯用户→单源字面模板（名字位以 {{char}}/{{user}} 留位）。
        // 两者统一经 resolveLazy，与旧实现逐字节等价：数据类「单宏≡builder」；可编辑为纯签名收敛（body 未改）、
        // 名字宏解析后≡旧的直接传名字。chatFormat 已上游处理（含追加条数）。
        val template = defaultModuleTemplate(type)
            ?: defaultEditableTemplate(type, ctx.strings, ctx.appSettings.textingToneEnabled)
        return PromptMacros.resolveLazy(template ?: "", producers)
    }

    /**
     * 数据类系统模块的默认模板 = 单个整块宏（提示词模块编辑重设计 Phase 1 · 契约 §3 档 B/C）。
     *
     * 返回 null = 可编辑 / 纯用户模块（核心规则、回复风格、防重复、情绪表达、通用指令、忙碌回复、互动场景），
     * 其字面默认模板随 Phase 2 编辑屏抽取。chatFormat 不在此（有专属"追加条数"逻辑，上游已拦截）。
     *
     * 单一事实源：buildModuleContent 的数据类装配 + Phase 2/4 编辑屏预填 / 预览共用本映射。
     */
    internal fun defaultModuleTemplate(type: SystemModuleType): String? = when (type) {
        SystemModuleType.CHARACTER_IDENTITY -> PromptMacros.CHAR_PROFILE
        SystemModuleType.CHARACTER_GROWTH -> PromptMacros.CHAR_GROWTH          // M14 (P4.1)
        SystemModuleType.USER_PERSONA -> PromptMacros.USER_PERSONA
        SystemModuleType.CHARACTER_MEMORY -> PromptMacros.CHAR_MEMORY
        SystemModuleType.TIME_AWARENESS -> PromptMacros.TIME_CONTEXT
        SystemModuleType.CALENDAR_AWARENESS -> PromptMacros.USER_CALENDAR      // M12 (P5.3a)
        SystemModuleType.SCHEDULE_AWARENESS -> PromptMacros.SCHEDULE_TODAY     // M12 (P5.2)
        SystemModuleType.CURRENT_MOMENT -> PromptMacros.CURRENT_MOMENT         // M12 (P5.2)
        SystemModuleType.MOMENTS_CONTEXT -> PromptMacros.MOMENTS_CONTEXT       // M06 (P7.2.6)
        SystemModuleType.STICKER_LIBRARY -> PromptMacros.STICKER_LIBRARY       // M17 (P8.1)
        SystemModuleType.PET_STATUS -> PromptMacros.PET_STATUS                 // M11 (P8.2d)
        SystemModuleType.OFFLINE_MEETING_MEMORY -> PromptMacros.MEETING_MEMORY // M16
        SystemModuleType.OUR_DAYS -> PromptMacros.OUR_DAYS                     // 卷二
        SystemModuleType.GIFT_HISTORY -> PromptMacros.GIFT_HISTORY             // M09 (P9.2b)
        SystemModuleType.CHARACTER_ECONOMIC_STATE -> PromptMacros.ECONOMIC_STATE // M10 (P9.1a)
        SystemModuleType.CORE_RULES,
        SystemModuleType.SCENARIO,
        SystemModuleType.RESPONSE_STYLE,
        SystemModuleType.CHAT_FORMAT,
        SystemModuleType.QUALITY_CONTROL,
        SystemModuleType.MOOD_EXPRESSION,
        SystemModuleType.GENERAL_INSTRUCTIONS,
        SystemModuleType.BUSY_REPLY_INSTRUCTION -> null
    }

    /**
     * 可编辑 / 纯用户系统模块的默认模板 = 字面文案 + `{{char}}`/`{{user}}` 字面留位（提示词模块编辑重设计 P1c · 契约 §3 档 A）。
     *
     * 单一事实源：buildModuleContent 的默认装配（content 空时）+ Phase 2 编辑屏预填共用本函数。
     * 用 [strings] 现取本地化资源 → 暗合本地化（装配按 app 语言）；`resolveLazy` 把 `{{char}}`/`{{user}}` 换成真名
     * → 与"直接传真名给 builder"按构造等价（builder 为纯签名收敛，body 逐字未改）。
     *
     * 数据类返回 null（用 [defaultModuleTemplate] 的整块宏）。chatFormat 含专属追加逻辑，装配走特殊分支，
     * 此处仍给出其基础模板供 UI 预填。
     */
    internal fun defaultEditableTemplate(
        type: SystemModuleType,
        strings: PromptStrings,
        // 活人感一期 P1：仅 RESPONSE_STYLE 消费——运行时装配（[buildModuleContent]）传 appSettings.textingToneEnabled；
        // 提示词模块编辑器预填 / 单测走默认 false → 既有默认模板逐字节不变（守 §0「唯一 UI 触点」·additive）。
        textingTone: Boolean = false,
    ): String? = when (type) {
        SystemModuleType.CORE_RULES -> buildCoreRulesContent(strings, PromptMacros.CHAR, PromptMacros.USER)
        SystemModuleType.CHAT_FORMAT -> buildChatFormatContent(strings)
        SystemModuleType.RESPONSE_STYLE -> buildResponseStyleContent(strings, textingTone)
        SystemModuleType.QUALITY_CONTROL -> buildQualityControlContent(strings)
        SystemModuleType.MOOD_EXPRESSION -> buildMoodExpressionContent(strings)
        SystemModuleType.GENERAL_INSTRUCTIONS -> buildGeneralInstructionsContent(strings)
        SystemModuleType.BUSY_REPLY_INSTRUCTION -> busyReplyTemplate()
        SystemModuleType.SCENARIO -> "" // 纯用户自定义，默认空
        else -> null // 数据类用 defaultModuleTemplate 的整块宏
    }

    /**
     * 全量宏 producer 目录（提示词模块编辑重设计 Phase 1 · 契约 §2）。值是 thunk，由 [PromptMacros.resolveLazy]
     * 惰性调用（模板含该宏才求值、每宏至多一次），避免向量检索 / DB 查询为无关模块空跑（契约 D8）。
     *
     * 数据宏直接挂现有 buildXxx / 字段（与旧 when 派发逐项一致，逻辑零改）；供模块默认模板 + 用户模板 + 预览复用。
     */
    private fun macroProducers(ctx: BuildContext): Map<String, () -> String> = buildMap {
        put(PromptMacros.CHAR) { ctx.resolvedCharacterName }
        put(PromptMacros.USER) { ctx.resolvedUserName }
        put(PromptMacros.NOW) { DateFormatters.yearMonthDayHourMinute(ctx.now.toEpochMilli()) }
        put(PromptMacros.USER_CITY) { ctx.userProfile?.cityName.orEmpty() }
        put(PromptMacros.USER_WEATHER) { "" } // 安卓天气未实现（weather-geo-userkey 待排期）
        put(PromptMacros.CHAR_PROFILE) { buildCharacterIdentityContent(ctx) }
        put(PromptMacros.CHAR_GROWTH) { buildCharacterGrowthContent(ctx) }
        put(PromptMacros.USER_PERSONA) { buildUserPersonaContent(ctx) }
        put(PromptMacros.CHAR_MEMORY) { buildCharacterMemoryContent(ctx) }
        put(PromptMacros.MEMORY_CONTENT) { ctx.character.memorySummary }
        put(PromptMacros.MEETING_MEMORY) { buildOfflineMeetingMemoryContent(ctx) } // 相框包装(§3.2·空→空,唯一收口)
        put(PromptMacros.OUR_DAYS) { com.situ.aichat.prompt.ourdays.buildOurDaysContent(ctx) } // 卷二(日期指名+那年今日·空→空)
        put(PromptMacros.TIME_CONTEXT) { buildTimeAwarenessContent(ctx) }
        put(PromptMacros.SCHEDULE_TODAY) { buildScheduleModule(ctx) }
        put(PromptMacros.CURRENT_MOMENT) { buildCurrentMomentModule(ctx) }
        put(PromptMacros.USER_CALENDAR) { buildCalendarAwarenessContent(ctx) }
        put(PromptMacros.MOMENTS_CONTEXT) { buildMomentsContextContent(ctx) }
        put(PromptMacros.STICKER_LIBRARY) { buildStickerLibraryContent(ctx) }
        put(PromptMacros.PET_STATUS) { buildPetContent(ctx) }
        put(PromptMacros.GIFT_HISTORY) { ctx.giftHistory ?: "" }
        put(PromptMacros.ECONOMIC_STATE) { buildCharacterEconomicStateContent(ctx) }
        put(PromptMacros.MOOD_FORMAT) { buildMoodExpressionContent(ctx.strings) }
        put(PromptMacros.REPLY_SEGMENTS) { replySegmentInstruction(ctx) }
        // 场景宏（忙碌回复等）真实值：由调用方经 extraMacros 传入
        ctx.extraMacros.forEach { (k, v) -> put(k) { v } }
    }

    // MARK: - 线下模式判定 / 预设解析

    /**
     * P1-1 脏状态兜底：判断对话是否处于「健康的线下模式」（1:1 iOS `isOfflineModeHealthy`）。
     * 必须同时满足 ① isInOfflineMode==true ② currentOfflineSessionId 去空白后非空。任一脏 → 按非线下处理，
     * 避免基于半状态构造错误线下 prompt。纯判定，不做 DB 修复（那是 OfflineStateGuard 的职责，10.2c）。
     */
    private fun isOfflineModeHealthy(conversation: ConversationEntity?): Boolean {
        if (conversation == null || !conversation.isInOfflineMode) return false
        return !conversation.currentOfflineSessionId.isNullOrBlank()
    }

    /**
     * 入场标记是否在 prompt 历史里保留（1:1 iOS filteredMessages 的 `.offlineMarkerStart` 分支）：
     * 仅当前在线下模式 + currentOfflineSessionId 与消息 offlineSessionId 均非空且相等才保留（提场景种子/上下文）；
     * 其他 session 的标记一律剥离，避免误匹配。
     */
    internal fun shouldKeepOfflineMarkerStart(
        isCurrentlyInOfflineMode: Boolean,
        currentOfflineSessionId: String?,
        messageOfflineSessionId: String?,
    ): Boolean =
        isCurrentlyInOfflineMode &&
            !currentOfflineSessionId.isNullOrEmpty() &&
            !messageOfflineSessionId.isNullOrEmpty() &&
            messageOfflineSessionId == currentOfflineSessionId

    /** 按 AppSettings 解析线下叙事预设（1:1 iOS `resolveOfflinePreset`；未知 raw 由 fromRaw 回退 plain）。 */
    private fun resolveOfflinePreset(appSettings: AppSettings): OfflineNarrativePreset =
        OfflineNarrativePreset.resolve(
            OfflineNarrativePreset.DetailLevel.fromRaw(appSettings.offlineNarrativeDetailRaw),
            appSettings.offlineCustomStylePrompt,
            appSettings.offlineCustomDirectivePrompt,
            appSettings.offlineCustomEmotionPrompt,
        )

}

/** [MessageEntity] 的消息类型解析 shorthand（提至顶层 internal·便于同包协作者[如 PromptBuilderWindow]复用·body 逐字不变）。 */
internal fun MessageEntity.kind(): MessageKind = MessageKind.fromRaw(messageKindRaw)
