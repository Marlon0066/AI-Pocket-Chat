package com.situ.aichat.offline

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.random.Random

/**
 * 见面后「朋友圈呼应帖」的排程算术（卷二 §5④·图纸 §3.3）——**纯函数**，随机与时钟一律由调用方注入，
 * 不在内部读 `System.currentTimeMillis()`/无种 Random（可测、可复现）。
 *
 * 三件事：掷点决定这次见面要不要发（[shouldPost]）、首次延迟多久（[initialDelayMinutes]）、
 * 到点撞上深夜要顺延到第二天上午多久（[lateNightRescheduleMinutes]）。数值全部是卷二 M5 锁定值。
 */
object MeetingMomentEchoPlanner {

    /** 中签概率 75%（调用方传 `random.nextInt(100)`）：roll ∈ [0,74] 中签，75 起不发。 */
    fun shouldPost(roll: Int): Boolean = roll < POST_PROBABILITY_PERCENT

    /**
     * 首次延迟：见面结束后 3–7 小时（[MIN_DELAY_MINUTES], [MIN_DELAY_MINUTES]+[DELAY_SPAN_MINUTES]-1 = 180..420 分钟）
     * ——不是刚分开就发，像真人一样过一阵才想起来发条朋友圈。
     */
    fun initialDelayMinutes(random: Random): Long = MIN_DELAY_MINUTES + random.nextLong(DELAY_SPAN_MINUTES)

    /**
     * 深夜顺延：到点时本地时刻落在 [23:30, 24:00) ∪ [00:00, 07:00) → 返回「距下一个 09:00 的分钟数 +
     * 0–150 分钟随机」（落点 = 09:00–11:30），否则返回 null（不是深夜，照常发）。深夜不发圈是真实感，不是限流。
     *
     * 跨日/时区/夏令时全交 [ZonedDateTime] 算（[Duration.between] 走真实经过时间）；当前时刻按**整分**取
     * （距离以整分计，避免秒尾把落点挤到 09:00 之前）。
     */
    fun lateNightRescheduleMinutes(nowMillis: Long, zone: ZoneId, random: Random): Long? {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone).truncatedTo(ChronoUnit.MINUTES)
        val minuteOfDay = now.hour * 60 + now.minute
        val inLateNight = minuteOfDay >= LATE_NIGHT_START_MINUTE || minuteOfDay < LATE_NIGHT_END_MINUTE
        if (!inLateNight) return null
        // 下一个 09:00（今天的已过 → 取明天的）。
        val todayNine = now.toLocalDate().atTime(MORNING_HOUR, 0).atZone(zone)
        val target = if (todayNine.isAfter(now)) todayNine else todayNine.plusDays(1)
        return Duration.between(now, target).toMinutes() + random.nextLong(MORNING_SPAN_MINUTES)
    }

    /**
     * 呼应帖的**确定性 uuid**（卷二 M2·锁定种子串 `"moment:echo:$sessionId"`·先例
     * [com.situ.aichat.world.live.WorldVisitGreeter]）：一场见面至多一条——worker 被 WorkManager 重投、
     * 或重装恢复后再跑，upsert 同 uuid 天然幂等。**全工程唯一定义点**，服务与生成端都引它。
     */
    fun echoPostUuid(sessionId: String): String =
        UUID.nameUUIDFromBytes("$UUID_SEED_PREFIX$sessionId".toByteArray()).toString()

    /** 中签概率（百分比·M5）。 */
    private const val POST_PROBABILITY_PERCENT = 75

    /** 首延下界（分钟·= 3 小时）。 */
    private const val MIN_DELAY_MINUTES = 180L

    /** 首延跨度（分钟·180+240=420 = 7 小时·nextLong 上界开区间故取 241）。 */
    private const val DELAY_SPAN_MINUTES = 241L

    /** 深夜窗起点 23:30（分钟序）。 */
    private const val LATE_NIGHT_START_MINUTE = 23 * 60 + 30

    /** 深夜窗终点 07:00（分钟序·开区间：07:00 整不算深夜）。 */
    private const val LATE_NIGHT_END_MINUTE = 7 * 60

    /** 顺延落点的基准时刻（09:00）。 */
    private const val MORNING_HOUR = 9

    /** 顺延落点的随机跨度（0–150 分钟 → 09:00–11:30·nextLong 上界开区间故取 151）。 */
    private const val MORNING_SPAN_MINUTES = 151L

    /** uuid 种子串前缀（M2 锁定·改它 = 老帖失去幂等身份）。 */
    private const val UUID_SEED_PREFIX = "moment:echo:"
}
