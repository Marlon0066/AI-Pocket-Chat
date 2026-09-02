package com.situ.aichat.prompt

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 时间锚（方案 G2 → 布局审计刀2「现在卡」改造，2026-07-11 过审）：生成 `<time_context>` 块 =
 * 客观时间事实（当前时刻/星期/间隔）+ **间隔五档措辞**。设计要点：
 * - 间隔行自带方向：「{userLabel}隔了约 X 才回你」（项目里最后一条消息恒为角色的，方向恒定——用户补充拍板）；
 * - 用户称呼**块级单源** [USER_LABEL_FALLBACK] 规则：有昵称叫昵称、空才叫「对方」，相识行与间隔行共用（图纸 §13）；
 * - 五档取代旧的一刀切「重新拿起手机」段：短憩(<10min 静默)/小隔(10min–2h 仅间隔行)/半日(≥2h，
 *   按时长分"这几个小时/这大半天")/跨夜(隔 1 自然日且 ≥6h)/数日(2–7 天)/久别(>7 天)；
 * - 只给事实与气口，不预设情绪（时间感知专项原则：状态交 AI 按人设判断）；
 * - 「间隔里的生活」不注入内容（与日程 [✓已发生] 行重复，用户拍板砍）——由档位措辞一句带过；
 * - 「（这段是给你看的，不要在回复里输出。）」尾注已随现在卡合并移至 currentMoment 末尾。
 *
 * 注入在 suffix 区**物理末位**（与 currentMoment 合并为一条「现在卡」，见 PromptBuilder 第 5 步）。
 * All times use the system default zone (HyperOS default Asia/Shanghai). Weekday is a hand-written map
 * (not locale-dependent) so output is stable regardless of device language.
 */
object TimeAnchorFormatter {

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val dateOnlyFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeOnlyFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val chineseWeekdayChars = listOf("日", "一", "二", "三", "四", "五", "六")

    /** 相识事实（现在卡第二行·相识天数图纸 §4.2）：[firstMessageDate] = 角色「第一次聊天时间」epoch ms；[streakCount] = 火花连续天数。用户称呼不在此，见 [buildTimeAnchor] 的 `userLabel`。 */
    data class AcquaintanceFacts(val firstMessageDate: Long, val streakCount: Int)

    /** 连续聊天半句门槛（D-8）。 */
    internal const val STREAK_MENTION_MIN_DAYS = 7

    /**
     * 昵称为空时的用户称呼（D-6）。**块级单源**：相识行与间隔行共用同一个 `userLabel`
     * （相识天数 R1 用户拍板 2026-09-03·图纸 §13），全 App 同一条规则「有昵称叫名字、没有才叫『对方』」。
     */
    internal const val USER_LABEL_FALLBACK = "对方"

    /**
     * @param directionalGapLine 间隔行措辞:true=「{userLabel}隔了约X才回你」(即时聊天,最后一条恒为角色的,方向成立);
     * false=中性「距离你上条回复：约X」——**延迟生成路**(进程恢复补生成)必须用中性:那段延迟是系统的,
     * 方向化会把锅甩给用户(T5 复核🟡④·2026-07-11 修)。
     * @param userLabel 块内对用户的统一称呼：昵称非空即昵称，空则 [USER_LABEL_FALLBACK]；相识行与方向化间隔行共用。
     */
    fun buildTimeAnchor(
        now: Instant,
        lastAssistantTime: Instant?,
        directionalGapLine: Boolean = true,
        acquaintance: AcquaintanceFacts? = null,
        userLabel: String = USER_LABEL_FALLBACK,
    ): String {
        val lines = mutableListOf<String>()
        lines.add(formatCurrentMoment(now))
        // 相识行（图纸 §4.2）：现在卡第二行，紧随「现在：…」——与现在日期并排，模型可自算周年。
        val acqLine = acquaintance?.let { acquaintanceLine(it, now, userLabel) }
        acqLine?.let { lines.add(it) }

        // 首次对话：没 assistant 历史就认定新会话
        if (lastAssistantTime == null) {
            // D-9：相识行在场时不出「第一次对话」句——老角色新开对话串，两句同段自相矛盾。
            if (acqLine == null) lines.add("这是你们的第一次对话")
            return wrap(lines, tierNote = null)
        }

        formatSinceLastAssistant(now, lastAssistantTime, directionalGapLine, userLabel)?.let { lines.add(it) }
        val seconds = Duration.between(lastAssistantTime, now).seconds
        return wrap(lines, gapTierNote(now, lastAssistantTime, seconds))
    }

