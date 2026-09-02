package com.situ.aichat.data.model

/**
 * App functions that can be assigned a dedicated API config — faithful port of iOS `APIFunction`.
 * Only [CHAT] and [MEMORY_SUMMARY] are wired on Android today; the rest are placeholders for
 * features landing in P4–P11 (the assignment UI lists them all, matching iOS).
 *
 * `displayName` / `subtitle` are kept inline (Chinese, matching iOS base strings) since they're
 * data-layer enum metadata; UI chrome (titles/category headers/"默认") is in string resources.
 */
enum class ApiFunction(val raw: String, val displayName: String, val subtitle: String) {
    // 副标题「思考/普通模型」建议口径（2026-07-11 拍板）：实时链路建议普通模型（思考开口慢）；
    // 简单小活普通模型即可（思考多花钱无感知提升）；后台重推理思考更佳、普通也够用。
    CHAT("chat", "聊天对话", "与 AI 文字聊天时使用；思考模型回复更好但更慢，普通模型更秒回。选带视觉能力的模型，聊天「+」里才会出现「照片」"),
    VOICE_CALL("voiceCall", "语音通话", "实时语音通话时使用，建议普通模型（思考模型开口慢）"),
    MEMORY_SUMMARY("memorySummary", "记忆总结", "后台自动总结对话记忆；思考模型更准，普通模型也够用"),
    IMAGE_UNDERSTANDING("imageUnderstanding", "图片理解", "给你发的照片生成一句文字描述，存进记忆、也让不看图的模型知道你发了什么；需带视觉能力，建议普通模型（更快）"),
    DIARY_GENERATION("diaryGeneration", "日记生成", "自动生成日记和日记评论；后台慢慢写，思考/普通均可"),
    MOMENT_GENERATION("momentGeneration", "朋友圈", "角色自动发朋友圈动态和评论；思考/普通均可"),
    SCHEDULE_GENERATION("scheduleGeneration", "日程生成", "生成角色每日日程并按天气调整；思考模型排得更合理"),
    STORY_CREATION("storyCreation", "故事创作", "写故事内容，支持思考模型（长篇更连贯）"),
    STORY_STRUCTURING("storyStructuring", "故事结构化", "将故事整理为结构化数据，建议用普通模型（省钱且够用）"),
    GROWTH_ANALYSIS("growthAnalysis", "成长分析", "后台分析角色成长变化；思考模型更准，普通模型也够用"),
    RELATIONSHIP_ANALYSIS("relationshipAnalysis", "关系分析", "后台分析关系变化；思考模型更准，普通模型也够用"),
    NOTIFICATION_TEMPLATE("notificationTemplate", "通知文案", "生成推送通知的文字内容，普通模型即可"),
    SCENE_PROGRESS("sceneProgress", "节拍状态", "线下见面时更新场景进度和允许结束标志，建议普通模型（要快）"),
    WORLD("world", "世界", "世界小报、偷听、风物志与初遇的润色，普通模型即可"),

    // 活人感内核·卷一《人设编译器》（图纸 §3.4 逐字锁定）。**追加在末尾**：entries 顺序即分配屏排序，插中间会挪位。
    // 独立功能位而非复用成长分析：编译是一次性、质量关键的调用（编译歪了会长期污染角色），值得单配更强的模型。
    // 未分配时 resolveConfigValues 自动回落活动配置 ⇒ 开箱即用，不会出现死按钮。
    PERSONA_COMPILE("personaCompile", "人设编译", "读一遍角色的性格描述，自动填好本性数值与敏感点；一次性调用，建议用你最好的模型"),

    // 「我们的日子」卷一《沉淀》（总图纸 §3.8 逐字锁定）。**追加在末尾**：entries 顺序即分配屏排序。每天零点后为前一天写手记 + 事实行，
    // 后台慢写、普通模型即可；未分配时 resolveConfigValues 自动回落活动配置。
    OUR_DAYS("ourDays", "我们的日子", "每天零点后为前一天写一段手记和一行记录；后台慢慢写，普通模型即可");

    val category: ApiFunctionCategory
        get() = when (this) {
            CHAT, VOICE_CALL -> ApiFunctionCategory.CONVERSATION
            MEMORY_SUMMARY, IMAGE_UNDERSTANDING, GROWTH_ANALYSIS, RELATIONSHIP_ANALYSIS, SCENE_PROGRESS,
            PERSONA_COMPILE, OUR_DAYS -> ApiFunctionCategory.BACKGROUND
            DIARY_GENERATION, MOMENT_GENERATION, SCHEDULE_GENERATION, STORY_CREATION,
            STORY_STRUCTURING, NOTIFICATION_TEMPLATE, WORLD -> ApiFunctionCategory.CONTENT
        }

    companion object {
        fun fromRaw(raw: String): ApiFunction? = entries.firstOrNull { it.raw == raw }
    }
}

/** UI grouping for the function-assignment screen — faithful port of iOS `APIFunctionCategory`. */
enum class ApiFunctionCategory {
    CONVERSATION,
    BACKGROUND,
    CONTENT;

    val functions: List<ApiFunction>
        get() = ApiFunction.entries.filter { it.category == this }
}
