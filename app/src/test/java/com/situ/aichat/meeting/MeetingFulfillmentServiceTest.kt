package com.situ.aichat.meeting

import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MeetingTimeGranularity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.offline.OfflineMarkerStartPayload
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 兑现判定单源行为测（图纸 2026-08-31）：companion 纯函数（时窗两粒度 / tier1·tier2 匹配矩阵 / 到期判定）
 * 直接测；三个编排（找兑现见面 / 入口核销 / 存量自愈）用 MockK。断言从图纸 §4 锁定项独立反推。
 */
class MeetingFulfillmentServiceTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private val hour = 3600 * 1000L

    private fun appt(
        uuid: String = "a1",
        status: String = "confirmed",
        scheduledAt: Long,
        granularity: String = "exact",
        activity: String = "买裙子",
        createdAt: Long,
        outcomeAt: Long? = null,
        conversationUuid: String = "conv1",
    ) = MeetingAppointmentEntity(
        uuid = uuid,
        conversationUuid = conversationUuid,
        status = status,
        scheduledAt = scheduledAt,
        timeGranularity = granularity,
        activity = activity,
        createdAt = createdAt,
        outcomeAt = outcomeAt,
    )

    private fun marker(ts: Long, activity: String = "买裙子", sessionId: String? = "sess-1") = MessageEntity(
        messageUUID = "m-$ts",
        conversationUuid = "conv1",
        roleRaw = "assistant",
        content = OfflineMarkerStartPayload("商场", activity, "15:00").makeContent(),
        timestamp = ts,
        isOfflineMode = true,
        offlineSessionId = sessionId,
        messageKindRaw = MessageKind.OFFLINE_MARKER_START.raw,
    )

    private fun service(
        store: MeetingAppointmentStore = mockk(relaxed = true),
        msgRepo: MessageRepository = mockk(relaxed = true),
        notif: MeetupNotificationService = mockk(relaxed = true),
    ) = MeetingFulfillmentService(store, msgRepo, notif)

    // ── 纯函数：兑现时窗起点 ──

    @Test fun windowStart_exact_isThreeHoursBeforeScheduled() {
        val scheduled = millis(2026, 8, 30, 15, 0)
        assertEquals(
            millis(2026, 8, 30, 12, 0),
            MeetingFulfillmentService.fulfillmentWindowStartMillis(scheduled, MeetingTimeGranularity.EXACT, zone),
        )
    }

    @Test fun windowStart_dayOnlyAndVague_isLocalDayStart() {
        val scheduled = millis(2026, 8, 30, 19, 0)
        val dayStart = millis(2026, 8, 30, 0, 0)
        assertEquals(dayStart, MeetingFulfillmentService.fulfillmentWindowStartMillis(scheduled, MeetingTimeGranularity.DAY_ONLY, zone))
        assertEquals(dayStart, MeetingFulfillmentService.fulfillmentWindowStartMillis(scheduled, MeetingTimeGranularity.VAGUE, zone))
    }

    // ── 纯函数：tier1 时窗匹配（无活动要求）──
    // createdAt 取远早于见面的时刻，确保 tier2 区间 [createdAt−48h, createdAt] 不干扰 tier1 断言。

    @Test fun tier1_edgesInclusive_outsideFalse() {
        val a = appt(scheduledAt = millis(2026, 8, 30, 15, 0), createdAt = millis(2026, 8, 25, 12, 0))
        // 窗起点 12:00（提前 3h）与爽约截止 18:00（过点 3h）双端含
        assertTrue(MeetingFulfillmentService.matchesAppointment(a, millis(2026, 8, 30, 12, 0), "买裙子", zone))
        assertTrue(MeetingFulfillmentService.matchesAppointment(a, millis(2026, 8, 30, 18, 0), "买裙子", zone))
        assertFalse(MeetingFulfillmentService.matchesAppointment(a, millis(2026, 8, 30, 11, 59), "买裙子", zone))
        assertFalse(MeetingFulfillmentService.matchesAppointment(a, millis(2026, 8, 30, 18, 1), "买裙子", zone))
    }

    @Test fun tier1_activityMismatchStillFulfills() {
        // 约定时间上见了面就算赴约，活动名不较真（按钮路核销同样不比对活动）。
        val a = appt(scheduledAt = millis(2026, 8, 30, 15, 0), createdAt = millis(2026, 8, 25, 12, 0))
        assertTrue(MeetingFulfillmentService.matchesAppointment(a, millis(2026, 8, 30, 15, 30), "看电影", zone))
    }

    @Test fun tier1_dayOnly_wholeDayCounts() {
        val a = appt(scheduledAt = millis(2026, 8, 30, 19, 0), granularity = "dayOnly", createdAt = millis(2026, 8, 25, 12, 0))
        // 那天早上 9 点见也算（窗=当天 0 点起）；前一天不算
        assertTrue(MeetingFulfillmentService.matchesAppointment(a, millis(2026, 8, 30, 9, 0), "买裙子", zone))
        assertFalse(MeetingFulfillmentService.matchesAppointment(a, millis(2026, 8, 29, 21, 0), "看电影", zone))
    }

    // ── 纯函数：tier2 幽灵匹配（约定生在见面后·带活动要求）──
    // 场景 = 真机实报还原：8/30 15:00 见面买裙子 → 见面后识别把旧话「明天买裙子」立成 8/31 的新约。

    private fun ghostAppt(activity: String = "买裙子") = appt(
        scheduledAt = millis(2026, 8, 31, 19, 0),
        granularity = "dayOnly",
        activity = activity,
        createdAt = millis(2026, 8, 30, 15, 40), // 见面结束后被识别出来
    )

    @Test fun tier2_meetingBeforeCreationSimilarActivity_fulfills() {
        assertTrue(MeetingFulfillmentService.matchesAppointment(ghostAppt(), millis(2026, 8, 30, 15, 0), "买裙子", zone))
    }

    @Test fun tier2_beyond48hLookback_false() {
        assertFalse(MeetingFulfillmentService.matchesAppointment(ghostAppt(), millis(2026, 8, 28, 15, 0), "买裙子", zone))
    }

    @Test fun tier2_meetingAfterCreation_false() {
        // 约定先立、见面在后且不在 tier1 时窗（8/31 前一天 16:00）→ 两 tier 皆不中
        assertFalse(MeetingFulfillmentService.matchesAppointment(ghostAppt(), millis(2026, 8, 30, 16, 0), "买裙子", zone))
    }

    @Test fun tier2_dissimilarActivity_false() {
        assertFalse(MeetingFulfillmentService.matchesAppointment(ghostAppt(), millis(2026, 8, 30, 15, 0), "看电影", zone))
    }

    @Test fun tier2_emptyAppointmentActivity_similarByExistingSemantics() {
        // activitySimilar 既有语义（LOW-2 登记）：一方为空即算相近 → 幽灵匹配放行。
        assertTrue(MeetingFulfillmentService.matchesAppointment(ghostAppt(activity = ""), millis(2026, 8, 30, 15, 0), "看电影", zone))
    }

    // ── 纯函数：入口核销到期判定 ──

    @Test fun isDueNow_confirmedWithinWindow_true_edgesAndStatusGuard() {
        val a = appt(scheduledAt = millis(2026, 8, 30, 15, 0), createdAt = millis(2026, 8, 29, 12, 0))
        assertTrue(MeetingFulfillmentService.isDueNow(a, millis(2026, 8, 30, 12, 0), zone)) // 提前 3h 边界
        assertTrue(MeetingFulfillmentService.isDueNow(a, millis(2026, 8, 30, 18, 0), zone)) // 截止边界
        assertFalse(MeetingFulfillmentService.isDueNow(a, millis(2026, 8, 30, 11, 0), zone))
        assertFalse(MeetingFulfillmentService.isDueNow(a, millis(2026, 8, 30, 18, 30), zone))
        assertFalse(MeetingFulfillmentService.isDueNow(a.copy(status = "proposed"), millis(2026, 8, 30, 15, 0), zone))
    }

    // ── 编排：找兑现见面 ──

    @Test fun findFulfillingMeeting_picksLatestMatch_linksSession() = runBlocking {
        val msgRepo = mockk<MessageRepository>(relaxed = true)
        val a = appt(scheduledAt = millis(2026, 8, 30, 15, 0), createdAt = millis(2026, 8, 25, 12, 0))
        coEvery { msgRepo.messagesByKind("conv1", MessageKind.OFFLINE_MARKER_START.raw) } returns listOf(
            marker(millis(2026, 8, 30, 13, 0), sessionId = "sess-early"),
            marker(millis(2026, 8, 30, 16, 0), sessionId = "sess-late"),
            marker(millis(2026, 8, 20, 13, 0), sessionId = "sess-old"), // 窗外不算
        )
        val r = service(msgRepo = msgRepo).findFulfillingMeeting(a, zone)!!
        assertEquals("sess-late", r.sessionId)
        assertEquals(millis(2026, 8, 30, 16, 0), r.startMillis)
    }

    @Test fun findFulfillingMeeting_noMatch_null() = runBlocking {
        val msgRepo = mockk<MessageRepository>(relaxed = true)
        val a = appt(scheduledAt = millis(2026, 8, 30, 15, 0), createdAt = millis(2026, 8, 25, 12, 0))
        coEvery { msgRepo.messagesByKind("conv1", MessageKind.OFFLINE_MARKER_START.raw) } returns
            listOf(marker(millis(2026, 8, 20, 13, 0)))
        assertNull(service(msgRepo = msgRepo).findFulfillingMeeting(a, zone))
    }

    // ── 编排：入口核销 ──

    @Test fun honorDue_honorsOnlyDueAndReschedulesOnce() = runBlocking {
        val store = mockk<MeetingAppointmentStore>(relaxed = true)
        val notif = mockk<MeetupNotificationService>(relaxed = true)
        val now = millis(2026, 8, 30, 15, 0)
        val due = appt(uuid = "due", scheduledAt = millis(2026, 8, 30, 15, 30), createdAt = now - 24 * hour)
        val far = appt(uuid = "far", scheduledAt = millis(2026, 9, 5, 15, 0), createdAt = now - 24 * hour)
        coEvery { store.activeForConversation("conv1") } returns listOf(due, far)
        coEvery { store.markHonored("due", any(), any()) } returns due.copy(status = "honored")

        service(store = store, notif = notif).honorDueAppointmentsOnMeetingStart("conv1", "sess-9", now, zone)

        coVerify(exactly = 1) { store.markHonored("due", "sess-9", now) }
        coVerify(exactly = 0) { store.markHonored("far", any(), any()) }
        coVerify(exactly = 1) { notif.rescheduleAll(any()) } // 默认参用 any() 补位（MockK 陷阱·记忆有档）
    }

    @Test fun honorDue_noneDue_zeroWritesZeroReschedule() = runBlocking {
        val store = mockk<MeetingAppointmentStore>(relaxed = true)
        val notif = mockk<MeetupNotificationService>(relaxed = true)
        val now = millis(2026, 8, 30, 15, 0)
        coEvery { store.activeForConversation("conv1") } returns
            listOf(appt(uuid = "far", scheduledAt = millis(2026, 9, 5, 15, 0), createdAt = now - 24 * hour))

        service(store = store, notif = notif).honorDueAppointmentsOnMeetingStart("conv1", "sess-9", now, zone)

        coVerify(exactly = 0) { store.markHonored(any(), any(), any()) }
        coVerify(exactly = 0) { notif.rescheduleAll(any()) }
    }

    @Test fun honorDue_guardRejected_noReschedule() = runBlocking {
        val store = mockk<MeetingAppointmentStore>(relaxed = true)
        val notif = mockk<MeetupNotificationService>(relaxed = true)
        val now = millis(2026, 8, 30, 15, 0)
        val due = appt(uuid = "due", scheduledAt = now, createdAt = now - 24 * hour)
        coEvery { store.activeForConversation("conv1") } returns listOf(due)
        coEvery { store.markHonored("due", any(), any()) } returns null // 并发已流转 → 守卫拒绝

        service(store = store, notif = notif).honorDueAppointmentsOnMeetingStart("conv1", "sess-9", now, zone)

        coVerify(exactly = 0) { notif.rescheduleAll(any()) }
    }

    // ── 编排：存量自愈 ──

    private fun missedGhost(outcomeAt: Long?) = appt(
        uuid = "ghost",
        status = "missed",
        scheduledAt = millis(2026, 8, 31, 19, 0),
        granularity = "dayOnly",
        createdAt = millis(2026, 8, 30, 15, 40),
        outcomeAt = outcomeAt,
    )

    private fun hint(ts: Long, content: String, uuid: String = "h-$ts-${content.hashCode()}") = MessageEntity(
        messageUUID = uuid,
        conversationUuid = "conv1",
        roleRaw = "user",
        content = content,
        timestamp = ts,
        messageKindRaw = MessageKind.SYSTEM_HINT.raw,
    )

    @Test fun repair_deletesHintThenFlips_inOrder() = runBlocking {
        val store = mockk<MeetingAppointmentStore>(relaxed = true)
        val msgRepo = mockk<MessageRepository>(relaxed = true)
        val outcomeAt = millis(2026, 9, 1, 8, 0)
        val a = missedGhost(outcomeAt)
        val missedHint = hint(outcomeAt, MeetingMissedReactionService.missedHint("8月31日", "商场", "买裙子", "小明"))
        val otherHint = hint(outcomeAt, "（用户打开了「发起见面」界面…取消了）")
        coEvery { store.allMissed() } returns listOf(a)
        coEvery { msgRepo.messagesByKind("conv1", MessageKind.OFFLINE_MARKER_START.raw) } returns
            listOf(marker(millis(2026, 8, 30, 15, 0), sessionId = "sess-real"))
        coEvery { msgRepo.messagesByKind("conv1", MessageKind.SYSTEM_HINT.raw) } returns listOf(missedHint, otherHint)

        service(store = store, msgRepo = msgRepo).repairMissedAppointments(millis(2026, 9, 1, 9, 0), zone)

        // 图纸 §4：先删旁白、后翻状态；非爽约旁白（无签名）绝不误删
        coVerifyOrder {
            msgRepo.deleteByUuid(missedHint.messageUUID)
            store.repairMissedToHonored("ghost", "sess-real", any())
        }
        coVerify(exactly = 0) { msgRepo.deleteByUuid(otherHint.messageUUID) }
    }

    @Test fun repair_noFulfillingMeeting_untouched() = runBlocking {
        val store = mockk<MeetingAppointmentStore>(relaxed = true)
        val msgRepo = mockk<MessageRepository>(relaxed = true)
        coEvery { store.allMissed() } returns listOf(missedGhost(outcomeAt = millis(2026, 9, 1, 8, 0)))
        coEvery { msgRepo.messagesByKind("conv1", MessageKind.OFFLINE_MARKER_START.raw) } returns emptyList()

        service(store = store, msgRepo = msgRepo).repairMissedAppointments(millis(2026, 9, 1, 9, 0), zone)

        coVerify(exactly = 0) { store.repairMissedToHonored(any(), any(), any()) }
        coVerify(exactly = 0) { msgRepo.deleteByUuid(any()) }
    }

    @Test fun repair_hintOutsideTolerance_notDeleted_stillFlips() = runBlocking {
        val store = mockk<MeetingAppointmentStore>(relaxed = true)
        val msgRepo = mockk<MessageRepository>(relaxed = true)
        val outcomeAt = millis(2026, 9, 1, 8, 0)
        val strayHint = hint(outcomeAt + 6 * 60 * 1000, "小明${MeetingMissedReactionService.MISSED_HINT_SIGNATURE}。")
        coEvery { store.allMissed() } returns listOf(missedGhost(outcomeAt))
        coEvery { msgRepo.messagesByKind("conv1", MessageKind.OFFLINE_MARKER_START.raw) } returns
            listOf(marker(millis(2026, 8, 30, 15, 0)))
        coEvery { msgRepo.messagesByKind("conv1", MessageKind.SYSTEM_HINT.raw) } returns listOf(strayHint)

        service(store = store, msgRepo = msgRepo).repairMissedAppointments(millis(2026, 9, 1, 9, 0), zone)

        coVerify(exactly = 0) { msgRepo.deleteByUuid(any()) } // ±5min 容差外不删（防误删别条爽约的旁白）
        coVerify(exactly = 1) { store.repairMissedToHonored("ghost", any(), any()) }
    }

    @Test fun repair_nullOutcomeAt_noDelete_stillFlips() = runBlocking {
        val store = mockk<MeetingAppointmentStore>(relaxed = true)
        val msgRepo = mockk<MessageRepository>(relaxed = true)
        coEvery { store.allMissed() } returns listOf(missedGhost(outcomeAt = null))
        coEvery { msgRepo.messagesByKind("conv1", MessageKind.OFFLINE_MARKER_START.raw) } returns
            listOf(marker(millis(2026, 8, 30, 15, 0)))

        service(store = store, msgRepo = msgRepo).repairMissedAppointments(millis(2026, 9, 1, 9, 0), zone)

        coVerify(exactly = 0) { msgRepo.deleteByUuid(any()) } // 异常数据不按内容盲删
        coVerify(exactly = 1) { store.repairMissedToHonored("ghost", any(), any()) }
    }
}
