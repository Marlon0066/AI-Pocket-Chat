package com.situ.aichat.meeting

import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.MeetingAppointmentDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.recovery.RecoveryClaimTracker
import com.situ.aichat.recovery.RecoveryReplyGenerator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId

/**
 * 爽约检测 + 自适应反应行为测（Phase 11）：confirmed 过宽限 → markMissed + 插隐藏 SYSTEM_HINT + 无头生成反应；
 * 未过宽限 / 非 confirmed / 终态不动；线下会话只清数据不插反应（§7）；同会话多爽约去重一次反应；markMissed 守卫
 * 拒绝则不插旁白。dao/store/convRepo/msgRepo/replyGenerator 用 MockK，claimTracker 用真实例。
 */
class MeetingMissedReactionServiceTest {

    private val now = 1_750_000_000_000L

    @Before fun setUp() = mockkStatic("androidx.room.RoomDatabaseKt") // Room withTransaction 扩展函数可桩
    @After fun tearDown() = unmockkAll()

    /** db.withTransaction 直接执行 block（单测里事务=同步跑 block·验证编排，不验真原子性）。
     *  扩展函数 mock：receiver 是 firstArg，block 是 secondArg。 */
    private fun db(): AppDatabase {
        val database = mockk<AppDatabase>()
        coEvery { database.withTransaction<Boolean>(any()) } coAnswers { secondArg<suspend () -> Boolean>().invoke() }
        return database
    }

    private fun appt(
        uuid: String,
        scheduledAt: Long,
        status: String = "confirmed",
        conversationUuid: String = "conv1",
        granularity: String = "exact",
    ) = MeetingAppointmentEntity(
        uuid = uuid,
        conversationUuid = conversationUuid,
        status = status,
        scheduledAt = scheduledAt,
        timeGranularity = granularity,
        location = "咖啡馆",
        activity = "喝咖啡",
    )

    private fun convRepo(offline: Boolean = false, found: Boolean = true, sessionId: String? = "sess-1"): ConversationRepository {
        val repo = mockk<ConversationRepository>()
        val convo = if (found) {
            mockk<ConversationEntity> {
                every { isInOfflineMode } returns offline
                every { currentOfflineSessionId } returns sessionId
            }
        } else {
            null
        }
        coEvery { repo.get(any()) } returns convo
        return repo
    }

    /** 兑现服务默认桩：查无兑现见面（既有用例行为不变）——relaxed 对可空 data class 返回链式 mock 而非 null，必须显式打桩。 */
    private fun fulfillment(found: MeetingFulfillmentService.FulfillingMeeting? = null): MeetingFulfillmentService =
        mockk<MeetingFulfillmentService>(relaxed = true) {
            coEvery { findFulfillingMeeting(any(), any()) } returns found
        }

    private fun service(
        dao: MeetingAppointmentDao,
        store: MeetingAppointmentStore,
        convRepo: ConversationRepository = convRepo(),
        msgRepo: MessageRepository = mockk(relaxed = true),
        gen: RecoveryReplyGenerator = mockk(relaxed = true),
        userProfileDao: UserProfileDao = mockk(relaxed = true), // get()→null → 兜底「用户」
        fulfillmentService: MeetingFulfillmentService = fulfillment(),
    ) = MeetingMissedReactionService(db(), dao, store, convRepo, msgRepo, gen, RecoveryClaimTracker(), userProfileDao, fulfillmentService)

    @Test fun missedConfirmed_marksMissed_insertsHiddenHint_generatesReaction() = runBlocking {
        val dao = mockk<MeetingAppointmentDao>()
        val store = mockk<MeetingAppointmentStore>(relaxed = true)
        val msgRepo = mockk<MessageRepository>(relaxed = true)
        val gen = mockk<RecoveryReplyGenerator>(relaxed = true)
        val a = appt("u1", scheduledAt = now - 4 * 3600 * 1000) // exact 4h 前·超 3h 宽限
        coEvery { dao.getAllAppointments() } returns listOf(a)
        coEvery { store.markMissed("u1", any()) } returns a.copy(status = "missed")

        service(dao, store, msgRepo = msgRepo, gen = gen).scanAndReact(now)

        coVerify { store.markMissed("u1", any()) }
        coVerify { msgRepo.upsert(match { it.messageKindRaw == "system_hint" && it.roleRaw == "user" && it.conversationUuid == "conv1" }) }
        coVerify { gen.generateAndPersist("conv1") }
    }

    @Test fun withinArrivalWindow_notTouched() = runBlocking {
        val dao = mockk<MeetingAppointmentDao>()
        val store = mockk<MeetingAppointmentStore>(relaxed = true)
        val gen = mockk<RecoveryReplyGenerator>(relaxed = true)
        coEvery { dao.getAllAppointments() } returns listOf(appt("u1", scheduledAt = now - 3600 * 1000)) // 过点 1h·仍在 3h 窗内

        service(dao, store, gen = gen).scanAndReact(now)

        coVerify(exactly = 0) { store.markMissed(any(), any()) }
        coVerify(exactly = 0) { gen.generateAndPersist(any()) }
    }

