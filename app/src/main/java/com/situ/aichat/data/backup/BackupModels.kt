package com.situ.aichat.data.backup

import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.prompt.PromptModule
import kotlinx.serialization.Serializable

/**
 * Backup JSON schema (the `manifest.json` inside the [BackupArchive] zip).
 *
 * 13.6「备份超越 iOS」：补齐 iOS 每角色 14 段 + 顶层段，并顺势加 iOS 不备的全局用户数据。结构对安卓做了
 * 「全局段拆出顶层」的有意调整（见下），媒体走 zip 文件（[CharacterExport.avatarArchiveKey] 等存 zip 内相对键，
 * 字节在 zip 的 `media/` 子目录），而非 iOS 的 Base64 内嵌。
 *
 * 与 iOS 的有意取舍（已记录）：
 * - 容器：安卓 = zip（manifest.json + media/）；iOS = LZFSE `.aichatbackup`（Base64 内嵌）。跨平台二进制互通本就
 *   不可达（LZFSE + ISO8601 vs zip + epoch），故不追求。
 * - 日期：epoch millis（Long），非 ISO-8601 串。
 * - 结构：iOS 把 moments/gifts/redpackets/stories 嵌在每个角色下（按 characterUUID 查，多角色去重）。安卓的朋友圈是
 *   **单一共享时间线**（用户帖 characterUuid=null + 各角色帖），iOS 的 `characterUUID==char` 查询会**漏掉用户自己的帖**；
 *   故安卓把 moments / diary / stories / gifts / redPackets / stickers / userWallet / userProfile / appSettings 提为
 *   **顶层全局段**（用真实 uuid 整体往返，无需下标映射、无需多角色去重，且捕获用户帖）。per-character 仅保留「随角色
 *   FK 级联」的私有段（会话+消息 / 里程碑 / 宠物 / 角色钱包 / 日程 / 通知模板 / 提示词模块覆盖）。
 * - 兑换码（redeemCodeUsages·14.6c）：已纳入（只存 codeHash）。聊天壁纸（per-角色·chunk1b）：媒体走 zip 文件
 *   （[CharacterExport.chatWallpaperArchiveKey] 存 zip 内相对键 `media/wallpapers/<uuid>.jpg`，仿头像）。
 *
 * 所有字段带默认值 → 旧 / 残缺 JSON 宽松解码（`Json { ignoreUnknownKeys = true; encodeDefaults = false }`）。
 * version 1 = 旧明文 `.json`（仅 4 段）；version 2 = 本 zip 格式（全量 + 媒体）。
 */