    /**
     * 线下见面专版（前后置区审计 🟡-1b·2026-07-13 拍板）：只给客观时刻事实（现在行 + 保真附言），
     * 不带间隔行/五档/首次对话——"回你""重新拿起手机"是短信框架措辞，面对面场景退场；
     * 见面的时间推进由末位九标签说明书承担，此处保留时段词（清晨/深夜）作场景补充。
     */
    fun buildTimeAnchorFactsOnly(now: Instant): String = wrap(listOf(formatCurrentMoment(now)), tierNote = null)

    fun formatCurrentMoment(instant: Instant): String {
        // 客观时段标签（清晨/上午/…/深夜）——只给「现在是什么时候」的事实，不替角色预设「该是什么状态」。
        val period = scheduleTimeOfDayLabel(instant.atZone(zone).hour)
        return "现在：${formatDateOnly(instant)} ${formatWeekday(instant)} ${formatTimeOnly(instant)}（$period）"
    }

    /**
     * 间隔行（自带方向）：间隔 < 10 分钟返回 null（正常聊天节奏不显示）；否则「{userLabel}隔了约 X 才回你」。
     * 旧「（跨夜）/（跨日）」后缀已废——跨夜语义由五档措辞承担，不再叠标注。
     */
    fun formatSinceLastAssistant(
        now: Instant,
        lastAssistantTime: Instant?,
        directional: Boolean = true,
        userLabel: String = USER_LABEL_FALLBACK,
    ): String? {
        if (lastAssistantTime == null) return null
        val seconds = Duration.between(lastAssistantTime, now).seconds
        if (seconds < 600) return null
        val duration = gapDurationForLine(seconds)
        // 相识天数 R1 用户拍板（图纸 §13）：方向化间隔行改用块级 [userLabel]，与相识行同一个称呼；
        // 昵称为空时退回 [USER_LABEL_FALLBACK] = 旧文案「对方隔了…才回你」逐字不变。
        // 中性变体（延迟生成路）不提人，保持原样。
        return if (directional) "${userLabel}隔了${duration}才回你" else "距离你上条回复：$duration"
    }

    /** 间隔行用时长文案：复用 [formatDuration]，仅 ≥1 年档改成能嵌进句子的「一年多」。 */
    private fun gapDurationForLine(seconds: Long): String {
        val text = formatDuration(seconds)
        return if (text == "好久没联系了") "一年多" else text
    }

    /**
     * 间隔五档措辞（刀2 过审表）。< 2 小时 → null（短憩/小隔无附言）；同日或跨 1 日但 <6h → 半日（几个小时）；
     * 同日 ≥6h → 半日（大半天）；跨 1 自然日且 ≥6h → 跨夜；2–7 天 → 数日；更久 → 久别。
     * 命中任一档均追加「长期持续的事自然延续」保命附言。
     * 深夜边界（23:00→01:30 = 跨日但仅 2.5h）刻意落半日档——「睡过一觉是新的一天」对熬夜快回的人是错误预设。
     * 双跨日边界（T5 复核🟡·前天 23:59→今天 00:30 = 跨 2 日历日但实际约 1 天）落跨夜档——
     * 否则间隔行「约 1 天」和数日档「隔了好几天」同段自相矛盾;<36h 的日历日差按实际时长归跨夜。
     */
    internal fun gapTierNote(now: Instant, lastAssistantTime: Instant, seconds: Long): String? {
        if (seconds < 2 * 3600) return null
        val daysDiff = calendarDayDifference(lastAssistantTime, now)
        val tier = when {
            daysDiff == 0 -> if (seconds < 6 * 3600) TIER_FEW_HOURS else TIER_MOST_OF_DAY
            daysDiff == 1 -> if (seconds < 6 * 3600) TIER_FEW_HOURS else TIER_OVERNIGHT
            daysDiff <= 7 -> if (seconds < 36 * 3600) TIER_OVERNIGHT else TIER_FEW_DAYS
            else -> TIER_LONG_GAP
        }
        return tier + PERSISTENT_NOTE
    }

    /**
     * 秒数 → 人类可读时长（细化刻度：越短越精确，越长越粗）。
     * <1h 凑 5 分钟「约 N 分钟」/ <24h 凑整点「约 N 小时」/ <7d「约 N 天」/ ~4 周内「约 N 周」/ <1y「约 N 个月」/ 更久「好久没联系了」。
     * 仅 [formatSinceLastAssistant] 用（已挡 <10 分钟），故无需为 0 特判。
     */
    fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60.0
        val hours = seconds / 3600.0
        val days = seconds / 86400.0

