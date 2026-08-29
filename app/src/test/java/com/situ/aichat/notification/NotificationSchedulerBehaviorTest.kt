package com.situ.aichat.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * NotificationScheduler 排程侧行为测试（主动通知真实感改造 T2-6）。跑真 `schedule()` 路径，协作者 MockK 假掉。
 *
 * **两个测试基建约束（决定了本文件的写法，勿"简化"回去）**：
 * 1. `WorkManager.getInstance` 是 **Companion 方法**（`WorkManager$Companion.getInstance`）而非 Java static
 *    → 必须 `mockkObject(WorkManager.Companion)`；用 `mockkStatic` 拦不住，真实现会拿假 Context 撞
 *    `AbstractMethodError`。（本项目无 work-testing 依赖，F28/§9 不得新增。）
 * 2. 时钟经构造注入 `Clock.fixed` **钉死**（2026-01-15 14:00 Asia/Shanghai），事件时间戳以该固定 now
 *    相对构造，断言与真实几点/哪天跑彻底无关。旧写法（真实 now 相对构造）仍随**日期**漂移：日程支抖动
 *    = djb2("$charId|schedule_i|日期") % 16，**抖动=0 的日期上** fireAt 恰落在候选事件自身 endTime
 *    （闭区间端点），`currentEvent` 的 firstOrNull 先命中候选事件而非同覆盖的睡觉事件 → 睡眠闸用例
 *    全天必挂（实锤 2026-08-28 抖动=0，曾被误诊「跨午夜 flaky」——其实是头天 08-27 抖动=15 恰好绿）。
 *    **换钉死日期必须避开抖动=0**；免打扰窗用例仍从触发时刻反算窗口，双保险。钉死日抖动=5。
 *
 * 断言从图纸 §3.1 / V1 / V3 / V4 / V5 独立反推。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationSchedulerBehaviorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val characterRepository: CharacterRepository = mockk(relaxed = true)
    private val scheduleDao: ScheduleDao = mockk(relaxed = true)
    private val messageDao: MessageDao = mockk(relaxed = true)
    private val conversationDao: ConversationDao = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val alarmScheduler: NotificationAlarmScheduler = mockk(relaxed = true)
    private val store: NotificationSchedulerStore = mockk(relaxed = true)
    private val learningService: NotificationLearningService = mockk(relaxed = true)

    private lateinit var scheduler: NotificationScheduler

    private val charId = "c-1"

    /** 与生产同源的时区（生产取注入 Clock 的 zone = 本 clock 的 zone，断言必须同源，否则 minuteOfDay 口径打架）。 */
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 钉死 now：2026-01-15 14:00。选日避开抖动=0（文件头约束 2），选时避开 21 点后回退支晚问候关窗。 */
    private val now: Long = LocalDate.of(2026, 1, 15).atTime(14, 0).atZone(zone).toInstant().toEpochMilli()

    private val clock: Clock = Clock.fixed(Instant.ofEpochMilli(now), zone)

    private fun minutesFromNow(minutes: Long): Long = now + minutes * 60_000L

    private fun minuteOfDay(millis: Long): Int =
        Instant.ofEpochMilli(millis).atZone(zone).let { it.hour * 60 + it.minute }

    private fun todayString(): String = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()

    private fun character() = CharacterEntity(
        uuid = charId,
        name = "林深",
        creationDate = 0L,
        lastChatDate = null,
        streakCount = 0,
    )

    /** 事件：[endInMinutes] 分钟后结束（默认持续 60 分钟）。 */
    private fun event(
        activity: String,
        endInMinutes: Long,
        durationMinutes: Long = 60,
        isPhoneAvailable: Boolean = true,
    ) = ScheduleEventEntity(
        uuid = "e-$activity-$endInMinutes",
        scheduleUuid = "s1",
        startTime = minutesFromNow(endInMinutes - durationMinutes),
        endTime = minutesFromNow(endInMinutes),
        activity = activity,
        isPhoneAvailable = isPhoneAvailable,
        innerThought = "有点想TA",
        moodEmoji = "🙂",
    )

    @Before
    fun setUp() {
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(any()) } returns mockk(relaxed = true)

        scheduler = NotificationScheduler(
            context, characterRepository, scheduleDao, conversationDao,
            settingsRepository, alarmScheduler, store, learningService,
            // R1 🟡-1 活跃桶族迁出后：给**真**分析器（同一批 mock DAO），使桶取值与迁移前逐字节同源，
            // 而非 mock 掉它——那会把「只搬不改」的证据换成一句桩。
            ActivityBucketAnalyzer(conversationDao, messageDao),
            clock,
        )
        coEvery { settingsRepository.isCharacterNotificationEnabled(charId) } returns true
        coEvery { characterRepository.get(charId) } returns character()
        coEvery { conversationDao.getByCharacter(charId) } returns emptyList()
        coEvery { conversationDao.totalUnread() } returns 0
        every { store.scheduledFor(charId) } returns emptyList()
        every { store.snapshot(charId) } returns null
        every { store.reservedFireTimesExcluding(charId) } returns emptyList()
        every { store.randomDecidedDate(charId) } returns todayString() // 钉死随机分支：今天已判过 → 不掷骰
        coEvery { learningService.windowStatsFor(any(), any()) } returns emptyList()
    }

    @After
    fun tearDown() = unmockkObject(WorkManager.Companion)

    private fun stubSettings(
        quietEnabled: Boolean = false,
        quietStart: Int = 1380,
        quietEnd: Int = 450,
    ) {
        coEvery { settingsRepository.getAppSettings() } returns AppSettings(
            notificationsEnabled = true,
            scheduleSystemEnabled = true,
            quietHoursEnabled = quietEnabled,
            quietHoursStartMinute = quietStart,
            quietHoursEndMinute = quietEnd,
        )
    }

    private fun stubTodaySchedule(vararg events: ScheduleEventEntity) {
        coEvery { scheduleDao.scheduleFor(charId, any()) } returns
            CharacterDailyScheduleEntity(uuid = "s1", characterUuid = charId, date = 0L)
        coEvery { scheduleDao.eventsForSchedule("s1") } returns events.toList()
    }

    private fun stubNoSchedule() {
        coEvery { scheduleDao.scheduleFor(charId, any()) } returns null
    }

    /** 捕获所有排进闹钟的 (requestKey, fireAt, payload)。 */
    private fun capturedAlarms(): List<Triple<String, Long, NotificationPayload>> {
        val keys = mutableListOf<String>()
        val times = mutableListOf<Long>()
        val payloads = mutableListOf<NotificationPayload>()
        coVerify { alarmScheduler.scheduleExact(capture(keys), capture(times), capture(payloads)) }
        return keys.indices.map { Triple(keys[it], times[it], payloads[it]) }
    }

    private fun jitterFor(index: Int): Long =
        NotificationScheduler.jitterMinutesFor(charId, "schedule_$index", todayString())

    // MARK: - 抖动（V3 · D-5 确定性）

    /** 抖动 ∈ [0,15] 整分钟、同输入恒同（重排不漂移）、随输入散开（不是写死常量）。 */
    @Test fun jitter_isDeterministicAndWithinZeroToFifteen() {
        val samples = (0..200).map { i ->
            NotificationScheduler.jitterMinutesFor("char-$i", "schedule_${i % 3}", "2026-01-1${i % 9}")
        }
        assertTrue("抖动须落 [0,15]", samples.all { it in 0L..15L })
        repeat(3) {
            assertEquals(
                NotificationScheduler.jitterMinutesFor("c-1", "schedule_0", "2026-01-15"),
                NotificationScheduler.jitterMinutesFor("c-1", "schedule_0", "2026-01-15"),
            )
        }
        assertTrue("抖动须随输入变化", samples.distinct().size > 1)
    }

    // MARK: - 日程支（V3：时刻 = 事件结束 + 抖动，不再对齐活跃桶）

    @Test fun scheduleBranch_firesAtEventEndPlusJitter() = runTest {
        stubSettings()
        stubTodaySchedule(event("画稿收尾", endInMinutes = 120))

        scheduler.schedule(character())

        val alarms = capturedAlarms()
        assertEquals(1, alarms.size)
        val (key, fireAt, payload) = alarms[0]
        assertEquals("aichat_streak_${charId}_schedule_0", key)
        assertEquals(minutesFromNow(120) + jitterFor(0) * 60_000L, fireAt)
        // V1：排程时不带正文；freshResolution 恒 true（到点现做）
        assertEquals("", payload.body)
        assertTrue(payload.freshResolution)
    }

    /** V4：日程支最多排 MAX_DAILY=3 条（给 5 个合适事件也只排 3）。 */
    @Test fun scheduleBranch_capsAtThreePerDay() = runTest {
        stubSettings()
        stubTodaySchedule(
            event("晨跑", 60), event("开会", 120), event("午饭", 180), event("画稿", 240), event("散步", 300),
        )

        scheduler.schedule(character())

        assertEquals(3, capturedAlarms().size)
        assertEquals(3, NotificationScheduler.MAX_DAILY_NOTIFICATIONS)
    }

    /** E17：抖动后 fireAt <= now 的事件（刚结束）弃排；且日程支早退，不落回退支硬凑。 */
    @Test fun scheduleBranch_pastEvent_isNotScheduled() = runTest {
        stubSettings()
        // 20 分钟前结束：入选筛选（endTime > now-30min）但 +抖动(≤15min) 仍 < now
        stubTodaySchedule(event("刚结束的会", endInMinutes = -20))

        scheduler.schedule(character())

        coVerify(exactly = 0) { alarmScheduler.scheduleExact(any(), any(), any()) }
    }

    /** V5：落在免打扰窗内的日程通知不排。窗口从**实际触发时刻**反算 → 与测试在几点跑无关。 */
    @Test fun scheduleBranch_insideQuietHours_isFiltered() = runTest {
        val fireAt = minutesFromNow(120) + jitterFor(0) * 60_000L
        val fireMinute = minuteOfDay(fireAt)
        // 造一个必然覆盖 fireMinute 的跨午夜窗：[fireMinute-1, fireMinute+1)
        stubSettings(
            quietEnabled = true,
            quietStart = (fireMinute - 1 + 1440) % 1440,
            quietEnd = (fireMinute + 1) % 1440,
        )
        stubTodaySchedule(event("画稿收尾", endInMinutes = 120))

        scheduler.schedule(character())

        coVerify(exactly = 0) { alarmScheduler.scheduleExact(any(), any(), any()) }
    }

    /** 对照组：同一事件、同一窗口但**免打扰关** → 恢复排（证明上条确是被免打扰闸拦的）。 */
    @Test fun scheduleBranch_quietHoursDisabled_sameEventIsScheduled() = runTest {
        val fireAt = minutesFromNow(120) + jitterFor(0) * 60_000L
        val fireMinute = minuteOfDay(fireAt)
        stubSettings(
            quietEnabled = false, // 唯一差异
            quietStart = (fireMinute - 1 + 1440) % 1440,
            quietEnd = (fireMinute + 1) % 1440,
        )
        stubTodaySchedule(event("画稿收尾", endInMinutes = 120))

        scheduler.schedule(character())

        assertEquals(1, capturedAlarms().size)
    }

    /** 睡眠闸（日程系统侧）与免打扰闸独立：触发时刻被"睡觉"事件覆盖 → 跳过。 */
    @Test fun scheduleBranch_fireTimeCoveredBySleepEvent_isSkipped() = runTest {
        // 前提自检：抖动=0 时 fireAt==候选事件自身 endTime，睡眠闸意图不可达（文件头约束 2）——
        // 换钉死日期若撞 0，让真因在此喊出来，而不是主断言语焉不详地红。
        assertTrue("钉死日期的抖动=0，换个日期（文件头约束 2）", jitterFor(0) >= 1)
        stubSettings() // 免打扰关，隔离出睡眠闸
        // A：120min 后结束（候选，fireAt=120+抖动）；B：睡觉事件覆盖 [119, 200] → 罩住 fireAt
        stubTodaySchedule(
            event("画稿收尾", endInMinutes = 120),
            event("睡觉", endInMinutes = 200, durationMinutes = 81),
        )

        scheduler.schedule(character())

        // 睡眠事件自身不入候选（关键词排除）；唯一候选 A 的触发时刻被 B 罩住 → 0 条
        coVerify(exactly = 0) { alarmScheduler.scheduleExact(any(), any(), any()) }
    }

    /** 台账 body 存空串（正文到点才现做）。 */
    @Test fun recordScheduled_storesEmptyBody() = runTest {
        stubSettings()
        stubTodaySchedule(event("画稿收尾", endInMinutes = 120))

        scheduler.schedule(character())

        val body = slot<String>()
        coVerify {
            learningService.recordScheduled(
                any(), any(), any(), any(), any(), capture(body), any(), any(), any(), any(),
            )
        }
        assertEquals("", body.captured)
    }

    // MARK: - 回退支（V4：NeedsChat 单条 · streak_urgent 整类退役）

    /** V4：NeedsChat 不再 remind+urgent+broken 三连——urgent / broken 一条都不许排。 */
    @Test fun fallbackBranch_needsChat_neverSchedulesUrgentOrBroken() = runTest {
        stubSettings()
        stubNoSchedule()
        // 昨天聊过、今天还没 → NeedsChat
        val needsChat = character().copy(lastChatDate = now - 24 * 60 * 60 * 1000L, streakCount = 3)
        coEvery { characterRepository.get(charId) } returns needsChat

        scheduler.schedule(needsChat)

        val categories = capturedAlarms().mapNotNull { it.third.category }
        assertTrue("NeedsChat 不得再排 streak_urgent", categories.none { it == "streak_urgent" })
        assertTrue("NeedsChat 不得再排 streak_broken", categories.none { it == "streak_broken" })
        // 火花类只允许 remind 一条（planner 可能因当日窗口已过而 0 条 → 至多 1 条）
        assertTrue(categories.count { it.startsWith("streak_") } <= 1)
        assertTrue(categories.filter { it.startsWith("streak_") }.all { it == "streak_remind" })
    }

    /** 全局：三种火花状态下都不再产生 streak_urgent 新通知（整类退役）。 */
    @Test fun noBranch_everSchedulesStreakUrgent() = runTest {
        stubSettings()
        stubNoSchedule()
        listOf(
            character(), // Broken（从未聊过）
            character().copy(lastChatDate = now - 24 * 60 * 60 * 1000L, streakCount = 2), // NeedsChat
            character().copy(lastChatDate = now, streakCount = 5), // Active
        ).forEach { c ->
            coEvery { characterRepository.get(charId) } returns c
            scheduler.schedule(c)
        }
        assertTrue(capturedAlarms().none { it.third.category == "streak_urgent" })
    }

    /** V1：回退支同样只烤空 body + freshResolution=true。 */
    @Test fun fallbackBranch_bakesEmptyBodyAndFreshResolution() = runTest {
        stubSettings()
        stubNoSchedule()
        // Broken → 今天 streak_remind；若当日窗口已过则 0 条，两种都不破坏"空 body"断言
        scheduler.schedule(character())

        capturedAlarms().forEach { (_, _, payload) ->
            assertEquals("", payload.body)
            assertTrue(payload.freshResolution)
        }
    }

    /** V1：「兜底保证今天至少一条」已删——所有候选时刻都在免打扰窗内时，今天就是 0 条，不硬凑。 */
    @Test fun noForcedDailyFloor_allCandidatesInQuietHours() = runTest {
        // 免打扰窗设成 00:00→23:59（几乎全天）→ 回退支任何时刻都落窗内
        stubSettings(quietEnabled = true, quietStart = 0, quietEnd = 1439)
        stubNoSchedule()

        scheduler.schedule(character())

        // 改造前此处必有一条（旧「兜底保证今天至少一条」无视睡眠/时段硬排 streak_remind）；
        // 现在允许今天一条都没有 —— 这正是 V1「宁缺毋滥」的可观测形态。
        coVerify(exactly = 0) { alarmScheduler.scheduleExact(any(), any(), any()) }
    }
}
