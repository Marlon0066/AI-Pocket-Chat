package com.situ.aichat.meeting

import android.content.Context
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.MeetingAppointmentDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.notification.NotificationAlarmScheduler
import com.situ.aichat.notification.NotificationPayload
import com.situ.aichat.notification.Notifier
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 到点通知调度器行为测（Phase 10 + 卷四 B-4）：全量对账 [MeetupNotificationService.rescheduleAll] 只为 confirmed-future 排闹钟、
 * 其余（proposed / 过点 / 终态 / 超额）一律撤；删角色防御撤；关开关撤全量；payload 字段（标题 / 会话 / category / requestKey / 时刻 / 头像）
 * 正确；以及纯函数挑选 / key / 正文模板。alarmScheduler / dao / characterRepo / context 用 MockK 假掉。
 */
class MeetupNotificationServiceTest {

    private val now = 1_750_000_000_000L

    private fun appt(
        uuid: String,
        status: String,
        scheduledAt: Long,
        characterUuid: String = "c1",
        conversationUuid: String = "conv1",
        activity: String = "看电影",
    ) = MeetingAppointmentEntity(
        uuid = uuid,
        characterUuid = characterUuid,
        conversationUuid = conversationUuid,
        status = status,
        scheduledAt = scheduledAt,
        activity = activity,
    )

    private fun character(name: String = "小冉", avatar: String? = null): CharacterEntity {
        val c = mockk<CharacterEntity>()
        every { c.name } returns name
        every { c.avatarPath } returns avatar
        return c
    }

    /** 无参 = 任何 uuid 都返回一个角色；有参 = 按 uuid 精确 stub（含返回 null 模拟已删）。 */
    private fun characterRepo(vararg pairs: Pair<String, CharacterEntity?>): CharacterRepository {
        val repo = mockk<CharacterRepository>()
        if (pairs.isEmpty()) {
            coEvery { repo.get(any()) } returns character()
        } else {
            pairs.forEach { (uuid, ch) -> coEvery { repo.get(uuid) } returns ch }
        }
        return repo
    }

    private fun service(
        dao: MeetingAppointmentDao,
        alarm: NotificationAlarmScheduler,
        characterRepo: CharacterRepository = characterRepo(),
        meetingReminderEnabled: Boolean = true,
    ): MeetupNotificationService {
        val context = mockk<Context>(relaxed = true) // getString → "" 足够（正文内容另有纯函数测）
        val settingsRepo = mockk<SettingsRepository> {
            every { appSettings } returns flowOf(AppSettings(notifMeetingReminderEnabled = meetingReminderEnabled))
        }
        return MeetupNotificationService(context, dao, characterRepo, alarm, settingsRepo)
    }

    @Test fun rescheduleAll_disabledInSettings_cancelsAllAndSchedulesNone() = runBlocking {
        val dao = mockk<MeetingAppointmentDao>()
        val alarm = mockk<NotificationAlarmScheduler>(relaxed = true)
        coEvery { dao.getAllAppointments() } returns listOf(
            appt("future1", "confirmed", now + 1_000),
            appt("future2", "confirmed", now + 2_000),
        )

        service(dao, alarm, meetingReminderEnabled = false).rescheduleAll(now)

        verify { alarm.cancel("meetup_future1") }
        verify { alarm.cancel("meetup_future2") }
        verify(exactly = 0) { alarm.scheduleExact(any(), any(), any()) }
    }

    @Test fun rescheduleAll_schedulesConfirmedFuture_cancelsEverythingElse() = runBlocking {
        val dao = mockk<MeetingAppointmentDao>()
        val alarm = mockk<NotificationAlarmScheduler>(relaxed = true)
        coEvery { dao.getAllAppointments() } returns listOf(
            appt("future1", "confirmed", now + 1_000),
            appt("future2", "confirmed", now + 2_000),
            appt("past", "confirmed", now - 1_000), // 已过点 → 撤（交爽约扫描）
            appt("proposed", "proposed", now + 1_000), // 还没确认 → 撤
            appt("cancelled", "cancelled", now + 1_000),
            appt("honored", "honored", now + 1_000),
            appt("missed", "missed", now + 1_000),
        )

        service(dao, alarm).rescheduleAll(now)

        verify { alarm.scheduleExact("meetup_future1", now + 1_000, any()) }
        verify { alarm.scheduleExact("meetup_future2", now + 2_000, any()) }
        verify { alarm.cancel("meetup_past") }
        verify { alarm.cancel("meetup_proposed") }
        verify { alarm.cancel("meetup_cancelled") }
        verify { alarm.cancel("meetup_honored") }
        verify { alarm.cancel("meetup_missed") }
        verify(exactly = 0) { alarm.scheduleExact("meetup_past", any(), any()) }
        verify(exactly = 0) { alarm.scheduleExact("meetup_proposed", any(), any()) }
    }

