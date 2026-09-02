package com.situ.aichat.gift

import com.situ.aichat.data.local.dao.CurrencyDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.MoodHistoryEntry
import com.situ.aichat.data.model.ProactiveGiftContext
import com.situ.aichat.data.model.ProactiveGiftTrigger
import com.situ.aichat.data.model.ProactiveGiftTriggerType
import com.situ.aichat.data.model.moodHistory
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.economy.CharacterEconomicStateService
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 主动送礼调度器（1:1 iOS `Services/ProactiveGiftScheduler.swift`，4 层架构的 Layer 1 候选筛选 + Layer 2 状态收集）。
 *
 * ## 职责边界
 * - **层 1 候选筛选**（纯规则）：扫 5 种触发时机（生日/纪念日/节日/察觉不开心/想你），返回所有候选触发（priority 降序）。
 * - **层 2 状态收集**：聚合角色状态 + 经济档位 + 记忆摘要 → [ProactiveGiftContext]。
 * - **硬守卫**：[hasReachedMonthlyLimit] 月上限 10 次。
 *
 * **不做**：不决定是否送（c-4 LLM）、不选礼物（c-3 筛候选）、不扣钱不插消息（c-5）。
 *
 * ## 冲突方案 γ（missingYou）
 * 距上次主动送礼 ≥ 14 天 → 硬底线触发；7-14 天 → 软概率 5%/天；< 7 天 → 不触发（其他触发仍可命中）。
 * 新角色守卫：距创建 < 3 天不触发 missingYou，但生日/节日/纪念日等时机型触发不受限。
 *
 * iOS 是 `@MainActor enum`（静态方法）；安卓改 `@Singleton` class（需 DB 读钱包/月上限计数 + currentRelationship + tier）。
 * 触发判定核心是注入原始值的**纯函数**（[candidateTriggersFrom] 等，internal 便于确定性单测），class 方法只做 DB 读取 + 委派。
 * 时间差用「整日截断」`(to-from)/DAY`（中国无夏令时 ↔ iOS `Calendar.dateComponents([.day])` 等价）。
 */
@Singleton
class ProactiveGiftScheduler @Inject constructor(
    private val currencyDao: CurrencyDao,
    private val characterRepo: CharacterRepository,
    private val economicStateService: CharacterEconomicStateService,
) {

    /**
     * 扫描角色所有候选触发（priority 降序，空 = 今天无任何送礼理由）。读钱包的 lastProactiveGiftDate 喂给 missingYou。
     * [randomValue] 测试注入（0..1），生产传 null 走 [Random]。
     */
    suspend fun candidateTriggers(
        character: CharacterEntity,
        userBirthday: Long?,
        now: Long = System.currentTimeMillis(),
        randomValue: Double? = null,
    ): List<ProactiveGiftTrigger> {
        val wallet = currencyDao.getCharacterWallet(character.uuid)
        return candidateTriggersFrom(
            userBirthday = userBirthday,
            firstMessageDate = character.firstMessageDate,
            creationDate = character.creationDate,
            moodColors = recentMoodColors(character.moodHistory),
            lastProactiveGiftDate = wallet?.lastProactiveGiftDate,
            now = now,
            randomValue = randomValue,
        )
    }

    /**
     * 构造 LLM 决策层所需的完整上下文（候选触发 + 经济档位 + 关系标签 + 心情摘要）。
     */
    suspend fun buildContext(
        character: CharacterEntity,
        userBirthday: Long?,
        now: Long = System.currentTimeMillis(),
        randomValue: Double? = null,
        /** 卷四 K-20：用户昵称透传给 [ProactiveGiftContext.userName]（意图句用）；默认空 = 既有调用点零改。 */
        userName: String = "",
    ): ProactiveGiftContext {
        val wallet = currencyDao.getCharacterWallet(character.uuid)
        val moodColors = recentMoodColors(character.moodHistory)
        val triggers = candidateTriggersFrom(
            userBirthday = userBirthday,
            firstMessageDate = character.firstMessageDate,
            creationDate = character.creationDate,
            moodColors = moodColors,
            lastProactiveGiftDate = wallet?.lastProactiveGiftDate,
            now = now,
            randomValue = randomValue,
        )
        val monthlySalary = wallet?.monthlySalary ?: 0
        val coinBalance = wallet?.coinBalance ?: 0
        val days = wallet?.lastProactiveGiftDate?.let { wholeDaysBetween(it, now) }
        val economicTier = economicStateService.tier(character.uuid, monthlySalary, coinBalance, now)
        val moodSummary = moodColors.take(SENSE_LOW_MOOD_RECENT_COUNT).joinToString("/")
        return ProactiveGiftContext(
            characterUUID = character.uuid,
            characterName = character.name,
            occupation = character.occupation,
            candidateTriggers = triggers,
            daysSinceLastProactiveGift = days,
            economicTier = economicTier,
            monthlySalary = monthlySalary,
            coinBalance = coinBalance,
            relationshipLabel = characterRepo.currentRelationship(character.uuid),
            recentMoodSummary = moodSummary.ifEmpty { "无记录" },
            userName = userName,
        )
    }

    /**
     * 检查角色最近 30 天主动送礼次数是否达上限（1:1 iOS `hasReachedMonthlyLimit`：character + spend + gift 计数 ≥ 10）。
     * c-5 执行前兜底，防 LLM 失控 + 偶发 bug 循环送礼。
     */
    suspend fun hasReachedMonthlyLimit(characterUuid: String, now: Long = System.currentTimeMillis()): Boolean {
        val monthStart = now - MONTHLY_WINDOW_DAYS * DAY_MILLIS
        return currencyDao.countCharacterGiftSpends(characterUuid, monthStart) >= MONTHLY_CAP_COUNT
    }

    companion object {
        /** 每月最多 10 次硬上限（防 LLM 失控 + 防 bug 循环送礼）。 */
        const val MONTHLY_CAP_COUNT = 10

        /** 月上限统计窗口（天）。 */
        const val MONTHLY_WINDOW_DAYS = 30L

        /**
         * 同日闸门豁免（1:1 iOS `shouldBypassDailyGate`）：生日/纪念日/节日是「一年一次」情感节点，不应被早些时候送出的
         * missingYou/senseLowMood 挡住（重复送礼由 c-5 幂等 key 兜底）。senseLowMood/missingYou 是「随时可补」的柔性关怀，
         * 一天 1 次足够，不豁免。
         */
        fun shouldBypassDailyGate(type: ProactiveGiftTriggerType): Boolean = when (type) {
            ProactiveGiftTriggerType.BIRTHDAY,
            ProactiveGiftTriggerType.ANNIVERSARY,
            ProactiveGiftTriggerType.FESTIVAL,
            -> true
            ProactiveGiftTriggerType.SENSE_LOW_MOOD,
            ProactiveGiftTriggerType.MISSING_YOU,
            -> false
        }
    }
}