    @Test fun futureConfirmed_notTouched() = runBlocking {
        val dao = mockk<MeetingAppointmentDao>()
        val store = mockk<MeetingAppointmentStore>(relaxed = true)
        coEvery { dao.getAllAppointments() } returns listOf(appt("u1", scheduledAt = now + 3600 * 1000))
        service(dao, store).scanAndReact(now)
        coVerify(exactly = 0) { store.markMissed(any(), any()) }
    }

    @Test fun proposedPastGrace_notTouched_onlyConfirmedMissed() = runBlocking {
        val dao = mockk<MeetingAppointmentDao>()
        val store = mockk<MeetingAppointmentStore>(relaxed = true)
        coEvery { dao.getAllAppointments() } returns listOf(appt("u1", scheduledAt = now - 4 * 3600 * 1000, status = "proposed"))
        service(dao, store).scanAndReact(now)
        coVerify(exactly = 0) { store.markMissed(any(), any()) }
    }

    /**
     * 卷一 D1b（拍板⑪·2026-08-26 改判）：扫描时会话正在线下见面 = 用户正在赴这场约 → **判 honored**
     * （旧行为 markMissed 先行、只跳过旁白，missed 终态仍落下 → 赴着约被记成爽约且无从更正）。
     */
    @Test fun offlineConversation_marksHonoredNotMissed_noHintNoReaction() = runBlocking {
        val dao = mockk<MeetingAppointmentDao>()
        val store = mockk<MeetingAppointmentStore>(relaxed = true)
        val msgRepo = mockk<MessageRepository>(relaxed = true)
        val gen = mockk<RecoveryReplyGenerator>(relaxed = true)
        val a = appt("u1", scheduledAt = now - 4 * 3600 * 1000)
        coEvery { dao.getAllAppointments() } returns listOf(a)
        coEvery { store.markHonored("u1", any(), any()) } returns a.copy(status = "honored")

        service(dao, store, convRepo = convRepo(offline = true), msgRepo = msgRepo, gen = gen).scanAndReact(now)

        coVerify(exactly = 1) { store.markHonored("u1", "sess-1", any()) } // 链上当前 sessionId
        coVerify(exactly = 0) { store.markMissed(any(), any()) } // 绝不再落 missed 终态
        coVerify(exactly = 0) { msgRepo.upsert(any()) } // 不插「你没来」旁白
        coVerify(exactly = 0) { gen.generateAndPersist(any()) }
    }

    /** E8：honored 守卫拒绝（并发已取消/已赴约）→ 不抛错、不回落 missed。 */
    @Test fun offlineConversation_honoredGuardRejected_stillNoMissed() = runBlocking {
        val dao = mockk<MeetingAppointmentDao>()
        val store = mockk<MeetingAppointmentStore>(relaxed = true)
        val msgRepo = mockk<MessageRepository>(relaxed = true)
        coEvery { dao.getAllAppointments() } returns listOf(appt("u1", scheduledAt = now - 4 * 3600 * 1000))
        coEvery { store.markHonored("u1", any(), any()) } returns null

        service(dao, store, convRepo = convRepo(offline = true), msgRepo = msgRepo).scanAndReact(now)

        coVerify(exactly = 0) { store.markMissed(any(), any()) }
        coVerify(exactly = 0) { msgRepo.upsert(any()) }
    }

    /** 脏态（旗标 true 而 sessionId 空）→ 仍判 honored，sessionId 传空串（fail-closed）。 */
    @Test fun offlineDirtyState_marksHonoredWithEmptySession() = runBlocking {
        val dao = mockk<MeetingAppointmentDao>()
        val store = mockk<MeetingAppointmentStore>(relaxed = true)
        val a = appt("u1", scheduledAt = now - 4 * 3600 * 1000)
        coEvery { dao.getAllAppointments() } returns listOf(a)
        coEvery { store.markHonored("u1", any(), any()) } returns a.copy(status = "honored")

        service(dao, store, convRepo = convRepo(offline = true, sessionId = null)).scanAndReact(now)

        coVerify(exactly = 1) { store.markHonored("u1", "", any()) }
    }

    @Test fun twoMissedSameConversation_twoHints_oneReaction() = runBlocking {
        val dao = mockk<MeetingAppointmentDao>()
        val store = mockk<MeetingAppointmentStore>(relaxed = true)
        val msgRepo = mockk<MessageRepository>(relaxed = true)
        val gen = mockk<RecoveryReplyGenerator>(relaxed = true)
        val a1 = appt("u1", scheduledAt = now - 4 * 3600 * 1000)
        val a2 = appt("u2", scheduledAt = now - 5 * 3600 * 1000)
        coEvery { dao.getAllAppointments() } returns listOf(a1, a2)
        coEvery { store.markMissed("u1", any()) } returns a1.copy(status = "missed")
        coEvery { store.markMissed("u2", any()) } returns a2.copy(status = "missed")

        service(dao, store, msgRepo = msgRepo, gen = gen).scanAndReact(now)

        coVerify(exactly = 2) { msgRepo.upsert(any()) } // 每条爽约一条旁白
        coVerify(exactly = 1) { gen.generateAndPersist("conv1") } // 同会话反应去重一次
    }