@Serializable
data class BackupPackage(
    val manifest: BackupManifest = BackupManifest(),
    val characters: List<CharacterBackupData> = emptyList(),

    // ── 顶层全局段（13.6；见类注释「结构」取舍） ──
    /** 💰 全局用户钱包（单例）。 */
    val userWallet: UserWalletExport? = null,
    /** 用户资料（昵称/简介/头像/城市/生日）——iOS 交 iCloud 不入备份，安卓补（超越 iOS）。 */
    val userProfile: UserProfileExport? = null,
    /** 全局应用设置/偏好（不含 API 密钥）——超越 iOS。 */
    val appSettings: AppSettings? = null,
    /** 朋友圈（全部帖+评论+赞，含用户帖）。 */
    val moments: MomentsExport? = null,
    /** 日记正文 + 评论 + 点赞（iOS 只备评论；安卓补正文/线程/点赞/交换日记作者，换机不丢日记——超越 iOS）。 */
    val diaryEntries: List<DiaryEntryExport>? = null,
    /** 月度回顾（R5·R6-3② 随备份·独立顶层段·monthStartMillis 唯一 = 每月一篇幂等）。 */
    val monthlyReviews: List<MonthlyReviewExport>? = null,
    /** 互动故事（+章节+角色）。 */
    val stories: List<StoryExport>? = null,
    /** 💰 礼物记录（pricePaid 快照；纯历史，导入不动钱包余额）。 */
    val gifts: List<GiftRecordExport>? = null,
    /** 💰 红包记录（全状态机；pending 托管金额，导入不重复扣/加币）。 */
    val redPackets: List<RedPacketRecordExport>? = null,
    /** 自定义表情包库（含图片字节）——iOS 无，安卓补（超越 iOS）。 */
    val stickers: List<CustomStickerExport>? = null,
    /** 💰 兑换码使用记录（14.6c·1:1 iOS：进备份，恢复后仍记得用过哪些码 → 不可「回炉」重兑·只存 codeHash）。 */
    val redeemCodeUsages: List<RedeemCodeUsageExport>? = null,
    /**
     * 💰 金币流水台账（R2：用户侧+角色侧全量）。随余额一起恢复——发薪/房租/季度奖金/入职储蓄靠 relatedEntityId
     * 幂等台账判「是否已发」，不进备份则换机/重装恢复后**重发工资/重扣房租**。导入按原 uuid/relatedEntityId 整表
     * REPLACE 搬回，**不动任何钱包余额**（余额由 wallet 段单独恢复）。
     */
    val currencyTransactions: List<CurrencyTransactionExport>? = null,
    /** 未来约定见面（全状态机往返；无钱路，confirmed 恢复后由打开会话/冷启扫描重排到点通知）。 */
    val futureAppointments: List<MeetingAppointmentExport>? = null,
    /** 世界书（WB6b·顶层全局段整体恢复一次）：书+条目+绑定角色 uuid；按 uuid 覆盖=幂等；嵌入/时效不进备份。 */
    val worldBooks: List<WorldBookBackupData>? = null,
    /** 世界系统（W1·顶层全局段·契约 §5/§20）：世界状态+原住民眼缘+关系边/事件+世界事件+风物志+发现+在途；世界从未初始化则为 null。 */
    val world: WorldBackupData? = null,
    /** 线下见面回忆表（梦剧场 B 部·顶层全局段·图纸 §3.2）：每次见面一行；恢复靠 characterUuid 幽灵过滤；uuid REPLACE 幂等。 */
    val offlineMeetingMemories: List<OfflineMeetingMemoryExport>? = null,
    /** 承诺账本（记忆改造一期·部件①·顶层全局段·图纸 §3.1）：「我们的约定」结构化行；恢复靠 characterUuid 幽灵过滤；uuid REPLACE 幂等。 */
    val promises: List<PromiseExport>? = null,
    /** 「我们的日子」（卷一《沉淀》·顶层全局段·图纸 §3.5）：一天 × 一角色的事实 + 手记行（不含向量）；恢复靠 characterUuid 幽灵过滤；uuid REPLACE 幂等。 */
    val ourDays: List<OurDayExport>? = null,
    /** 故事「我的模板」（图纸四 §3.2·顶层全局段）：整套创作设定；无幽灵过滤（不挂角色/故事）；uuid REPLACE 幂等。 */
    val userStoryTemplates: List<UserStoryTemplateExport>? = null,
)

@Serializable
data class BackupManifest(
    /** 1 = 旧明文 .json（4 段）；2 = zip 全量格式（13.6）。 */
    val version: Int = 1,
    val appVersion: String = "",
    val exportDate: Long = 0L,
    /** 本包是否含媒体字节（关闭时仅结构化数据 + archiveKey 留空）。 */
    val includesMedia: Boolean = false,
    /** 媒体文件数（manifest 速览/导入预览用）。 */
    val mediaCount: Int = 0,
    val characterSummaries: List<CharacterSummary> = emptyList(),
)

@Serializable
data class CharacterSummary(
    val name: String = "",
    val uuid: String = "",
    val messageCount: Int = 0,
)

@Serializable
data class CharacterBackupData(
    val character: CharacterExport,
    val conversations: List<ConversationExport>? = null,
    val milestones: List<MilestoneExport>? = null,
    /** The character's prompt-module override, if any (PromptModule is already wire-compatible). */
    val promptModules: List<PromptModule>? = null,

    // ── 13.6 新增 per-character 私有段（随角色 FK 级联） ──
    val pet: CharacterPetExport? = null,
    /** 💰 角色钱包（per-character；与全局 userWallet 不同）。 */
    val wallet: CharacterWalletExport? = null,
    val schedules: List<ScheduleExport>? = null,
    val notificationTemplates: List<NotificationTemplateExport>? = null,
)

