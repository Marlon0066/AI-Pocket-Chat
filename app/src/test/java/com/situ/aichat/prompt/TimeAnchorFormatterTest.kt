package com.situ.aichat.prompt

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * 时间锚（刀2 现在卡·间隔五档规格,2026-07-11 过审）测试。断言从过审表独立反推：
 * - 间隔行自带方向：「{userLabel}隔了约 X 才回你」（§13：有昵称叫昵称、空退「对方」）；<10 分钟静默；≥1 年档句内用「一年多」；
 * - 五档：<2h 无附言 / 同日或跨日但 <6h=半日(几个小时) / 同日 ≥6h=半日(大半天) / 跨 1 日且 ≥6h=跨夜 /
 *   2–7 天=数日 / >7 天=久别；命中任一档追加「长期持续」保命附言；
 * - 深夜边界（23:00→01:30 跨日 2.5h）落半日档，不预设「睡过一觉」；
 * - 「（这段是给你看的…）」尾注已移至 currentMoment（本类输出不得再含）。
 * 时长分档表 formatDuration 规格未变。时区钉死 Asia/Shanghai 保证确定性。
 */
class TimeAnchorFormatterTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private lateinit var originalTz: TimeZone

    @Before
    fun pinTimeZone() {
        originalTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTz)
    }

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Instant =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant()

    private fun tier(now: Instant, last: Instant): String? =
        TimeAnchorFormatter.gapTierNote(now, last, Duration.between(last, now).seconds)

    // MARK: - formatDuration 分档边界（规格未变）

    @Test
    fun duration_buckets_fullTable() {
        assertEquals("约 25 分钟", TimeAnchorFormatter.formatDuration(23 * 60))
        assertEquals("约 1 小时", TimeAnchorFormatter.formatDuration(58 * 60)) // 凑到 60 分 → 进位 1 小时
        assertEquals("约 3 小时", TimeAnchorFormatter.formatDuration(3 * 3600))
        assertEquals("约 23 小时", TimeAnchorFormatter.formatDuration(23 * 3600))
        assertEquals("约 1 天", TimeAnchorFormatter.formatDuration(24 * 3600))
        assertEquals("约 1 周", TimeAnchorFormatter.formatDuration(7 * 86400))
        assertEquals("约 2 个月", TimeAnchorFormatter.formatDuration(70L * 86400))
        assertEquals("好久没联系了", TimeAnchorFormatter.formatDuration(400L * 86400))
    }

    // MARK: - 间隔行（方向化措辞）

    @Test
    fun sinceLast_underTenMinutes_silent() {
        val now = at(2026, 6, 13, 12, 9)
        assertNull(TimeAnchorFormatter.formatSinceLastAssistant(now, at(2026, 6, 13, 12, 0)))
    }

    @Test
    fun sinceLast_directionalWording_noCrossMarker() {
        val s = TimeAnchorFormatter.formatSinceLastAssistant(at(2026, 6, 13, 15, 0), at(2026, 6, 13, 12, 0))
        assertEquals("对方隔了约 3 小时才回你", s)
        // 旧「（跨夜）/（跨日）」后缀已废——跨夜语义由五档措辞承担。
        val overnight = TimeAnchorFormatter.formatSinceLastAssistant(at(2026, 6, 13, 8, 0), at(2026, 6, 12, 23, 0))
        assertEquals("对方隔了约 9 小时才回你", overnight)
    }

    @Test
    fun sinceLast_delayedGeneration_neutralWording() {
        // 延迟生成路(进程恢复):间隔是系统欠的 → 中性措辞,绝不说「对方隔了…才回你」(T5 复核🟡④)。
        val s = TimeAnchorFormatter.formatSinceLastAssistant(at(2026, 6, 13, 20, 0), at(2026, 6, 13, 12, 0), directional = false)
        assertEquals("距离你上条回复：约 8 小时", s)
        val anchor = TimeAnchorFormatter.buildTimeAnchor(at(2026, 6, 13, 20, 0), at(2026, 6, 13, 12, 0), directionalGapLine = false)
        assertEquals(false, anchor.contains("对方隔了"))
        assertTrue(anchor.contains("距离你上条回复"))
    }

    @Test
    fun sinceLast_overOneYear_sentenceCompatible() {
        val s = TimeAnchorFormatter.formatSinceLastAssistant(at(2027, 8, 1, 12, 0), at(2026, 6, 1, 12, 0))
        assertEquals("对方隔了一年多才回你", s)
    }

    // MARK: - 五档边界

    @Test
    fun tier_underTwoHours_noNote() {
        assertNull(tier(at(2026, 6, 13, 13, 59), at(2026, 6, 13, 12, 0)))
    }

    @Test
    fun tier_sameDayFewHours_halfDayFewHours() {
        val note = tier(at(2026, 6, 13, 15, 0), at(2026, 6, 13, 12, 0))!!
        assertTrue(note.startsWith(TimeAnchorFormatter.TIER_FEW_HOURS))
        assertTrue("命中档位必带保命附言", note.contains("长期、持续的事"))
    }

    @Test
    fun tier_sameDayOverSixHours_mostOfDay() {
        val note = tier(at(2026, 6, 13, 20, 0), at(2026, 6, 13, 7, 0))!!
        assertTrue(note.startsWith(TimeAnchorFormatter.TIER_MOST_OF_DAY))
    }

    @Test
    fun tier_overnightBigGap_overnightNote() {
        val note = tier(at(2026, 6, 13, 8, 0), at(2026, 6, 12, 23, 0))!!
        assertTrue(note.startsWith(TimeAnchorFormatter.TIER_OVERNIGHT))
    }

    @Test
    fun tier_lateNightShortCross_fallsToHalfDay_notOvernight() {
        // 23:00 → 次日 01:30（跨日但仅 2.5h）：熬夜快回,「睡过一觉」是错误预设 → 半日档。
        val note = tier(at(2026, 6, 13, 1, 30), at(2026, 6, 12, 23, 0))!!
        assertTrue(note.startsWith(TimeAnchorFormatter.TIER_FEW_HOURS))
    }

    @Test
    fun tier_doubleCalendarDayButOneActualDay_fallsToOvernight() {
        // T5 复核🟡修：前天 23:30 → 今天 00:30（跨 2 日历日但实际 25h）→ 跨夜档,
        // 与间隔行「约 1 天」一致,不再说「隔了好几天」。
        val note = tier(at(2026, 6, 13, 0, 30), at(2026, 6, 11, 23, 30))!!
        assertTrue(note.startsWith(TimeAnchorFormatter.TIER_OVERNIGHT))
    }

    @Test
    fun tier_fewDays_and_longGap() {
        val fewDays = tier(at(2026, 6, 15, 10, 0), at(2026, 6, 12, 10, 0))!!
        assertTrue(fewDays.startsWith(TimeAnchorFormatter.TIER_FEW_DAYS))
        val longGap = tier(at(2026, 6, 30, 10, 0), at(2026, 6, 12, 10, 0))!!
        assertTrue(longGap.startsWith(TimeAnchorFormatter.TIER_LONG_GAP))
    }

    // MARK: - 当前时刻与星期映射（未变）

    @Test
    fun currentMoment_knownMondayAndSunday() {
        assertEquals("现在：2024-01-01 周一 14:30（下午）", TimeAnchorFormatter.formatCurrentMoment(at(2024, 1, 1, 14, 30)))
        assertEquals("现在：2024-01-07 周日 08:05（清晨）", TimeAnchorFormatter.formatCurrentMoment(at(2024, 1, 7, 8, 5)))
    }

    // MARK: - buildTimeAnchor 整体结构

    @Test
    fun buildAnchor_firstConversation_marked() {
        val out = TimeAnchorFormatter.buildTimeAnchor(at(2026, 6, 13, 12, 0), null)
        assertTrue(out.contains("这是你们的第一次对话"))
        assertTrue(out.startsWith("<time_context>") && out.contains("</time_context>"))
        assertTrue("基础护栏始终在", out.contains("以上是此刻的真实时间"))
    }

    @Test
    fun buildAnchor_normalRhythm_onlyCurrentLine() {
        val out = TimeAnchorFormatter.buildTimeAnchor(at(2026, 6, 13, 12, 5), at(2026, 6, 13, 12, 0))
        assertTrue(out.contains("现在：2026-06-13"))
        assertEquals(false, out.contains("对方隔了"))
        assertEquals(false, out.contains("重新拿起手机"))
    }

    @Test
    fun buildAnchor_privateNoteMovedToCurrentMoment() {
        // 「（这段是给你看的…）」尾注已随现在卡合并移至 currentMoment 末尾——时间锚任何档位都不再输出。
        val out = TimeAnchorFormatter.buildTimeAnchor(at(2026, 6, 20, 12, 0), at(2026, 6, 12, 10, 0))
        assertEquals(false, out.contains("这段是给你看的"))
        assertTrue(out.contains(TimeAnchorFormatter.TIER_LONG_GAP))
    }

    // MARK: - 相识行（相识天数图纸 §4.2·断言从锁定文案与 D-6/D-7/D-8/D-9 独立反推）

    /** 块内第 N 行（0 = 现在行、1 = 相识行）——只看 <time_context> 块内，尾注不算。 */
    private fun blockLine(anchor: String, index: Int): String =
        anchor.substringAfter("<time_context>\n").substringBefore("\n</time_context>").split("\n")[index]

    private fun facts(first: Instant, streak: Int) =
        TimeAnchorFormatter.AcquaintanceFacts(first.toEpochMilli(), streak)

    /** 带昵称的整块（图纸 §13：`userLabel` 是块级单源，相识行与方向化间隔行共用）。 */
    private fun anchorWith(
        now: Instant,
        last: Instant?,
        first: Instant,
        streak: Int,
        label: String = "小明",
        directional: Boolean = true,
    ): String = TimeAnchorFormatter.buildTimeAnchor(
        now, last, directionalGapLine = directional, acquaintance = facts(first, streak), userLabel = label,
    )

    @Test
    fun 相识行_有昵称_逐字() {
        val now = at(2026, 9, 2, 21, 15)
        val out = anchorWith(now, now.minus(Duration.ofHours(1)), at(2026, 6, 1, 10, 0), streak = 3)
        // 93 = 6 月 30 天 + 7 月 31 + 8 月 31 + 9/2 那 1 天（手算）
        assertEquals("现在：2026-09-02 周三 21:15（晚上）", blockLine(out, 0))
        assertEquals("你和小明是 2026-06-01 第一次聊天认识的，到今天相识 93 天。", blockLine(out, 1))
    }

    @Test
    fun 相识行_连续满7天追加半句() {
        val now = at(2026, 9, 2, 21, 15)
        val out = anchorWith(now, now.minus(Duration.ofHours(1)), at(2026, 6, 1, 10, 0), streak = 12)
        assertEquals("你和小明是 2026-06-01 第一次聊天认识的，到今天相识 93 天，最近连续 12 天每天都聊。", blockLine(out, 1))
    }

    @Test
    fun 相识行_连续6天不追加半句() {
        val now = at(2026, 9, 2, 21, 15)
        val out = anchorWith(now, now.minus(Duration.ofHours(1)), at(2026, 6, 1, 10, 0), streak = 6)
        assertEquals("不满一周像打卡记录，不上屏", "你和小明是 2026-06-01 第一次聊天认识的，到今天相识 93 天。", blockLine(out, 1))
    }

    @Test
    fun 相识行_连续超过相识天数不追加半句() {
        // 坏数据（连续 7 天 > 相识 3 天 + 1）不上屏。
        val now = at(2026, 9, 2, 21, 15)
        val out = anchorWith(now, now.minus(Duration.ofHours(1)), at(2026, 8, 30, 10, 0), streak = 7)
        assertEquals("你和小明是 2026-08-30 第一次聊天认识的，到今天相识 3 天。", blockLine(out, 1))
    }

    @Test
    fun 相识行_昵称为空时用对方() {
        val now = at(2026, 9, 2, 21, 15)
        val out = anchorWith(
            now, now.minus(Duration.ofHours(1)), at(2026, 6, 1, 10, 0), streak = 3,
            label = TimeAnchorFormatter.USER_LABEL_FALLBACK,
        )
        assertEquals("你和对方是 2026-06-01 第一次聊天认识的，到今天相识 93 天。", blockLine(out, 1))
    }

    @Test
    fun 相识行_当天认识不出行_输出逐字节同无相识事实() {
        val now = at(2026, 9, 2, 21, 15)
        val last = now.minus(Duration.ofHours(1))
        val out = anchorWith(now, last, at(2026, 9, 2, 8, 0), streak = 3, label = TimeAnchorFormatter.USER_LABEL_FALLBACK)
        assertEquals(TimeAnchorFormatter.buildTimeAnchor(now, last), out)
    }

    @Test
    fun 相识行_未来日期不出行_输出逐字节同无相识事实() {
        // 时钟回拨 / 坏值：首聊时间在「今天」之后 → 日历日差为负 → 不出行。
        val now = at(2026, 9, 2, 21, 15)
        val last = now.minus(Duration.ofHours(1))
        val out = anchorWith(now, last, at(2026, 9, 3, 8, 0), streak = 3, label = TimeAnchorFormatter.USER_LABEL_FALLBACK)
        assertEquals(TimeAnchorFormatter.buildTimeAnchor(now, last), out)
    }

    @Test
    fun 相识行_跨零点按日历日算1天() {
        // 09-01 23:59 首聊 → 09-02 00:01 提问：实际只隔 2 分钟，但日历日差 = 1。
        val now = at(2026, 9, 2, 0, 1)
        val out = anchorWith(now, now.minus(Duration.ofMinutes(1)), at(2026, 9, 1, 23, 59), streak = 2)
        assertEquals("你和小明是 2026-09-01 第一次聊天认识的，到今天相识 1 天。", blockLine(out, 1))
    }

    @Test
    fun 首次对话_有相识行时不出第一次对话句_无事实时逐字回归() {
        val now = at(2026, 9, 2, 21, 15)
        val withFacts = anchorWith(now, null, at(2026, 6, 1, 10, 0), streak = 3)
        assertEquals(false, withFacts.contains("这是你们的第一次对话"))
        assertEquals(
            "<time_context>\n现在：2026-09-02 周三 21:15（晚上）\n" +
                "你和小明是 2026-06-01 第一次聊天认识的，到今天相识 93 天。\n</time_context>\n" +
                "↑ 以上是此刻的真实时间，以它为准。",
            withFacts,
        )
        // 回归钉：不传相识事实 → 与改前逐字节相同（现在行 + 第一次对话句）。
        assertEquals(
            "<time_context>\n现在：2026-09-02 周三 21:15（晚上）\n这是你们的第一次对话\n</time_context>\n" +
                "↑ 以上是此刻的真实时间，以它为准。",
            TimeAnchorFormatter.buildTimeAnchor(now, null),
        )
    }

    @Test
    fun 相识行_延迟生成路照出_间隔行仍中性() {
        val now = at(2026, 9, 2, 21, 15)
        val out = anchorWith(now, at(2026, 9, 2, 13, 15), at(2026, 6, 1, 10, 0), streak = 3, directional = false)
        assertEquals("你和小明是 2026-06-01 第一次聊天认识的，到今天相识 93 天。", blockLine(out, 1))
        assertTrue("延迟生成路的间隔行仍是中性措辞", out.contains("距离你上条回复：约 8 小时"))
        // 中性变体一个人都不提：块内没有任何一行是方向化间隔行（「…隔了…才回你」），既不是「对方隔了」也不是「小明隔了」。
        // ⚠️ 不能写成 `!out.contains("隔了")`：五档措辞里的 [TIER_FEW_DAYS] 本身就是「隔了好几天——…」，
        // 那样的断言只是碰巧对 8 小时这个输入成立，换成 2–7 天的间隔就会误红（自查发现，2026-09-03）。
        val block = out.substringAfter("<time_context>\n").substringBefore("\n</time_context>").split("\n")
        assertEquals("块内不得出现方向化间隔行", 0, block.count { it.endsWith("才回你") })
    }

    /**
     * 自查补例（2026-09-03）：延迟生成路 + **数日档**——五档措辞 [TIER_FEW_DAYS] 自带「隔了好几天」，
     * 但中性间隔行仍不提人。这条正是上一例那句过宽断言会误红的输入，钉住「不能靠 `contains("隔了")` 判有没有方向化间隔行」。
     */
    @Test
    fun 延迟生成路_数日档_五档带隔了但间隔行仍中性() {
        val now = at(2026, 9, 5, 21, 15)
        val out = anchorWith(now, at(2026, 9, 2, 13, 15), at(2026, 6, 1, 10, 0), streak = 3, directional = false)
        assertTrue("命中数日档措辞", out.contains(TimeAnchorFormatter.TIER_FEW_DAYS))
        assertTrue("含「隔了」但那是五档措辞，不是间隔行", out.contains("隔了"))
        val block = out.substringAfter("<time_context>\n").substringBefore("\n</time_context>").split("\n")
        assertEquals("块内不得出现方向化间隔行", 0, block.count { it.endsWith("才回你") })
        assertTrue("中性间隔行照常在", block.any { it.startsWith("距离你上条回复：") })
    }

    // MARK: - 间隔行称呼（图纸 §13·用户拍板 2026-09-03：块级单源 userLabel）

    @Test
    fun 间隔行_有昵称时用昵称() {
        val s = TimeAnchorFormatter.formatSinceLastAssistant(
            at(2026, 6, 13, 15, 0), at(2026, 6, 13, 12, 0), userLabel = "小明",
        )
        assertEquals("小明隔了约 3 小时才回你", s)
    }

    @Test
    fun 间隔行_昵称为空时逐字回退旧文案() {
        // 回归钉：不传称呼（= 昵称为空退 USER_LABEL_FALLBACK）时与 §13 之前逐字相同。
        val s = TimeAnchorFormatter.formatSinceLastAssistant(at(2026, 6, 13, 15, 0), at(2026, 6, 13, 12, 0))
        assertEquals("对方隔了约 3 小时才回你", s)
    }

    @Test
    fun 现在卡_有昵称时相识行与间隔行同一个称呼() {
        val now = at(2026, 9, 2, 21, 15)
        val out = anchorWith(now, now.minus(Duration.ofHours(3)), at(2026, 6, 1, 10, 0), streak = 3)
        assertEquals("你和小明是 2026-06-01 第一次聊天认识的，到今天相识 93 天。", blockLine(out, 1))
        assertEquals("小明隔了约 3 小时才回你", blockLine(out, 2))
        // 同一段里绝不出现第二种叫法（这正是 §13 要治的）。只看块内：五档措辞今天都不含「对方」，
        // 但那是它们自己的事，块外的措辞变化不该让这条为无关原因误红。
        val block = out.substringAfter("<time_context>\n").substringBefore("\n</time_context>")
        assertEquals(false, block.contains("对方"))
    }

    @Test
    fun 现在卡_昵称为空时两行都叫对方() {
        val now = at(2026, 9, 2, 21, 15)
        val out = anchorWith(
            now, now.minus(Duration.ofHours(3)), at(2026, 6, 1, 10, 0), streak = 3,
            label = TimeAnchorFormatter.USER_LABEL_FALLBACK,
        )
        assertEquals("你和对方是 2026-06-01 第一次聊天认识的，到今天相识 93 天。", blockLine(out, 1))
        assertEquals("对方隔了约 3 小时才回你", blockLine(out, 2))
    }

    @Test
    fun factsOnly_offlineVariant_onlyCurrentLineAndNote() {
        // 线下见面专版（前后置区审计 🟡-1b）：仅时刻事实 + 保真附言——无间隔行/五档/首次对话（短信框架措辞退场）。
        val out = TimeAnchorFormatter.buildTimeAnchorFactsOnly(at(2026, 7, 11, 13, 39))
        assertEquals(
            "<time_context>\n现在：2026-07-11 周六 13:39（中午）\n</time_context>\n↑ 以上是此刻的真实时间，以它为准。",
            out,
        )
    }
}