// ── 纯函数（internal，确定性单测，断言反推 iOS 阈值/边界/metaId/key 格式） ──────────────

internal const val DAY_MILLIS = 24L * 60 * 60 * 1000

/** missingYou 硬底线：距上次 ≥ 14 天必触发。 */
internal const val HARD_MISSING_YOU_DAYS = 14

/** missingYou 软窗口起点：7 天。 */
internal const val SOFT_MISSING_YOU_MIN_DAYS = 7

/** 软窗口内每天触发概率（5%）。 */
internal const val SOFT_MISSING_YOU_PROBABILITY = 0.05

/** 新角色保护期：创建 < 3 天不触发 missingYou（其他触发不受此限）。 */
internal const val NEW_CHARACTER_PROTECTION_DAYS = 3

/** 察觉不开心：近 N 条 mood。 */
internal const val SENSE_LOW_MOOD_RECENT_COUNT = 3

/** 近 N 条中需要 red 的最小个数。 */
internal const val SENSE_LOW_MOOD_REQUIRED_RED = 2

/** 相识纪念日里程碑（天）。 */
internal val ANNIVERSARY_MILESTONE_DAYS: List<Int> = listOf(30, 100, 365, 730, 1095, 1460, 1825)

/** 整日截断差（1:1 iOS `Calendar.dateComponents([.day]).day`；中国无夏令时，等价于 `(to-from)/DAY` 朝零截断）。 */
internal fun wholeDaysBetween(from: Long, to: Long): Int = ((to - from) / DAY_MILLIS).toInt()

/** 5 类触发组合（1:1 iOS `candidateTriggers`）：逐一检测 → priority 降序。 */
internal fun candidateTriggersFrom(
    userBirthday: Long?,
    firstMessageDate: Long?,
    creationDate: Long,
    moodColors: List<String>,
    lastProactiveGiftDate: Long?,
    now: Long,
    randomValue: Double?,
    zone: ZoneId = ZoneId.systemDefault(),
): List<ProactiveGiftTrigger> {
    val triggers = mutableListOf<ProactiveGiftTrigger>()
    checkBirthday(userBirthday, now, zone)?.let { triggers.add(it) }
    checkAnniversary(firstMessageDate, now)?.let { triggers.add(it) }
    checkFestival(now)?.let { triggers.add(it) }
    checkSenseLowMood(moodColors, now)?.let { triggers.add(it) }
    checkMissingYou(creationDate, lastProactiveGiftDate, now, randomValue)?.let { triggers.add(it) }
    return triggers.sortedByDescending { it.type.priority }
}