@Serializable
data class CharacterExport(
    val uuid: String,
    val name: String,
    val creationDate: Long,
    /** 旧明文 .json：头像 JPEG 的 Base64（含媒体时）。新 zip 格式留空，改用 [avatarArchiveKey]。 */
    val avatarData: String? = null,
    /** 新 zip 格式：头像在 zip 内的相对键（如 `media/avatars/<uuid>.jpg`）。 */
    val avatarArchiveKey: String? = null,
    /** 聊天壁纸在 zip 内的相对键（如 `media/wallpapers/<uuid>.jpg`·chunk1b·仿 [avatarArchiveKey]）。 */
    val chatWallpaperArchiveKey: String? = null,
    val systemPrompt: String = "",
    val personalityDescription: String = "",
    val gender: String = "",
    val birthday: Long? = null,
    val ageModeRaw: String = "growing",
    val fixedAge: Int = 0,
    val appearanceDescription: String = "",
    val occupation: String = "",
    val backstory: String = "",
    val speakingStyle: String = "",
    val catchphrases: String = "",
    val exampleDialogues: String = "",
    val initialInterests: String = "",
    val memorySummary: String = "",
    val previousMemorySummary: String = "",
    val offlineMeetingMemorySummary: String = "",
    val voiceIdentifier: String = "",
    val remoteVoiceID: String = "",
    val ttsEmotionRaw: String = "auto",
    val ttsSpeed: Double = 1.0,
    val ttsPitch: Int = 0,
    val lastMoodEmoji: String = "",
    val lastMoodText: String = "",
    val lastMoodColorName: String = "green",
    val firstMessageDate: Long? = null,
    val streakCount: Int = 0,
    val lastChatDate: Long? = null,
    val personalitySpectrumJSON: String = "",
    val relationshipQualityJSON: String = "",
    // 成长原型校准（图纸 §3.4）：新列对称往返。老备份无此字段 → null 兜底 → 导入后 recalibrateAll 补算。
    val relationshipArchetypeId: String? = null,
    val moodHistoryJSON: String = "",
    val dynamicInterestsJSON: String = "",
    val growthLogJSON: String = "",
    val growthMetadataJSON: String = "",
    val structuredMemoryJSON: String = "",
    val structuredMemoryMetadataJSON: String = "",
    val previousStructuredMemoryJSON: String = "",
    val relationshipMessageCount: Int = 0,
    val lastRelationshipAnalysisDate: Long? = null,
    val cityName: String? = null,
    val cityLatitude: Double? = null,
    val cityLongitude: Double? = null,
    val offlineThemeColorHex: String? = null,
    // 世界系统「加入世界」态 + 住址（W1）。默认值保证旧备份可恢复（老备份缺字段 → 不加入 + 家乡城）。
    val joinedWorld: Boolean = false,
    val worldHomeCityId: String = "city_yunye",
    val worldJoinedAt: Long? = null,
    /** 朋友圈消化水位线（记忆改造一期·图纸 §3.5-B）。老备份缺字段 → 0 = 从未消化（收集时视作 now−7 天起步）。 */
    val momentsDigestedUntilMillis: Long = 0,
    // 活人感内核·卷一《人设编译器》四列对称往返（图纸 §表3）。老备份缺字段 → "" = 从未编译过：
    // 锚点走解码访问器兜底（本性 == 现在），其余三列回落默认值，不崩不清零。
    val personalityAnchorJSON: String = "",
    val personaCompileMetaJSON: String = "",
    val personaGainsJSON: String = "",
    val personaOperatorsJSON: String = "",
    /** 活人感内核·卷二《正负双压》关系压强列（图纸 §表3）。老备份缺字段 → "" = 访问器按净额播种，不崩不清零。 */
    val relationshipPressureJSON: String = "",
    /** 活人感内核·卷三《场内核与渲染收编》四场列（图纸 §表3）。老备份缺字段 → "" = 访问器回默认场，不崩不清零。 */
    val affectFieldJSON: String = "",
    /** 活人感内核·卷四《意图队列 + 性格复盘》意图列（图纸 §3.2 · E22）。老备份缺字段 → "" = 访问器回默认队列，不崩不清零。 */
    val intentQueueJSON: String = "",
    /** 「我们的日子」一次性回填完成标记（卷一图纸 §2.2 · 总图纸 §3.2）。老备份缺字段 → null = 未回填 → 导入后下次 catch-up 走一次回填（E14）。 */
    val ourDaysBackfilledAt: Long? = null,
)

