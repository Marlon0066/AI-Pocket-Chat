package com.situ.aichat.prompt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 1:1 port of the iOS prompt-module type system (`PromptModuleService.swift`).
 *
 * `@SerialName` values mirror the Swift enum `rawValue`s exactly so the persisted JSON
 * (`promptModulesJSON` / `characterPromptModulesJSON` / `promptModulePresetsJSON`) is wire-compatible
 * with iOS backups. The enum declaration ORDER is the default `sortOrder` (0…21) — 挪声明位置只改默认布局、
 * 不碰 `@SerialName` 线格式，但**必须**为老用户配一次性迁移（先例：G4 timeAwareness 尾移；
 * 2026-07-11 offlineMeetingMemory 前移到「角色记忆」之后）。
 */
@Serializable
enum class SystemModuleType {
    @SerialName("coreRules") CORE_RULES,
    @SerialName("characterIdentity") CHARACTER_IDENTITY,
    @SerialName("scenario") SCENARIO,
    @SerialName("userPersona") USER_PERSONA,
    @SerialName("characterGrowth") CHARACTER_GROWTH,
    @SerialName("characterMemory") CHARACTER_MEMORY,
    // 见面记忆前置（2026-07-11 拍板）：offlineMeetingMemory 默认位置 SUFFIX→PREFIX，声明位从原 index 16
    // （宠物状态 15 与礼物记忆 17 之间）上移到「角色记忆」正后。理由：见面摘要 07-11 改第一人称日记体后，裸投
    // 后置区会成离生成点最近的抒情范文（与 texting 口吻对打）、且系统「你」=角色 × 日记「你」=对方指代翻转；
    // 搬进前置区大 system、紧贴角色记忆解决。仅改默认序 + defaultPosition；老用户持久化 JSON 由
    // SettingsRepository.migratePromptModuleMeetingMemoryOnce 一次性归位（@SerialName 线格式零碰）。
    @SerialName("offlineMeetingMemory") OFFLINE_MEETING_MEMORY,
    // 「我们的日子」卷二（2026-09-02·图纸 §3.2）：按日期翻到的当天记录（日期指名 + 那年今日），声明位紧随「见面记忆」
    // = 新用户默认序；老用户持久化 JSON 由 SettingsRepository.migratePromptModuleOurDaysOnce 一次性归位到见面记忆正后
    // （用户手动挪过不动）。@SerialName 线格式自此冻结。
    @SerialName("ourDays") OUR_DAYS,
    @SerialName("calendarAwareness") CALENDAR_AWARENESS,
    @SerialName("scheduleAwareness") SCHEDULE_AWARENESS,
    @SerialName("momentsContext") MOMENTS_CONTEXT,
    @SerialName("responseStyle") RESPONSE_STYLE,
    @SerialName("chatFormat") CHAT_FORMAT,
    @SerialName("qualityControl") QUALITY_CONTROL,
    @SerialName("moodExpression") MOOD_EXPRESSION,
    @SerialName("generalInstructions") GENERAL_INSTRUCTIONS,
    @SerialName("stickerLibrary") STICKER_LIBRARY,
    @SerialName("petStatus") PET_STATUS,
    @SerialName("giftHistory") GIFT_HISTORY,
    @SerialName("characterEconomicState") CHARACTER_ECONOMIC_STATE,
    /** ⚠️已废（忙碌延迟回复功能 2026-07-11 删除）：枚举保留=持久化 JSON/iOS 备份线格式冻结;其场景永不触发=天然惰性,模块页隐藏。 */
    @SerialName("busyReplyInstruction") BUSY_REPLY_INSTRUCTION,
    // 方案 G4（时间感知优化）：timeAwareness + currentMoment 移到 enum 末尾——defaultModules 的
    // sortOrder=index，故二者在 suffix 内排到「风格/格式/防重复/情绪/通用指令」之后、紧贴生成处。
    // 依据酒馆(SillyTavern)经验：当前状态/时间锚越靠近 prompt 底部，对下一条回复影响越大(低 depth)。
    // 仅改默认顺序；已自定义过模块顺序的用户其持久化 JSON 不被覆盖（尊重其安排，无强制迁移）。
    // 布局审计第一招（2026-07-11）：非线下时这两个模块由 PromptBuilder 在发射点**硬钉到物理最末位**
    // （守卫/工具段之后），不再依赖 sortOrder 兑现"紧贴生成处"——sortOrder 只决定它们与彼此的相对序。
    @SerialName("timeAwareness") TIME_AWARENESS,
    @SerialName("currentMoment") CURRENT_MOMENT;

