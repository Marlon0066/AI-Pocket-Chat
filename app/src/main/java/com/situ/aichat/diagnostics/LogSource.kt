package com.situ.aichat.diagnostics

/**
 * 上下文日志来源常量（批 D·移植 iOS `LogSource`）。统一管理每条 LLM 调用记录的 `source` 值，
 * 替代散落各处的硬编码串，确保日志分类筛选（[LogCategory]）能准确匹配。
 *
 * 值为**固定中文串**（不随系统语言变化），仅用于内部标识 + 筛选 + 与 iOS 备份对账。改任何串=破坏旧日志筛选。
 *
 * 前 18 条 = iOS `LogSource.swift` 1:1；末 7 条 = 安卓特有调用路径（iOS 无对应 service），归入语义最近的类目
 * （见 [LogCategory]），保证「每个 source 至少属一个具体类目」不变量、避开 iOS 已知的归类漏映射 bug。
 */
object LogSource {
    // MARK: - 对话（iOS-parity）
    const val CHAT = "对话"
    const val VOICE_CALL = "语音通话"

    // MARK: - 记忆（iOS-parity）
    const val MEMORY_SUMMARY = "记忆总结"
    const val STRUCTURED_MEMORY = "结构化记忆提取"

    // MARK: - 场内滚动压缩·前情提要（记忆改造二期·部件⑤·安卓特有·归 MEMORY 类目）
    const val IN_SCENE_RECAP = "场内前情提要"

    // MARK: - 分析（iOS-parity）
    const val GROWTH_ANALYSIS = "成长分析"
    const val RELATIONSHIP_ANALYSIS = "关系评估"
    const val MEETING_DETECTION = "未来约定识别"

    // MARK: - 承诺回连（活人感一期 P2·安卓特有·归 ANALYSIS 类目，照 MEETING_DETECTION）
    const val OPEN_LOOP_SCAN = "惦记清单提取"
    const val OPEN_LOOP_MESSAGE = "惦记回连消息"

    // MARK: - 承诺账本对账（记忆改造一期·部件②·安卓特有·归 ANALYSIS 类目，照 OPEN_LOOP_SCAN）
    const val PROMISE_RECONCILE = "约定对账"

    // MARK: - 人设编译（活人感内核·卷一·安卓特有·归 ANALYSIS 类目，照 PROMISE_RECONCILE）
    const val PERSONA_COMPILE = "人设编译"

    // MARK: - 性格复盘（活人感内核·卷四·调用 C·安卓特有·归 ANALYSIS 类目，照 PERSONA_COMPILE）
    const val PERSONA_REVIEW = "性格复盘"

    // MARK: - 我们的日子（卷一·安卓特有·归 MEMORY 类目）
    const val OUR_DAYS = "日子手记"

    // MARK: - 通知（iOS-parity）
    const val NOTIFICATION_TEMPLATE = "通知文案生成"
    const val DYNAMIC_NOTIFICATION = "动态通知生成"

    // MARK: - 朋友圈（iOS-parity）
    const val MOMENT_POST = "朋友圈动态"
    const val MOMENT_COMMENT = "朋友圈评论"

    // MARK: - 日记（iOS-parity）
    const val DIARY_GENERATION = "日记生成"
    const val DIARY_COMMENT = "日记评论"

    // MARK: - 故事（iOS-parity）
    const val STORY_GENERATION = "故事生成"

    // MARK: - 图片理解（iOS-parity·ApiFunction.IMAGE_UNDERSTANDING）
    const val IMAGE_UNDERSTANDING = "图片理解"

    // MARK: - 日程（iOS-parity；天气调整安卓暂未做，常量保留=前向兼容）
    const val SCHEDULE_GENERATION = "日程生成"
    const val SCHEDULE_WEATHER_ADJUST = "日程天气调整"

    // MARK: - 礼物（iOS-parity）
    const val PROACTIVE_GIFT = "角色主动送礼"
    const val GIFT_REACTION = "礼物反应生成"

    // MARK: - 安卓特有路径（iOS 无对应 LLM service）
    const val BUSY_REPLY = "忙碌回复"
    const val RECOVERY_REPLY = "恢复回复"
    const val SALARY_INFERENCE = "月薪推断"
    const val AFFINITY_SENSE = "好感判断"
    const val RED_PACKET_DECISION = "红包决策"
    const val PET_DIARY = "宠物日记"
    const val OFFLINE_MEETING_MEMORY = "见面记忆"
    const val OFFLINE_AFTERGLOW = "见面余温"

    // MARK: - 世界系统（安卓特有·W5 联动闭环开机小报润色）
    const val WORLD_BULLETIN = "世界小报"

    // MARK: - 世界系统（W12 快聊偷听·决策 43③ 世界日志类目三新来源）
    const val WORLD_EAVESDROP = "世界偷听"
    const val WORLD_LORE = "世界风物志"
    const val WORLD_FIRST_MEET = "世界初遇"

    /** 全部已知来源（[LogCategory] 覆盖不变量测试 + 去重校验的枚举源）。新增 source 务必同步追加。 */
    val ALL: List<String> = listOf(
        CHAT, VOICE_CALL,
        MEMORY_SUMMARY, STRUCTURED_MEMORY, IN_SCENE_RECAP, OUR_DAYS,
        GROWTH_ANALYSIS, RELATIONSHIP_ANALYSIS, MEETING_DETECTION,
        OPEN_LOOP_SCAN, OPEN_LOOP_MESSAGE,
        PROMISE_RECONCILE,
        PERSONA_COMPILE,
        PERSONA_REVIEW,
        NOTIFICATION_TEMPLATE, DYNAMIC_NOTIFICATION,
        MOMENT_POST, MOMENT_COMMENT,
        DIARY_GENERATION, DIARY_COMMENT,
        STORY_GENERATION,
        SCHEDULE_GENERATION, SCHEDULE_WEATHER_ADJUST,
        PROACTIVE_GIFT, GIFT_REACTION,
        BUSY_REPLY, RECOVERY_REPLY, SALARY_INFERENCE, AFFINITY_SENSE,
        RED_PACKET_DECISION, PET_DIARY, OFFLINE_MEETING_MEMORY, OFFLINE_AFTERGLOW,
        WORLD_BULLETIN, WORLD_EAVESDROP, WORLD_LORE, WORLD_FIRST_MEET,
    )
}