/** 用户生日月日匹配今天（1:1 iOS `checkBirthday`）。 */
internal fun checkBirthday(userBirthday: Long?, now: Long, zone: ZoneId = ZoneId.systemDefault()): ProactiveGiftTrigger? {
    if (userBirthday == null) return null
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val b = Instant.ofEpochMilli(userBirthday).atZone(zone).toLocalDate()
    if (today.monthValue != b.monthValue || today.dayOfMonth != b.dayOfMonth) return null
    return ProactiveGiftTrigger(ProactiveGiftTriggerType.BIRTHDAY, "用户生日", "user", now)
}

/** 相识纪念日命中里程碑天数（1:1 iOS `checkAnniversary`）。 */
internal fun checkAnniversary(firstMessageDate: Long?, now: Long): ProactiveGiftTrigger? {
    if (firstMessageDate == null) return null
    val days = wholeDaysBetween(firstMessageDate, now)
    if (days !in ANNIVERSARY_MILESTONE_DAYS) return null
    return ProactiveGiftTrigger(ProactiveGiftTriggerType.ANNIVERSARY, "相识 $days 天", "${days}d", now)
}

/** 节日命中（1:1 iOS `checkFestival`，取命中的第一个）。 */
internal fun checkFestival(now: Long): ProactiveGiftTrigger? {
    val festival = FestivalCalendar.festivalsMatching(now).firstOrNull() ?: return null
    return ProactiveGiftTrigger(ProactiveGiftTriggerType.FESTIVAL, festival.name, festival.id, now)
}

/**
 * 角色情绪历史 → 颜色序列，**按时间倒序（最近在前）**。
 *
 * moodHistory 以 append 序存储（最旧在前、最新在后）；而 [checkSenseLowMood] / recentMoodSummary 取「近 N 条」= 列表前 N 个，
 * 故喂入前必须按时间倒序，否则会读到「最旧 N 条」——聊得越久越跑偏（修复审计揪出的 1:1-iOS 移植隐患：原 take(3) 取了最旧 3）。
 */
internal fun recentMoodColors(moodHistory: List<MoodHistoryEntry>): List<String> =
    moodHistory.sortedByDescending { it.timestamp }.map { it.colorName }

/**
 * 察觉不开心：近 3 条 mood 中 red ≥ 2（输入须为按时间倒序的颜色序列，见 [recentMoodColors]）。metaId 固定 "current"
 * （不随 redCount 变化，否则心情刷新 2→3 条 red 会生成新幂等 key 让同一天重复送礼；iOS 方案 B 修复）。
 */
internal fun checkSenseLowMood(moodColors: List<String>, now: Long): ProactiveGiftTrigger? {
    val recent = moodColors.take(SENSE_LOW_MOOD_RECENT_COUNT)
    val redCount = recent.count { it == "red" }
    if (redCount < SENSE_LOW_MOOD_REQUIRED_RED) return null
    return ProactiveGiftTrigger(ProactiveGiftTriggerType.SENSE_LOW_MOOD, "察觉到 TA 最近心情低落", "current", now)
}

/** 想你（1:1 iOS `checkMissingYou`）：新角色保护期 → 硬底线 14 天 → 软概率 7-14 天 5%。 */
internal fun checkMissingYou(
    creationDate: Long,
    lastProactiveGiftDate: Long?,
    now: Long,
    randomValue: Double?,
): ProactiveGiftTrigger? {
    val ageDays = wholeDaysBetween(creationDate, now)
    if (ageDays < NEW_CHARACTER_PROTECTION_DAYS) return null

    val days = if (lastProactiveGiftDate != null) wholeDaysBetween(lastProactiveGiftDate, now) else Int.MAX_VALUE

    if (days >= HARD_MISSING_YOU_DAYS) {
        return ProactiveGiftTrigger(ProactiveGiftTriggerType.MISSING_YOU, "想你 · 好久没给 TA 带过东西了", "hard", now)
    }
    if (days >= SOFT_MISSING_YOU_MIN_DAYS) {
        val r = randomValue ?: Random.nextDouble()
        if (r < SOFT_MISSING_YOU_PROBABILITY) {
            return ProactiveGiftTrigger(ProactiveGiftTriggerType.MISSING_YOU, "想你", "soft", now)
        }
    }
    return null
}