    /** 显示名称（设置页用，对齐 iOS displayName）。 */
    val displayName: String
        get() = when (this) {
            CORE_RULES -> "核心规则"
            CHARACTER_IDENTITY -> "角色身份"
            SCENARIO -> "互动场景"
            USER_PERSONA -> "用户人设"
            CHARACTER_GROWTH -> "角色成长"
            CHARACTER_MEMORY -> "角色记忆"
            TIME_AWARENESS -> "时间感知"
            CALENDAR_AWARENESS -> "日历感知"
            SCHEDULE_AWARENESS -> "角色日程"
            CURRENT_MOMENT -> "此刻状态"
            MOMENTS_CONTEXT -> "朋友圈上下文"
            RESPONSE_STYLE -> "回复风格"
            CHAT_FORMAT -> "聊天格式"
            QUALITY_CONTROL -> "防重复与质量"
            MOOD_EXPRESSION -> "情绪表达"
            GENERAL_INSTRUCTIONS -> "通用指令"
            STICKER_LIBRARY -> "表情包"
            PET_STATUS -> "宠物状态"
            OFFLINE_MEETING_MEMORY -> "见面记忆"
            OUR_DAYS -> "我们的日子"
            GIFT_HISTORY -> "礼物记忆"
            CHARACTER_ECONOMIC_STATE -> "经济状况"
            BUSY_REPLY_INSTRUCTION -> "忙碌回复指令"
        }

    /** 内容是否可编辑（可编辑模块允许用户填写自定义 content 覆盖默认生成）。 */
    val isContentEditable: Boolean
        get() = when (this) {
            CORE_RULES, SCENARIO, RESPONSE_STYLE, CHAT_FORMAT, QUALITY_CONTROL,
            MOOD_EXPRESSION, GENERAL_INSTRUCTIONS, BUSY_REPLY_INSTRUCTION -> true
            else -> false
        }

    /**
     * 默认位置。方案 G2/G3/V2:timeAwareness / currentMoment / busyReplyInstruction 及多数末尾约束类
     * 模块放 suffix（聊天历史之后），利用 LLM 近因偏差对抗长对话注意力衰减。
     */
    val defaultPosition: PromptModulePosition
        get() = when (this) {
            RESPONSE_STYLE, CHAT_FORMAT, QUALITY_CONTROL, MOOD_EXPRESSION, GENERAL_INSTRUCTIONS,
            TIME_AWARENESS, BUSY_REPLY_INSTRUCTION, CURRENT_MOMENT ->
                PromptModulePosition.SUFFIX
            else -> PromptModulePosition.PREFIX
        }

    /**
     * 此系统模块在哪些场景下默认启用。`null` = 所有聊天场景启用（大多数通用模块）。
     * 仅 busyReplyInstruction 限定 `.busyReply`（它含 `{{busy_activity}}` 等专属宏）。
     */
    val defaultEnabledScenes: Set<PromptScene>?
        get() = when (this) {
            BUSY_REPLY_INSTRUCTION -> setOf(PromptScene.BUSY_REPLY)
            // 短信腔四件线下退场（两语境模型 2026-07-12）：只需声明 ONLINE_CHAT 位——装配端
            // moduleScene 二值化使语音/忙碌一律按此位走（数据即语义）。
            CHAT_FORMAT, RESPONSE_STYLE, MOOD_EXPRESSION, STICKER_LIBRARY -> setOf(PromptScene.ONLINE_CHAT)
            // 「我们的日子」卷二：在线聊天 + 语音通话（提案默认场景）；线下见面不注入（E54）。
            OUR_DAYS -> setOf(PromptScene.ONLINE_CHAT, PromptScene.VOICE_CALL)
            else -> null
        }