@Serializable
data class ConversationExport(
    val uuid: String,
    val title: String = "",
    val creationDate: Long,
    val lastSummarizedMessageDate: Long? = null,
    val lastMemorySummarySuccessDate: Long? = null,
    val lastMemorySummaryFailureDate: Long? = null,
    val lastMemorySummaryAttemptDate: Long? = null,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isReservedForNotifications: Boolean = false,
    val lastReadDate: Long? = null,
    val lastMessageDate: Long? = null,
    val lastMessagePreview: String = "",
    val lastMessageRole: String = "",
    val moodEmoji: String = "",
    val moodText: String = "",
    val moodColorName: String = "green",
    val cachedUnreadCount: Int = 0,
    val voiceRoundsSinceLastVoice: Int = 0,
    val voiceNextThreshold: Int = 0,
    // 13.6 补齐：线下见面状态（M16，2.6 备份写成时这些列还没加 → 当时漏备）。
    val isInOfflineMode: Boolean = false,
    val currentOfflineSessionId: String? = null,
    val currentSceneProgress: String = "",
    val pendingOfflineSummarySessionId: String? = null,
    val pendingOfflineSummaryFailCount: Int = 0,
    val pendingOfflineSummaryLastAttemptAt: Long? = null,
    val offlineSummaryFallbackSessionIds: String = "",
    // 记忆改造二期·部件⑤ 场内前情提要（默认值兜底旧包·无此三字段 → 等价无提要）。
    val inSceneRecapText: String = "",
    val inSceneRecapSessionKey: String = "",
    val inSceneRecapUntilMillis: Long = 0,
    val messages: List<MessageExport>? = null,
)

@Serializable
data class MessageExport(
    val messageUUID: String,
    val role: String = "user",
    val content: String = "",
    val timestamp: Long,
    val isVoiceMessage: Boolean = false,
    val isPartOfVoiceCall: Boolean = false,
    /** 旧字段（绝对路径，换机失效；保留供旧 .json 解码）。新 zip 改用 [audioArchiveKey]。 */
    val audioRelativePath: String? = null,
    val audioDuration: Double? = null,
    val imageRelativePath: String? = null,
    val imageThumbnailRelativePath: String? = null,
    val mediaMemorySummary: String = "",
    val isContentRevealed: Boolean = true,
    val isHeldForDelivery: Boolean = false,
    val scheduledDeliveryDate: Long? = null,
    val quotedMessageUUID: String? = null,
    val quotedContent: String? = null,
    val quotedSenderRole: String? = null,
    val emotionTag: String? = null,
    val isPetMessage: Boolean = false,
    val isOfflineMode: Boolean = false,
    val offlineSessionId: String? = null,
    val messageKindRaw: String = "plain_text",
    /** 12.3：消息向量 embedding 的 Base64（encodeDefaults=false → 无 embedding 时省略，旧备份缺此字段=null）。 */
    val embedding: String? = null,
    // 13.6 新 zip 媒体键（字节在 zip 的 media/ 下；含媒体时填，否则 null）。
    val audioArchiveKey: String? = null,
    val imageArchiveKey: String? = null,
    val imageThumbnailArchiveKey: String? = null,
)

@Serializable
data class MilestoneExport(
    val relationshipName: String = "",
    val establishedDate: Long,
    val reason: String = "初始设定",
    val triggerTypeRaw: String = "userAdvance",
    val phase: String? = null,
)

