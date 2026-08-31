package com.situ.aichat.meeting

import com.situ.aichat.data.local.dao.MeetingAppointmentDao
import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.model.MeetingTimeGranularity
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
 * 真理源服务单测：companion 纯函数（状态机守卫 + 字段变化 + 查重）直接测；DAO 编排（读→算→写、守卫拒绝不写库）用 MockK 行为测。
 */
class MeetingAppointmentStoreTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun appt(
        uuid: String = "u1",
        status: String = "proposed",
        scheduledAt: Long = 0L,
        activity: String = "",
        confirmedAt: Long? = null,
        lastReminderScheduledAt: Long? = null,
    ) = MeetingAppointmentEntity(
        uuid = uuid,
        status = status,
        scheduledAt = scheduledAt,
        activity = activity,
        confirmedAt = confirmedAt,
        lastReminderScheduledAt = lastReminderScheduledAt,
    )

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    // ── 纯函数：状态机 ──

    @Test fun confirmed_stampsConfirmedAtOnce() {
        val r = MeetingAppointmentStore.confirmed(appt(status = "proposed", confirmedAt = null), 500L)!!
        assertEquals("confirmed", r.status)
        assertEquals(500L, r.confirmedAt)
        // 已有 confirmedAt 不被覆盖
        val r2 = MeetingAppointmentStore.confirmed(appt(status = "confirmed", confirmedAt = 100L), 500L)!!
        assertEquals(100L, r2.confirmedAt)
    }

    @Test fun transitions_rejectedFromTerminalStates() {
        listOf("honored", "missed", "cancelled").forEach { terminal ->
            assertNull(MeetingAppointmentStore.confirmed(appt(status = terminal), 1L))
            assertNull(MeetingAppointmentStore.cancelled(appt(status = terminal), 1L))
            assertNull(MeetingAppointmentStore.honored(appt(status = terminal), "s", 1L))
            assertNull(MeetingAppointmentStore.missed(appt(status = terminal), 1L))
            assertNull(
                MeetingAppointmentStore.rescheduled(
                    appt(status = terminal),
                    MeetingTimeResolver.Resolution(1L, MeetingTimeGranularity.EXACT),
                ),
            )
        }
    }

    @Test fun honored_linksSessionAndStampsOutcome() {
        val r = MeetingAppointmentStore.honored(appt(status = "confirmed"), "sess-1", 700L)!!
        assertEquals("honored", r.status)
        assertEquals("sess-1", r.honoredSessionId)
        assertEquals(700L, r.outcomeAt)
    }

    @Test fun cancelled_and_missed_stampOutcome() {
        assertEquals("cancelled", MeetingAppointmentStore.cancelled(appt(status = "proposed"), 1L)!!.status)
        assertEquals(9L, MeetingAppointmentStore.missed(appt(status = "confirmed"), 9L)!!.outcomeAt)
    }

    /** 图纸 2026-08-31：自愈修复 = 状态机唯一终态例外，仅 missed→honored；其余四状态一律拒绝。 */
    @Test fun repairedToHonored_onlyFromMissed() {
        val r = MeetingAppointmentStore.repairedToHonored(appt(status = "missed"), "sess-real", 800L)!!
        assertEquals("honored", r.status)
        assertEquals("sess-real", r.honoredSessionId)
        assertEquals(800L, r.outcomeAt)
        listOf("proposed", "confirmed", "honored", "cancelled").forEach { other ->
            assertNull(MeetingAppointmentStore.repairedToHonored(appt(status = other), "s", 1L))
        }
    }

    @Test fun rescheduled_updatesTimeAndClearsReminder() {
        val a = appt(status = "confirmed", lastReminderScheduledAt = 999L)
        val res = MeetingTimeResolver.Resolution(12_345L, MeetingTimeGranularity.EXACT)
        val r = MeetingAppointmentStore.rescheduled(a, res)!!
        assertEquals(12_345L, r.scheduledAt)
        assertEquals("exact", r.timeGranularity)
        assertNull(r.lastReminderScheduledAt) // 清通知标记供重排
        assertEquals("confirmed", r.status) // 改期不改状态
    }

    // ── 纯函数：查重 ──

    @Test fun activitySimilar_cases() {
        assertTrue(MeetingAppointmentStore.activitySimilar("看电影", "看电影"))
        assertTrue(MeetingAppointmentStore.activitySimilar("看电影", "一起看电影")) // 互相包含
        assertTrue(MeetingAppointmentStore.activitySimilar(" 看电影 ", "看电影")) // 归一化空白
        assertTrue(MeetingAppointmentStore.activitySimilar("", "看电影")) // 一方空 → 仅凭同天
        assertFalse(MeetingAppointmentStore.activitySimilar("看电影", "吃饭"))
    }

    @Test fun findDuplicate_sameDayActivitySimilar_hits() {
        val existing = listOf(appt(uuid = "a", status = "confirmed", scheduledAt = millis(2026, 6, 27, 15, 0), activity = "看电影"))
        // 同天不同时段、活动相近 → 命中
        assertEquals("a", MeetingAppointmentStore.findDuplicate(millis(2026, 6, 27, 19, 0), "一起看电影", existing, zone)?.uuid)
        // 不同天 → 不命中
        assertNull(MeetingAppointmentStore.findDuplicate(millis(2026, 6, 28, 15, 0), "看电影", existing, zone))
        // 同天但活动不相近 → 不命中
        assertNull(MeetingAppointmentStore.findDuplicate(millis(2026, 6, 27, 15, 0), "吃饭", existing, zone))
    }

    @Test fun findDuplicate_skipsTerminal() {
        val existing = listOf(appt(uuid = "a", status = "cancelled", scheduledAt = millis(2026, 6, 27, 15, 0), activity = "看电影"))
        // 已取消的不算重复（可以重新约同一天同活动）
        assertNull(MeetingAppointmentStore.findDuplicate(millis(2026, 6, 27, 19, 0), "看电影", existing, zone))
    }

    /** C3 识别路查重（图纸 2026-08-31）：HONORED 计入重复（防幽灵）；MISSED/CANCELLED 仍放行（重约正当）。 */
    @Test fun findDuplicateIncludingHonored_honoredCounts_missedCancelledStillPass() {
        fun one(status: String) = listOf(appt(uuid = "a", status = status, scheduledAt = millis(2026, 6, 27, 15, 0), activity = "买裙子"))
        assertEquals("a", MeetingAppointmentStore.findDuplicateIncludingHonored(millis(2026, 6, 27, 19, 0), "买裙子", one("honored"), zone)?.uuid)
        assertEquals("a", MeetingAppointmentStore.findDuplicateIncludingHonored(millis(2026, 6, 27, 19, 0), "买裙子", one("confirmed"), zone)?.uuid)
        assertNull(MeetingAppointmentStore.findDuplicateIncludingHonored(millis(2026, 6, 27, 19, 0), "买裙子", one("missed"), zone))
        assertNull(MeetingAppointmentStore.findDuplicateIncludingHonored(millis(2026, 6, 27, 19, 0), "买裙子", one("cancelled"), zone))
        // 不同天的 honored 不拦
        assertNull(MeetingAppointmentStore.findDuplicateIncludingHonored(millis(2026, 6, 28, 19, 0), "买裙子", one("honored"), zone))
    }

    // ── DAO 编排：MockK 行为测 ──

    /** C3：近 N 内已赴约（按 outcomeAt 过滤·别的角色/别的状态/过老的不进）。 */
    @Test fun recentlyHonored_filtersByCharacterStatusAndOutcome() = runBlocking {
        val dao = mockk<MeetingAppointmentDao>(relaxed = true)
        fun row(uuid: String, char: String, status: String, outcomeAt: Long?) =
            MeetingAppointmentEntity(uuid = uuid, characterUuid = char, status = status, outcomeAt = outcomeAt)
        coEvery { dao.getAllAppointments() } returns listOf(
            row("keep", "c1", "honored", 9_000L),
            row("tooOld", "c1", "honored", 1_000L),
            row("otherChar", "c2", "honored", 9_000L),
            row("missed", "c1", "missed", 9_000L),
            row("nullOutcome", "c1", "honored", null),
        )
        val r = MeetingAppointmentStore(dao, mockk(relaxed = true))
            .recentlyHonoredForCharacter("c1", nowMillis = 10_000L, withinMillis = 5_000L)
        assertEquals(listOf("keep"), r.map { it.uuid })
    }

    /** 图纸 2026-08-31：repairMissedToHonored 编排——missed 才写库；其他状态守卫拒绝零写。 */
    @Test fun repairMissedToHonored_writesOnlyFromMissed() = runBlocking {
        val dao = mockk<MeetingAppointmentDao>(relaxed = true)
        coEvery { dao.getByUuid("u1") } returns appt(status = "missed")
        val r = MeetingAppointmentStore(dao, mockk(relaxed = true)).repairMissedToHonored("u1", "sess-1", 900L)
        assertEquals("honored", r?.status)
        coVerify { dao.update(match { it.status == "honored" && it.honoredSessionId == "sess-1" }) }

        val dao2 = mockk<MeetingAppointmentDao>(relaxed = true)
        coEvery { dao2.getByUuid("u2") } returns appt(status = "confirmed")
        assertNull(MeetingAppointmentStore(dao2, mockk(relaxed = true)).repairMissedToHonored("u2", "s", 1L))
        coVerify(exactly = 0) { dao2.update(any()) }
    }

    @Test fun confirm_readsThenUpdates() = runBlocking {
        val dao = mockk<MeetingAppointmentDao>(relaxed = true)
        coEvery { dao.getByUuid("u1") } returns appt(status = "proposed", confirmedAt = null)
        val result = MeetingAppointmentStore(dao, mockk(relaxed = true)).confirm("u1", nowMillis = 1000L)
        assertEquals("confirmed", result?.status)
        coVerify { dao.update(match { it.status == "confirmed" && it.confirmedAt == 1000L }) }
    }

    @Test fun confirm_terminalStatus_doesNotWrite() = runBlocking {
        val dao = mockk<MeetingAppointmentDao>(relaxed = true)
        coEvery { dao.getByUuid("u1") } returns appt(status = "honored")
        assertNull(MeetingAppointmentStore(dao, mockk(relaxed = true)).confirm("u1", 1000L))
        coVerify(exactly = 0) { dao.update(any()) }
    }

    @Test fun confirm_notFound_returnsNull() = runBlocking {
        val dao = mockk<MeetingAppointmentDao>(relaxed = true)
        coEvery { dao.getByUuid("missing") } returns null
        assertNull(MeetingAppointmentStore(dao, mockk(relaxed = true)).confirm("missing", 1L))
        coVerify(exactly = 0) { dao.update(any()) }
    }

    @Test fun createProposed_insertsProposedRow() = runBlocking {
        val dao = mockk<MeetingAppointmentDao>(relaxed = true)
        val store = MeetingAppointmentStore(dao, mockk(relaxed = true))
        val res = MeetingTimeResolver.Resolution(millis(2026, 6, 27, 19, 0), MeetingTimeGranularity.DAY_ONLY)
        store.createProposed(
            candidate = com.situ.aichat.data.model.MeetingCandidate(activity = "看电影"),
            resolution = res,
            characterUuid = "c1",
            conversationUuid = "conv1",
            nowMillis = 42L,
        )
        coVerify {
            dao.insert(
                match {
                    it.status == "proposed" && it.characterUuid == "c1" && it.conversationUuid == "conv1" &&
                        it.activity == "看电影" && it.scheduledAt == res.scheduledAtMillis && it.createdAt == 42L
                },
            )
        }
    }

    @Test fun nextUpcoming_picksFirstConfirmedFuture() = runBlocking {
        val dao = mockk<MeetingAppointmentDao>(relaxed = true)
        val now = millis(2026, 6, 27, 12, 0)
        coEvery { dao.activeForCharacter("c1") } returns listOf(
            appt(uuid = "past", status = "confirmed", scheduledAt = millis(2026, 6, 26, 12, 0)), // 已过
            appt(uuid = "proposedFuture", status = "proposed", scheduledAt = millis(2026, 6, 28, 12, 0)), // 未确认
            appt(uuid = "confirmedFuture", status = "confirmed", scheduledAt = millis(2026, 6, 29, 12, 0)), // ✓
        )
        assertEquals("confirmedFuture", MeetingAppointmentStore(dao, mockk(relaxed = true)).nextUpcomingForCharacter("c1", now)?.uuid)
    }

    // ── 删角色 / 删会话清理：§7 坑「先撤到点通知、再删行」 ──

    @Test fun deleteForCharacter_cancelsEachNotificationBeforeDeletingRows() = runBlocking {
        val dao = mockk<MeetingAppointmentDao>(relaxed = true)
        val meetup = mockk<MeetupNotificationService>(relaxed = true)
        coEvery { dao.uuidsForCharacter("c1") } returns listOf("m1", "m2")

        MeetingAppointmentStore(dao, meetup).deleteForCharacter("c1")

        // 逐条撤到点通知（防孤儿），且全在删行之前。
        coVerifyOrder {
            meetup.cancel("m1")
            meetup.cancel("m2")
            dao.deleteForCharacter("c1")
        }
    }

    @Test fun deleteForConversation_cancelsEachNotificationBeforeDeletingRows() = runBlocking {
        val dao = mockk<MeetingAppointmentDao>(relaxed = true)
        val meetup = mockk<MeetupNotificationService>(relaxed = true)
        coEvery { dao.uuidsForConversations(listOf("conv1")) } returns listOf("m9")

        MeetingAppointmentStore(dao, meetup).deleteForConversation("conv1")

        coVerifyOrder {
            meetup.cancel("m9")
            dao.deleteForConversations(listOf("conv1"))
        }
    }
}
