package com.situ.aichat.prompt.ourdays

import com.situ.aichat.data.local.entity.OurDayEntity
import com.situ.aichat.ourdays.OurDayKey
import com.situ.aichat.prompt.PromptBuilder
import java.time.Instant
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId

/**
 * `{{我们的日子}}` 宏 producer 入口（卷二图纸 §3.3）：从 ctx 取材后委托纯核 [OurDaysInjection.render]。
 * `ctx.ourDays` 空 ⇒ `""`（模块整块跳过·提示词字节级不变·E40）。时区 = 系统默认（`prompt` 包既有口径）；
 * 生日取月日（2/29 由 `MonthDay.atYear` 自然钳位）。
 */
internal fun buildOurDaysContent(ctx: PromptBuilder.BuildContext): String {
    if (ctx.ourDays.isEmpty()) return ""
    val zone = ZoneId.systemDefault()
    val today = ctx.now.atZone(zone).toLocalDate()
    fun monthDay(millis: Long?): MonthDay? = millis?.let { MonthDay.from(Instant.ofEpochMilli(it).atZone(zone)) }
    return OurDaysInjection.render(
        rows = ctx.ourDays,
        turnText = ctx.ourDaysTurnText,
        today = today,
        windowEarliestMillis = ctx.windowEarliestMillis,
        zone = zone,
        characterName = ctx.resolvedCharacterName,
        userRefName = ctx.resolvedUserName,
        characterBirthday = monthDay(ctx.character.birthday),
        userBirthday = monthDay(ctx.userProfile?.birthday),
    )
}

/**
 * 「我们的日子」注入块渲染（卷二图纸 §3.4 算法 + §4.1 文案·逐字锁定·W-1）：块内只放**日期指名 + 那年今日**两路；
 * 向量路在检索片段层（[com.situ.aichat.prompt.memory.VectorMemoryService]）不进本块。
 *
 * 纯函数：[rows] 已是注入候选（DAO 谓词 `deleted = 0 ∧ hiddenFromMemory = 0 ∧ factLine != ''`），本对象只筛选渲染；
 * 零 DB、零时钟裸取。今天永不出行；原文窗口内的日子永不出行；产出 `""` = 模块整块跳过（E40）。
 *
 * 行格式 `[yyyy-MM-dd 周X] factLine` 与 `pb_mem_format_ban` 既有禁令 / `OurDayNoteParser.DATE_PREFIX` 强耦合
 * （REDLINES §1 登记·改格式须四处同步）。
 */
internal object OurDaysInjection {

    /** 块头两行（§4.1 逐字）。 */
    private const val HEADER_TITLE = "[我们的日子 · 按日期翻到的记录]"
    private fun headerLine(characterName: String, userRefName: String): String =
        "这是${characterName}和${userRefName}当天的记录。同一天若与记忆里的概括有出入，以这里为准。"
    private fun rangeHeader(days: Int): String = "那段时间你们有 $days 天有记录，事最多的几天："
    private const val ONE_YEAR_AGO_PREFIX = "一年前的今天："
    private const val ONE_MONTH_AGO_PREFIX = "一个月前的今天："

    /** 日期指名行上限 / 范围段事件最多天数 / 那年今日行数（§9.2 锁定 7 / 5 / 2）。 */
    private const val DATE_LINES_MAX = 7
    private const val RANGE_TOP = 5

    /** 行格式单源（§3.4）。 */
    internal fun dayLine(row: OurDayEntity): String = "[${row.dayKey} ${OurDayKey.weekdayCn(row.dayKey)}] ${row.factLine}"

    /** 热度分（卷一单源口径 `messageCount + (callSeconds / 60) * 3`·范围段排序用）。 */
    private fun heat(row: OurDayEntity): Int = row.messageCount + (row.callSeconds / 60) * 3

    fun render(
        rows: List<OurDayEntity>,
        turnText: String,
        today: LocalDate,
        windowEarliestMillis: Long?,
        zone: ZoneId,
        characterName: String,
        userRefName: String,
        characterBirthday: MonthDay?,
        userBirthday: MonthDay?,
    ): String {
        if (rows.isEmpty()) return ""
        val byKey = rows.associateBy { it.dayKey }
        val windowStartKey = windowEarliestMillis?.let { OurDayKey.dayKey(it, zone) }
        val todayKey = OurDayKey.keyOf(today)
        fun injectable(key: String): Boolean =
            byKey[key] != null && key < todayKey && (windowStartKey == null || key < windowStartKey)

        val meetingKeys = rows.filter { it.hasMeeting }.map { it.dayKey }
        val anchors = OurDayDateMention.Anchors(
            firstDay = byKey.keys.minOrNull()?.let(OurDayKey::parse),
            firstMeetingDay = meetingKeys.minOrNull()?.let(OurDayKey::parse),
            lastMeetingDay = meetingKeys.maxOrNull()?.let(OurDayKey::parse),
            userBirthday = userBirthday,
            characterBirthday = characterBirthday,
        )
        val mentions = OurDayDateMention.resolve(turnText, today, anchors, zone)

        val dateKeys = mentions.days.map(OurDayKey::keyOf).filter(::injectable).distinct().take(DATE_LINES_MAX).sorted()

        val rangeKeys: List<String> = mentions.range?.let { r ->
            val startKey = OurDayKey.keyOf(r.start)
            val endKey = OurDayKey.keyOf(r.endInclusive)
            byKey.keys.filter { it in startKey..endKey && injectable(it) }
        }.orEmpty()
        // R1 🔵-5：已在日期行里的日子不再进「事最多的几天」（同日一行·E43）；天数 header 仍按 rangeKeys 全数计。
        val rangeTop = rangeKeys.filter { it !in dateKeys }.sortedByDescending { heat(byKey.getValue(it)) }.take(RANGE_TOP).sorted()

        val anniversaries = listOf(
            ONE_YEAR_AGO_PREFIX to OurDayKey.keyOf(today.minusYears(1)),
            ONE_MONTH_AGO_PREFIX to OurDayKey.keyOf(today.minusMonths(1)),
        ).filter { (_, key) -> injectable(key) && key !in dateKeys && key !in rangeTop }

        if (dateKeys.isEmpty() && rangeTop.isEmpty() && anniversaries.isEmpty()) return ""

        val lines = ArrayList<String>()
        lines += HEADER_TITLE
        lines += headerLine(characterName, userRefName)
        for (key in dateKeys) lines += dayLine(byKey.getValue(key))
        if (rangeTop.isNotEmpty()) {
            lines += rangeHeader(rangeKeys.size)
            for (key in rangeTop) lines += dayLine(byKey.getValue(key))
        }
        for ((prefix, key) in anniversaries) lines += prefix + dayLine(byKey.getValue(key))
        return lines.joinToString("\n")
    }
}