    /**
     * `@SerialName` rawValue（= iOS Swift enum `rawValue`，如 `"coreRules"`），供批 D 上下文日志的
     * [com.situ.aichat.prompt.ContextSegment.systemModuleType] 存模块标识，由日志详情页据此映射 Fable-5 图标。
     *
     * 这里与上方 `@SerialName` 串一致是**冻结契约**（= 持久化 JSON 与 iOS 备份的线格式，永不可改）；显式列出避免引入
     * 实验序列化 API（描述符取名需 opt-in）。单测 `SystemModuleTypeRawValueTest` 用描述符全量交叉校验此表 = `@SerialName`，
     * 任一漂移即红。
     */
    val rawValue: String
        get() = when (this) {
            CORE_RULES -> "coreRules"
            CHARACTER_IDENTITY -> "characterIdentity"
            SCENARIO -> "scenario"
            USER_PERSONA -> "userPersona"
            CHARACTER_GROWTH -> "characterGrowth"
            CHARACTER_MEMORY -> "characterMemory"
            TIME_AWARENESS -> "timeAwareness"
            CALENDAR_AWARENESS -> "calendarAwareness"
            SCHEDULE_AWARENESS -> "scheduleAwareness"
            CURRENT_MOMENT -> "currentMoment"
            MOMENTS_CONTEXT -> "momentsContext"
            RESPONSE_STYLE -> "responseStyle"
            CHAT_FORMAT -> "chatFormat"
            QUALITY_CONTROL -> "qualityControl"
            MOOD_EXPRESSION -> "moodExpression"
            GENERAL_INSTRUCTIONS -> "generalInstructions"
            STICKER_LIBRARY -> "stickerLibrary"
            PET_STATUS -> "petStatus"
            OFFLINE_MEETING_MEMORY -> "offlineMeetingMemory"
            OUR_DAYS -> "ourDays"
            GIFT_HISTORY -> "giftHistory"
            CHARACTER_ECONOMIC_STATE -> "characterEconomicState"
            BUSY_REPLY_INSTRUCTION -> "busyReplyInstruction"
        }
}

/** 模块注入位置。 */
@Serializable
enum class PromptModulePosition {
    @SerialName("prefix") PREFIX,
    @SerialName("suffix") SUFFIX,
}

/**
 * 聊天类提示词场景。这 4 个场景都走 `PromptBuilder.buildMessages` 同一管线，共享模块系统。
 * 生成类场景（朋友圈/日记/故事/通知/日程/宠物日记）不进入此枚举。
 */
@Serializable
enum class PromptScene {
    @SerialName("onlineChat") ONLINE_CHAT,
    @SerialName("offlineMeeting") OFFLINE_MEETING,
    @SerialName("voiceCall") VOICE_CALL,
    /** ⚠️已废（忙碌延迟回复功能 2026-07-11 删除）：场景保留=线格式冻结,运行时永不产生。 */
    @SerialName("busyReply") BUSY_REPLY,
}

/**
 * 提示词模块。`content` 仅自定义模块/可编辑模块用户填写时非空；系统模块运行时动态生成。
 * `enabledScenes == null` 表示所有聊天场景启用（= 老 JSON 缺此字段时的行为）。
 */
@Serializable
data class PromptModule(
    val id: String,                              // UUID 字符串（与 iOS JSON 一致，大写）
    var name: String,
    var content: String = "",
    /** 线下见面版自定义文案（两语境模型 2026-07-12）：空 = 线下用内置专版（buildOfflineCoreRulesContent）。
     *  仅 CORE_RULES 消费；主 content 只管普通聊天。旧 JSON 缺此字段 = ""（ignoreUnknownKeys 双向兼容·零迁移）。 */
    var offlineContent: String = "",
    var sortOrder: Int,
    var isEnabled: Boolean = true,
    var isSystemGenerated: Boolean = false,
    var systemModuleType: SystemModuleType? = null,
    var position: PromptModulePosition,
    var enabledScenes: Set<PromptScene>? = null,
)

/** 提示词模块预设。 */
@Serializable
data class PromptModulePreset(
    val id: String,
    var name: String,
    var modules: List<PromptModule>,
    var isBuiltIn: Boolean = false,
)