// ─────────────────────────────── 13.6 新增 per-character 段 ───────────────────────────────

@Serializable
data class CharacterPetExport(
    val uuid: String,
    val name: String = "",
    val speciesRaw: String = "cat",
    val isHiddenSpecies: Boolean = false,
    val personalityTypeRaw: String = "lively",
    val adoptedDate: Long = 0L,
    val hunger: Int = 0,
    val cleanliness: Int = 100,
    val happiness: Int = 80,
    val health: Int = 100,
    val growthStageRaw: String = "baby",
    val growthPoints: Int = 0,
    val totalInteractions: Int = 0,
    val lastFedDate: Long? = null,
    val lastCleanedDate: Long? = null,
    val lastPlayedDate: Long? = null,
    val lastInteractionDate: Long? = null,
    val neglectPhaseRaw: String = "none",
    val petGrowthLogJson: String = "",
    val petMetadataJson: String = "",
)

/** 💰 角色钱包。所有金额为 Int 金币（无小数/舍入）。导入据快照直接写余额，绝不重放流水。 */
@Serializable
data class CharacterWalletExport(
    val uuid: String,
    val createdAt: Long = 0L,
    val coinBalance: Int = 0,
    val totalEarned: Int = 0,
    val totalSpent: Int = 0,
    val monthlySalary: Int = 0,
    val salaryInferred: Boolean = false,
    val salaryDay: Int = 15,
    val lastSalaryDate: Long? = null,
    val lastEconomicScanDate: Long? = null,
    val lastProactiveGiftDate: Long? = null,
    val affinityFromUser: Int = 0,
    val affinityToUser: Int = 0,
)

@Serializable
data class ScheduleExport(
    val uuid: String,
    val date: Long,
    val cityName: String? = null,
    val weatherCondition: String? = null,
    val weatherEmoji: String? = null,
    val temperatureHigh: Double? = null,
    val temperatureLow: Double? = null,
    val timezoneIdentifier: String? = null,
    val generatedAt: Long? = null,
    val lastWeatherCheckAt: Long? = null,
    val isBackfilled: Boolean = false,
    val events: List<ScheduleEventExport>? = null,
)

@Serializable
data class ScheduleEventExport(
    val uuid: String,
    val startTime: Long,
    val endTime: Long,
    val periodLabel: String = "",
    val location: String = "",
    val activity: String = "",
    val moodEmoji: String = "",
    val moodText: String? = null,
    val innerThought: String? = null,
    val isPhoneAvailable: Boolean = true,
    val eventTypeRaw: String = "planned",
    val relatedCharacterNames: String? = null,
    val relatedMessageUUID: String? = null,
    val sourceRaw: String = "generated",
    val sortOrder: Int = 0,
)

@Serializable
data class NotificationTemplateExport(
    val id: String,
    val category: String = "",
    val content: String = "",
    val isUsed: Boolean = false,
    val createdAt: Long = 0L,
)

// ─────────────────────────────── 13.6 顶层全局段 ───────────────────────────────

/** 💰 全局用户钱包（单例）。 */
@Serializable
data class UserWalletExport(
    val uuid: String,
    val createdAt: Long = 0L,
    val coinBalance: Int = 100,
    val totalEarned: Int = 0,
    val totalSpent: Int = 0,
)

@Serializable
data class UserProfileExport(
    val nickname: String = "",
    val bio: String = "",
    /** 头像在 zip 内的相对键（含媒体时）。 */
    val avatarArchiveKey: String? = null,
    val cityName: String? = null,
    val cityLatitude: Double? = null,
    val cityLongitude: Double? = null,
    val birthday: Long? = null,
    /** 「希望 TA 怎么待你」相处偏好（四小件·2026-07-16）。默认 "" = 老备份包（无此字段）导入后的兜底值。 */
    val companionPreference: String = "",
)

@Serializable
data class MomentsExport(
    val posts: List<MomentPostExport>? = null,
    val comments: List<MomentCommentExport>? = null,
    val likes: List<MomentLikeExport>? = null,
)

