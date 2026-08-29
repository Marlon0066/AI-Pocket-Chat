package com.situ.aichat.world.notify

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldEventEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.local.entity.WorldTravelEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.R
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.notification.NotificationAlarmScheduler
import com.situ.aichat.notification.NotifierWorld
import com.situ.aichat.world.WorldClock
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.atlas.WorldAtlas
import com.situ.aichat.world.bulletin.WorldLlmBudget
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.Runs
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.util.UUID

/**
 * [WorldNotifyService] T2-1/2（W8 图纸 §7·E5–E14/E20·Robolectric 真 Room + 真 [WorldLlmBudget] + 真 [WorldNotifyStateStore]
 * + mockk [NotificationAlarmScheduler]/[SettingsRepository] + mockkObject([NotifierWorld]) + foregroundCheck 注入缝）：
 * fire 八道门逐门 + 封顶/摘要/曲线/类目隔离/时区。断言从图纸 §3.3/§3.5/§4.3 独立反推。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldNotifyServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var alarmScheduler: NotificationAlarmScheduler
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var stateStore: WorldNotifyStateStore
    private lateinit var service: WorldNotifyService
    private val context = RuntimeEnvironment.getApplication()
    private val seed = 1L
    private val home = WorldIds.HOME_CITY_ID // city_yunye（用户当前城）
    private val near = "city_g_yunze_2"       // seed=1·距 home 50 里·角色家乡（来访腿 to=home ≠ 家 near）
    private val t0 = 1_000_000_000_000L
    private val zoneUtc = ZoneId.of("UTC")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        alarmScheduler = mockk(relaxed = true)
        settingsRepo = mockk()
        // 清 prefs·真 stateStore（E12 测 markWorldEntered 真写）。
        context.getSharedPreferences("world_notify_state", 0).edit().clear().commit()
        context.getSharedPreferences("notification_post_ledger", 0).edit().clear().commit()
        stateStore = WorldNotifyStateStore(context)
        service = WorldNotifyService(
            context, db.worldDao(), db.characterDao(), alarmScheduler, WorldLlmBudget(db), settingsRepo, stateStore,
            db.conversationDao(),
        )
        service.foregroundCheck = { false } // 默认后台
        mockkObject(NotifierWorld)
        every { NotifierWorld.postArrival(any(), any(), any(), any()) } just Runs
        every { NotifierWorld.postSummary(any(), any()) } just Runs
    }

    @After
    fun tearDown() {
        unmockkObject(NotifierWorld)
        db.close()
    }

    // ─── 夹具 ───

    private fun tier(t: String, enabled: Boolean = true) {
        every { settingsRepo.appSettings } returns flowOf(AppSettings(notificationsEnabled = enabled, worldNotificationTier = t))
    }

    private fun seedState(createdAt: Long = t0, tz: String = "UTC") = runBlocking {
        db.worldDao().upsertState(WorldStateEntity(seed = seed, userTimezoneId = tz, createdAt = createdAt))
    }

    /** 来访腿：角色家=near，行 near→home（to=home ≠ 家 → 有效来访腿）。 */
    private fun seedVisitRow(charUuid: String, name: String, departAt: Long, arriveAt: Long) = runBlocking {
        db.characterDao().upsert(CharacterEntity(uuid = charUuid, name = name, creationDate = t0, joinedWorld = true, worldHomeCityId = near))
        db.worldDao().upsertTravel(WorldTravelEntity(charUuid, near, home, departAt, arriveAt, WorldIds.TravelModes.CAR, 0L))
    }

    private fun seedUserRow(departAt: Long, arriveAt: Long) = runBlocking {
        db.worldDao().upsertTravel(WorldTravelEntity(WorldIds.USER_ID, home, near, departAt, arriveAt, WorldIds.TravelModes.CAR, 0L))
    }

    private fun visitUuid(charUuid: String, departAt: Long) =
        UUID.nameUUIDFromBytes("world:visit:$charUuid:$departAt".toByteArray()).toString()

    private fun seedVisitEvent(charUuid: String, departAt: Long, arriveAt: Long, seenAt: Long? = null, notifiedAt: Long? = null) = runBlocking {
        db.worldDao().upsertEvent(
            WorldEventEntity(uuid = visitUuid(charUuid, departAt), kindRaw = "visit", cityId = home, summary = "到访", happenedAt = arriveAt, seenAt = seenAt, notifiedAt = notifiedAt),
        )
    }

    private fun visitKey(charUuid: String) = NotifierWorld.REQUEST_KEY_VISIT_PREFIX + charUuid
    private fun notifSpend(now: Long, cat: String = "notif") = runBlocking {
        db.worldBulletinDao().spendCount(WorldClock.localDateOf(now, zoneUtc).toEpochDay(), cat) ?: 0
    }

    // ─── E5 快乐路（来访腿·行在·后台·额度足） ───

    @Test
    fun `E5 来访快乐路_发个体_现读名与城_消费1格_事件notifiedAt落`() = runBlocking {
        tier(AppSettings.WORLD_NOTIFICATION_ALL)
        seedState()
        seedVisitRow("cA", "小晚", departAt = t0 - 1000L, arriveAt = t0)
        seedVisitEvent("cA", departAt = t0 - 1000L, arriveAt = t0)
        val now = t0 + 1000L
        val key = visitKey("cA")

        service.fire(key, now)

        val expectedBody = context.getString(R.string.notif_world_visit_arrival_body, WorldAtlas.of(seed).cityById(home)!!.name)
        verify(exactly = 1) { NotifierWorld.postArrival(context, key.hashCode(), "小晚", expectedBody) }
        assertEquals("消费 notif 1 格", 1, notifSpend(now))
        assertEquals("事件 notifiedAt 落", now, db.worldDao().getEvent(visitUuid("cA", t0 - 1000L))!!.notifiedAt)
    }

    @Test
    fun `E5 用户腿快乐路_到站啦_城名为目的地`() = runBlocking {
        tier(AppSettings.WORLD_NOTIFICATION_ALL)
        seedState()
        seedUserRow(departAt = t0 - 1000L, arriveAt = t0)
        val now = t0 + 1000L

        service.fire(NotifierWorld.REQUEST_KEY_USER_ARRIVAL, now)

        val expectedTitle = context.getString(R.string.notif_world_user_arrival_title)
        val expectedBody = context.getString(R.string.notif_world_user_arrival_body, WorldAtlas.of(seed).cityById(near)!!.name)
        verify(exactly = 1) { NotifierWorld.postArrival(context, NotifierWorld.REQUEST_KEY_USER_ARRIVAL.hashCode(), expectedTitle, expectedBody) }
    }

    // ─── E6 档位（gentle user / silent 任意 → 跳） ───

    @Test
    fun `E6 gentle档_用户腿跳_零消费零发`() = runBlocking {
        tier(AppSettings.WORLD_NOTIFICATION_GENTLE)
        seedState()
        seedUserRow(t0 - 1000L, t0)
        service.fire(NotifierWorld.REQUEST_KEY_USER_ARRIVAL, t0 + 1000L)
        verify(exactly = 0) { NotifierWorld.postArrival(any(), any(), any(), any()) }
        assertEquals(0, notifSpend(t0 + 1000L))
    }

    @Test
    fun `E6 silent档_来访腿也跳`() = runBlocking {
        tier(AppSettings.WORLD_NOTIFICATION_SILENT)
        seedState()
        seedVisitRow("cA", "小晚", t0 - 1000L, t0)
        service.fire(visitKey("cA"), t0 + 1000L)
        verify(exactly = 0) { NotifierWorld.postArrival(any(), any(), any(), any()) }
        assertEquals(0, notifSpend(t0 + 1000L))
    }

    // ─── E7 全局开关关 → 跳 ───

    @Test
    fun `E7 全局通知关_跳_零消费`() = runBlocking {
        tier(AppSettings.WORLD_NOTIFICATION_ALL, enabled = false)
        seedState()
        seedVisitRow("cA", "小晚", t0 - 1000L, t0)
        service.fire(visitKey("cA"), t0 + 1000L)
        verify(exactly = 0) { NotifierWorld.postArrival(any(), any(), any(), any()) }
        assertEquals(0, notifSpend(t0 + 1000L))
    }

    // ─── E8 验真跳（无行 / 角色删 / 来访腿被返程替换） ───

    @Test
    fun `E8 无在途行_验真跳`() = runBlocking {
        tier(AppSettings.WORLD_NOTIFICATION_ALL)
        seedState()
        // 无 travel 行（已被前台结算删）。
        service.fire(visitKey("cGone"), t0 + 1000L)
        verify(exactly = 0) { NotifierWorld.postArrival(any(), any(), any(), any()) }
        assertEquals(0, notifSpend(t0 + 1000L))
    }

    @Test
    fun `E8 角色已删_孤儿闹钟_验真跳`() = runBlocking {
        tier(AppSettings.WORLD_NOTIFICATION_ALL)
        seedState()
        // 行在但角色不存在（删角清理竞窗）。
        db.worldDao().upsertTravel(WorldTravelEntity("cDel", near, home, t0 - 1000L, t0, WorldIds.TravelModes.CAR, 0L))
        service.fire(visitKey("cDel"), t0 + 1000L)
        verify(exactly = 0) { NotifierWorld.postArrival(any(), any(), any(), any()) }
    }

    @Test
    fun `E8 来访腿已换返程腿_to等于家_跳`() = runBlocking {
        tier(AppSettings.WORLD_NOTIFICATION_ALL)
        seedState()
        db.characterDao().upsert(CharacterEntity(uuid = "cR", name = "小晚", creationDate = t0, joinedWorld = true, worldHomeCityId = near))
        // 返程腿：to = near = 家乡 → 门3 跳。
        db.worldDao().upsertTravel(WorldTravelEntity("cR", home, near, t0 - 1000L, t0, WorldIds.TravelModes.CAR, 0L))
        service.fire(visitKey("cR"), t0 + 1000L)
        verify(exactly = 0) { NotifierWorld.postArrival(any(), any(), any(), any()) }
    }

    @Test
    fun `E8 未到达_now早于arriveAt_回拨跳`() = runBlocking {
        tier(AppSettings.WORLD_NOTIFICATION_ALL)
        seedState()
        seedVisitRow("cA", "小晚", t0 - 1000L, t0)
        service.fire(visitKey("cA"), t0 - 1L) // now < arriveAt
        verify(exactly = 0) { NotifierWorld.postArrival(any(), any(), any(), any()) }
    }

    // ─── E3 门4 过期（>12h 跳·恰 12h 放） ───

    @Test
    fun `E3 过期边界_恰12h放_超1ms跳`() = runBlocking {
        tier(AppSettings.WORLD_NOTIFICATION_ALL)
        seedState()
        seedVisitRow("cA", "小晚", t0 - 1000L, t0)
        // 恰 12h → 放（cA 发个体）。
        service.fire(visitKey("cA"), t0 + WorldNotifyRules.T_STALE_MS)
        verify(exactly = 1) { NotifierWorld.postArrival(context, visitKey("cA").hashCode(), any(), any()) }
        // 12h+1ms → 跳（另起角色 cB 避免额度/事件干扰）。
        seedVisitRow("cB", "阿离", t0 - 1000L, t0)
        service.fire(visitKey("cB"), t0 + WorldNotifyRules.T_STALE_MS + 1L)
        verify(exactly = 0) { NotifierWorld.postArrival(context, visitKey("cB").hashCode(), any(), any()) }
    }

    // ─── E9 seenAt 守卫（小报讲过 → 跳·notifiedAt 不动） ───

    @Test
    fun `E9 seenAt已置_小报讲过_跳_notifiedAt不动`() = runBlocking {
        tier(AppSettings.WORLD_NOTIFICATION_ALL)
        seedState()
        seedVisitRow("cA", "小晚", t0 - 1000L, t0)
        seedVisitEvent("cA", departAt = t0 - 1000L, arriveAt = t0, seenAt = 888L)
        service.fire(visitKey("cA"), t0 + 1000L)
        verify(exactly = 0) { NotifierWorld.postArrival(any(), any(), any(), any()) }
        assertNull("notifiedAt 未动", db.worldDao().getEvent(visitUuid("cA", t0 - 1000L))!!.notifiedAt)
    }

    // ─── E10 前台 → 跳·零消费 ───

    @Test
    fun `E10 前台fire_跳_零消费`() = runBlocking {
        tier(AppSettings.WORLD_NOTIFICATION_ALL)
        seedState()
        seedVisitRow("cA", "小晚", t0 - 1000L, t0)
        service.foregroundCheck = { true }
        service.fire(visitKey("cA"), t0 + 1000L)
        verify(exactly = 0) { NotifierWorld.postArrival(any(), any(), any(), any()) }
        assertEquals(0, notifSpend(t0 + 1000L))
    }

    // ─── 门9 见面（卷一 C4）：到达角色正与用户线下见面 → 静默吞·零消费 ───

    @Test
    fun `门9 见面中fire_跳_零消费`() = runBlocking {
        tier(AppSettings.WORLD_NOTIFICATION_ALL)
        seedState()
        seedVisitRow("cA", "小晚", t0 - 1000L, t0)
        db.conversationDao().upsert(
            com.situ.aichat.data.local.entity.ConversationEntity(
                uuid = "conv-cA", title = "小晚", characterUuid = "cA", creationDate = t0,
                isInOfflineMode = true, currentOfflineSessionId = "sess-1",
            ),
        )
        service.fire(visitKey("cA"), t0 + 1000L)
        verify(exactly = 0) { NotifierWorld.postArrival(any(), any(), any(), any()) }
        assertEquals("见面闸在门7/门8 之前 → 不排顺延闹钟、不扣额度", 0, notifSpend(t0 + 1000L))
        verify(exactly = 0) { alarmScheduler.scheduleExact(any(), any(), any()) }
    }

    /** N1 对照：同夹具但会话非见面 → 照常发（证明上面的静默是见面闸拦下的）。 */
    @Test
    fun `门9 非见面_照常发`() = runBlocking {
        tier(AppSettings.WORLD_NOTIFICATION_ALL)
        seedState()
        seedVisitRow("cA", "小晚", t0 - 1000L, t0)
        db.conversationDao().upsert(
            com.situ.aichat.data.local.entity.ConversationEntity(
                uuid = "conv-cA", title = "小晚", characterUuid = "cA", creationDate = t0,
            ),
        )
        service.fire(visitKey("cA"), t0 + 1000L)
        verify(exactly = 1) { NotifierWorld.postArrival(any(), any(), any(), any()) }
    }

    // ─── E13 门7 排队顺延（重排同 key·now+120s·零额度消费） ───

    @Test
    fun `E13 排队顺延_重排同key_now加120s_零额度消费`() = runBlocking {
        tier(AppSettings.WORLD_NOTIFICATION_ALL)
        seedState()
        seedVisitRow("cA", "小晚", t0 - 1000L, t0)
        val now = t0 + 1000L
        // 台账「上次出声」= now-60s（<120s → 顺延）。
        context.getSharedPreferences("notification_post_ledger", 0).edit().putLong("last_alert_post_at", now - 60_000L).commit()
        service.fire(visitKey("cA"), now)
        verify(exactly = 0) { NotifierWorld.postArrival(any(), any(), any(), any()) }
        verify(exactly = 1) { alarmScheduler.scheduleExact(visitKey("cA"), now + WorldNotifyRules.PACER_GAP_MS, any()) }
        assertEquals("零额度消费", 0, notifSpend(now))
    }

    // ─── E11 封顶：第1/2条个体·第3条起摘要（onlyAlertOnce·同id覆盖） ───

    @Test
    fun `E11 封顶_前2条个体_第3条摘要N1_第4条摘要N2`() = runBlocking {
        tier(AppSettings.WORLD_NOTIFICATION_ALL)
        seedState() // createdAt=t0·daysSince=0·cap=2
        val now = t0 + 1000L
        for (c in listOf("c1", "c2", "c3", "c4")) seedVisitRow(c, "客$c", t0 - 1000L, t0)
        service.fire(visitKey("c1"), now)
        service.fire(visitKey("c2"), now)
        service.fire(visitKey("c3"), now)
        service.fire(visitKey("c4"), now)
        verify(exactly = 2) { NotifierWorld.postArrival(any(), any(), any(), any()) } // 前 2 条个体
        verify(exactly = 1) { NotifierWorld.postSummary(context, 1) } // 第 3 条 → 摘要 N=1
        verify(exactly = 1) { NotifierWorld.postSummary(context, 2) } // 第 4 条 → 摘要 N=2
    }

    // ─── E12 世界自安静（≥14天 cap=1·markWorldEntered 后恢复 cap=2） ───

    @Test
    fun `E12 十四天未进世界_第2条即摘要_markWorldEntered后恢复cap2`() = runBlocking {
        tier(AppSettings.WORLD_NOTIFICATION_ALL)
        val now = t0 + 1000L
        seedState(createdAt = now - 15L * 86_400_000L) // 锚 15 天前·daysSince=15≥14·cap=1
        seedVisitRow("q1", "客q1", t0 - 1000L, t0)
        seedVisitRow("q2", "客q2", t0 - 1000L, t0)
        service.fire(visitKey("q1"), now) // 个体（消费 1）
        service.fire(visitKey("q2"), now) // cap=1·已满 → 摘要
        verify(exactly = 1) { NotifierWorld.postArrival(any(), any(), any(), any()) }
        verify(exactly = 1) { NotifierWorld.postSummary(context, 1) }
        // 回世界 → cap 恢复 2 → 第 3 条又是个体。
        stateStore.markWorldEntered(now)
        seedVisitRow("q3", "客q3", t0 - 1000L, t0)
        service.fire(visitKey("q3"), now)
        verify(exactly = 2) { NotifierWorld.postArrival(any(), any(), any(), any()) }
    }

    // ─── E14 台账类目隔离 ───

    @Test
    fun `E14 台账类目隔离_notif不动bulletin_反之亦然`() = runBlocking {
        tier(AppSettings.WORLD_NOTIFICATION_ALL)
        seedState()
        val now = t0 + 1000L
        val epochDay = WorldClock.localDateOf(now, zoneUtc).toEpochDay()
        // 预占 bulletin 额度 2 格。
        val budget = WorldLlmBudget(db)
        repeat(2) { budget.tryConsume("bulletin", epochDay, 3) }
        seedVisitRow("cA", "小晚", t0 - 1000L, t0)
        service.fire(visitKey("cA"), now)
        assertEquals("notif 独立计数", 1, db.worldBulletinDao().spendCount(epochDay, "notif"))
        assertEquals("bulletin 不受影响", 2, db.worldBulletinDao().spendCount(epochDay, "bulletin"))
    }

    // ─── E20 时区（非 UTC·按 state.userTimezoneId 判日·封顶跨日） ───

    @Test
    fun `E20 时区Asia_Shanghai_epochDay按state时区判`() = runBlocking {
        tier(AppSettings.WORLD_NOTIFICATION_ALL)
        // now = 2026-01-01 23:30 UTC = 2026-01-02 07:30 +8 → UTC 日与 +8 日不同。
        val now = java.time.ZonedDateTime.of(2026, 1, 1, 23, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        seedState(createdAt = now, tz = "Asia/Shanghai")
        seedVisitRow("cA", "小晚", now - 1000L, now)
        service.fire(visitKey("cA"), now)
        val dayPlus8 = WorldClock.localDateOf(now, ZoneId.of("Asia/Shanghai")).toEpochDay()
        val dayUtc = WorldClock.localDateOf(now, zoneUtc).toEpochDay()
        assertEquals("按 +8 日计数", 1, db.worldBulletinDao().spendCount(dayPlus8, "notif"))
        assertNull("非 UTC 日计数（跨日）", db.worldBulletinDao().spendCount(dayUtc, "notif"))
    }
}
