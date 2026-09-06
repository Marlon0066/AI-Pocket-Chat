package com.situ.aichat.data.model

import kotlinx.serialization.Serializable

/**
 * Read-model snapshot of the iOS `AppSettings` @Model — the fields the prompt pipeline (M02) reads.
 *
 * Defaults mirror the iOS source exactly (`AppSettings.swift`). PromptBuilder consumes this immutably
 * during a build. Persistence (DataStore / Room) and the settings UI fill these in later; until then a
 * default instance produces an iOS-faithful online-chat prompt (empty module JSON → default modules).
 *
 * `@Serializable` so the whole settings snapshot round-trips through the backup as the "全局应用设置/偏好"
 * segment (13.6). Only constructor properties serialize (the computed `sanitized*` vals are ignored).
 * Lenient decode (`ignoreUnknownKeys` + `encodeDefaults=false`) tolerates version skew across backups.
 */
@Serializable
data class AppSettings(
    // 记忆 / 截断
    val shortTermMemoryLength: Int = DEFAULT_SHORT_TERM_MEMORY_LENGTH,
    val autoSummarizeInterval: Int = 10,        // 摘要触发下限（轮），0=关
    val memorySummaryMaxLength: Int = DEFAULT_MEMORY_SUMMARY_MAX_LENGTH, // 摘要最大字数
    /** 两次记忆总结之间的最小间隔（分钟）。0 = 不限（时间轨恒就绪，攒够轮数即总结）。 */
    val memorySummaryCooldownMinutes: Int = DEFAULT_MEMORY_SUMMARY_COOLDOWN_MINUTES,
    /**
     * 智能渐进压缩（2026-06-20 新增）。开=记忆接近上限时按四级渐进话术逐级压缩(合并日期/每天留一句/不整段删);
     * 关=只给一句硬字数要求,如何取舍交给 LLM。**默认 false（关）**——有意改变默认行为(此前等于恒「开」),
     * 老用户升级后默认走朴素硬字数要求。仅切换提取提示词的压缩话术,**不**在代码层截断存储(两态都照存 LLM 实际返回长度)。
     * 用户自定义了 [memoryExtractionPrompt] 时开关让位(UI 置灰+逻辑走 NONE)。
     */
    val progressiveCompressionEnabled: Boolean = false,
    val memoryExtractionPrompt: String = "",    // 自定义抽取 prompt（空=默认模板）
    val structuredMemoryInterval: Int = 30,     // 结构化记忆触发下限（轮），0=关
    // 见面记忆字数预算（梦剧场 B 部·§3.4）：注入文本的字数上限（超出时最早的完整摘要降为存档行·由 Renderer 承担）。
    // 默认 1200。（旧 meetingRetentionDays「原文保留期」已随原文通道退役删除·§3.6。）
    val meetingMemoryMaxLength: Int = 1200,
    // 普通聊天注入「最近 N 次见面完整摘要」的 N（梦剧场 B 部·契约 §B2 决议 B-2·图纸 §3.4）：默认 3·范围 1–10；
    // 更早的合并为一行存档；meetingMemoryMaxLength 语义改为注入文本字数预算（超出时最早完整摘要降为存档行）。
    val meetingMemoryInjectCount: Int = 3,
    // 见面后余温消息开关（梦剧场 B 部·涟漪①·图纸 §3.10）：开 → 见面结束几小时后 TA 主动发一条回味见面的短消息。默认 true。
    val offlineAfterglowEnabled: Boolean = true,
    val memoryInjectionPrompt: String = "",
    val moodHistoryMaxCount: Int = 200,

    /** 向量检索余弦阈值（整数百分比 0-100，0=关闭）。对齐 iOS：默认 65（= 0.65）。 */
    val vectorSearchThreshold: Int = 65,

    // 世界书触发设置（WB7c·契约 FABLE5_WORLDBOOK_PROPOSAL.md §12.7；默认值照 ST 全局默认 + D4 字符预算）
    /** 世界书扫描深度：回看最近 N 条消息找关键词。 */
    val worldInfoScanDepth: Int = 2,
    /** 世界书篇幅预算（字符·D4 拍板默认 6000 字）。 */
    val worldInfoBudgetChars: Int = 6000,
    /** 世界书递归扫描（UI 叫「设定联动」）。 */
    val worldInfoRecursiveScan: Boolean = false,
    /** 递归轮数上限（0 = 不限）。 */
    val worldInfoMaxRecursionSteps: Int = 0,
    /** 插入策略（存 WorldInfoInsertionStrategy 名字；CHARACTER_FIRST = ST/V2 卡规范默认）。 */
    val worldInfoInsertionStrategy: String = "CHARACTER_FIRST",
    /** 世界书关键词大小写敏感（全局默认关）。 */
    val worldInfoCaseSensitive: Boolean = false,
    /** 世界书整词匹配（中文场景必须保持关·契约 §5-1）。 */
    val worldInfoMatchWholeWords: Boolean = false,

    // 世界系统（W1·契约 FABLE5_WORLD_SYSTEM_PROPOSAL.md；≠ 上方「世界书」）。逻辑消费在 W2+，本块只存储。
    /** 鲜活度三档（省/标准/豪华·契约 §7.A）：合法值 [WORLD_VIVIDNESS_LITE]/[WORLD_VIVIDNESS_STANDARD]/[WORLD_VIVIDNESS_RICH]，默认标准。 */
    val worldVividnessTier: String = WORLD_VIVIDNESS_STANDARD,
    /** 角色↔角色关系系统开关（契约 §8），默认开。 */
    val worldRelationshipsEnabled: Boolean = true,
    /** 角色↔角色恋爱线开关（契约 §8），默认关。 */
    val worldRomanceEnabled: Boolean = false,
    /** 世界通知三档（静默/克制/全部·契约 §7.A）：合法值 [WORLD_NOTIFICATION_SILENT]/[WORLD_NOTIFICATION_GENTLE]/[WORLD_NOTIFICATION_ALL]，默认克制。 */
    val worldNotificationTier: String = WORLD_NOTIFICATION_GENTLE,
    /** 世界首启轻三步是否已走过（W13·true 后永不再弹·图纸 §3.4/§3.5）。 */
    val worldOnboardingDone: Boolean = false,

    // 家的蛋巢之约（W12.5·决策 42）：恒一巢单蛋 = 单值天然限流；空 uuid = 无之约。随 applyBackupSettings 免费进备份（W14 回归零负担）。
    /** 之约角色 uuid（空 = 蛋巢空着）。巢态其余全为派生（该角色是否已有宠物 + canAdopt），零新表。 */
    val eggNestPactCharacterUuid: String = "",
    /** 定约时刻（epoch millis；0 = 无之约）。仅存档，巢态派生不读它。 */
    val eggNestPactAt: Long = 0L,

    // 创造力（温度，14.3b）——全局，影响主聊天 + 语音通话；后台 service 各自硬编码温度不受此影响（1:1 iOS）。
    val llmTemperature: Double = DEFAULT_LLM_TEMPERATURE,

    /** 故事正章创作温度（卷一 V1）——只影响故事创作与截断续写；思考模型配置不发温度（LlmClient 保险丝）不受此影响。UI 入口随卷三。 */
    val storyCreationTemperature: Double = DEFAULT_STORY_CREATION_TEMPERATURE,

    /**
     * 全局「文字忌口」（2026-07-30）。**三态语义（锁定·不许折叠）**：
     * - `null` = 从未设置 → 用内置默认 [com.situ.aichat.story.StoryWritingTechniques.bannedExpressionsBaseline]
     * - `""` = 用户主动清空 → **不注入任何忌口段**（提案 §6.2 过审「清空允许、不硬拦」）
     * - 其他 = 用户自定义文本，原样注入
     *
     * 后两态必须分开：任何地方用 `?: default` 把空串也回退成默认，用户就永远删不掉忌口。
     * 取值单源 = [com.situ.aichat.story.StoryPromptSections.resolvedBannedExpressions]（本故事 › 全局 › 默认）。
     */
    val storyBannedExpressions: String? = null,

    /**
     * 全局「场面节拍」（故事二期卷一·提案 §3.1）。**三态语义（锁定·不许折叠）**：
     * - `null` = 从未设置 → 用出厂默认 [com.situ.aichat.story.StoryCraftSections.SCENE_BEATS_DEFAULT]
     * - `""` = 用户主动清空 → **全局关掉主节拍段**（本书没覆盖时不注入）
     * - 其他 = 用户自定义文本，原样注入
     *
     * 取值单源 = [com.situ.aichat.story.StoryCraftSections.resolvedSceneBeats]（本书 › 全局 › 出厂默认）。
     * 编辑入口（App 设置「故事」组）归卷四。
     */
    val storySceneBeats: String? = null,

    /**
     * 全局「读者口味画像」（故事二期卷一·提案 §5.3）。三态同 [storySceneBeats]，但**无出厂默认**：
     * `null`（从未设置）与 `""`（主动清空）在本书也没覆盖时同样落到「不注入」。
     * 取值单源 = [com.situ.aichat.story.StoryCraftSections.resolvedTasteProfile]。编辑入口归卷四。
     */
    val storyTasteProfile: String? = null,

    // 回复分条范围（bounds 1..15，默认 2..6）
    val replySegmentMin: Int = DEFAULT_REPLY_SEGMENT_MIN,
    val replySegmentMax: Int = DEFAULT_REPLY_SEGMENT_MAX,

    // 语音回复轮次范围（P10.1c，bounds 1..20，默认 3..5）：每隔随机 N 轮把一条文字回复改投语音。
    val voiceReplyRoundMin: Int = DEFAULT_VOICE_REPLY_ROUND_MIN,
    val voiceReplyRoundMax: Int = DEFAULT_VOICE_REPLY_ROUND_MAX,

    /**
     * 语音通话打断灵敏度（P10.1d）：存的是能量阈值（1:1 iOS `userConfiguredThreshold` 默认 0.15，范围
     * [0.05, 0.40]）。打断检测/状态机（10.1e）读 [sanitizedVoiceCallInterruptThreshold]；滑块 UI 在 10.1g
     * （滑块 0.05..0.40，存值 = 0.45 − 滑块），暂无 DataStore 恒默认。
     */
    val voiceCallInterruptThreshold: Float = DEFAULT_VOICE_CALL_INTERRUPT_THRESHOLD,

    // 系统开关（默认 ON）
    val growthSystemEnabled: Boolean = true,
    val scheduleSystemEnabled: Boolean = true,
    /** 货币/经济系统总开关（M10，默认开；关闭后停发薪/经济事件，红包过期扫描/兑换码不受此控）。设置 UI 在 P12 接 DataStore。 */
    val currencySystemEnabled: Boolean = true,
    /** 角色主动送礼开关（P9.2c，默认开=iOS `characterProactiveGiftEnabled ?? true`；受 [currencySystemEnabled] 二级门控）。设置 UI 在 9.2d/P12 接 DataStore。 */
    val characterProactiveGiftEnabled: Boolean = true,

    /** 日程跨角色互动级别（P5.1；0=关 1=偶尔约20% 2=经常约50% 3=频繁约80%）。对齐 iOS 默认 1。设置 UI 在 P12。 */
    val crossCharacterLevel: Int = 1,

    /** 日历感知（P5.3a）：是否把设备日历事件注入提示词。对齐 iOS `calendarIntegrationEnabled` 默认 true；实际仍需用户授予 READ_CALENDAR。 */
    val calendarIntegrationEnabled: Boolean = true,

    /** 日历操作确认（P5.3b）：AI 写日历事件前是否弹确认卡片。对齐 iOS `calendarActionConfirmation` 默认 true。设置 UI 在 P12。 */
    val calendarActionConfirmation: Boolean = true,

    /**
     * 日历提醒方式（P6.3，安卓 decision② 新增；iOS 无此设置，总是两者都发）。见
     * [com.situ.aichat.notification.CalendarReminderMode]：system=仅系统 15min 提醒 / character=仅 app 30min
     * 角色通知 / both=两者都发。默认 "both" = 1:1 iOS 行为。
     */
    val calendarReminderMode: String = "both",
    val petSystemEnabled: Boolean = true,
    // 宠物养成平衡值（M11；默认对齐 iOS AppSettings.swift；设置 UI 在 P12，暂恒默认无 DataStore）
    val petGrowthPointsPerFeed: Int = 5,
    val petGrowthPointsPerClean: Int = 3,
    val petGrowthPointsPerPlay: Int = 8,
    val petGrowthPointsPerChat: Int = 1,
    val petHungerDecayPerHour: Int = 2,
    val petCleanlinessDecayPerHour: Int = 1,
    val petHappinessDecayPerHour: Int = 1,
    /** 每日自动生成宠物视角日记（M11，1:1 iOS）。默认 **false**（对齐 iOS AppSettings.swift:176）；开启后回前台首次检查生成。 */
    val petDiaryAutoGenerateEnabled: Boolean = false,
    /** 2026-07-11 上下文布局改造拍板:默认关——表情包教学块(~1500 字)是上下文最大单一指令块;未手动设置过的用户随默认变关。 */
    val characterCanSendStickersEnabled: Boolean = false,
    val characterCanInitiateOfflineMeeting: Boolean = true,

    // 线下模式细腻程度（M16；1:1 iOS AppSettings.swift。读侧 [com.situ.aichat.prompt.PromptBuilder].resolveOfflinePreset
    // → [com.situ.aichat.offline.OfflineNarrativePreset].resolve。持久化 + 设置 UI 在 10.2f；暂恒默认无 DataStore）
    /** 线下叙事细腻程度 raw（"plain"/"normal"/"detailed"/"custom"，默认 plain；未知值回退 plain = iOS）。 */
    val offlineNarrativeDetailRaw: String = "plain",
    /** custom 档：写作风格指导（非空 → 替代内置风格规则 rule12/rule13）。 */
    val offlineCustomStylePrompt: String = "",
    /** custom 档：每轮叙事指令（每行一条，随机轮换 → narrativeTechniquePool）。 */
    val offlineCustomDirectivePrompt: String = "",
    /** custom 档：情绪底色（每行一条，随机轮换 → emotionalRegisterPool）。 */
    val offlineCustomEmotionPrompt: String = "",
    /** 沉浸输入开关：true → 见面中用四步标签输入替换普通输入栏（默认 false = iOS AppSettings.swift:279）。 */
    val offlineImmersiveInputEnabled: Boolean = false,
    /** 线下沉浸背景样式 raw（"particle"/"solidColor"/"customImage"，默认 particle = iOS :282）。 */
    val offlineBackgroundStyleRaw: String = "particle",
    /** 线下沉浸背景纯色 hex（纯色样式用 + 全局自定义图文件名键；默认空 = iOS :285）。 */
    val offlineBackgroundColor: String = "",
    /** 线下沉浸粒子风格 raw（"stars"/"firefly"/"dust"，默认 stars = iOS :288）。 */
    val offlineParticleStyleRaw: String = "stars",

    // 成长 / 关系分析（M14；默认对齐 iOS AppSettings.swift）
    val growthAnalysisInterval: Int = 30,                // 成长分析触发下限（轮），0=关
    val growthLogMaxCount: Int = 100,                    // 成长日志保留上限
    val interestCooldownDays: Int = 14,                  // 兴趣冷却天数
    val relationshipAutoAdvanceEnabled: Boolean = true,  // 关系自动评估（4.2b 用）

    // 聊天动效（P1-5·批5）
    /** 消息情绪动画：角色消息出现时按 mood emoji 播放入场动画；同一开关亦门控线下沉浸块入场（=iOS OfflineModeView.swift:144）。1:1 iOS AppSettings.swift:122，默认开。 */
    val emotionAnimationEnabled: Boolean = true,

    // 口吻（活人感一期 P1）
    /** 自然短句口吻：开 → 回复风格块追加「像手机打字那样说话」全局规则（pb_style_l3）；关 → 风格块与旧值逐字节一致。默认开，书面风角色可关。 */
    val textingToneEnabled: Boolean = true,

    // 主动消息通知（P6.1c）
    /** 全局主动消息通知开关（关 = 不为任何角色调度续火花 / 主动消息）。默认开（实际仍受系统 POST_NOTIFICATIONS 授权约束）。 */
    val notificationsEnabled: Boolean = true,
    /** 角色经济动态通知三档 raw（"detailed"=含金额 / "brief"=不含金额[默认] / "off"=关）。见 [com.situ.aichat.notification.EconomyNotificationTier]（P1-40·安卓超越 iOS：iOS 对经济事件零通知）。 */
    val economyNotificationTier: String = "brief",
    /** 关系里程碑庆祝通知开关（P1-33·安卓超越 iOS：iOS 自动评估路径零通知）。默认开；关 = 全静默（=iOS 原生行为）。 */
    val milestoneNotificationEnabled: Boolean = true,
    /** 主动消息夜间免打扰开关（开 = 窗内到点的主动消息一律作废、不顺延补发）。默认开。 */
    val quietHoursEnabled: Boolean = true,
    /** 免打扰窗起点（当日分钟数，默认 1380=23:00）。跨午夜语义：start>end 表示跨天。 */
    val quietHoursStartMinute: Int = 1380,
    /** 免打扰窗终点（当日分钟数，默认 450=07:30 与 morning 候选窗起点衔接，见图纸 D-4）。 */
    val quietHoursEndMinute: Int = 450,

    // 忙碌时延迟回复（P6.2，1:1 iOS busyMode*）
    /** 角色处于忙碌日程（事件 isPhoneAvailable=false）时不立刻回复、空闲后统一回复。默认关（对齐 iOS）。 */
    /** ⚠️已废（忙碌延迟回复功能 2026-07-11 删除）：字段保留仅为备份/DataStore 线格式兼容,运行时零消费。 */
    val busyModeEnabled: Boolean = false,
    /** 最大延迟时间（分钟）：角色最晚多久必须回复，即使日程未结束。默认 30，最小钳到 5（对齐 iOS）。 */
    val busyModeMaxMinutes: Int = 30,

    // 日记自动生成（P7.1.2，1:1 iOS diary*）。宠物日记 → P8。
    /** 每日自动生成日记总开关。默认关（对齐 iOS）；开启后过设定时间首次回前台生成当天日记。 */
    val diaryAutoGenerateEnabled: Boolean = false,
    /** 自动生成时间 "HH:mm"，默认 "21:00"（对齐 iOS）。过此时刻当天才生成。 */
    val diaryAutoGenerateTime: String = "21:00",
    /** 自动生成的日记**直接发布**（跳过草稿·发布即走角色评论）。默认关=保留先润色的权利（R3·O3 锁定）。 */
    val diaryAutoPublishEnabled: Boolean = false,
    /** 交换日记固定笔友 uuid（R4·O1 锁定「兼有」）。空 = 自动（当天聊得最多的角色）；固定后不轮换。 */
    val diaryExchangePartnerUuid: String = "",

    // 日记角色评论（P7.1.3，1:1 iOS diary*）。
    /** AI 角色是否可评论用户发布的日记。默认开（对齐 iOS）。 */
    val diaryCharacterInteractionEnabled: Boolean = true,
    /** 允许评论的角色 uuid（逗号分隔，**空 = 全部角色**，对齐 iOS）。 */
    val diaryInteractingCharacterUUIDs: String = "",
    /** AI 评论延迟（分钟），默认 5，UI 滑块 1~15（对齐 iOS）。 */
    val diaryCommentDelay: Int = 5,
    /**
     * 日记本最后查看时刻（epoch millis；diary-1，对齐 iOS lastViewedDiaryDate）。0L = 从未看过（≈ distantPast），
     * 此时所有评论都算未读。枢纽日记卡未读角标 = 评论 timestamp > 本值的条数（严格 >）。进/出日记列表都会写为 now。
     */
    val lastViewedDiaryDate: Long = 0L,

    // 日记写作规则（2026-09-05·两套四项·图纸 docs/handoff/2026-09-05-日记提示词补角色卡与写作规则可编辑.md §3.5）。
    // 文本三项空串 = 用默认文案；字数直接是数值（1000 = 与历史行为逐字节相同）。
    val diaryWordCount: Int = DEFAULT_DIARY_WORD_COUNT,
    val diaryNarrativePerson: String = "",
    val diaryStyleHint: String = "",
    val diaryExtraRules: String = "",
    val diaryExchangeWordCount: Int = DEFAULT_DIARY_WORD_COUNT,
    val diaryExchangeNarrativePerson: String = "",
    val diaryExchangeStyleHint: String = "",
    val diaryExchangeExtraRules: String = "",

    // 朋友圈（P7.2，1:1 iOS moment*）。频率沿用 iOS 默认（decision②）；设置 UI → 7.2.8（暂无 DataStore，恒默认）。
    /** 每角色每日自动发帖上限，默认 2，UI 滑块 0~5（0=关）。 */
    val momentAutoPostFrequency: Int = 2,
    /** 每帖 AI 评论人数上限，默认 2，UI 滑块 0~3（0=关，语义=上限非固定数）。7.2.4 消费。 */
    val momentAutoCommentFrequency: Int = 2,
    /** 自动点赞开关，默认开。7.2.4 消费。 */
    val momentAutoLikeEnabled: Boolean = true,
    /** 首条评论基础延迟（分钟），默认 3，UI 滑块 1~10。7.2.4 消费。 */
    val momentCommentDelay: Int = 3,
    /**
     * 「X 发了新动态」系统通知开关（13.7e，**安卓超越 iOS**：iOS 对角色自己的新帖零提醒、只在 feed 静默出现）。
     * 默认开；仅后台周期 worker 发的帖推（回前台补发不推），每角色每天≤1、多角色合并。仍受系统 POST_NOTIFICATIONS 约束。
     */
    val momentNewPostNotificationEnabled: Boolean = true,

    // 内容过滤规则（14.3c；JSON 数组，空 = 首次使用，设置页 VM 写默认 5 预设）。被 ChatViewModel/BusyReplyService
    // 经 ContentFilterService.applyFilters 读取，对 AI 回复正文做删除/替换净化。1:1 iOS contentFilterRulesJSON。
    val contentFilterRulesJSON: String = "",

    // 上下文日志（批 D；iOS LogService 容量轮转 + 隐私开关）
    /** 日志保留条数：超出自动轮转删最旧（默认 100；UI 滑块 10..500，可手填超限 = iOS）。读 [sanitizedLogRetentionCount]。 */
    val logRetentionCount: Int = DEFAULT_LOG_RETENTION_COUNT,
    /**
     * 是否记录完整上下文 + 回复正文。**默认 false**（= iOS AppSettings.swift:392 真实默认，隐私优先）：关时只存元数据
     * + 分段统计（字符数/token），正文不入库；正文与日志表本就不进备份导出，故敏感对话不跨设备。
     */
    val logDetailEnabled: Boolean = false,

    /** 性能采集开关（卷 0·**默认关**）。关时全部采集点立即 return，App 行为与未做本卷等价；诊断数据不跨设备。 */
    val perfCollectEnabled: Boolean = false,

    // 提示词模块持久化（JSON；空 = 用默认模块）
    val promptModulesJSON: String = "",
    val characterPromptModulesJSON: String = "",
    val promptModulePresetsJSON: String = "",

    // 一次性迁移 flag
    val timeAwarenessPositionMigratedG2: Boolean = false,
    val currentMomentSortOrderMigratedG3: Boolean = false,
) {
    /**
     * clamp 到 bounds + 保证 upper > lower（对齐 iOS `sanitizedReplySegmentRange`）。
     * 返回 [lower, upper] pair（闭区间）。
     */
    val sanitizedReplySegmentRange: IntRange
        get() {
            var lower = replySegmentMin.coerceIn(REPLY_SEGMENT_MIN_BOUND, REPLY_SEGMENT_MAX_BOUND)
            var upper = replySegmentMax.coerceIn(REPLY_SEGMENT_MIN_BOUND, REPLY_SEGMENT_MAX_BOUND)
            if (upper <= lower) {
                if (lower < REPLY_SEGMENT_MAX_BOUND) {
                    upper = lower + 1
                } else {
                    lower = REPLY_SEGMENT_MAX_BOUND - 1
                    upper = REPLY_SEGMENT_MAX_BOUND
                }
            }
            return lower..upper
        }

    /**
     * clamp 到 bounds + 保证 upper > lower（对齐 iOS `sanitizedVoiceReplyRoundRange`）。语音回复轮次阈值
     * 从该闭区间随机取。
     */
    val sanitizedVoiceReplyRoundRange: IntRange
        get() {
            var lower = voiceReplyRoundMin.coerceIn(VOICE_REPLY_ROUND_MIN_BOUND, VOICE_REPLY_ROUND_MAX_BOUND)
            var upper = voiceReplyRoundMax.coerceIn(VOICE_REPLY_ROUND_MIN_BOUND, VOICE_REPLY_ROUND_MAX_BOUND)
            if (upper <= lower) {
                if (lower < VOICE_REPLY_ROUND_MAX_BOUND) {
                    upper = lower + 1
                } else {
                    lower = VOICE_REPLY_ROUND_MAX_BOUND - 1
                    upper = VOICE_REPLY_ROUND_MAX_BOUND
                }
            }
            return lower..upper
        }

    /** clamp 打断阈值到 [0.05, 0.40]（对齐 iOS 滑块边界）。打断检测/状态机读这个。 */
    val sanitizedVoiceCallInterruptThreshold: Float
        get() = voiceCallInterruptThreshold.coerceIn(VOICE_CALL_THRESHOLD_MIN, VOICE_CALL_THRESHOLD_MAX)

    /** clamp 创造力(温度)到 [0,2]，非有限值(NaN/∞)回退默认 1.0（滑块 0..2 step0.1）。 */
    val sanitizedLlmTemperature: Double
        get() = if (llmTemperature.isFinite()) llmTemperature.coerceIn(LLM_TEMPERATURE_MIN, LLM_TEMPERATURE_MAX) else DEFAULT_LLM_TEMPERATURE

    /** clamp 故事创作温度到 [0,2]，非有限值回退默认 1.0（=DeepSeek V4 官方推荐；滑块口径同聊天创造力）。 */
    val sanitizedStoryCreationTemperature: Double
        get() = if (storyCreationTemperature.isFinite()) storyCreationTemperature.coerceIn(LLM_TEMPERATURE_MIN, LLM_TEMPERATURE_MAX) else DEFAULT_STORY_CREATION_TEMPERATURE

    /** 有效日志保留条数：>0 直接用（手填可超滑杆上限），否则回退默认 100（对齐 iOS `LogService.fetchRetentionCount`）。 */
    val sanitizedLogRetentionCount: Int
        get() = if (logRetentionCount > 0) logRetentionCount else DEFAULT_LOG_RETENTION_COUNT

    companion object {
        // 长期记忆（滚动摘要）字数上限默认值（2026-07-11 拍板 3000→5000；滑杆 200..5000 步 100，手填可超上限）
        const val DEFAULT_MEMORY_SUMMARY_MAX_LENGTH = 5000
        // 短期记忆窗口默认轮数（2026-09-05 拍板 20→30；默认参与 SettingsRepository 回退的单源）
        const val DEFAULT_SHORT_TERM_MEMORY_LENGTH = 30
        // 两次记忆总结的最小间隔默认分钟（2026-09-05 拍板：原硬编码 30 分钟成功冷却做成可调项；0=不限）
        const val DEFAULT_MEMORY_SUMMARY_COOLDOWN_MINUTES = 30

        /** 日记篇幅默认（两套共用·1000 = 与写作规则可编辑之前逐字节相同）。 */
        const val DEFAULT_DIARY_WORD_COUNT = 1000

        const val REPLY_SEGMENT_MIN_BOUND = 1
        const val REPLY_SEGMENT_MAX_BOUND = 15
        const val DEFAULT_REPLY_SEGMENT_MIN = 2
        const val DEFAULT_REPLY_SEGMENT_MAX = 6

        // 语音回复轮次（对齐 iOS AppSettings.voiceReplyRoundRangeBounds 1...20 / defaultVoiceReplyRoundRange 3...5）
        const val VOICE_REPLY_ROUND_MIN_BOUND = 1
        const val VOICE_REPLY_ROUND_MAX_BOUND = 20
        const val DEFAULT_VOICE_REPLY_ROUND_MIN = 3
        const val DEFAULT_VOICE_REPLY_ROUND_MAX = 5

        // 创造力（温度，14.3b；2026-07-11 默认 0.8→1.0 = 跟随模型厂商推荐，CREATIVITY_RELOCATION D-2；滑块 0.0..2.0 step 0.1）
        const val DEFAULT_LLM_TEMPERATURE = 1.0
        const val LLM_TEMPERATURE_MIN = 0.0
        const val LLM_TEMPERATURE_MAX = 2.0

        // 故事正章创作温度（卷一 V1）：默认 1.0 = DeepSeek V4-Pro 官方唯一推荐值；边界复用聊天创造力的 [0,2]。
        const val DEFAULT_STORY_CREATION_TEMPERATURE = 1.0

        // 语音通话打断阈值（对齐 iOS AppSettings.userConfiguredThreshold 默认 0.15；存值范围 [0.05, 0.40]）
        const val DEFAULT_VOICE_CALL_INTERRUPT_THRESHOLD = 0.15f
        const val VOICE_CALL_THRESHOLD_MIN = 0.05f
        const val VOICE_CALL_THRESHOLD_MAX = 0.40f

        // 上下文日志保留（批 D；默认 100·滑块 10..500，对齐 iOS LogRetentionSettingsView）
        const val DEFAULT_LOG_RETENTION_COUNT = 100
        const val LOG_RETENTION_SLIDER_MIN = 10
        const val LOG_RETENTION_SLIDER_MAX = 500

        // 世界系统鲜活度三档（W1·契约 §7.A；默认 standard）
        const val WORLD_VIVIDNESS_LITE = "lite"
        const val WORLD_VIVIDNESS_STANDARD = "standard"
        const val WORLD_VIVIDNESS_RICH = "rich"

        // 世界系统通知三档（W1·契约 §7.A；默认 gentle）
        const val WORLD_NOTIFICATION_SILENT = "silent"
        const val WORLD_NOTIFICATION_GENTLE = "gentle"
        const val WORLD_NOTIFICATION_ALL = "all"
    }
}
