package com.situ.aichat.diagnostics

/**
 * 上下文日志分类筛选（批 D·移植 iOS `LogListView.LogFilter` 并**修正其归类 bug**）。
 *
 * ★ iOS 原版漏映射三个 source（只在「全部」露脸、点任何具体类目都不显）：
 *   - `节拍状态`（[LogSource.SCENE_PROGRESS]，互动故事节拍）→ iOS 不在 Story 类目里
 *   - `角色主动送礼`/`礼物反应生成`（[LogSource.PROACTIVE_GIFT]/[LogSource.GIFT_REACTION]）→ iOS 根本没有礼物类目
 * 本移植：节拍归 [STORY]、**新增 [GIFT] 类目**，并给安卓特有 source 各定归属，使「每个 source ≥1 具体类目」
 * 成立（[LogCategoryCoverage] 单测守门，防回归）。
 *
 * [ALL]/[FAILED] 是特殊筛选（非按 source）：ALL=全部、FAILED=`isSuccess=false`，故 [sources] 空、[isSourceFilter]=false。
 */
enum class LogCategory(val sources: List<String>) {
    ALL(emptyList()),

    CHAT(listOf(LogSource.CHAT, LogSource.BUSY_REPLY, LogSource.RECOVERY_REPLY, LogSource.OFFLINE_AFTERGLOW)),

    VOICE_CALL(listOf(LogSource.VOICE_CALL)),

    MEMORY(
        listOf(
            LogSource.MEMORY_SUMMARY, LogSource.STRUCTURED_MEMORY,
            LogSource.OFFLINE_MEETING_MEMORY, LogSource.IN_SCENE_RECAP,
            LogSource.OUR_DAYS,
        ),
    ),

    ANALYSIS(
        listOf(
            LogSource.GROWTH_ANALYSIS, LogSource.RELATIONSHIP_ANALYSIS,
            LogSource.NOTIFICATION_TEMPLATE, LogSource.DYNAMIC_NOTIFICATION,
            LogSource.SALARY_INFERENCE, LogSource.RED_PACKET_DECISION,
            LogSource.MEETING_DETECTION,
            LogSource.OPEN_LOOP_SCAN, LogSource.OPEN_LOOP_MESSAGE,
            LogSource.PROMISE_RECONCILE, LogSource.PERSONA_COMPILE,
            LogSource.PERSONA_REVIEW,
        ),
    ),

    MOMENTS(listOf(LogSource.MOMENT_POST, LogSource.MOMENT_COMMENT)),

    DIARY(listOf(LogSource.DIARY_GENERATION, LogSource.DIARY_COMMENT, LogSource.PET_DIARY)),

    /** ★ 修复点：节拍状态（故事节拍推进）并入故事类目，iOS 原版漏了。 */
    STORY(listOf(LogSource.STORY_GENERATION, LogSource.SCENE_PROGRESS)),

    SCHEDULE(listOf(LogSource.SCHEDULE_GENERATION, LogSource.SCHEDULE_WEATHER_ADJUST)),

    /** ★ 新增类目：iOS 无礼物类目→主动送礼/礼物反应/好感判断无处归属。 */
    GIFT(listOf(LogSource.PROACTIVE_GIFT, LogSource.GIFT_REACTION, LogSource.AFFINITY_SENSE)),

    /**
     * ★ W12 决策 43③ 新增类目：世界系统日志单开「世界」——「世界小报」自 [ANALYSIS] 迁入，
     * W12 三来源（偷听/风物志/初遇）同归其下（销 W5-D16「类目再议」挂账）。
     */
    WORLD(
        listOf(
            LogSource.WORLD_BULLETIN, LogSource.WORLD_EAVESDROP,
            LogSource.WORLD_LORE, LogSource.WORLD_FIRST_MEET,
        ),
    ),

    FAILED(emptyList());

    /** true=按 source 过滤的具体类目；false=特殊筛选（[ALL]/[FAILED]）。 */
    val isSourceFilter: Boolean get() = this != ALL && this != FAILED

    /** 筛选 chip 显示名（固定中文·与 [LogSource] 同口径=诊断域不随系统语言变；D-3 UI 直接用）。 */
    val displayName: String
        get() = when (this) {
            ALL -> "全部"
            CHAT -> "对话"
            VOICE_CALL -> "语音通话"
            MEMORY -> "记忆"
            ANALYSIS -> "分析"
            MOMENTS -> "朋友圈"
            DIARY -> "日记"
            STORY -> "故事"
            SCHEDULE -> "日程"
            GIFT -> "礼物"
            WORLD -> "世界"
            FAILED -> "失败"
        }

    companion object {
        /** 某 source 命中的具体类目（不含 [ALL]/[FAILED]）。空=孤儿（应被覆盖不变量测试拦下）。 */
        fun sourceFilterCategoriesFor(source: String): List<LogCategory> =
            entries.filter { it.isSourceFilter && source in it.sources }
    }
}
