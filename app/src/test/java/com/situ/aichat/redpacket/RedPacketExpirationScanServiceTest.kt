package com.situ.aichat.redpacket

import android.content.Context
import com.situ.aichat.data.local.dao.RedPacketDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.RedPacketRecordEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.WalletOwnerType
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.notification.NotificationAlarmScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 红包过期扫描纯函数单测 + 卷四 B-4 门控行为测（P9.3b-2 + 独立复核）：
 * 过期判定 now>expiresAt、22h 预警窗口（过期前 2h）+ 三条件、预警文案（不含金额）、
 * 以及 B-4 关闭开关后冷启动不排预警闹钟、切换开关撤/重排闹钟、且**绝不影响 24h 过期自动退钱**。
 */
class RedPacketExpirationScanServiceTest {

    private val h24 = 24L * 60 * 60 * 1000
    private val h2 = 2L * 60 * 60 * 1000

    /** createdAt=0、expiresAt=24h 的角色→用户红包（receiver=user）。 */
    private fun characterToUser(
        notified: Boolean = false,
        expiresAt: Long = h24,
    ) = RedPacketRecordEntity(
        uuid = "r1",
        senderType = WalletOwnerType.CHARACTER.raw,
        senderCharacterUUID = "c1",
        receiverType = WalletOwnerType.USER.raw,
        receiverCharacterUUID = "",
        amount = 520,
        status = "pending",
        createdAt = 0,
        expiresAt = expiresAt,
        notifiedExpiringSoon = notified,
    )

    /** 用户→角色红包（receiver=character，不应预警）。 */
    private fun userToCharacter(expiresAt: Long = h24) = RedPacketRecordEntity(
        uuid = "r2",
        senderType = WalletOwnerType.USER.raw,
        receiverType = WalletOwnerType.CHARACTER.raw,
        receiverCharacterUUID = "c1",
        amount = 88,
        status = "pending",
        createdAt = 0,
        expiresAt = expiresAt,
    )

    // ── 过期判定：now > expiresAt ──

    @Test fun is_expired_strict_greater_than() {
        val r = characterToUser()
        assertFalse(RedPacketExpirationScanService.isExpired(r, h24)) // 恰好 = expiresAt 未过期
        assertFalse(RedPacketExpirationScanService.isExpired(r, h24 - 1))
        assertTrue(RedPacketExpirationScanService.isExpired(r, h24 + 1))
    }

    // ── 预警窗口常量 = 过期前 2h ──

    @Test fun warning_lead_is_2h() {
        assertEquals(h2, RedPacketExpirationScanService.WARNING_LEAD_MS)
    }

    // ── shouldWarn 三条件：receiver==user && !notified && now>=expiresAt-2h ──

    @Test fun should_warn_in_window_for_character_to_user() {
        val r = characterToUser()
        val warningAt = h24 - h2 // 22h
        assertFalse("窗口前不预警", RedPacketExpirationScanService.shouldWarn(r, warningAt - 1))
        assertTrue("恰好进窗口预警", RedPacketExpirationScanService.shouldWarn(r, warningAt))
        assertTrue("窗口内预警", RedPacketExpirationScanService.shouldWarn(r, warningAt + 1))
    }

    @Test fun should_not_warn_when_already_notified() {
        val r = characterToUser(notified = true)
        assertFalse(RedPacketExpirationScanService.shouldWarn(r, h24 - h2))
    }

    @Test fun should_not_warn_user_to_character() {
        // 用户发给角色的红包不推预警（由决策服务即时处理，1:1 iOS）
        val r = userToCharacter()
        assertFalse(RedPacketExpirationScanService.shouldWarn(r, h24 - h2))
        assertFalse(RedPacketExpirationScanService.shouldWarn(r, h24 - 1))
    }

    // ── 预警文案（不含金额） ──

