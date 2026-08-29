package com.situ.aichat.world.notify

import androidx.room.Room
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.local.entity.WorldTravelEntity
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.notification.NotificationAlarmScheduler
import com.situ.aichat.notification.NotifierWorld
import com.situ.aichat.work.NotificationRescheduleWorker
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.bulletin.WorldLlmBudget
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 世界到达闹钟重烤 T2-2（W14 图纸 §3.2/§5 E1/E9·Robolectric 真 Room 造 travel 行 + mockk [NotificationAlarmScheduler]）：
 * [WorldNotifyService.rescheduleArrivals] 扫在途行分发给既有两预烤方法 + worker 接线。断言从图纸 §3.2 独立反推。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldNotifyRescheduleTest {

    private lateinit var db: AppDatabase
    private lateinit var alarmScheduler: NotificationAlarmScheduler
    private lateinit var service: WorldNotifyService
    private val context = RuntimeEnvironment.getApplication()
    private val near = "city_g_yunze_2"
    private val home = WorldIds.HOME_CITY_ID
    private val now = 1_000_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        alarmScheduler = mockk(relaxed = true)
        // rescheduleArrivals → onUserDeparted/onVisitInvited 只读 state/角色/行 + 排闹钟，不触 budget/settings/stateStore。
        service = WorldNotifyService(
            context, db.worldDao(), db.characterDao(), alarmScheduler,
            mockk<WorldLlmBudget>(relaxed = true), mockk<SettingsRepository>(relaxed = true),
            mockk<WorldNotifyStateStore>(relaxed = true),
            db.conversationDao(),
        )
        runBlocking { db.worldDao().upsertState(WorldStateEntity(seed = 1L, userTimezoneId = "UTC", createdAt = now)) }
    }

    @After
    fun tearDown() = db.close()

    private fun seedChar(uuid: String) = runBlocking {
        db.characterDao().upsert(CharacterEntity(uuid = uuid, name = "客$uuid", creationDate = now, joinedWorld = true, worldHomeCityId = near))
    }

    private fun seedTravel(ownerId: String, arriveAt: Long) = runBlocking {
        db.worldDao().upsertTravel(WorldTravelEntity(ownerId, near, home, departAt = now - 1000L, arriveAt = arriveAt, modeRaw = WorldIds.TravelModes.CAR, costGold = 0L))
    }

    /** E1：三行（用户未来到 / 角色未来到 / 已过点）→ 前两条各排对 key 的闹钟、过点行零排。 */
    @Test
    fun `E1 在途三行_未来到两条各排对key_过点行零排`() = runBlocking {
        val userArrive = now + 3_600_000L
        val cFutureArrive = now + 7_200_000L
        val cPastArrive = now - 3_600_000L
        seedChar("cFuture"); seedChar("cPast")
        seedTravel(WorldIds.USER_ID, userArrive)
        seedTravel("cFuture", cFutureArrive)
        seedTravel("cPast", cPastArrive) // 已过点

        service.rescheduleArrivals(now)

        verify(exactly = 1) { alarmScheduler.scheduleExact(NotifierWorld.REQUEST_KEY_USER_ARRIVAL, userArrive, any()) }
        verify(exactly = 1) { alarmScheduler.scheduleExact(NotifierWorld.REQUEST_KEY_VISIT_PREFIX + "cFuture", cFutureArrive, any()) }
        verify(exactly = 0) { alarmScheduler.scheduleExact(NotifierWorld.REQUEST_KEY_VISIT_PREFIX + "cPast", any(), any()) }
        verify(exactly = 2) { alarmScheduler.scheduleExact(any(), any(), any()) } // 仅两条未来到行
    }

    /** E9：在途角色已被删（幽灵行）→ onVisitInvited 既有守卫静默跳过、循环继续（真实行照排）。 */
    @Test
    fun `E9 幽灵在途行_静默跳过_循环继续排真实行`() = runBlocking {
        val realArrive = now + 3_600_000L
        val ghostArrive = now + 5_400_000L
        seedChar("cReal") // cGhost 不建角色 = 幽灵行
        seedTravel("cReal", realArrive)
        seedTravel("cGhost", ghostArrive)

        service.rescheduleArrivals(now) // 幽灵行的 null 守卫若抛异常 → runBlocking 传播失败

        verify(exactly = 1) { alarmScheduler.scheduleExact(NotifierWorld.REQUEST_KEY_VISIT_PREFIX + "cReal", realArrive, any()) }
        verify(exactly = 0) { alarmScheduler.scheduleExact(NotifierWorld.REQUEST_KEY_VISIT_PREFIX + "cGhost", any(), any()) }
        verify(exactly = 1) { alarmScheduler.scheduleExact(any(), any(), any()) } // 仅真实行
    }

    /** worker 接线：doWork 尾调 rescheduleArrivals 恰一次（其余重排依赖 relaxed mock）。 */
    @Test
    fun `worker doWork 调 rescheduleArrivals 一次`() = runBlocking {
        val worldNotify = mockk<WorldNotifyService>(relaxed = true)
        val worker = NotificationRescheduleWorker(
            appContext = context,
            params = mockk<WorkerParameters>(relaxed = true),
            scheduler = mockk(relaxed = true),
            calendarNotificationScheduler = mockk(relaxed = true),
            storyUnlockNotificationScheduler = mockk(relaxed = true),
            petReminderScheduler = mockk(relaxed = true),
            meetupNotificationService = mockk(relaxed = true),
            worldNotifyService = worldNotify,
        )

        val result = worker.doWork()

        coVerify(exactly = 1) { worldNotify.rescheduleArrivals(any()) }
        assertTrue("重排成功", result is ListenableWorker.Result.Success)
    }
}
