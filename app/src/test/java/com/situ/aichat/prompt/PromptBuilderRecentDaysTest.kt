package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.model.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * 【你最近几天的日子】接入日程模块的行为测试（时间感知三期·图纸 §7 T2-5～T2-7）。
 * 验证：空表时今日日程段逐字不变（B1/B2 回归锁）、日程系统关时一并不出、非空时段落逐字对 §4.1
 * 且位于【你今天完整的日程】之前隔一个空行。时区钉死 Asia/Shanghai 保证断言确定性。
 *
 * 独立成类（不并进 `PromptBuilderScheduleTest`）：那个类是纯函数 JUnit 测试，本组需要 Robolectric
 * 取 `PromptStrings`——并进去会把整类的 runner 换掉，牵连既有 12 例。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class PromptBuilderRecentDaysTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private lateinit var originalTz: TimeZone
    private val today = LocalDate.of(2026, 9, 3)
    private val now = LocalDateTime.of(2026, 9, 3, 13, 39).atZone(zone).toInstant()

    @Before fun pinTimeZone() {
        originalTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
    }

    @After fun restoreTimeZone() {
        TimeZone.setDefault(originalTz)
    }

    private fun at(day: LocalDate, h: Int, m: Int): Long =
        LocalDateTime.of(day, java.time.LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()

    private fun event(day: LocalDate, h: Int, period: String, activity: String) = ScheduleEventEntity(
        uuid = "$day-$h", scheduleUuid = "s-$day", startTime = at(day, h, 0), endTime = at(day, h + 1, 0),
        periodLabel = period, location = "咖啡店", activity = activity,
    )

    private val todaySchedule = CharacterDailyScheduleEntity(
        uuid = "s1", characterUuid = "c1",
        date = today.atStartOfDay(zone).toInstant().toEpochMilli(), generatedAt = at(today, 6, 0),
    )
    private val todayEvents = listOf(
        event(today, 9, "上午", "开店"),
        event(today, 15, "下午", "拉花赶单"),
    )

    /** 返回系统提示词（第一条 system 消息）。[schedule] 传 null = 复刻「今天的日程还没生成」。 */
    private fun systemPrompt(
        recentDays: List<ScheduleEventEntity>,
        settings: AppSettings = AppSettings(),
        schedule: CharacterDailyScheduleEntity? = todaySchedule,
    ): String = PromptBuilder.buildMessages(
        character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L),
        sortedMessages = listOf(
            MessageEntity(
                messageUUID = "u1", conversationUuid = "c1", roleRaw = "user",
                content = "在干嘛", timestamp = now.toEpochMilli() - 60_000,
            ),
        ),
        userProfile = null,
        appSettings = settings,
        strings = PromptStrings(RuntimeEnvironment.getApplication()),
        todaySchedule = schedule,
        todayScheduleEvents = if (schedule == null) emptyList() else todayEvents,
        recentDaysScheduleEvents = recentDays,
        now = now,
    ).first().content.orEmpty()

    @Test
    fun emptyRecentDays_todayScheduleSectionUnchanged() {
        // T2-5（B1/B2 回归锁）：空表时整段不出，今日日程段的标题与首行逐字不变；
        // 且「不传该参数」与「传空表」两条路输出完全相同（默认值等价）。
        val withEmpty = systemPrompt(emptyList())
        assertFalse("空表不得出标题", withEmpty.contains("【你最近几天的日子】"))
        assertTrue(withEmpty.contains("【你今天完整的日程】\n[✓已发生] 上午 开店"))

        val withoutParam = PromptBuilder.buildMessages(
            character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L),
            sortedMessages = listOf(
                MessageEntity(
                    messageUUID = "u1", conversationUuid = "c1", roleRaw = "user",
                    content = "在干嘛", timestamp = now.toEpochMilli() - 60_000,
                ),
            ),
            userProfile = null,
            appSettings = AppSettings(),
            strings = PromptStrings(RuntimeEnvironment.getApplication()),
            todaySchedule = todaySchedule,
            todayScheduleEvents = todayEvents,
            now = now,
        ).first().content.orEmpty()
        assertEquals(withoutParam, withEmpty)
    }

    @Test
    fun todayScheduleMissing_recentDaysSectionStillInjected() {
        // R1 🟡-1：今日日程未生成 / 生成失败时，最近几天段**仍要注入**——「隔了几天回来」正是它存在的理由，
        // 而那时今天的日程恰恰最可能还没生成（往期靠 backfill 补到昨天，今天那份靠异步 LLM 调用）。
        val out = systemPrompt(
            listOf(
                event(today.minusDays(3), 9, "全天", "出差在外"),
                event(today.minusDays(2), 9, "上午", "睡到中午"),
                event(today.minusDays(1), 9, "上午", "在家赶稿"),
            ),
            schedule = null,
        )
        assertTrue(
            "三行倒序的最近几天段仍在",
            out.contains(
                """
                【你最近几天的日子】
                9月2日：上午 在家赶稿
                9月1日：上午 睡到中午
                8月31日：全天 出差在外
                """.trimIndent(),
            ),
        )
        assertFalse("今天没日程 → 今日日程段不出", out.contains("【你今天完整的日程】"))
    }

    @Test
    fun todayScheduleMissingAndNoRecentDays_moduleStaysEmpty() {
        // 两边都没数据 → 整个日程模块返空串（= 改动前行为，【此刻】兜底交给 currentMoment）。
        val out = systemPrompt(emptyList(), schedule = null)
        assertFalse(out.contains("【你最近几天的日子】"))
        assertFalse(out.contains("【你今天完整的日程】"))
        assertFalse("连状态标签说明都不该出（整模块为空）", out.contains("【状态标签说明"))
    }

    @Test
    fun scheduleSystemDisabled_sectionAlsoGone() {
        // T2-6（E14）：日程系统关 → buildScheduleModule 首行守卫直接返空串，最近几天段一并不出。
        val out = systemPrompt(
            listOf(event(today.minusDays(1), 9, "上午", "在家赶稿")),
            settings = AppSettings(scheduleSystemEnabled = false),
        )
        assertFalse(out.contains("【你最近几天的日子】"))
        assertFalse(out.contains("【你今天完整的日程】"))
    }

    @Test
    fun threeDays_sectionRendersBeforeTodayScheduleWithBlankLine() {
        // T2-7（§4.1）：标题 + 日期倒序 + 「 → 」串联，位于【你今天完整的日程】之前、两段之间一个空行；
        // 今天的事件即使混进本参数也不进本段（拍板 6）。
        val out = systemPrompt(
            listOf(
                event(today.minusDays(3), 9, "全天", "出差在外"),
                event(today.minusDays(1), 19, "晚上", "追剧"),
                event(today.minusDays(1), 9, "上午", "在家赶稿"),
                event(today.minusDays(2), 14, "下午", "收拾屋子"),
                event(today.minusDays(1), 14, "下午", "见客户"),
                event(today.minusDays(2), 9, "上午", "睡到中午"),
                event(today, 9, "上午", "开店"),
            ),
        )
        assertTrue(
            "段落逐字 + 空行 + 今日日程标题紧随其后",
            out.contains(
                """
                【你最近几天的日子】
                9月2日：上午 在家赶稿 → 下午 见客户 → 晚上 追剧
                9月1日：上午 睡到中午 → 下午 收拾屋子
                8月31日：全天 出差在外

                【你今天完整的日程】
                """.trimIndent(),
            ),
        )
        assertEquals("今天的「开店」只属于今日日程段", 1, Regex("开店").findAll(out).count())
    }
}
