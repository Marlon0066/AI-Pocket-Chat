package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Mirrors the iOS `AICharacter` @Model. Growth / structured-memory data is kept as JSON
 * strings (same as iOS), decoded by the domain layer via kotlinx.serialization.
 * Enums are stored as their iOS rawValue strings for forward backup compatibility.
 */
@Entity(
    tableName = "characters",
    indices = [Index("creationDate")],
)
data class CharacterEntity(
    @PrimaryKey val uuid: String,
    val name: String,
    val avatarPath: String? = null,
    // 聊天壁纸：per-角色全屏壁纸绝对路径（空=无壁纸→聊天纯色底 / 见面全局背景，零变化）。见 FABLE5_CHAT_WALLPAPER_PROPOSAL.md。
    val chatWallpaperPath: String? = null,
    val systemPrompt: String = "",
    val personalityDescription: String = "",
    val creationDate: Long,

    // Identity
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

    // Memory summaries
    val memorySummary: String = "",
    val previousMemorySummary: String = "",
    val offlineMeetingMemorySummary: String = "",

    // Voice (remoteVoiceID / tts* used on Android; voiceIdentifier is iOS-only, kept for parity)
    val voiceIdentifier: String = "",
    val remoteVoiceID: String = "",
    val ttsEmotionRaw: String = "auto",
    val ttsSpeed: Double = 1.0,
    val ttsPitch: Int = 0,

    // Mood
    val lastMoodEmoji: String = "",
    val lastMoodText: String = "",
    val lastMoodColorName: String = "green",

    // Companion stats
    val firstMessageDate: Long? = null,
    val streakCount: Int = 0,
    val lastChatDate: Long? = null,

    // Growth & structured memory (JSON blobs, mirror iOS)
    val personalitySpectrumJSON: String = "",
    val relationshipQualityJSON: String = "",
    // 活人感统一内核·卷二《正负双压》（图纸 docs/handoff/2026-09-02-活人感内核-卷二-正负双压.md §3.1）：
    // 关系 8 维的正压/负压双记账。空 = 老数据，解码访问器按 fromQuality 播种（pos=净额, neg=0）；
    // 上一列的净额语义不变、**只由 RelationshipPressure.toQuality() 派生**，两列恒在同一条 UPDATE 里写。
    val relationshipPressureJSON: String = "",
    // 成长原型校准（图纸 docs/handoff/2026-07-11-成长原型校准.md D-2）：名分识别出的关系原型 id
    // （null = 无名分 / 词表未识别 / 存量未扫）。渲染侧据此三分支调度；写侧由校准器单点维护。
    val relationshipArchetypeId: String? = null,
    val moodHistoryJSON: String = "",
    val dynamicInterestsJSON: String = "",
    val growthLogJSON: String = "",
    val growthMetadataJSON: String = "",
    val structuredMemoryJSON: String = "",
    val structuredMemoryMetadataJSON: String = "",
    val previousStructuredMemoryJSON: String = "",

    // Affinity-sense cache
    val affinitySensePackageJSON: String = "",
    val affinitySensePackageGeneratedAt: Long? = null,

    // Relationship analysis counters
    val relationshipMessageCount: Int = 0,
    val lastRelationshipAnalysisDate: Long? = null,

    // Location
    val cityName: String? = null,
    val cityLatitude: Double? = null,
    val cityLongitude: Double? = null,

    // Offline meeting personalization
    val offlineThemeColorHex: String? = null,

    // World system (契约 FABLE5_WORLD_SYSTEM_PROPOSAL.md §6 / W1 图纸 §3)：角色的「加入世界」态 + 住址。
    // 默认「不加入」= 私密 1:1 陪伴、不进世界（旧角色迁移回填 joinedWorld=false / worldHomeCityId='city_yunye'）。
    // 加入/离开的世界事件、互斥校验、住址生效均属 W6/W13——本块只加列，不做任何校验或联动。
    val joinedWorld: Boolean = false,
    val worldHomeCityId: String = "city_yunye",
    val worldJoinedAt: Long? = null,

    /**
     * 朋友圈消化水位线（记忆改造一期·朋友圈消化·图纸 §3.5-B）：已消化进长期记忆的朋友圈动态时间戳上界
     * （epoch millis）。0 = 从未消化（新装 / 旧备份）→ 收集时视作 now−7 天起步，绝不深挖历史。
     */
    val momentsDigestedUntilMillis: Long = 0,

    // 活人感统一内核·卷一《人设编译器》（图纸 docs/handoff/2026-09-01-活人感内核-卷一-人设编译器.md §3.1）：
    // 人设文本编译出的三产物（锚点 / 增益 / 算子）+ 编译元数据。四列一律 JSON 字符串，空 = 从未编译过
    // （解码访问器兜底，见 CharacterGrowthTypes.kt 末尾），写口唯一 = CharacterDao.updatePersonaCompile。
    /** 本性锚点（8 维 PersonalitySpectrum 的 JSON）。空 ⇒ 访问器回落 personalitySpectrum（图纸 Y-1）。 */
    val personalityAnchorJSON: String = "",
    /** 编译元数据 PersonaCompileMeta（来源 / 时间 / 人设 hash / 失败戳 / 丢弃数）。 */
    val personaCompileMetaJSON: String = "",
    /** 增益 PersonaGains（27 项档位覆盖 + 专属项）。卷一只存不消费。 */
    val personaGainsJSON: String = "",
    /** 算子 List<PersonaOperator>（条件→动作固定反应）。卷一只存不求值。 */
    val personaOperatorsJSON: String = "",

    // 活人感统一内核·卷三《场内核与渲染收编》（图纸 docs/handoff/2026-09-02-活人感内核-卷三-场内核与渲染收编.md §3.1）：
    // 四场 AffectField（安全感/投入度/效价/激活度 + 日预算 + 最近命中）的 JSON。空 = 从未写过 ⇒ 访问器回默认。
    // 唯二写者 = 每轮 tick 与成长分析通道（都在 AffectKernel 的 per-uuid Mutex 内），写口 = CharacterDao.updateAffectField
    // 列级盲写（I-3 列集与其它写者零重叠 ⇒ 不进 CharacterWriteLock）。
    val affectFieldJSON: String = "",

    // 活人感统一内核·卷四《意图队列 + 性格复盘》（图纸 docs/handoff/2026-09-02-活人感内核-卷四-意图队列与性格复盘.md §3.2）：
    // IntentQueueState（意图队列 + 性格复盘计数）的 JSON。空 = 从未写过 ⇒ 访问器回默认 IntentQueueState()。
    // 唯一写者 = IntentKernel（tick / 分析通道两条写路（性格复盘已于修缮卷砍除）都在它的 per-uuid Mutex 内），写口 = CharacterDao.updateIntentQueue
    // 列级盲写（I-3 列集与其它写者零重叠 ⇒ 不进 CharacterWriteLock）。
    val intentQueueJSON: String = "",

    // 「我们的日子」卷一《沉淀》（总图纸 docs/handoff/2026-09-02-我们的日子-总图纸.md §3.2）：一次性历史回填完成标记。
    // 非 null = 已回填（此后 catch-up 只扫近 7 天窗·Z-5）；null = 未回填（老角色 / 旧备份 / 新角色——新角色无史，首次
    // 回填枚举零候选即置位·§0.2）。唯一写口 = CharacterDao.updateOurDaysBackfilledAt 列级盲写；备份三处对称（E14）。
    val ourDaysBackfilledAt: Long? = null,
)