        if (hours < 1.0) {
            val rounded5 = (Math.round(minutes / 5.0) * 5).toInt().coerceAtLeast(5)
            if (rounded5 < 60) return "约 $rounded5 分钟"
            // 凑到 60 分钟 → 落入小时档
        }
        val roundedHours = Math.round(hours).toInt()
        if (roundedHours < 24) return "约 ${roundedHours.coerceAtLeast(1)} 小时"
        val roundedDays = Math.round(days).toInt()
        if (roundedDays < 7) return "约 ${roundedDays.coerceAtLeast(1)} 天"
        val roundedWeeks = Math.round(days / 7.0).toInt()
        if (roundedWeeks < 5) return "约 ${roundedWeeks.coerceAtLeast(1)} 周"
        val months = Math.floor(days / 30.0).toInt()
        if (months < 12) return "约 ${months.coerceAtLeast(1)} 个月"
        return "好久没联系了"
    }

    /** 自然日差（基于本地日期，不受时分干扰）。 */
    fun calendarDayDifference(from: Instant, to: Instant): Int {
        val fromDay = from.atZone(zone).toLocalDate()
        val toDay = to.atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(fromDay, toDay).toInt()
    }

    private fun formatDateOnly(instant: Instant): String =
        instant.atZone(zone).toLocalDate().format(dateOnlyFmt)

    private fun formatTimeOnly(instant: Instant): String =
        instant.atZone(zone).toLocalTime().format(timeOnlyFmt)

    private fun formatWeekday(instant: Instant): String {
        // java DayOfWeek: MON=1..SUN=7 → 映射到 iOS 的 [日一二三四五六]（日=0）
        val dow = instant.atZone(zone).dayOfWeek.value % 7  // SUN(7)→0, MON(1)→1, ... SAT(6)→6
        return "周${chineseWeekdayChars[dow]}"
    }

    /**
     * 相识行（图纸 §4.2·D-6/D-7/D-8）：「你和{昵称}是 yyyy-MM-dd 第一次聊天认识的，到今天相识 N 天。」
     * 天数 = 本地日历日差；当天（0）与未来（负·时钟回拨/坏值）一律不出行。连续聊天满 [STREAK_MENTION_MIN_DAYS]
     * 天、且不超过相识天数 +1（坏数据不上屏）时追加半句「，最近连续 N 天每天都聊。」。
     * 角色恒称「你」、用户用块级 [userLabel]（空昵称退 [USER_LABEL_FALLBACK]）——与同块间隔行**同一个称呼**（图纸 §13）。
     */
    internal fun acquaintanceLine(facts: AcquaintanceFacts, now: Instant, userLabel: String = USER_LABEL_FALLBACK): String? {
        val first = Instant.ofEpochMilli(facts.firstMessageDate)
        val days = calendarDayDifference(first, now)
        if (days < 1) return null
        val base = "你和${userLabel}是 ${formatDateOnly(first)} 第一次聊天认识的，到今天相识 $days 天"
        return if (facts.streakCount >= STREAK_MENTION_MIN_DAYS && facts.streakCount <= days + 1) {
            "$base，最近连续 ${facts.streakCount} 天每天都聊。"
        } else {
            "$base。"
        }
    }

    /**
     * 包成 <time_context> 块 + 事实后附言：基础护栏一句始终在；[tierNote] 非空时接续同段（五档措辞 + 保命附言）。
     * 「（这段是给你看的，不要在回复里输出。）」已移至 currentMoment 末尾（现在卡合并后全卡只留一处）。
     */
    private fun wrap(lines: List<String>, tierNote: String?): String {
        val xmlBlock = "<time_context>\n${lines.joinToString("\n")}\n</time_context>"
        val note = buildString {
            append("↑ 以上是此刻的真实时间，以它为准。")
            if (tierNote != null) append(tierNote)
        }
        return "$xmlBlock\n$note"
    }

    // MARK: - 五档措辞（刀2 过审终稿·只给事实与气口，不预设情绪）

    internal const val TIER_FEW_HOURS =
        "这几个小时你在过自己的生活，现在才重新拿起手机——上次聊到一半的场景已经过去了。"
    internal const val TIER_MOST_OF_DAY =
        "这大半天你在过自己的生活，现在才重新拿起手机——上次聊到一半的场景已经过去了。"
    internal const val TIER_OVERNIGHT =
        "你已经睡过一觉，是新的一天了——昨天聊的事是昨天的，今天有今天的状态。"
    internal const val TIER_FEW_DAYS =
        "隔了好几天——这几天你照常过自己的日子，接话时可以自然带一点这几天的生活气。"
    internal const val TIER_LONG_GAP =
        "很久没联系了——上次聊天的细节你可以记得模糊些，你的近况也该有变化；找回联系的感觉，但别刻意煽情。"
    internal const val PERSISTENT_NOTE =
        "长期、持续的事（还在感冒、人在外地）本来就会延续——具体哪些还算数、此刻你是什么状态，你按现在的时间自己判断。"
}