@Serializable
data class MomentPostExport(
    val uuid: String,
    val content: String = "",
    val timestamp: Long = 0L,
    val authorTypeRaw: String = "user",
    val characterUuid: String? = null,
    val isAutoGenerated: Boolean = false,
    val isSoftDeleted: Boolean = false,
    val triggerTypeRaw: String = "auto_draft",
    val relatedGiftId: String? = null,
    /** 多图：每张图在 zip 内的相对键（含媒体时）。 */
    val imageArchiveKeys: List<String>? = null,
)

@Serializable
data class MomentCommentExport(
    val uuid: String,
    val content: String = "",
    val timestamp: Long = 0L,
    val authorTypeRaw: String = "user",
    val characterUuid: String? = null,
    val replyToName: String? = null,
    /** 用真实 uuid 关联（非 iOS 的数组下标）：所属帖 + 父评论。 */
    val postUuid: String? = null,
    val parentCommentUuid: String? = null,
)

@Serializable
data class MomentLikeExport(
    val timestamp: Long = 0L,
    val authorTypeRaw: String = "user",
    val characterUuid: String? = null,
    val postUuid: String? = null,
)

@Serializable
data class DiaryEntryExport(
    val uuid: String,
    val content: String = "",
    val timestamp: Long = 0L,
    val moodEmoji: String? = null,
    val moodText: String? = null,
    val isAutoGenerated: Boolean = false,
    val isDraft: Boolean = false,
    val isPetDiary: Boolean = false,
    val petSpeciesRaw: String? = null,
    val visibilityRaw: String = "openToAI",
    val triggerTypeRaw: String = "auto_draft",
    val relatedGiftId: String? = null,
    /** 交换日记作者角色 uuid（R6-3②·缺则恢复后 TA 的信永久变用户日记）；null = 用户日记。老备份缺字段→null 兜底。 */
    val authorCharacterUuid: String? = null,
    /** 交换日记作者名快照（R6-3①·角色被删仍可署名）；老备份缺字段→null 兜底。 */
    val authorNameSnapshot: String? = null,
    /** 交换日记回流消化标记（记忆改造一期·图纸 §3.5-C）；老备份缺字段→null=未消化兜底。 */
    val digestedAtMillis: Long? = null,
    /** 多图在 zip 内的相对键（含媒体时）。 */
    val imageArchiveKeys: List<String>? = null,
    /** 该日记下的评论（嵌套，随日记一起往返·R6-3② 补 parentCommentId/isFromUser 令线程/用户参与不塌平）。 */
    val comments: List<DiaryCommentExport>? = null,
    /** 该日记下 AI 角色的点赞（R6-3② 新增·嵌套随日记往返·恢复经 FK CASCADE 幂等）。 */
    val reactions: List<DiaryReactionExport>? = null,
)

@Serializable
data class DiaryCommentExport(
    val id: String,
    val content: String = "",
    val timestamp: Long = 0L,
    val characterUuid: String? = null,
    /** 回复线程根 id（R6-3②）；null = 顶层评论。老备份缺字段→null=顶层（向后兼容）。 */
    val parentCommentId: String? = null,
    /** true = 用户写的评论/回复（R6-3②）；老备份缺字段→false=角色（向后兼容）。 */
    val isFromUser: Boolean = false,
)

/** 日记点赞（R3 角色点赞·R6-3② 随备份往返）。entryUuid 由嵌套的父日记隐含，恢复时回挂。 */
@Serializable
data class DiaryReactionExport(
    val id: String,
    val characterUuid: String = "",
    val emoji: String = "",
    val timestamp: Long = 0L,
)

/** 月度回顾（R5·R6-3② 顶层段）。绝对快照原样往返（monthStartMillis 唯一 → 恢复幂等）。 */
@Serializable
data class MonthlyReviewExport(
    val uuid: String,
    val monthStartMillis: Long = 0L,
    val content: String = "",
    val moodCountsJson: String = "",
    val generatedAt: Long = 0L,
)