    @Test fun expiring_content_no_amount() {
        val (title, body) = RedPacketExpirationScanService.buildExpiringContent("小七")
        assertEquals("🧧 小七 给你发的红包快过期啦", title)
        assertEquals("还有 2 小时不拆就要退回咯", body)
        assertFalse("预警标题不含金额", title.contains("520"))
        assertFalse("预警正文不含金额", body.contains("520"))
    }

    // ── requestKey 前缀稳定（精确闹钟 schedule/cancel 配对 + 通知 id 派生） ──

    @Test fun warning_request_key_prefix() {
        assertEquals("red_packet_expiring_r1", RedPacketExpirationScanService.warningRequestKey("r1"))
    }

    // ── B-4 门控行为测（冷启动直读 DataStore + 绝不阻塞过期退钱） ──

    private fun buildService(
        dao: RedPacketDao,
        redPacketService: RedPacketService,
        characterRepo: CharacterRepository = mockk(relaxed = true),
        alarm: NotificationAlarmScheduler = mockk(relaxed = true),
        expiringEnabled: Boolean,
    ): RedPacketExpirationScanService {
        val settingsRepo = mockk<SettingsRepository> {
            every { appSettings } returns flowOf(AppSettings(notifRedPacketExpiringEnabled = expiringEnabled))
        }
        return RedPacketExpirationScanService(
            context = mockk<Context>(relaxed = true),
            redPacketDao = dao,
            redPacketService = redPacketService,
            characterRepo = characterRepo,
            alarmScheduler = alarm,
            settingsRepo = settingsRepo,
        )
    }

    @Test fun B4_冷启动开关关闭时_scheduleWarningAlarm绝不排闹钟() = runBlocking {
        val alarm = mockk<NotificationAlarmScheduler>(relaxed = true)
        val svc = buildService(
            dao = mockk(relaxed = true),
            redPacketService = mockk(relaxed = true),
            alarm = alarm,
            expiringEnabled = false,
        )
        svc.scheduleWarningAlarm(characterToUser(), "小七")
        verify(exactly = 0) { alarm.scheduleExact(any(), any(), any()) }
    }

    @Test fun B4_关闭提醒开关后_24h过期退钱路径仍完整执行() = runBlocking {
        val expiredRecord = characterToUser(expiresAt = h24)
        val dao = mockk<RedPacketDao>()
        coEvery { dao.pendingRecords() } returns listOf(expiredRecord)
        val rpService = mockk<RedPacketService>(relaxed = true)
        val alarm = mockk<NotificationAlarmScheduler>(relaxed = true)
        val svc = buildService(dao = dao, redPacketService = rpService, alarm = alarm, expiringEnabled = false)

        svc.scan(now = h24 + 1)

        coVerify(exactly = 1) { rpService.expireRedPacket("r1", h24 + 1) }
        verify(exactly = 1) { alarm.cancel("red_packet_expiring_r1") }
    }

    @Test fun B4_开关切换撤闹钟与重排闹钟_且绝不触碰退钱() = runBlocking {
        val activeRecord = characterToUser(expiresAt = h24)
        val dao = mockk<RedPacketDao>()
        coEvery { dao.pendingRecords() } returns listOf(activeRecord)
        val rpService = mockk<RedPacketService>(relaxed = true)
        val charRepo = mockk<CharacterRepository>()
        coEvery { charRepo.get("c1") } returns CharacterEntity(uuid = "c1", name = "小七", creationDate = 0L)
        val alarm = mockk<NotificationAlarmScheduler>(relaxed = true)
        val svc = buildService(
            dao = dao,
            redPacketService = rpService,
            characterRepo = charRepo,
            alarm = alarm,
            expiringEnabled = true,
        )

        svc.onExpiringNotificationSettingChanged(enabled = false, now = 1000L)
        verify(exactly = 1) { alarm.cancel("red_packet_expiring_r1") }

        svc.onExpiringNotificationSettingChanged(enabled = true, now = 1000L)
        verify(exactly = 1) { alarm.scheduleExact("red_packet_expiring_r1", h24 - h2, any()) }
        coVerify(exactly = 0) { rpService.expireRedPacket(any(), any()) }
    }
}