    /**
     * 图纸 2026-08-31 C1 真见面闸：过宽限但时窗内确实见过面（任意入口进的）→ 判 honored 链实证 session，
     * 绝不 markMissed、不插「你没来」旁白、不生成怪罪反应。
     */
    @Test fun fulfilledMeeting_gateMarksHonored_noMissedNoHintNoReaction() = runBlocking {
        val dao = mockk<MeetingAppointmentDao>()
        val store = mockk<MeetingAppointmentStore>(relaxed = true)
        val msgRepo = mockk<MessageRepository>(relaxed = true)
        val gen = mockk<RecoveryReplyGenerator>(relaxed = true)
        val a = appt("u1", scheduledAt = now - 4 * 3600 * 1000)
        coEvery { dao.getAllAppointments() } returns listOf(a)
        coEvery { store.markHonored("u1", any(), any()) } returns a.copy(status = "honored")
        val ff = fulfillment(MeetingFulfillmentService.FulfillingMeeting("sess-real", now - 4 * 3600 * 1000))

        service(dao, store, msgRepo = msgRepo, gen = gen, fulfillmentService = ff).scanAndReact(now)

        coVerify(exactly = 1) { store.markHonored("u1", "sess-real", any()) }
        coVerify(exactly = 0) { store.markMissed(any(), any()) }
        coVerify(exactly = 0) { msgRepo.upsert(any()) }
        coVerify(exactly = 0) { gen.generateAndPersist(any()) }
    }

    /** 图纸 2026-08-31 C1：每次扫描先跑一遍存量自愈（幂等·失败不拖垮扫描本体）。 */
    @Test fun scan_runsRepairFirst() = runBlocking {
        val dao = mockk<MeetingAppointmentDao>()
        val store = mockk<MeetingAppointmentStore>(relaxed = true)
        coEvery { dao.getAllAppointments() } returns emptyList()
        val ff = fulfillment()

        service(dao, store, fulfillmentService = ff).scanAndReact(now)

        coVerify(exactly = 1) { ff.repairMissedAppointments(now, any()) }
    }

    @Test fun markMissedGuardRejected_skipsHintAndReaction() = runBlocking {
        val dao = mockk<MeetingAppointmentDao>()
        val store = mockk<MeetingAppointmentStore>(relaxed = true)
        val msgRepo = mockk<MessageRepository>(relaxed = true)
        val gen = mockk<RecoveryReplyGenerator>(relaxed = true)
        coEvery { dao.getAllAppointments() } returns listOf(appt("u1", scheduledAt = now - 4 * 3600 * 1000))
        coEvery { store.markMissed("u1", any()) } returns null // 并发已流转 → 守卫拒绝

        service(dao, store, msgRepo = msgRepo, gen = gen).scanAndReact(now)

        coVerify(exactly = 0) { msgRepo.upsert(any()) }
        coVerify(exactly = 0) { gen.generateAndPersist(any()) }
    }

    // ── 纯函数 ──

    @Test fun isMissedConfirmed_pastGraceConfirmed_true() {
        val zone = ZoneId.of("Asia/Shanghai")
        assertTrue(MeetingMissedReactionService.isMissedConfirmed(appt("u", now - 4 * 3600 * 1000), now, zone))
    }

    @Test fun isMissedConfirmed_withinWindow_false() {
        val zone = ZoneId.of("Asia/Shanghai")
        assertFalse(MeetingMissedReactionService.isMissedConfirmed(appt("u", now - 3600 * 1000), now, zone))
    }

    @Test fun isMissedConfirmed_proposedPastGrace_false() {
        val zone = ZoneId.of("Asia/Shanghai")
        assertFalse(MeetingMissedReactionService.isMissedConfirmed(appt("u", now - 4 * 3600 * 1000, status = "proposed"), now, zone))
    }

    @Test fun missedHint_statesFactsAndNudge_avoidsReservedTitles_named() {
        val hint = MeetingMissedReactionService.missedHint("6月27日 周六 15:00", "咖啡馆", "喝咖啡", "小明")
        assertTrue(hint.contains("喝咖啡"))
        // B3a：「用户」→真名（角色直读·房子风格「你」=角色不动）。
        assertTrue("约定用真名", hint.contains("你和小明约好了"))
        assertTrue("缺席主语用真名", hint.contains("小明始终没有出现"))
        assertFalse("无通用码约定", hint.contains("你和用户"))
        assertFalse("无通用码缺席", hint.contains("用户始终"))
        assertTrue(hint.startsWith("（") && hint.endsWith("）")) // 纯括号旁白
        assertFalse(hint.contains("【")) // 不含 DirtyMessageDetector 保留段标题
    }

    @Test fun missedHint_blankDetail_usesNamedFallbackPlan_E5() {
        // 三参皆空白 → detail 空 → 走「你和${userName}约好的那次见面」分支（E5），且用真名。
        val hint = MeetingMissedReactionService.missedHint("", "", "", "小明")
        assertTrue("空 detail 命名兜底句", hint.contains("你和小明约好的那次见面"))
        assertFalse(hint.contains("你和用户"))
    }
}