@Serializable
data class StoryExport(
    val id: String,
    val title: String = "",
    val genre: String = "",
    val coverColorScheme: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val worldSetting: String? = null,
    val plotDirection: String? = null,
    val writingStyle: String = "",
    val chapterLengthPreference: Int = 1500,
    val maxChapters: Int? = null,
    val autoExtendCount: Int = 0,
    val chatInfluenceWeight: String = "medium",
    val narrativePerson: String = "second",
    val updateMode: String = "free",
    val unlockHour: Int = 20,
    val unlockMinute: Int = 0,
    val status: String = "serializing",
    val storySummary: String? = null,
    val currentArc: String? = null,
    val characterStates: String? = null,
    val openThreads: String? = null,
    val storyBible: String? = null,
    val lastCompressedAtChapter: Int? = null,
    val lastBibleCompressedAtChapter: Int? = null,
    val storyOutline: String? = null,
    val pendingChapterBeats: String? = null,
    /** 章级节拍是否被用户在导演台改过（卷一·老备份缺字段→false=AI 预排，向后兼容）。 */
    val pendingBeatsUserEdited: Boolean = false,
    val currentArcStartChapter: Int? = null,
    /** 弧线简史（卷二 B2·老备份缺字段→null=还没写完过弧线，向后兼容）。 */
    val arcHistory: String? = null,
    /** 关系史账本（故事二期卷一·老备份缺字段→null=还没有已确立的关系事实）。 */
    val intimacyLedger: String? = null,
    /** 章末场景状态快照（卷一·老备份缺字段→null=无快照）。 */
    val sceneState: String? = null,
    /** 场景台账（卷一·老备份缺字段→null=还没写过重点场景）。 */
    val sceneLedger: String? = null,
    val customPromptsJson: String? = null,
    val requestedEndingType: String? = null,
    val requestedEndingDetail: String? = null,
    val rewriteInstruction: String? = null,
    /** 重写期旧稿接力棒（C3·老备份缺字段→null=没有进行中的重写，向后兼容）。 */
    val pendingRewriteDraftJson: String? = null,
    /** 终章弧收尾计划类型（卷二 J1·老备份缺字段→null=无收尾计划，向后兼容）。 */
    val finaleEndingType: String? = null,
    /** 终章弧收尾方向（仅 custom 有值·卷二 J1）。 */
    val finaleEndingDetail: String? = null,
    val finalEndingType: String? = null,
    val cachedChapterCount: Int = 0,
    val cachedLatestChapterNumber: Int? = null,
    val cachedLatestChapterTitle: String? = null,
    val cachedLatestChapterCreatedAt: Long? = null,
    val cachedHasPendingChoice: Boolean = false,
    val chapters: List<StoryChapterExport>? = null,
    val characterRoles: List<StoryCharacterRoleExport>? = null,
)

@Serializable
data class StoryChapterExport(
    val id: String,
    val chapterNumber: Int = 0,
    val title: String = "",
    val teaser: String? = null,
    val createdAt: Long = 0L,
    val content: String = "",
    val mood: String = "peaceful",
    val scenes: String? = null,
    val hasChoice: Boolean = false,
    val choicePrompt: String? = null,
    val choiceOptions: String? = null,
    val userChoice: String? = null,
    val choiceMadeAt: Long? = null,
    val chapterSummary: String? = null,
    val unlockAt: Long? = null,
    /** AI 自标结局的印（ST11）；老备份无此字段 → 缺省 false。 */
    val aiSuggestedEnding: Boolean = false,
    /** 「上一版」单槽 JSON（C3·旧稿是用户资产必须进备份）；老备份无此字段 → 缺省 null = 无旧稿可回翻。 */
    val previousDraftJson: String? = null,
    /** 读者三档快评 1/2/3（故事二期卷一）；老备份无此字段 → 缺省 null = 未评。 */
    val userRating: Int? = null,
)

@Serializable
data class StoryCharacterRoleExport(
    val id: String,
    val roleName: String = "",
    val roleType: String = "supporting",
    val roleDescription: String? = null,
    val isUserRole: Boolean = false,
    /** 关联的 AI 角色 uuid；null = 纯故事角色。 */
    val characterId: String? = null,
    /** 私下反差（故事二期卷一·用户创作设定必须进备份）；老备份无此字段 → 缺省 null。 */
    val intimatePersona: String? = null,
)