    @Test fun rescheduleAll_capsAtSixteenNearest_cancelsTheRest() = runBlocking {
        val dao = mockk<MeetingAppointmentDao>()
        val alarm = mockk<NotificationAlarmScheduler>(relaxed = true)
        // 18 条 confirmed-future（时刻递增）：只应排最近 16（f1..f16），f17/f18 撤。
        coEvery { dao.getAllAppointments() } returns (1..18).map { appt("f$it", "confirmed", now + it * 1_000L) }

        service(dao, alarm).rescheduleAll(now)

        verify { alarm.scheduleExact("meetup_f1", any(), any()) }
        verify { alarm.scheduleExact("meetup_f16", any(), any()) }
        verify { alarm.cancel("meetup_f17") }
        verify { alarm.cancel("meetup_f18") }
        verify(exactly = 0) { alarm.scheduleExact("meetup_f17", any(), any()) }
        verify(exactly = 0) { alarm.scheduleExact("meetup_f18", any(), any()) }
    }

    @Test fun rescheduleAll_characterDeleted_cancelsInsteadOfScheduling() = runBlocking {
        val dao = mockk<MeetingAppointmentDao>()
        val alarm = mockk<NotificationAlarmScheduler>(relaxed = true)
        coEvery { dao.getAllAppointments() } returns listOf(appt("f1", "confirmed", now + 1_000, characterUuid = "gone"))

        service(dao, alarm, characterRepo("gone" to null)).rescheduleAll(now)

        verify { alarm.cancel("meetup_f1") }
        verify(exactly = 0) { alarm.scheduleExact("meetup_f1", any(), any()) }
    }

    @Test fun rescheduleAll_payloadCarriesDeepLinkFields() = runBlocking {
        val dao = mockk<MeetingAppointmentDao>()
        val alarm = mockk<NotificationAlarmScheduler>(relaxed = true)
        coEvery { dao.getAllAppointments() } returns
            listOf(appt("f1", "confirmed", now + 5_000, conversationUuid = "convX", characterUuid = "cX"))

        service(dao, alarm, characterRepo("cX" to character("小冉", "/a.png"))).rescheduleAll(now)

        verify {
            alarm.scheduleExact(
                "meetup_f1",
                now + 5_000,
                match<NotificationPayload> { p ->
                    p.title == "小冉" &&
                        p.conversationUuid == "convX" &&
                        p.characterId == "cX" &&
                        p.category == Notifier.CATEGORY_MEETUP &&
                        p.requestKey == "meetup_f1" &&
                        p.scheduledAtMillis == now + 5_000 &&
                        p.avatarPath == "/a.png" &&
                        p.notificationId == "meetup_f1".hashCode()
                },
            )
        }
    }

    @Test fun cancel_cancelsByMeetupKey() {
        val alarm = mockk<NotificationAlarmScheduler>(relaxed = true)
        service(mockk(), alarm).cancel("xyz")
        verify { alarm.cancel("meetup_xyz") }
    }

    // ── 纯函数 ──

    @Test fun upcomingToSchedule_filtersConfirmedFuture_sortedAscending() {
        val all = listOf(
            appt("c_far", "confirmed", now + 9_000),
            appt("c_near", "confirmed", now + 1_000),
            appt("c_past", "confirmed", now - 1), // 过点排除
            appt("p", "proposed", now + 500), // 非 confirmed 排除
            appt("x", "cancelled", now + 500), // 终态排除
        )
        val picked = MeetupNotificationService.upcomingToSchedule(all, now)
        assertEquals(listOf("c_near", "c_far"), picked.map { it.uuid }) // 升序
    }

    @Test fun upcomingToSchedule_capsAt16() {
        val all = (1..20).map { appt("f$it", "confirmed", now + it * 1_000L) }
        val picked = MeetupNotificationService.upcomingToSchedule(all, now)
        assertEquals(MeetupNotificationService.MAX_MEETUP_NOTIFICATIONS, picked.size)
        assertEquals("f1", picked.first().uuid)
        assertEquals("f16", picked.last().uuid)
        assertFalse(picked.any { it.uuid == "f17" })
    }

    @Test fun requestKey_isMeetupPrefixedUuid() {
        assertEquals("meetup_abc", MeetupNotificationService.requestKey("abc"))
    }

    @Test fun meetupBody_withActivity_usesActivityTemplate_trimmed() {
        val ctx = mockk<Context>()
        every { ctx.getString(R.string.meetup_notif_body_activity, "喝咖啡") } returns "ACT"
        assertEquals("ACT", MeetupNotificationService.meetupBody(ctx, "  喝咖啡  "))
        verify { ctx.getString(R.string.meetup_notif_body_activity, "喝咖啡") }
    }

    @Test fun meetupBody_blankActivity_usesGenericTemplate() {
        val ctx = mockk<Context>()
        every { ctx.getString(R.string.meetup_notif_body) } returns "GEN"
        assertEquals("GEN", MeetupNotificationService.meetupBody(ctx, "   "))
        verify { ctx.getString(R.string.meetup_notif_body) }
    }
}
