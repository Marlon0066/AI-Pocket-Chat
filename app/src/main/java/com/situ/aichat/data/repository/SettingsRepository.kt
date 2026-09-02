package com.situ.aichat.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.AutoBackupConfig
import com.situ.aichat.pet.EggNestPact
import com.situ.aichat.prompt.PromptModuleService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the parts of [AppSettings] the prompt-module editor manages — the three module JSON
 * blobs (global / per-character override / custom presets) — in DataStore, and rebuilds an
 * [AppSettings] for the prompt pipeline. Other AppSettings fields keep their iOS defaults until a
 * full settings UI lands (P12); this keeps the chat prompt iOS-faithful while letting P2.5 edits
 * actually reach [com.situ.aichat.prompt.PromptBuilder] via [com.situ.aichat.prompt.PromptModuleService.effectiveModules].
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val appSettings: Flow<AppSettings> = dataStore.data.map { p ->
        AppSettings(
            promptModulesJSON = p[KEY_PROMPT_MODULES] ?: "",
            characterPromptModulesJSON = p[KEY_CHARACTER_PROMPT_MODULES] ?: "",
            promptModulePresetsJSON = p[KEY_PROMPT_MODULE_PRESETS] ?: "",
            // 记忆参数（P12.1 设置页）——被 ChatViewModel/BusyReplyService/RecoveryReplyGenerator 读取，故显式映射。
            shortTermMemoryLength = p[KEY_SHORT_TERM_MEMORY] ?: 20,
            autoSummarizeInterval = p[KEY_AUTO_SUMMARIZE_INTERVAL] ?: 10,
            memorySummaryMaxLength = p[KEY_MEMORY_SUMMARY_MAX] ?: AppSettings.DEFAULT_MEMORY_SUMMARY_MAX_LENGTH,
            // 智能渐进压缩开关（2026-06-20）——被 MemorySummaryCoordinator/MemoryService 提取话术读取；默认 false(关)。
            progressiveCompressionEnabled = p[KEY_PROGRESSIVE_COMPRESSION] ?: false,
            structuredMemoryInterval = p[KEY_STRUCTURED_MEMORY_INTERVAL] ?: 30,
            vectorSearchThreshold = p[KEY_VECTOR_SEARCH_THRESHOLD] ?: 65,
            // 世界书触发设置（WB7c）——AssistantTurnEngine 每回合现读现用（热更新 §12.11-3）。
            worldInfoScanDepth = p[KEY_WI_SCAN_DEPTH] ?: 2,
            worldInfoBudgetChars = p[KEY_WI_BUDGET_CHARS] ?: 6000,
            worldInfoRecursiveScan = p[KEY_WI_RECURSIVE] ?: false,
            worldInfoMaxRecursionSteps = p[KEY_WI_MAX_RECURSION] ?: 0,
            worldInfoInsertionStrategy = p[KEY_WI_STRATEGY] ?: "CHARACTER_FIRST",
            worldInfoCaseSensitive = p[KEY_WI_CASE_SENSITIVE] ?: false,
            worldInfoMatchWholeWords = p[KEY_WI_WHOLE_WORDS] ?: false,
            // 世界系统设置（W1）——逻辑消费在 W2+；缺值 → 默认 standard / 开 / 关 / gentle。
            worldVividnessTier = p[KEY_WORLD_VIVIDNESS_TIER] ?: AppSettings.WORLD_VIVIDNESS_STANDARD,
            worldRelationshipsEnabled = p[KEY_WORLD_RELATIONSHIPS_ENABLED] ?: true,
            worldRomanceEnabled = p[KEY_WORLD_ROMANCE_ENABLED] ?: false,
            worldNotificationTier = p[KEY_WORLD_NOTIFICATION_TIER] ?: AppSettings.WORLD_NOTIFICATION_GENTLE,
            worldOnboardingDone = p[KEY_WORLD_ONBOARDING_DONE] ?: false,
            // 家的蛋巢之约（W12.5）——EggNestService 派生巢态读；空 uuid = 无之约。
            eggNestPactCharacterUuid = p[KEY_EGG_NEST_PACT_UUID] ?: "",
            eggNestPactAt = p[KEY_EGG_NEST_PACT_AT] ?: 0L,
            // 上下文日志（批 D）——记录器读保留条数轮转、读 detail 开关决定是否存正文；设置页（D-3）写。
            logRetentionCount = p[KEY_LOG_RETENTION_COUNT] ?: AppSettings.DEFAULT_LOG_RETENTION_COUNT,
            logDetailEnabled = p[KEY_LOG_DETAIL_ENABLED] ?: false,
            perfCollectEnabled = p[KEY_PERF_COLLECT_ENABLED] ?: false,
            // 记忆提取/注入提示词自定义（14.5b）——被 ChatViewModel/VoiceCallPostReplyRounds（提取）与 PromptBuilder
            // （注入）消费，空=默认模板。设置 UI 接入后显式持久化。
            memoryExtractionPrompt = p[KEY_MEMORY_EXTRACTION_PROMPT] ?: "",
            memoryInjectionPrompt = p[KEY_MEMORY_INJECTION_PROMPT] ?: "",
            // 回复规则（14.3a）——被 ChatViewModel 读 sanitized*Range 消费，设置 UI 接入后显式持久化。
            replySegmentMin = p[KEY_REPLY_SEGMENT_MIN] ?: AppSettings.DEFAULT_REPLY_SEGMENT_MIN,
            replySegmentMax = p[KEY_REPLY_SEGMENT_MAX] ?: AppSettings.DEFAULT_REPLY_SEGMENT_MAX,
            voiceReplyRoundMin = p[KEY_VOICE_REPLY_ROUND_MIN] ?: AppSettings.DEFAULT_VOICE_REPLY_ROUND_MIN,
            voiceReplyRoundMax = p[KEY_VOICE_REPLY_ROUND_MAX] ?: AppSettings.DEFAULT_VOICE_REPLY_ROUND_MAX,
            // 创造力（温度，14.3b）——被 ChatViewModel / VoiceCallTurnService 读 sanitizedLlmTemperature 接进 LLM 请求。
            llmTemperature = p[KEY_LLM_TEMPERATURE] ?: AppSettings.DEFAULT_LLM_TEMPERATURE,
            // 故事正章创作温度（卷一 V1）——被 StoryGenerationService 读 sanitizedStoryCreationTemperature 接进创作/续写请求。
            storyCreationTemperature = p[KEY_STORY_CREATION_TEMPERATURE] ?: AppSettings.DEFAULT_STORY_CREATION_TEMPERATURE,
            storyBannedExpressions = p[KEY_STORY_BANNED_EXPRESSIONS], // 无键即 null = 「从未设置」（三态语义，勿加 ?: 兜底）
            // 全局场面节拍 / 口味画像（故事二期卷一）——三态同忌口，无键即 null，勿加 ?: 兜底。
            storySceneBeats = p[KEY_STORY_SCENE_BEATS],
            storyTasteProfile = p[KEY_STORY_TASTE_PROFILE],
            // 子系统总开关（P12.1b 系统开关屏）——被各服务/PromptBuilder/ChatViewModel 读取以 gate 整个子系统。
            // 默认全 true（= 之前恒默认，向后兼容）。scheduleSystemEnabled 已在下方映射（沉浸快照也写它）。
            growthSystemEnabled = p[KEY_GROWTH_SYSTEM] ?: true,
            // 成长分析参数（P12.1d 成长设置屏）——被 ChatViewModel/VoiceCallPostReplyRounds（分析触发轮）与
            // GrowthAnalysisCoordinator（日志上限/兴趣冷却）读取；过去仅有默认值，设置 UI 接入后显式持久化。
            growthAnalysisInterval = p[KEY_GROWTH_ANALYSIS_INTERVAL] ?: 30,
            growthLogMaxCount = p[KEY_GROWTH_LOG_MAX_COUNT] ?: 100,
            // 情绪记录数量（成长设置屏·moodHistory 复活后接入）——被聊天/未答恢复的情绪历史归档路径读为 takeLast 截断上限。
            moodHistoryMaxCount = p[KEY_MOOD_HISTORY_MAX_COUNT] ?: 200,
            interestCooldownDays = p[KEY_INTEREST_COOLDOWN_DAYS] ?: 14,
            currencySystemEnabled = p[KEY_CURRENCY_SYSTEM] ?: true,
            petSystemEnabled = p[KEY_PET_SYSTEM] ?: true,
            characterProactiveGiftEnabled = p[KEY_PROACTIVE_GIFT] ?: true,
            relationshipAutoAdvanceEnabled = p[KEY_RELATIONSHIP_AUTO_ADVANCE] ?: true,
            // 消息情绪动画（P1-5）——ChatScreen/OfflineModeView 消费；缺值 → 默认开（=iOS 默认 true）。
            emotionAnimationEnabled = p[KEY_EMOTION_ANIMATION] ?: true,
            // 自然短句口吻（活人感一期 P1）——PromptBuilder 回复风格块消费；缺值 → 默认开。
            textingToneEnabled = p[KEY_TEXTING_TONE] ?: true,
            // 主动消息通知（P6.1c）——这两项需真正持久化并被调度器读取，故在此显式映射。
            notificationsEnabled = p[KEY_NOTIFICATIONS_ENABLED] ?: true,
            // 角色经济动态通知三档（P1-40）——维护循环读取；缺值 → 默认简要 brief。
            economyNotificationTier = p[KEY_ECONOMY_NOTIFICATION_TIER] ?: "brief",
            // 关系里程碑庆祝通知（P1-33）——缺值 → 默认开。
            milestoneNotificationEnabled = p[KEY_MILESTONE_NOTIF_ENABLED] ?: true,
            // 主动消息夜间免打扰——排程侧与到点侧共用；缺值（含旧版升级用户）→ 默认开 23:00–07:30。
            quietHoursEnabled = p[KEY_NOTIF_QUIET_HOURS_ENABLED] ?: true,
            quietHoursStartMinute = p[KEY_NOTIF_QUIET_HOURS_START] ?: 1380,
            quietHoursEndMinute = p[KEY_NOTIF_QUIET_HOURS_END] ?: 450,
            // 忙碌时延迟回复（P6.2）——需被 BusyReplyService / ChatViewModel 读取，故显式映射。
            busyModeEnabled = p[KEY_BUSY_MODE_ENABLED] ?: false,
            busyModeMaxMinutes = p[KEY_BUSY_MODE_MAX_MINUTES] ?: 30,
            // 日历提醒方式（P6.3）——需被 CalendarWriter（系统提醒门控）/ CalendarNotificationScheduler 读取。
            calendarReminderMode = p[KEY_CALENDAR_REMINDER_MODE] ?: "both",
            // 日记自动生成（P7.1.2）——需被 DiaryGenerationCoordinator 读取；设置 UI 写入在 7.1.5。
            diaryAutoGenerateEnabled = p[KEY_DIARY_AUTO_GENERATE] ?: false,
            diaryAutoGenerateTime = p[KEY_DIARY_AUTO_GENERATE_TIME] ?: "21:00",
            diaryAutoPublishEnabled = p[KEY_DIARY_AUTO_PUBLISH] ?: false,
            diaryExchangePartnerUuid = p[KEY_DIARY_EXCHANGE_PARTNER] ?: "",
            // 日记角色评论（P7.1.3）——需被 DiaryCommentService 读取；设置 UI 写入在 7.1.5。
            diaryCharacterInteractionEnabled = p[KEY_DIARY_CHAR_INTERACTION] ?: true,
            diaryInteractingCharacterUUIDs = p[KEY_DIARY_INTERACTING_CHARS] ?: "",
            diaryCommentDelay = p[KEY_DIARY_COMMENT_DELAY] ?: 5,
            lastViewedDiaryDate = p[KEY_LAST_VIEWED_DIARY_DATE] ?: 0L,
            // 朋友圈设置（P7.2.8）——被 MomentGenerationService/MomentInteractionService 读取；设置 UI 在 7.2.8。
            momentAutoPostFrequency = p[KEY_MOMENT_AUTO_POST_FREQ] ?: 2,
            momentAutoCommentFrequency = p[KEY_MOMENT_AUTO_COMMENT_FREQ] ?: 2,
            momentAutoLikeEnabled = p[KEY_MOMENT_AUTO_LIKE] ?: true,
            momentCommentDelay = p[KEY_MOMENT_COMMENT_DELAY] ?: 3,
            momentNewPostNotificationEnabled = p[KEY_MOMENT_NEW_POST_NOTIF] ?: true,
            // 表情包（P8.1）——被 PromptBuilder/ChatViewModel/BusyReplyService 读取；设置 UI 在 8.1c 管理页。
            // 2026-07-11 拍板默认关(与 AppSettings 默认同步):未落过盘的用户读到 false。
            characterCanSendStickersEnabled = p[KEY_CHARACTER_CAN_SEND_STICKERS] ?: false,
            // 宠物日记自动生成（P8.2）——被 PetDiaryGenerationService 读取；设置 UI → P12（默认关，对齐 iOS）。
            petDiaryAutoGenerateEnabled = p[KEY_PET_DIARY_AUTO_GENERATE] ?: false,
            // 语音通话打断灵敏度（P10.1h-3）——被 VoiceCallController 读取（sanitize 后作能量打断阈值）。
            voiceCallInterruptThreshold =
                p[KEY_VOICE_CALL_INTERRUPT_THRESHOLD] ?: AppSettings.DEFAULT_VOICE_CALL_INTERRUPT_THRESHOLD,
            calendarIntegrationEnabled = p[KEY_CALENDAR_INTEGRATION] ?: true,
            // 日历操作确认（P12.1c）——被 ChatViewModel 读取以决定 AI 写日历前是否弹确认卡片；过去仅有默认值
            // （恒 true），设置 UI 接入后显式持久化。默认 true 对齐 iOS。
            calendarActionConfirmation = p[KEY_CALENDAR_ACTION_CONFIRMATION] ?: true,
            scheduleSystemEnabled = p[KEY_SCHEDULE_SYSTEM] ?: true,
            // 角色跨日程互动频率（「角色之间互相来往」设置·0=关 1=偶尔 2=经常 3=频繁）——ScheduleCoordinator/
            // ScheduleGenerationService 读，仅影响下次生成日程。默认 1 对齐 AppSettings.crossCharacterLevel。
            crossCharacterLevel = p[KEY_CROSS_CHARACTER_LEVEL] ?: 1,
            // 线下模式偏好（10.2f；线下设置页写、ChatScreen/沉浸 UI/PromptBuilder.resolveOfflinePreset 读）。
            characterCanInitiateOfflineMeeting = p[KEY_CHARACTER_CAN_INITIATE_MEETING] ?: true,
            offlineImmersiveInputEnabled = p[KEY_OFFLINE_IMMERSIVE_INPUT] ?: false,
            offlineBackgroundStyleRaw = p[KEY_OFFLINE_BG_STYLE] ?: "particle",
            offlineParticleStyleRaw = p[KEY_OFFLINE_PARTICLE_STYLE] ?: "stars",
            offlineBackgroundColor = p[KEY_OFFLINE_BG_COLOR] ?: "",
            offlineNarrativeDetailRaw = p[KEY_OFFLINE_NARRATIVE_DETAIL] ?: "plain",
            offlineCustomStylePrompt = p[KEY_OFFLINE_CUSTOM_STYLE] ?: "",
            offlineCustomDirectivePrompt = p[KEY_OFFLINE_CUSTOM_DIRECTIVE] ?: "",
            offlineCustomEmotionPrompt = p[KEY_OFFLINE_CUSTOM_EMOTION] ?: "",
            meetingMemoryMaxLength = p[KEY_MEETING_MEMORY_MAX_LENGTH] ?: 1200,
            meetingMemoryInjectCount = p[KEY_MEETING_MEMORY_INJECT_COUNT] ?: 3,
            offlineAfterglowEnabled = p[KEY_OFFLINE_AFTERGLOW] ?: true,
            // 内容过滤规则（14.3c）——被 ChatViewModel/BusyReplyService 经 ContentFilterService 读取净化 AI 正文；
            // 空 = 首次使用（设置页 VM 写默认预设），读侧 loadRules 空→返回默认（不持久化）等价。
            contentFilterRulesJSON = p[KEY_CONTENT_FILTER_RULES] ?: "",
        )
    }

    suspend fun getAppSettings(): AppSettings = appSettings.first()

    // ── 定时自动备份配置（13.6c；设备本地，不进 AppSettings/备份文件） ──

    val autoBackupConfig: Flow<AutoBackupConfig> = dataStore.data.map { p ->
        AutoBackupConfig(
            enabled = p[KEY_AUTO_BACKUP_ENABLED] ?: false,
            treeUri = p[KEY_AUTO_BACKUP_TREE_URI] ?: "",
            lastBackupAt = p[KEY_AUTO_BACKUP_LAST_AT] ?: 0L,
            lastMediaBackupAt = p[KEY_AUTO_BACKUP_LAST_MEDIA_AT] ?: 0L,
        )
    }

    suspend fun getAutoBackupConfig(): AutoBackupConfig = autoBackupConfig.first()

    suspend fun setAutoBackupEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_AUTO_BACKUP_ENABLED] = enabled }

    suspend fun setAutoBackupTreeUri(treeUri: String) =
        dataStore.edit { it[KEY_AUTO_BACKUP_TREE_URI] = treeUri }

    /** 记一次完成：更新上次备份时间；[mediaAt] 非 null（本次含媒体）时同时更新上次含媒体备份时间。 */
    suspend fun setAutoBackupLastRun(at: Long, mediaAt: Long?) =
        dataStore.edit { p ->
            p[KEY_AUTO_BACKUP_LAST_AT] = at
            if (mediaAt != null) p[KEY_AUTO_BACKUP_LAST_MEDIA_AT] = mediaAt
        }

    /**
     * 备份恢复（13.6b-2）：把整份 [AppSettings] 写回 DataStore（一次原子 `edit`）——是上面 [appSettings] 读映射的
     * 精确逆。含三块提示词模块 JSON（promptModules/characterPromptModules/presets）= 整体覆盖式恢复，故新 zip
     * 导入走此路即可，无需再单独 merge 每角色模块覆盖。仅写读映射覆盖到的键（[AppSettings] 未持久化的字段如
     * pet 平衡值等本就恒默认，不写；回复规则 replySegment/voiceReplyRound 自 14.3a 起持久化，故纳入往返）；
     * KEY_NOTIF_DISABLED_CHARS 不属 AppSettings、不动。
     */
    suspend fun applyBackupSettings(s: AppSettings) {
        dataStore.edit { p ->
            // 恢复旧备份会带回旧顺序（时间/此刻非末尾、见面记忆在后置）且迁移 flag 已置,故随恢复链式补迁(两者幂等·各 null=原样)。
            val timeMigrated =
                PromptModuleService.migratePromptModuleTimeOrder(s.promptModulesJSON, s.characterPromptModulesJSON)
            val gJson = timeMigrated?.first ?: s.promptModulesJSON
            val cJson = timeMigrated?.second ?: s.characterPromptModulesJSON
            val mmMigrated = PromptModuleService.migratePromptModuleMeetingMemory(gJson, cJson)
            // 短信腔四件线下退场（2026-07-12）：老备份四模块 enabledScenes=null → 现场补迁（链迁不置 once flag,与前两节一致·幂等）。
            val gJson2 = mmMigrated?.first ?: gJson
            val cJson2 = mmMigrated?.second ?: cJson
            val sceneMigrated = PromptModuleService.migratePromptModuleSceneDefaults(gJson2, cJson2)
            // 「我们的日子」卷二（2026-09-02）：老备份无 ourDays 模块 → 链尾补迁到见面记忆正后（不置 once flag·幂等）。
            val gJson3 = sceneMigrated?.first ?: gJson2
            val cJson3 = sceneMigrated?.second ?: cJson2
            val ourDaysMigrated = PromptModuleService.migratePromptModuleOurDays(gJson3, cJson3)
            p[KEY_PROMPT_MODULES] = ourDaysMigrated?.first ?: gJson3
            p[KEY_CHARACTER_PROMPT_MODULES] = ourDaysMigrated?.second ?: cJson3
            p[KEY_PROMPT_MODULE_PRESETS] = s.promptModulePresetsJSON
            p[KEY_SHORT_TERM_MEMORY] = s.shortTermMemoryLength
            p[KEY_AUTO_SUMMARIZE_INTERVAL] = s.autoSummarizeInterval
            p[KEY_MEMORY_SUMMARY_MAX] = s.memorySummaryMaxLength
            p[KEY_PROGRESSIVE_COMPRESSION] = s.progressiveCompressionEnabled
            p[KEY_STRUCTURED_MEMORY_INTERVAL] = s.structuredMemoryInterval
            p[KEY_VECTOR_SEARCH_THRESHOLD] = s.vectorSearchThreshold
            p[KEY_WI_SCAN_DEPTH] = s.worldInfoScanDepth
            p[KEY_WI_BUDGET_CHARS] = s.worldInfoBudgetChars
            p[KEY_WI_RECURSIVE] = s.worldInfoRecursiveScan
            p[KEY_WI_MAX_RECURSION] = s.worldInfoMaxRecursionSteps
            p[KEY_WI_STRATEGY] = s.worldInfoInsertionStrategy
            p[KEY_WI_CASE_SENSITIVE] = s.worldInfoCaseSensitive
            p[KEY_WI_WHOLE_WORDS] = s.worldInfoMatchWholeWords
            // 世界系统设置随备份往返（保持 applyBackupSettings 为读映射的精确逆·防恢复后世界设置丢失）。
            p[KEY_WORLD_VIVIDNESS_TIER] = s.worldVividnessTier
            p[KEY_WORLD_RELATIONSHIPS_ENABLED] = s.worldRelationshipsEnabled
            p[KEY_WORLD_ROMANCE_ENABLED] = s.worldRomanceEnabled
            p[KEY_WORLD_NOTIFICATION_TIER] = s.worldNotificationTier
            p[KEY_WORLD_ONBOARDING_DONE] = s.worldOnboardingDone
            // 家的蛋巢之约随备份往返（W12.5·保持 applyBackupSettings 为读映射的精确逆·E8）。
            p[KEY_EGG_NEST_PACT_UUID] = s.eggNestPactCharacterUuid
            p[KEY_EGG_NEST_PACT_AT] = s.eggNestPactAt
            p[KEY_MEMORY_EXTRACTION_PROMPT] = s.memoryExtractionPrompt
            p[KEY_MEMORY_INJECTION_PROMPT] = s.memoryInjectionPrompt
            p[KEY_REPLY_SEGMENT_MIN] = s.replySegmentMin
            p[KEY_REPLY_SEGMENT_MAX] = s.replySegmentMax
            p[KEY_VOICE_REPLY_ROUND_MIN] = s.voiceReplyRoundMin
            p[KEY_VOICE_REPLY_ROUND_MAX] = s.voiceReplyRoundMax
            p[KEY_LLM_TEMPERATURE] = s.llmTemperature
            p[KEY_STORY_CREATION_TEMPERATURE] = s.storyCreationTemperature
            s.storyBannedExpressions?.let { p[KEY_STORY_BANNED_EXPRESSIONS] = it } ?: p.remove(KEY_STORY_BANNED_EXPRESSIONS) // null=从未设置→移键，保三态
            s.storySceneBeats?.let { p[KEY_STORY_SCENE_BEATS] = it } ?: p.remove(KEY_STORY_SCENE_BEATS) // 同上：保三态
            s.storyTasteProfile?.let { p[KEY_STORY_TASTE_PROFILE] = it } ?: p.remove(KEY_STORY_TASTE_PROFILE) // 同上：保三态
            p[KEY_GROWTH_SYSTEM] = s.growthSystemEnabled
            p[KEY_GROWTH_ANALYSIS_INTERVAL] = s.growthAnalysisInterval
            p[KEY_GROWTH_LOG_MAX_COUNT] = s.growthLogMaxCount
            p[KEY_MOOD_HISTORY_MAX_COUNT] = s.moodHistoryMaxCount
            p[KEY_INTEREST_COOLDOWN_DAYS] = s.interestCooldownDays
            p[KEY_CURRENCY_SYSTEM] = s.currencySystemEnabled
            p[KEY_PET_SYSTEM] = s.petSystemEnabled
            p[KEY_PROACTIVE_GIFT] = s.characterProactiveGiftEnabled
            p[KEY_RELATIONSHIP_AUTO_ADVANCE] = s.relationshipAutoAdvanceEnabled
            p[KEY_EMOTION_ANIMATION] = s.emotionAnimationEnabled
            p[KEY_TEXTING_TONE] = s.textingToneEnabled
            p[KEY_NOTIFICATIONS_ENABLED] = s.notificationsEnabled
            p[KEY_ECONOMY_NOTIFICATION_TIER] = s.economyNotificationTier
            p[KEY_MILESTONE_NOTIF_ENABLED] = s.milestoneNotificationEnabled
            p[KEY_NOTIF_QUIET_HOURS_ENABLED] = s.quietHoursEnabled
            p[KEY_NOTIF_QUIET_HOURS_START] = s.quietHoursStartMinute
            p[KEY_NOTIF_QUIET_HOURS_END] = s.quietHoursEndMinute
            p[KEY_BUSY_MODE_ENABLED] = s.busyModeEnabled
            p[KEY_BUSY_MODE_MAX_MINUTES] = s.busyModeMaxMinutes
            p[KEY_CALENDAR_REMINDER_MODE] = s.calendarReminderMode
            p[KEY_DIARY_AUTO_GENERATE] = s.diaryAutoGenerateEnabled
            p[KEY_DIARY_AUTO_GENERATE_TIME] = s.diaryAutoGenerateTime
            p[KEY_DIARY_AUTO_PUBLISH] = s.diaryAutoPublishEnabled
            p[KEY_DIARY_EXCHANGE_PARTNER] = s.diaryExchangePartnerUuid
            p[KEY_DIARY_CHAR_INTERACTION] = s.diaryCharacterInteractionEnabled
            p[KEY_DIARY_INTERACTING_CHARS] = s.diaryInteractingCharacterUUIDs
            p[KEY_DIARY_COMMENT_DELAY] = s.diaryCommentDelay
            p[KEY_LAST_VIEWED_DIARY_DATE] = s.lastViewedDiaryDate
            p[KEY_MOMENT_AUTO_POST_FREQ] = s.momentAutoPostFrequency
            p[KEY_MOMENT_AUTO_COMMENT_FREQ] = s.momentAutoCommentFrequency
            p[KEY_MOMENT_AUTO_LIKE] = s.momentAutoLikeEnabled
            p[KEY_MOMENT_COMMENT_DELAY] = s.momentCommentDelay
            p[KEY_MOMENT_NEW_POST_NOTIF] = s.momentNewPostNotificationEnabled
            p[KEY_CHARACTER_CAN_SEND_STICKERS] = s.characterCanSendStickersEnabled
            p[KEY_PET_DIARY_AUTO_GENERATE] = s.petDiaryAutoGenerateEnabled
            p[KEY_VOICE_CALL_INTERRUPT_THRESHOLD] = s.voiceCallInterruptThreshold
            p[KEY_CALENDAR_INTEGRATION] = s.calendarIntegrationEnabled
            p[KEY_CALENDAR_ACTION_CONFIRMATION] = s.calendarActionConfirmation
            p[KEY_SCHEDULE_SYSTEM] = s.scheduleSystemEnabled
            p[KEY_CROSS_CHARACTER_LEVEL] = s.crossCharacterLevel
            p[KEY_CHARACTER_CAN_INITIATE_MEETING] = s.characterCanInitiateOfflineMeeting
            p[KEY_OFFLINE_IMMERSIVE_INPUT] = s.offlineImmersiveInputEnabled
            p[KEY_OFFLINE_BG_STYLE] = s.offlineBackgroundStyleRaw
            p[KEY_OFFLINE_PARTICLE_STYLE] = s.offlineParticleStyleRaw
            p[KEY_OFFLINE_BG_COLOR] = s.offlineBackgroundColor
            p[KEY_OFFLINE_NARRATIVE_DETAIL] = s.offlineNarrativeDetailRaw
            p[KEY_OFFLINE_CUSTOM_STYLE] = s.offlineCustomStylePrompt
            p[KEY_OFFLINE_CUSTOM_DIRECTIVE] = s.offlineCustomDirectivePrompt
            p[KEY_OFFLINE_CUSTOM_EMOTION] = s.offlineCustomEmotionPrompt
            p[KEY_MEETING_MEMORY_MAX_LENGTH] = s.meetingMemoryMaxLength
            p[KEY_MEETING_MEMORY_INJECT_COUNT] = s.meetingMemoryInjectCount
            p[KEY_OFFLINE_AFTERGLOW] = s.offlineAfterglowEnabled
            p[KEY_CONTENT_FILTER_RULES] = s.contentFilterRulesJSON
            p[KEY_LOG_RETENTION_COUNT] = s.logRetentionCount
            p[KEY_LOG_DETAIL_ENABLED] = s.logDetailEnabled
        }
    }

    suspend fun setPromptModulesJSON(json: String) =
        dataStore.edit { it[KEY_PROMPT_MODULES] = json }

    suspend fun setCharacterPromptModulesJSON(json: String) =
        dataStore.edit { it[KEY_CHARACTER_PROMPT_MODULES] = json }

    suspend fun setPromptModulePresetsJSON(json: String) =
        dataStore.edit { it[KEY_PROMPT_MODULE_PRESETS] = json }

    /**
     * 一次性迁移（时间感知优化·修 A）：把已存在的全局/角色模块里的 timeAwareness + currentMoment 调到 suffix
     * 末尾（紧贴生成处，最影响下条回复——新建角色走默认已在末尾，老角色的持久化顺序享受不到，故补此迁移）。
     * [KEY_TIME_MODULE_ORDER_MIGRATED] 守卫只跑一次；之后用户拖动顺序被尊重。最坏情况（解码失败）= 顺序不变、
     * 不抛（解码器内部已兜底），仍置 flag 不反复试。
     */
    suspend fun migratePromptModuleTimeOrderOnce() {
        // 读-检查-迁-写全在同一个 edit 事务里：① 原子（不会丢「启动期与提示词模块编辑器」之间的并发写）；
        // ② JSON 编解码在 DataStore 自有调度器执行，不占主线程（调用方 viewModelScope 在 Main）。
        dataStore.edit { p ->
            if (p[KEY_TIME_MODULE_ORDER_MIGRATED] == true) return@edit
            val result = PromptModuleService.migratePromptModuleTimeOrder(
                globalJson = p[KEY_PROMPT_MODULES] ?: "",
                characterJson = p[KEY_CHARACTER_PROMPT_MODULES] ?: "",
            )
            result?.first?.let { p[KEY_PROMPT_MODULES] = it }
            result?.second?.let { p[KEY_CHARACTER_PROMPT_MODULES] = it }
            p[KEY_TIME_MODULE_ORDER_MIGRATED] = true
        }
    }

    /**
     * 一次性迁移（见面记忆前置·2026-07-11 拍板）：把老用户全局/角色模块里 offlineMeetingMemory 从 SUFFIX 迁到
     * PREFIX、插到「角色记忆」正后。[KEY_MEETING_MEMORY_POSITION_MIGRATED] 守卫只跑一次；最坏情况解码失败=顺序不变不抛,仍置 flag。
     */
    suspend fun migratePromptModuleMeetingMemoryOnce() {
        // 读-检-迁-写同一 edit 事务（原子·不占主线程·同 [migratePromptModuleTimeOrderOnce]）。
        dataStore.edit { p ->
            if (p[KEY_MEETING_MEMORY_POSITION_MIGRATED] == true) return@edit
            val result = PromptModuleService.migratePromptModuleMeetingMemory(
                globalJson = p[KEY_PROMPT_MODULES] ?: "",
                characterJson = p[KEY_CHARACTER_PROMPT_MODULES] ?: "",
            )
            result?.first?.let { p[KEY_PROMPT_MODULES] = it }
            result?.second?.let { p[KEY_CHARACTER_PROMPT_MODULES] = it }
            p[KEY_MEETING_MEMORY_POSITION_MIGRATED] = true
        }
    }

    /**
     * 一次性迁移（「我们的日子」卷二·2026-09-02·图纸 §3.2）：把老用户全局/角色模块里缺席或停在追加位的 ourDays 归位到
     * 「见面记忆」正后。[KEY_OUR_DAYS_MODULE_ORDER_MIGRATED] 守卫只跑一次；最坏情况解码失败=顺序不变不抛,仍置 flag。
     */
    suspend fun migratePromptModuleOurDaysOnce() {
        // 读-检-迁-写同一 edit 事务（原子·不占主线程·同 [migratePromptModuleTimeOrderOnce]）。
        dataStore.edit { p ->
            if (p[KEY_OUR_DAYS_MODULE_ORDER_MIGRATED] == true) return@edit
            val result = PromptModuleService.migratePromptModuleOurDays(
                globalJson = p[KEY_PROMPT_MODULES] ?: "",
                characterJson = p[KEY_CHARACTER_PROMPT_MODULES] ?: "",
            )
            result?.first?.let { p[KEY_PROMPT_MODULES] = it }
            result?.second?.let { p[KEY_CHARACTER_PROMPT_MODULES] = it }
            p[KEY_OUR_DAYS_MODULE_ORDER_MIGRATED] = true
        }
    }

    /**
     * 一次性迁移（短信腔四件线下退场·两语境模型 2026-07-12）：老用户全局/角色模块里 enabledScenes==null 的四模块
     * 写 setOf(ONLINE_CHAT)（经 type.defaultEnabledScenes 单源·手改零碰）。守卫只跑一次；解码失败=不变不抛仍置 flag。
     */
    suspend fun migratePromptModuleSceneDefaultsOnce() {
        // 读-检-迁-写同一 edit 事务（原子·不占主线程·同 [migratePromptModuleTimeOrderOnce]）。
        dataStore.edit { p ->
            if (p[KEY_PROMPT_MODULE_SCENE_DEFAULTS_MIGRATED] == true) return@edit
            val result = PromptModuleService.migratePromptModuleSceneDefaults(
                globalJson = p[KEY_PROMPT_MODULES] ?: "",
                characterJson = p[KEY_CHARACTER_PROMPT_MODULES] ?: "",
            )
            result?.first?.let { p[KEY_PROMPT_MODULES] = it }
            result?.second?.let { p[KEY_CHARACTER_PROMPT_MODULES] = it }
            p[KEY_PROMPT_MODULE_SCENE_DEFAULTS_MIGRATED] = true
        }
    }

    // MARK: - 记忆参数设置（P12.1；范围对齐 iOS MemoryAndModelSettingsView + 安卓向量阈值）

    // settings-slider-manualinput：手填可超过滑杆上限（1:1 iOS——iOS 这些值无上限钳位）。仅保留下限，去掉上限钳。
    /** 短期记忆轮数（直接可见的最近对话），下限 1（iOS Conversation Rounds，手填可超滑杆上限）。 */
    suspend fun setShortTermMemoryLength(rounds: Int) =
        dataStore.edit { it[KEY_SHORT_TERM_MEMORY] = rounds.coerceAtLeast(1) }

    /** 长期记忆（滚动摘要）触发下限轮，下限 0（0=关，iOS Trigger Threshold，手填可超滑杆上限）。 */
    suspend fun setAutoSummarizeInterval(rounds: Int) =
        dataStore.edit { it[KEY_AUTO_SUMMARIZE_INTERVAL] = rounds.coerceAtLeast(0) }

    /** 角色长期记忆摘要软上限字数，下限 200（iOS Character Long-Term Memory Limit，手填可超滑杆上限）。 */
    suspend fun setMemorySummaryMaxLength(chars: Int) =
        dataStore.edit { it[KEY_MEMORY_SUMMARY_MAX] = chars.coerceAtLeast(200) }

    /** 智能渐进压缩开关（2026-06-20）：开=四级渐进压缩话术；关=一句硬字数要求。仅影响主聊天摘要提取话术，不改存储。 */
    suspend fun setProgressiveCompressionEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_PROGRESSIVE_COMPRESSION] = enabled }

    /** 结构化记忆（10 要点）触发下限轮，下限 0（0=关，手填可超滑杆上限）。 */
    suspend fun setStructuredMemoryInterval(rounds: Int) =
        dataStore.edit { it[KEY_STRUCTURED_MEMORY_INTERVAL] = rounds.coerceAtLeast(0) }

    /** 向量记忆检索相似度门槛百分比（安卓特有 ONNX 向量层；0=关，越高越严格），写入钳 0~100。 */
    suspend fun setVectorSearchThreshold(percent: Int) =
        dataStore.edit { it[KEY_VECTOR_SEARCH_THRESHOLD] = percent.coerceIn(0, 100) }

    // MARK: - 聊天合并等待窗（输入排契约 FABLE5_CHAT_INPUT_BAR_PROPOSAL §3.2-1；范围/默认值单源在 ChatMessageDispatcher）

    /** 合并等待窗秒数；未设过（含首启）返回 null——默认值由 UI 层 ChatMessageDispatcher.DEFAULT_WAIT_SECONDS 单源兜底。 */
    suspend fun getChatSendWaitSeconds(): Float? = dataStore.data.first()[KEY_CHAT_SEND_WAIT_SECONDS]

    /** 同上的响应式版（设置页滑块显示用——自适应在后台改值时行随之刷新）。 */
    val chatSendWaitSecondsFlow: Flow<Float?> = dataStore.data.map { it[KEY_CHAT_SEND_WAIT_SECONDS] }

    /** 写合并等待窗秒数（自适应重算与设置页滑块 C2 共用此值·范围钳位由 dispatcher 口径负责，这里只兜底防负数）。 */
    suspend fun setChatSendWaitSeconds(value: Float) =
        dataStore.edit { it[KEY_CHAT_SEND_WAIT_SECONDS] = value.coerceAtLeast(0f) }

    /** 自适应样本：最近发送时间戳（毫秒·逗号串·条数上限由 dispatcher 控制），解析宽容坏值。 */
    suspend fun getChatSendTimestamps(): List<Long> =
        dataStore.data.first()[KEY_CHAT_SEND_TIMESTAMPS]?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList()

    suspend fun setChatSendTimestamps(values: List<Long>) =
        dataStore.edit { it[KEY_CHAT_SEND_TIMESTAMPS] = values.joinToString(",") }

    // MARK: - 世界书触发设置（WB7c·契约 FABLE5_WORLDBOOK_PROPOSAL.md §12.7；改完即用 = 热更新 §12.11-3）

    suspend fun setWorldInfoScanDepth(depth: Int) =
        dataStore.edit { it[KEY_WI_SCAN_DEPTH] = depth.coerceIn(1, 50) }

    suspend fun setWorldInfoBudgetChars(chars: Int) =
        dataStore.edit { it[KEY_WI_BUDGET_CHARS] = chars.coerceIn(500, 50_000) }

    suspend fun setWorldInfoRecursiveScan(enabled: Boolean) =
        dataStore.edit { it[KEY_WI_RECURSIVE] = enabled }

    suspend fun setWorldInfoMaxRecursionSteps(steps: Int) =
        dataStore.edit { it[KEY_WI_MAX_RECURSION] = steps.coerceIn(0, 10) }

    /** 存 [com.situ.aichat.worldbook.WorldInfoInsertionStrategy] 名字（读侧宽容降级默认）。 */
    suspend fun setWorldInfoInsertionStrategy(strategyName: String) =
        dataStore.edit { it[KEY_WI_STRATEGY] = strategyName }

    suspend fun setWorldInfoCaseSensitive(enabled: Boolean) =
        dataStore.edit { it[KEY_WI_CASE_SENSITIVE] = enabled }

    suspend fun setWorldInfoMatchWholeWords(enabled: Boolean) =
        dataStore.edit { it[KEY_WI_WHOLE_WORDS] = enabled }

    // MARK: - 世界系统设置（W1·契约 FABLE5_WORLD_SYSTEM_PROPOSAL.md §7.A/§8；逻辑消费在 W2+）

    /** 鲜活度三档（存 [AppSettings.WORLD_VIVIDNESS_LITE]/STANDARD/RICH；读侧宽容降级默认）。 */
    suspend fun setWorldVividnessTier(tier: String) =
        dataStore.edit { it[KEY_WORLD_VIVIDNESS_TIER] = tier }

    suspend fun setWorldRelationshipsEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_WORLD_RELATIONSHIPS_ENABLED] = enabled }

    suspend fun setWorldRomanceEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_WORLD_ROMANCE_ENABLED] = enabled }

    /** 世界通知三档（存 [AppSettings.WORLD_NOTIFICATION_SILENT]/GENTLE/ALL；读侧宽容降级默认）。 */
    suspend fun setWorldNotificationTier(tier: String) =
        dataStore.edit { it[KEY_WORLD_NOTIFICATION_TIER] = tier }

    /** 世界首启轻三步已走过标记（W13·true 后永不再弹·图纸 §3.4/§3.5）。 */
    suspend fun setWorldOnboardingDone(done: Boolean) =
        dataStore.edit { it[KEY_WORLD_ONBOARDING_DONE] = done }

    // MARK: - 家的蛋巢之约（W12.5·决策 42；恒一巢单蛋 = 单值天然限流·零新表）

    /**
     * 之约响应式流（W12.5）：uuid 空/缺 = null（无之约）。EggNestService 据此 + petDao/角色行/eligibility 派生巢态。
     * 读两键 → 任一变即重发（含清键→null）。
     */
    val eggNestPactFlow: Flow<EggNestPact?> = dataStore.data.map { p ->
        p[KEY_EGG_NEST_PACT_UUID]?.takeIf { it.isNotEmpty() }?.let { uuid ->
            EggNestPact(uuid, p[KEY_EGG_NEST_PACT_AT] ?: 0L)
        }
    }

    /** 定约（原子单次 edit，双键同写；双击/并发同值幂等·E4）。 */
    suspend fun setEggNestPact(characterUuid: String, atMs: Long) =
        dataStore.edit { p ->
            p[KEY_EGG_NEST_PACT_UUID] = characterUuid
            p[KEY_EGG_NEST_PACT_AT] = atMs
        }

    /** 自愈/兑现清键（原子单次 edit·幂等·§3）。 */
    suspend fun clearEggNestPact() =
        dataStore.edit { p ->
            p.remove(KEY_EGG_NEST_PACT_UUID)
            p.remove(KEY_EGG_NEST_PACT_AT)
        }

    // MARK: - 上下文日志设置（批 D；对齐 iOS LogRetentionSettingsView）

    /** 日志保留条数：下限 1（手填可超滑杆上限 500 = iOS）；写入后由记录器在下次写入时轮转裁旧。 */
    suspend fun setLogRetentionCount(count: Int) =
        dataStore.edit { it[KEY_LOG_RETENTION_COUNT] = count.coerceAtLeast(1) }

    /** 是否记录完整上下文 + 回复正文（关=只存元数据 + 分段统计，隐私优先）。 */
    suspend fun setLogDetailEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_LOG_DETAIL_ENABLED] = enabled }

    /** 性能采集开关（默认关·卷 0）。PerfCollector 订阅本键刷新进程内缓存；**有意不进 applyBackupSettings**（本机设备态，同 KEY_AUTO_BACKUP_* 一族）。 */
    suspend fun setPerfCollectEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_PERF_COLLECT_ENABLED] = enabled }

    // MARK: - 回复规则设置（14.3a；范围对齐 iOS ReplySegmentRangeSettingsView / VoiceReplyRoundRangeSettingsView）

    /** 回复段数范围（每次回复随机拆成几条），双值钳到 bounds 1..15；upper>lower 由读侧 sanitize 兜底。 */
    suspend fun setReplySegmentRange(min: Int, max: Int) =
        dataStore.edit {
            it[KEY_REPLY_SEGMENT_MIN] = min.coerceIn(AppSettings.REPLY_SEGMENT_MIN_BOUND, AppSettings.REPLY_SEGMENT_MAX_BOUND)
            it[KEY_REPLY_SEGMENT_MAX] = max.coerceIn(AppSettings.REPLY_SEGMENT_MIN_BOUND, AppSettings.REPLY_SEGMENT_MAX_BOUND)
        }

    /** 语音条回复轮次范围（随机取几轮以语音条发送），双值钳到 bounds 1..20；upper>lower 由读侧 sanitize 兜底。 */
    suspend fun setVoiceReplyRoundRange(min: Int, max: Int) =
        dataStore.edit {
            it[KEY_VOICE_REPLY_ROUND_MIN] = min.coerceIn(AppSettings.VOICE_REPLY_ROUND_MIN_BOUND, AppSettings.VOICE_REPLY_ROUND_MAX_BOUND)
            it[KEY_VOICE_REPLY_ROUND_MAX] = max.coerceIn(AppSettings.VOICE_REPLY_ROUND_MIN_BOUND, AppSettings.VOICE_REPLY_ROUND_MAX_BOUND)
        }

    /** 创造力（温度，14.3b），写入钳 [0,2]（对齐 iOS 滑块范围；后台 service 硬编码温度不受影响）。 */
    suspend fun setLlmTemperature(value: Double) =
        dataStore.edit { it[KEY_LLM_TEMPERATURE] = value.coerceIn(AppSettings.LLM_TEMPERATURE_MIN, AppSettings.LLM_TEMPERATURE_MAX) }

    /** 故事正章创作温度（卷一 V1），写入钳 [0,2]（边界同聊天创造力；UI 入口随卷三）。 */
    suspend fun setStoryCreationTemperature(value: Double) {
        dataStore.edit { it[KEY_STORY_CREATION_TEMPERATURE] = value.coerceIn(AppSettings.LLM_TEMPERATURE_MIN, AppSettings.LLM_TEMPERATURE_MAX) }
    }

    /** 全局文字忌口（2026-07-30）。三态照存：null=移键（回内置默认）／""=主动清空（不注入）／文本=自定义。绝不 trim、绝不判空回退。 */
    suspend fun setStoryBannedExpressions(text: String?) = dataStore.edit { if (text == null) it.remove(KEY_STORY_BANNED_EXPRESSIONS) else it[KEY_STORY_BANNED_EXPRESSIONS] = text }

    /** 全局场面节拍（故事二期卷一）。三态照存：null=移键（回出厂默认）／""=主动清空（不注入）／文本=自定义。绝不 trim、绝不判空回退。 */
    suspend fun setStorySceneBeats(text: String?) = dataStore.edit { if (text == null) it.remove(KEY_STORY_SCENE_BEATS) else it[KEY_STORY_SCENE_BEATS] = text }

    /** 全局读者口味画像（故事二期卷一）。三态照存，口径同 [setStorySceneBeats]（本项无出厂默认，null 与 "" 都落到不注入）。 */
    suspend fun setStoryTasteProfile(text: String?) = dataStore.edit { if (text == null) it.remove(KEY_STORY_TASTE_PROFILE) else it[KEY_STORY_TASTE_PROFILE] = text }

    // MARK: - 内容过滤规则设置（14.3c；规则列表 JSON 整体覆盖式存取，对应 iOS ContentFilterService.saveRules）

    /** 保存整份内容过滤规则列表（JSON，由 ContentFilterService.encodeRules 编码；空串=回到首次状态）。 */
    suspend fun setContentFilterRulesJSON(json: String) =
        dataStore.edit { it[KEY_CONTENT_FILTER_RULES] = json }

    /** 记忆提取/注入提示词自定义（14.5b）。空串=用默认模板（编辑器在「等于默认」时存空，对齐 iOS onChange）。 */
    suspend fun setMemoryExtractionPrompt(prompt: String) =
        dataStore.edit { it[KEY_MEMORY_EXTRACTION_PROMPT] = prompt }

    suspend fun setMemoryInjectionPrompt(prompt: String) =
        dataStore.edit { it[KEY_MEMORY_INJECTION_PROMPT] = prompt }

    // MARK: - 子系统总开关设置（P12.1b；对齐 iOS SettingsView General/成长 区的系统开关）

    /** 角色成长（性格/关系/兴趣随聊天演化）总开关。关 → 跳过成长分析、成长 prompt 段为空。 */
    suspend fun setGrowthSystemEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_GROWTH_SYSTEM] = enabled }

    /** 自动关系进化（基于成长分析自动判定关系变化）开关。 */
    suspend fun setRelationshipAutoAdvanceEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_RELATIONSHIP_AUTO_ADVANCE] = enabled }

    // MARK: - 成长分析参数设置（P12.1d；范围对齐 iOS GrowthRelationshipSettingsView）

    /** 性格分析触发下限轮，下限 0（0=关，iOS 性格分析频率，手填可超滑杆上限）。 */
    suspend fun setGrowthAnalysisInterval(rounds: Int) =
        dataStore.edit { it[KEY_GROWTH_ANALYSIS_INTERVAL] = rounds.coerceAtLeast(0) }

    /** 成长日志保留上限条数，下限 20（iOS 成长记录数量，手填可超滑杆上限）。 */
    suspend fun setGrowthLogMaxCount(count: Int) =
        dataStore.edit { it[KEY_GROWTH_LOG_MAX_COUNT] = count.coerceAtLeast(20) }

    /** 情绪历史保留上限条数，下限 50（iOS 情绪记录数量 50–500，手填可超滑杆上限；归档路径 takeLast 截断读此值）。 */
    suspend fun setMoodHistoryMaxCount(count: Int) =
        dataStore.edit { it[KEY_MOOD_HISTORY_MAX_COUNT] = count.coerceAtLeast(50) }

    /** 兴趣遗忘周期天数，下限 1（iOS 兴趣遗忘周期，手填可超滑杆上限）。 */
    suspend fun setInterestCooldownDays(days: Int) =
        dataStore.edit { it[KEY_INTEREST_COOLDOWN_DAYS] = days.coerceAtLeast(1) }

    /** 日程轨迹（角色每日日程：忙碌/睡眠时段）总开关。注：沉浸模式快照也写此键（沉浸期间强制关）。 */
    suspend fun setScheduleSystemEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_SCHEDULE_SYSTEM] = enabled }

    /** 角色跨日程互动频率（「角色之间互相来往」·0=关 1=偶尔约20% 2=经常约50% 3=频繁约80%）。
     * ScheduleCoordinator/ScheduleGenerationService 读，仅影响下次生成日程；钳到 [0,3]。 */
    suspend fun setCrossCharacterLevel(level: Int) =
        dataStore.edit { it[KEY_CROSS_CHARACTER_LEVEL] = level.coerceIn(0, 3) }

    /** 宠物系统（M11）总开关。关 → 所有宠物行为/prompt 注入跳过。 */
    suspend fun setPetSystemEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_PET_SYSTEM] = enabled }

    /** 货币系统（M10）总开关。关 → 月薪/经济事件/主动送礼全停（主动送礼受其二级门控）。 */
    suspend fun setCurrencySystemEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_CURRENCY_SYSTEM] = enabled }

    /** 角色主动送礼开关（二级，受货币系统门控；货币关则不生效）。 */
    suspend fun setCharacterProactiveGiftEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_PROACTIVE_GIFT] = enabled }

    // MARK: - 主动消息通知设置（P6.1c）

    /** 全局主动消息通知开关（关 = 不为任何角色调度）。 */
    suspend fun setNotificationsEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_NOTIFICATIONS_ENABLED] = enabled }

    /** 角色经济动态通知三档（[com.situ.aichat.notification.EconomyNotificationTier] 的 raw，P1-40）。 */
    suspend fun setEconomyNotificationTier(tierRaw: String) =
        dataStore.edit { it[KEY_ECONOMY_NOTIFICATION_TIER] = tierRaw }

    /** 关系里程碑庆祝通知开关（P1-33）。 */
    suspend fun setMilestoneNotificationEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_MILESTONE_NOTIF_ENABLED] = enabled }

    /** 主动消息夜间免打扰开关（窗内到点一律作废、不补发）。 */
    suspend fun setQuietHoursEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_NOTIF_QUIET_HOURS_ENABLED] = enabled }

    /** 免打扰窗起点（当日分钟数；跨午夜语义见 [com.situ.aichat.notification.NotificationScheduleRules.isInQuietHours]）。 */
    suspend fun setQuietHoursStartMinute(minuteOfDay: Int) =
        dataStore.edit { it[KEY_NOTIF_QUIET_HOURS_START] = minuteOfDay }

    /** 免打扰窗终点（当日分钟数）。 */
    suspend fun setQuietHoursEndMinute(minuteOfDay: Int) =
        dataStore.edit { it[KEY_NOTIF_QUIET_HOURS_END] = minuteOfDay }

    /** 消息情绪动画开关（P1-5·1:1 iOS AppSettings.swift:122）。 */
    suspend fun setEmotionAnimationEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_EMOTION_ANIMATION] = enabled }

    /** 自然短句口吻开关（活人感一期 P1）。 */
    suspend fun setTextingToneEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_TEXTING_TONE] = enabled }

    // MARK: - 忙碌时延迟回复设置（P6.2）

    // 忙碌延迟回复 setter 已随功能删除（2026-07-11）；读路径/KEY 保留 = DataStore/备份线格式兼容。

    // MARK: - 日历提醒方式设置（P6.3）

    /** 日历提醒方式（[com.situ.aichat.notification.CalendarReminderMode] 的 raw：system / character / both）。 */
    suspend fun setCalendarReminderMode(modeRaw: String) =
        dataStore.edit { it[KEY_CALENDAR_REMINDER_MODE] = modeRaw }

    // MARK: - 日历集成设置（P12.1c；对齐 iOS CalendarSettingsView）

    /**
     * 日历集成总开关（关 → 不把设备日历事件注入提示词、AI 也不写日历）。注：沉浸模式快照也写此键
     * （沉浸期间强制关），与本直接 setter last-write-wins，iOS 同源。开启时由 UI 联动请求 READ/WRITE_CALENDAR。
     */
    suspend fun setCalendarIntegrationEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_CALENDAR_INTEGRATION] = enabled }

    /** 日历操作确认（AI 写日历前是否弹确认卡片；关 → 直接创建并弹窗告知）。受日历集成门控。 */
    suspend fun setCalendarActionConfirmation(enabled: Boolean) =
        dataStore.edit { it[KEY_CALENDAR_ACTION_CONFIRMATION] = enabled }

    // MARK: - 日记设置（P7.1.2/7.1.3；设置 UI 在 7.1.5）

    /** 每日自动生成日记总开关。 */
    suspend fun setDiaryAutoGenerateEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_DIARY_AUTO_GENERATE] = enabled }

    /** 自动生成时间 "HH:mm"。 */
    suspend fun setDiaryAutoGenerateTime(time: String) =
        dataStore.edit { it[KEY_DIARY_AUTO_GENERATE_TIME] = time }

    /** 自动生成的日记直接发布（R3 评论区活化·默认关=保留先润色的权利）。 */
    suspend fun setDiaryAutoPublishEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_DIARY_AUTO_PUBLISH] = enabled }

    /** 交换日记固定笔友 uuid（R4·空=自动轮换）。 */
    suspend fun setDiaryExchangePartnerUuid(uuid: String) =
        dataStore.edit { it[KEY_DIARY_EXCHANGE_PARTNER] = uuid }

    /** AI 角色是否可评论日记。 */
    suspend fun setDiaryCharacterInteractionEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_DIARY_CHAR_INTERACTION] = enabled }

    /** 允许评论的角色 uuid（逗号分隔，空=全部）。 */
    suspend fun setDiaryInteractingCharacterUUIDs(csv: String) =
        dataStore.edit { it[KEY_DIARY_INTERACTING_CHARS] = csv }

    /** AI 评论延迟（分钟），写入时钳到 1~15（对齐 iOS 滑块范围）。 */
    suspend fun setDiaryCommentDelay(minutes: Int) =
        dataStore.edit { it[KEY_DIARY_COMMENT_DELAY] = minutes.coerceIn(1, 15) }

    /** 日记本最后查看时刻（diary-1，进/出日记列表都写 now，对齐 iOS markDiaryAsRead）。 */
    suspend fun setLastViewedDiaryDate(epochMillis: Long) =
        dataStore.edit { it[KEY_LAST_VIEWED_DIARY_DATE] = epochMillis }

    // MARK: - 朋友圈设置（P7.2.8；对齐 iOS MomentSettingsView 的 4 控件）

    /** 每角色每日自动发帖上限，写入钳 0~5（0=关）。 */
    suspend fun setMomentAutoPostFrequency(count: Int) =
        dataStore.edit { it[KEY_MOMENT_AUTO_POST_FREQ] = count.coerceIn(0, 5) }

    /** 每帖 AI 评论人数上限，写入钳 0~3（0=关，语义=上限非固定数）。 */
    suspend fun setMomentAutoCommentFrequency(count: Int) =
        dataStore.edit { it[KEY_MOMENT_AUTO_COMMENT_FREQ] = count.coerceIn(0, 3) }

    /** 自动点赞开关。 */
    suspend fun setMomentAutoLikeEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_MOMENT_AUTO_LIKE] = enabled }

    /** 首条评论基础延迟（分钟），写入钳 1~10（对齐 iOS 滑块范围）。 */
    suspend fun setMomentCommentDelay(minutes: Int) =
        dataStore.edit { it[KEY_MOMENT_COMMENT_DELAY] = minutes.coerceIn(1, 10) }

    /** 「X 发了新动态」系统通知开关（13.7e；关 = 后台发帖不再推系统通知，feed 内仍照常出现）。 */
    suspend fun setMomentNewPostNotificationEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_MOMENT_NEW_POST_NOTIF] = enabled }

    // MARK: - 表情包设置（P8.1；设置 UI 在 8.1c 管理页）

    /** 角色是否可发表情包（默认开；关闭则 prompt 不注入清单 + 历史/新回复全剥）。 */
    suspend fun setCharacterCanSendStickersEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_CHARACTER_CAN_SEND_STICKERS] = enabled }

    // MARK: - 宠物日记设置（P8.2；设置 UI → P12）

    /** 每日自动生成宠物视角日记（默认关，对齐 iOS）。 */
    suspend fun setPetDiaryAutoGenerateEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_PET_DIARY_AUTO_GENERATE] = enabled }

    // MARK: - 语音通话设置（P10.1h-3）

    /** 打断灵敏度阈值（写入时钳到 [0.05, 0.40]，对齐 iOS 滑块边界 + AppSettings.sanitize）。 */
    suspend fun setVoiceCallInterruptThreshold(value: Float) =
        dataStore.edit {
            it[KEY_VOICE_CALL_INTERRUPT_THRESHOLD] =
                value.coerceIn(AppSettings.VOICE_CALL_THRESHOLD_MIN, AppSettings.VOICE_CALL_THRESHOLD_MAX)
        }

    /**
     * 每角色通知开关（对齐 iOS `CharacterStreakNotificationPreference`，默认开）。
     * 存「已关闭」角色 uuid 的集合：不在集合 = 开（与 iOS `?? true` 等价），在集合 = 关。
     * 用集合而非 Room 列，避免 6.1c bump DB（DeliveryRecord/WindowStats 才 bump，见 6.1d/6.1e）。
     */
    val disabledNotificationCharacterIds: Flow<Set<String>> =
        dataStore.data.map { it[KEY_NOTIF_DISABLED_CHARS] ?: emptySet() }

    /** 某角色是否启用通知（默认 true）。 */
    suspend fun isCharacterNotificationEnabled(characterId: String): Boolean =
        !disabledNotificationCharacterIds.first().contains(characterId)

    /** 设置某角色通知开关（开 = 从「已关闭」集合移除；关 = 加入）。 */
    suspend fun setCharacterNotificationEnabled(characterId: String, enabled: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_NOTIF_DISABLED_CHARS] ?: emptySet()
            prefs[KEY_NOTIF_DISABLED_CHARS] = if (enabled) current - characterId else current + characterId
        }
    }

    // MARK: - 线下模式偏好设置（10.2f；线下设置页写入）

    /** 角色可否主动发起线下见面（关 → 角色不弹邀约卡片，仅用户手动发起）。 */
    suspend fun setCharacterCanInitiateOfflineMeeting(enabled: Boolean) =
        dataStore.edit { it[KEY_CHARACTER_CAN_INITIATE_MEETING] = enabled }

    /** 沉浸输入开关（开 → 见面中四步标签输入替换普通输入栏）。 */
    suspend fun setOfflineImmersiveInputEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_OFFLINE_IMMERSIVE_INPUT] = enabled }

    /** 线下叙事细腻程度 raw（plain/normal/detailed/custom）。 */
    suspend fun setOfflineNarrativeDetailRaw(raw: String) =
        dataStore.edit { it[KEY_OFFLINE_NARRATIVE_DETAIL] = raw }

    /** custom 档：写作风格指导。 */
    suspend fun setOfflineCustomStylePrompt(text: String) =
        dataStore.edit { it[KEY_OFFLINE_CUSTOM_STYLE] = text }

    /** custom 档：每轮叙事指令（每行一条）。 */
    suspend fun setOfflineCustomDirectivePrompt(text: String) =
        dataStore.edit { it[KEY_OFFLINE_CUSTOM_DIRECTIVE] = text }

    /** custom 档：情绪底色（每行一条）。 */
    suspend fun setOfflineCustomEmotionPrompt(text: String) =
        dataStore.edit { it[KEY_OFFLINE_CUSTOM_EMOTION] = text }

    /** 线下沉浸背景样式 raw（particle/solidColor/customImage）。 */
    suspend fun setOfflineBackgroundStyleRaw(raw: String) =
        dataStore.edit { it[KEY_OFFLINE_BG_STYLE] = raw }

    /** 线下沉浸粒子风格 raw（stars/firefly/dust）。 */
    suspend fun setOfflineParticleStyleRaw(raw: String) =
        dataStore.edit { it[KEY_OFFLINE_PARTICLE_STYLE] = raw }

    /** 线下沉浸背景纯色 hex（也复用作自定义图文件名键）。 */
    suspend fun setOfflineBackgroundColor(hex: String) =
        dataStore.edit { it[KEY_OFFLINE_BG_COLOR] = hex }

    /** 普通聊天注入「最近 N 次见面完整摘要」的 N（写入钳 1~10·梦剧场 §3.4）。 */
    suspend fun setMeetingMemoryInjectCount(count: Int) =
        dataStore.edit { it[KEY_MEETING_MEMORY_INJECT_COUNT] = count.coerceIn(1, 10) }

    /** 见面后余温消息开关（开 → 见面结束几小时后 TA 主动发一条回味见面的短消息·梦剧场 §3.10）。 */
    suspend fun setOfflineAfterglowEnabled(enabled: Boolean) =
        dataStore.edit { it[KEY_OFFLINE_AFTERGLOW] = enabled }

    /** 见面记忆摘要软上限字数，下限 200（iOS SettingSliderRow，手填可超滑杆上限）。 */
    suspend fun setMeetingMemoryMaxLength(chars: Int) =
        dataStore.edit { it[KEY_MEETING_MEMORY_MAX_LENGTH] = chars.coerceAtLeast(200) }

    // internal：LegacyImmersiveMigration（沉浸模式移除的一次性 DataStore 迁移）需按键还原快照值。
    internal companion object {
        val KEY_PROMPT_MODULES = stringPreferencesKey("prompt_modules_json")
        val KEY_CHARACTER_PROMPT_MODULES = stringPreferencesKey("character_prompt_modules_json")
        val KEY_PROMPT_MODULE_PRESETS = stringPreferencesKey("prompt_module_presets_json")
        // 时间感知优化·修 A：timeAwareness/currentMoment 调到 suffix 末尾的一次性迁移守卫。
        val KEY_TIME_MODULE_ORDER_MIGRATED = booleanPreferencesKey("time_module_order_migrated_v1")
        // 见面记忆前置（2026-07-11）：offlineMeetingMemory SUFFIX→PREFIX·插到「角色记忆」正后的一次性迁移守卫。
        val KEY_MEETING_MEMORY_POSITION_MIGRATED = booleanPreferencesKey("meeting_memory_position_migrated_v1")
        val KEY_PROMPT_MODULE_SCENE_DEFAULTS_MIGRATED = booleanPreferencesKey("prompt_module_scene_defaults_migrated")
        // 「我们的日子」卷二（2026-09-02）：ourDays 归位到「见面记忆」正后的一次性迁移守卫。
        val KEY_OUR_DAYS_MODULE_ORDER_MIGRATED = booleanPreferencesKey("our_days_module_order_migrated_v1")
        // 聊天合并等待窗（输入排契约 C1）
        val KEY_CHAT_SEND_WAIT_SECONDS = floatPreferencesKey("chat_send_wait_seconds")
        val KEY_CHAT_SEND_TIMESTAMPS = stringPreferencesKey("chat_send_timestamps")
        val KEY_SHORT_TERM_MEMORY = intPreferencesKey("short_term_memory_length")
        val KEY_AUTO_SUMMARIZE_INTERVAL = intPreferencesKey("auto_summarize_interval")
        val KEY_MEMORY_SUMMARY_MAX = intPreferencesKey("memory_summary_max_length")
        val KEY_PROGRESSIVE_COMPRESSION = booleanPreferencesKey("progressive_compression_enabled")
        val KEY_STRUCTURED_MEMORY_INTERVAL = intPreferencesKey("structured_memory_interval")
        val KEY_VECTOR_SEARCH_THRESHOLD = intPreferencesKey("vector_search_threshold")
        // 世界书触发设置（WB7c）
        val KEY_WI_SCAN_DEPTH = intPreferencesKey("world_info_scan_depth")
        val KEY_WI_BUDGET_CHARS = intPreferencesKey("world_info_budget_chars")
        val KEY_WI_RECURSIVE = booleanPreferencesKey("world_info_recursive_scan")
        val KEY_WI_MAX_RECURSION = intPreferencesKey("world_info_max_recursion_steps")
        val KEY_WI_STRATEGY = stringPreferencesKey("world_info_insertion_strategy")
        val KEY_WI_CASE_SENSITIVE = booleanPreferencesKey("world_info_case_sensitive")
        val KEY_WI_WHOLE_WORDS = booleanPreferencesKey("world_info_match_whole_words")
        // 世界系统设置（W1）——key 字符串锁定（图纸 §9），改名即破坏已存偏好。
        val KEY_WORLD_VIVIDNESS_TIER = stringPreferencesKey("world_vividness_tier")
        val KEY_WORLD_RELATIONSHIPS_ENABLED = booleanPreferencesKey("world_char_relationships_enabled")
        val KEY_WORLD_ROMANCE_ENABLED = booleanPreferencesKey("world_char_romance_enabled")
        val KEY_WORLD_NOTIFICATION_TIER = stringPreferencesKey("world_notification_tier")
        val KEY_WORLD_ONBOARDING_DONE = booleanPreferencesKey("world_onboarding_done")
        // 家的蛋巢之约（W12.5·图纸 §9 key 字符串锁定，改名即破坏已存偏好）。
        val KEY_EGG_NEST_PACT_UUID = stringPreferencesKey("egg_nest_pact_character_uuid")
        val KEY_EGG_NEST_PACT_AT = longPreferencesKey("egg_nest_pact_at")
        // 上下文日志（批 D）：保留条数 + 是否存正文。
        val KEY_LOG_RETENTION_COUNT = intPreferencesKey("log_retention_count")
        val KEY_LOG_DETAIL_ENABLED = booleanPreferencesKey("log_detail_enabled")
        val KEY_PERF_COLLECT_ENABLED = booleanPreferencesKey("perf_collect_enabled")
        // 回复规则（14.3a）：回复段数 / 语音条轮次范围。
        val KEY_REPLY_SEGMENT_MIN = intPreferencesKey("reply_segment_min")
        val KEY_REPLY_SEGMENT_MAX = intPreferencesKey("reply_segment_max")
        val KEY_VOICE_REPLY_ROUND_MIN = intPreferencesKey("voice_reply_round_min")
        val KEY_VOICE_REPLY_ROUND_MAX = intPreferencesKey("voice_reply_round_max")
        val KEY_LLM_TEMPERATURE = doublePreferencesKey("llm_temperature")
        val KEY_STORY_CREATION_TEMPERATURE = doublePreferencesKey("story_creation_temperature")
        val KEY_STORY_BANNED_EXPRESSIONS = stringPreferencesKey("story_banned_expressions")
        val KEY_STORY_SCENE_BEATS = stringPreferencesKey("story_scene_beats")
        val KEY_STORY_TASTE_PROFILE = stringPreferencesKey("story_taste_profile")
        val KEY_GROWTH_SYSTEM = booleanPreferencesKey("growth_system_enabled")
        val KEY_GROWTH_ANALYSIS_INTERVAL = intPreferencesKey("growth_analysis_interval")
        val KEY_GROWTH_LOG_MAX_COUNT = intPreferencesKey("growth_log_max_count")
        val KEY_MOOD_HISTORY_MAX_COUNT = intPreferencesKey("mood_history_max_count")
        val KEY_INTEREST_COOLDOWN_DAYS = intPreferencesKey("interest_cooldown_days")
        val KEY_RELATIONSHIP_AUTO_ADVANCE = booleanPreferencesKey("relationship_auto_advance_enabled")
        val KEY_PET_SYSTEM = booleanPreferencesKey("pet_system_enabled")
        val KEY_CURRENCY_SYSTEM = booleanPreferencesKey("currency_system_enabled")
        val KEY_PROACTIVE_GIFT = booleanPreferencesKey("character_proactive_gift_enabled")
        val KEY_NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val KEY_ECONOMY_NOTIFICATION_TIER = stringPreferencesKey("economy_notification_tier")
        val KEY_MILESTONE_NOTIF_ENABLED = booleanPreferencesKey("milestone_notification_enabled")
        val KEY_NOTIF_QUIET_HOURS_ENABLED = booleanPreferencesKey("notif_quiet_hours_enabled")
        val KEY_NOTIF_QUIET_HOURS_START = intPreferencesKey("notif_quiet_hours_start")
        val KEY_NOTIF_QUIET_HOURS_END = intPreferencesKey("notif_quiet_hours_end")
        val KEY_EMOTION_ANIMATION = booleanPreferencesKey("emotion_animation_enabled")
        val KEY_TEXTING_TONE = booleanPreferencesKey("texting_tone_enabled")
        val KEY_NOTIF_DISABLED_CHARS = stringSetPreferencesKey("notif_disabled_char_ids")
        val KEY_BUSY_MODE_ENABLED = booleanPreferencesKey("busy_mode_enabled")
        val KEY_BUSY_MODE_MAX_MINUTES = intPreferencesKey("busy_mode_max_minutes")
        val KEY_CALENDAR_REMINDER_MODE = stringPreferencesKey("calendar_reminder_mode")
        val KEY_DIARY_AUTO_GENERATE = booleanPreferencesKey("diary_auto_generate_enabled")
        val KEY_DIARY_AUTO_GENERATE_TIME = stringPreferencesKey("diary_auto_generate_time")
        val KEY_DIARY_AUTO_PUBLISH = booleanPreferencesKey("diary_auto_publish_enabled")
        val KEY_DIARY_EXCHANGE_PARTNER = stringPreferencesKey("diary_exchange_partner_uuid")
        val KEY_DIARY_CHAR_INTERACTION = booleanPreferencesKey("diary_character_interaction_enabled")
        val KEY_DIARY_INTERACTING_CHARS = stringPreferencesKey("diary_interacting_character_uuids")
        val KEY_DIARY_COMMENT_DELAY = intPreferencesKey("diary_comment_delay")
        val KEY_LAST_VIEWED_DIARY_DATE = longPreferencesKey("last_viewed_diary_date")
        val KEY_MOMENT_AUTO_POST_FREQ = intPreferencesKey("moment_auto_post_frequency")
        val KEY_MOMENT_AUTO_COMMENT_FREQ = intPreferencesKey("moment_auto_comment_frequency")
        val KEY_MOMENT_AUTO_LIKE = booleanPreferencesKey("moment_auto_like_enabled")
        val KEY_MOMENT_COMMENT_DELAY = intPreferencesKey("moment_comment_delay")
        val KEY_MOMENT_NEW_POST_NOTIF = booleanPreferencesKey("moment_new_post_notification_enabled")
        val KEY_CHARACTER_CAN_SEND_STICKERS = booleanPreferencesKey("character_can_send_stickers_enabled")
        val KEY_PET_DIARY_AUTO_GENERATE = booleanPreferencesKey("pet_diary_auto_generate_enabled")
        val KEY_VOICE_CALL_INTERRUPT_THRESHOLD = floatPreferencesKey("voice_call_interrupt_threshold")
        val KEY_CALENDAR_INTEGRATION = booleanPreferencesKey("calendar_integration_enabled")
        val KEY_CALENDAR_ACTION_CONFIRMATION = booleanPreferencesKey("calendar_action_confirmation")
        val KEY_SCHEDULE_SYSTEM = booleanPreferencesKey("schedule_system_enabled")
        val KEY_CROSS_CHARACTER_LEVEL = intPreferencesKey("cross_character_level")
        val KEY_CHARACTER_CAN_INITIATE_MEETING = booleanPreferencesKey("character_can_initiate_offline_meeting")
        val KEY_OFFLINE_IMMERSIVE_INPUT = booleanPreferencesKey("offline_immersive_input_enabled")
        val KEY_OFFLINE_BG_STYLE = stringPreferencesKey("offline_background_style_raw")
        val KEY_OFFLINE_PARTICLE_STYLE = stringPreferencesKey("offline_particle_style_raw")
        val KEY_OFFLINE_BG_COLOR = stringPreferencesKey("offline_background_color")
        val KEY_OFFLINE_NARRATIVE_DETAIL = stringPreferencesKey("offline_narrative_detail_raw")
        val KEY_OFFLINE_CUSTOM_STYLE = stringPreferencesKey("offline_custom_style_prompt")
        val KEY_OFFLINE_CUSTOM_DIRECTIVE = stringPreferencesKey("offline_custom_directive_prompt")
        val KEY_OFFLINE_CUSTOM_EMOTION = stringPreferencesKey("offline_custom_emotion_prompt")
        val KEY_MEETING_MEMORY_MAX_LENGTH = intPreferencesKey("meeting_memory_max_length")
        val KEY_MEETING_MEMORY_INJECT_COUNT = intPreferencesKey("meeting_memory_inject_count")
        val KEY_OFFLINE_AFTERGLOW = booleanPreferencesKey("offline_afterglow_enabled")
        val KEY_CONTENT_FILTER_RULES = stringPreferencesKey("content_filter_rules_json")
        val KEY_MEMORY_EXTRACTION_PROMPT = stringPreferencesKey("memory_extraction_prompt")
        val KEY_MEMORY_INJECTION_PROMPT = stringPreferencesKey("memory_injection_prompt")

        // 定时自动备份（13.6c；设备本地，不进备份文件）。
        val KEY_AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
        val KEY_AUTO_BACKUP_TREE_URI = stringPreferencesKey("auto_backup_tree_uri")
        val KEY_AUTO_BACKUP_LAST_AT = longPreferencesKey("auto_backup_last_at")
        val KEY_AUTO_BACKUP_LAST_MEDIA_AT = longPreferencesKey("auto_backup_last_media_at")
    }
}
