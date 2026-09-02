package com.situ.aichat.data.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 角色主动送礼触发类型（1:1 iOS `Models/ProactiveGiftTrigger.swift` 的 `ProactiveGiftTriggerType`）。
 *
 * 5 种触发 + [priority]（多候选同时命中时供 LLM 参考，数字越大越优先）。LLM（c-4）拿到全部候选 + priority，
 * 自行综合决定是否送 + 送什么。[raw] 1:1 iOS rawValue（持久化进幂等 key 的 type 段，**不可改**）。
 */
enum class ProactiveGiftTriggerType(val raw: String, val priority: Int, val displayName: String) {
    /** 用户生日（UserProfile.birthday 月日匹配今天） */
    BIRTHDAY("birthday", 100, "用户生日"),

    /** 相识纪念日（firstMessageDate 距今 ∈ 里程碑天数） */
    ANNIVERSARY("anniversary", 80, "相识纪念日"),

    /** 节日（FestivalCalendar 命中） */
    FESTIVAL("festival", 60, "节日"),

    /** 察觉用户不开心（角色 moodHistory 最近 3 条红色 ≥ 2） */
    SENSE_LOW_MOOD("sense_low_mood", 40, "察觉不开心"),

    /** 想你（14 天硬底线，7-14 天软概率） */
    MISSING_YOU("missing_you", 20, "想你");

    companion object {
        private val byRaw = entries.associateBy { it.raw }
        fun fromRaw(raw: String): ProactiveGiftTriggerType? = byRaw[raw]
    }
}

/**
 * 一次具体的触发事件 + 幂等 key 生成（1:1 iOS `ProactiveGiftTrigger`）。
 *
 * - [label]：LLM prompt 读到的人话描述（"相识 100 天"、"情人节"）
 * - [metaId]：幂等 key 的一部分，区分同触发类型的不同实例（"100d" / "valentines_day" / "hard" / "soft" / "current"）
 * - [firedAt]：触发时刻（epoch millis），用于 key 的日期段
 *
 * 幂等 key 格式：`proactive_gift_{characterUUID}_{YYYYMMDD}_{type}_{metaId}`，保证同一角色同一天同一触发类型同一实例
 * 最多送一次礼。日期段用设备时区的公历日（= iOS gregorian + en_US_POSIX + TimeZone.current；中国无夏令时，
 * 系统默认时区即等价）。
 */
data class ProactiveGiftTrigger(
    val type: ProactiveGiftTriggerType,
    val label: String,
    val metaId: String,
    val firedAt: Long,
) {
    /** 生成幂等 key（需 app 层传入 characterUuid 拼装）。 */
    fun relatedEntityKey(characterUuid: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val yyyymmdd = Instant.ofEpochMilli(firedAt).atZone(zone).toLocalDate().format(YMD)
        val meta = metaId.ifEmpty { "-" }
        return "proactive_gift_${characterUuid}_${yyyymmdd}_${type.raw}_$meta"
    }

    private companion object {
        /** 稳定 yyyyMMdd（公历/ISO，与设备语言无关）。 */
        val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}

/**
 * 决策上下文（1:1 iOS `ProactiveGiftContext`）：扫描角色后聚合出的「信息包」，喂给 c-4 的 LLM 决策。
 *
 * **这不是决策结果**，而是 app 收集好的信息，LLM 拿到后自己判断「今天是否应该送/送什么/说什么」。
 * 由 [com.situ.aichat.gift.ProactiveGiftScheduler.buildContext] 生成。
 */
data class ProactiveGiftContext(
    val characterUUID: String,
    val characterName: String,
    val occupation: String,
    /** 候选触发列表（按 priority 降序，可能为空表示今天无候选理由） */
    val candidateTriggers: List<ProactiveGiftTrigger>,
    /** 距上次主动送礼的天数，null = 从未送过 */
    val daysSinceLastProactiveGift: Int?,
    /** 经济档位（复用 [EconomicStatusTier]），null = 月薪 0 角色不参与经济 */
    val economicTier: EconomicStatusTier?,
    val monthlySalary: Int,
    val coinBalance: Int,
    /** 当前关系标签（朋友/恋人等），null 表示无 */
    val relationshipLabel: String?,
    /** 最近 mood 色彩摘要（如 "red/red/yellow"），用于 LLM 判断心情 */
    val recentMoodSummary: String,
    /** 卷四：用户昵称（礼物出口意图句用·K-20）；空 ⇒ 提示词回退「用户」。默认字段 ⇒ 既有构造点零改。 */
    val userName: String = "",
) {
    /** 是否有任何候选触发 */
    val hasAnyCandidate: Boolean get() = candidateTriggers.isNotEmpty()

    /** 最高优先级的候选触发（用于 fallback 时的默认选择） */
    val topTrigger: ProactiveGiftTrigger? get() = candidateTriggers.firstOrNull()
}