/** 💰 礼物记录（pricePaid Int 金币快照；纯历史，导入不动钱包余额）。 */
@Serializable
data class GiftRecordExport(
    val uuid: String,
    val timestamp: Long = 0L,
    val senderType: String = "user",
    val senderCharacterUUID: String = "",
    val receiverType: String = "character",
    val receiverCharacterUUID: String = "",
    val giftItemId: String = "",
    val pricePaid: Int = 0,
    val isDIY: Boolean = false,
    val diyTitle: String = "",
    val diyContent: String = "",
    /** DIY 礼物图在 zip 内的相对键（无条件带——对齐 iOS diyImageData 不受 includeMedia 门控）。 */
    val diyImageArchiveKey: String? = null,
    val context: String = "random",
    val senderMessage: String = "",
    val reactionText: String = "",
    val reactionMoodEmoji: String = "",
    val affinityGain: Int = 0,
    val relationshipImpactJSON: String = "",
)

/** 💰 红包记录（amount Int 金币托管快照；全状态机往返，导入不重复扣/加币）。 */
@Serializable
data class RedPacketRecordExport(
    val uuid: String,
    val messageUuid: String = "",
    val conversationUuid: String = "",
    val senderType: String = "user",
    val senderCharacterUUID: String = "",
    val receiverType: String = "character",
    val receiverCharacterUUID: String = "",
    val amount: Int = 0,
    val blessingText: String = "",
    val festivalId: String? = null,
    val status: String = "pending",
    val createdAt: Long = 0L,
    val expiresAt: Long = 0L,
    val resolvedAt: Long? = null,
    val rejectionReason: String = "",
    val notifiedExpiringSoon: Boolean = false,
)

/** 未来约定见面记录（全状态机往返；无钱路，confirmed 恢复后由打开会话/冷启扫描重排到点通知）。 */
@Serializable
data class MeetingAppointmentExport(
    val uuid: String,
    val characterUuid: String = "",
    val conversationUuid: String = "",
    val status: String = "proposed",
    val proposedBy: String = "character",
    val source: String = "extraction",
    val scheduledAt: Long = 0L,
    val timeGranularity: String = "exact",
    val rawWhenText: String = "",
    val location: String = "",
    val activity: String = "",
    val invitationText: String = "",
    val tensionHint: String = "",
    val hiddenTensionSeed: String = "",
    val createdAt: Long = 0L,
    val confirmedAt: Long? = null,
    val outcomeAt: Long? = null,
    val honoredSessionId: String? = null,
    val lastReminderScheduledAt: Long? = null,
)

@Serializable
data class CustomStickerExport(
    val stickerUuid: String,
    val name: String = "",
    val semanticDescription: String = "",
    val isAnimated: Boolean = false,
    /** 贴纸图在 zip 内的相对键（含媒体时）。 */
    val imageArchiveKey: String? = null,
    val createdAt: Long = 0L,
    val usageCount: Int = 0,
)

@Serializable
data class RedeemCodeUsageExport(
    val uuid: String,
    val codeHash: String = "",
    val redeemedAt: Long = 0L,
    val amount: Int = 0,
)

/**
 * 💰 金币流水台账导出（R2）。十字段全量保真——[relatedEntityId] 是发薪/房租/送礼/兑换/进化等幂等 key，
 * [balanceAfter] 是交易后余额快照（账本 UI 免累加）；恢复按原 uuid REPLACE，不动钱包余额。
 */
@Serializable
data class CurrencyTransactionExport(
    val uuid: String,
    val timestamp: Long = 0L,
    val ownerTypeRaw: String = "user",
    val characterUuid: String = "",
    val kindRaw: String = "earn",
    val categoryRaw: String = "other",
    val amount: Int = 0,
    val balanceAfter: Int = 0,
    val relatedEntityId: String? = null,
    val note: String = "",
)
