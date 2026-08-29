package com.situ.aichat.seam

import androidx.test.core.app.ApplicationProvider
import com.situ.aichat.data.local.dao.NotificationDeliveryDao
import com.situ.aichat.data.local.dao.NotificationTemplateDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.notification.ConversationPhase
import com.situ.aichat.notification.ConversationState
import com.situ.aichat.notification.ConversationStateEvaluator
import com.situ.aichat.notification.ProactiveDeliveryInput
import com.situ.aichat.notification.ProactiveDeliveryPipeline
import com.situ.aichat.notification.ProactiveDropReason
import com.situ.aichat.notification.ProactiveVerdict
import com.situ.aichat.notification.StreakNotificationBridgeService
import com.situ.aichat.prompt.notification.ProactiveMessageComposer
import com.situ.aichat.proactive.ProactiveReplyDeliverer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 卷一 C4「主动消息家族」行为测试（图纸 §7 T2-C4）：见面中 → Drop(IN_MEETING)；
 * **按会话不按全局**（E11 双角色夹具：A 在见面时 B 照常发）；见面闸早于 HOT/AFTERGLOW 相位闸生效（E12）。
 * 另钉 A7 主动投递守卫（余温/惦记回连共用）与 A6 通知物化顺延。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProactivePipelineInMeetingTest {

    // 真 Context（物化路尾部会调 NotificationManagerCompat 撤回通知，纯 mock Context 会 ClassCastException）。
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val characterRepository: CharacterRepository = mockk()
    private val settingsRepository: SettingsRepository = mockk()
    private val evaluator: ConversationStateEvaluator = mockk()
    private val composer: ProactiveMessageComposer = mockk(relaxed = true)
    private val deliveryDao: NotificationDeliveryDao = mockk(relaxed = true)
    private val conversationRepository: ConversationRepository = mockk(relaxed = true)

    private val pipeline = ProactiveDeliveryPipeline(
        context, characterRepository, settingsRepository, mockk<ScheduleDao>(relaxed = true),
        evaluator, composer, mockk<ApiConfigRepository>(relaxed = true),
        mockk<NotificationTemplateDao>(relaxed = true), deliveryDao, conversationRepository,
    )

    private val zone: ZoneId = ZoneOffset.UTC
    private val now: Long = LocalDateTime.of(2026, 1, 15, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun convo(charUuid: String, inMeeting: Boolean) = ConversationEntity(
        uuid = "conv-$charUuid", title = "t", characterUuid = charUuid, creationDate = 0L,
        isInOfflineMode = inMeeting, currentOfflineSessionId = if (inMeeting) "sess-$charUuid" else null,
    )

    private fun input(charId: String) = ProactiveDeliveryInput(charId, "random", null, now - 60_000L)

    private fun stubGates(charId: String, phase: ConversationPhase = ConversationPhase.NORMAL) {
        coEvery { characterRepository.get(charId) } returns CharacterEntity(uuid = charId, name = "林深", creationDate = 0L)
        coEvery { settingsRepository.getAppSettings() } returns AppSettings(
            notificationsEnabled = true, scheduleSystemEnabled = false, quietHoursEnabled = false,
        )
        coEvery { settingsRepository.isCharacterNotificationEnabled(charId) } returns true
        coEvery { evaluator.evaluate(charId, any(), any()) } returns ConversationState(
            phase = phase, minutesSinceLastMessage = 3000L, lastMessageFromUser = false,
            unansweredProactiveCount = 0, daysSinceLastUserMessage = 2, latestMessageUuid = "m-1",
        )
        coEvery { evaluator.latestMessageUuid(charId) } returns "m-1"
        coEvery { deliveryDao.countDeliveredSince(any(), any()) } returns 0
        coEvery { deliveryDao.recentDeliveredBodies(any(), any()) } returns emptyList()
    }

    @Test
    fun 见面中_主动消息被闸_Drop_IN_MEETING() = runTest {
        stubGates("A")
        coEvery { conversationRepository.recentActiveConversationFor("A") } returns convo("A", inMeeting = true)
        assertEquals(ProactiveVerdict.Drop(ProactiveDropReason.IN_MEETING), pipeline.execute(input("A"), 0, now, zone) { false })
        // 闸在联网之前：文案绝不现做（省钱且防「生成了又扔」）。
        coVerify(exactly = 0) { composer.compose(any(), any(), any(), any(), any(), any()) }
    }

    /** E11 并发见面（拍板⑩）：A 在见面中丝毫不影响 B——B 的主动消息照常走到后续闸。 */
    @Test
    fun 另一角色见面中_本角色照常放行() = runTest {
        stubGates("B")
        coEvery { conversationRepository.recentActiveConversationFor("A") } returns convo("A", inMeeting = true)
        coEvery { conversationRepository.recentActiveConversationFor("B") } returns convo("B", inMeeting = false)
        val verdict = pipeline.execute(input("B"), 0, now, zone) { false }
        assertTrue("B 不该被 A 的见面连坐: $verdict", verdict != ProactiveVerdict.Drop(ProactiveDropReason.IN_MEETING))
    }

    /** E12 超长见面：见面闸早于相位闸生效（否则 >2h 静置会先掉进 HOT/AFTERGLOW，观测点看不出真因）。 */
    @Test
    fun 见面闸早于相位闸() = runTest {
        stubGates("A", phase = ConversationPhase.HOT)
        coEvery { conversationRepository.recentActiveConversationFor("A") } returns convo("A", inMeeting = true)
        assertEquals(ProactiveVerdict.Drop(ProactiveDropReason.IN_MEETING), pipeline.execute(input("A"), 0, now, zone) { false })
    }

    /** N1：无会话 / 非见面 → 闸不介入（照常往下走）。 */
    @Test
    fun 无会话或非见面_闸不介入() = runTest {
        stubGates("A")
        coEvery { conversationRepository.recentActiveConversationFor("A") } returns null
        assertTrue(pipeline.execute(input("A"), 0, now, zone) { false } != ProactiveVerdict.Drop(ProactiveDropReason.IN_MEETING))
        coEvery { conversationRepository.recentActiveConversationFor("A") } returns convo("A", inMeeting = false)
        assertTrue(pipeline.execute(input("A"), 0, now, zone) { false } != ProactiveVerdict.Drop(ProactiveDropReason.IN_MEETING))
    }

    // ── A7 主动投递守卫（见面余温 / 惦记回连共用的落库口）──

    @Test
    fun 主动投递_见面中_零落库零通知() = runTest {
        val convoRepo: ConversationRepository = mockk(relaxed = true)
        val messageRepo: com.situ.aichat.data.repository.MessageRepository = mockk(relaxed = true)
        coEvery { convoRepo.get("conv-A") } returns convo("A", inMeeting = true)
        val deliverer = ProactiveReplyDeliverer(
            context = context, conversationRepo = convoRepo, messageRepo = messageRepo,
            vectorMemory = mockk(relaxed = true), db = mockk(relaxed = true),
        )
        deliverer.persistAndNotify(
            conversationUuid = "conv-A",
            character = CharacterEntity(uuid = "A", name = "林深", creationDate = 0L),
            settings = AppSettings(),
            text = "刚路过你说的那家店",
            logTag = "TestTag",
        )
        coVerify(exactly = 0) { messageRepo.upsert(any()) }
        coVerify(exactly = 0) { convoRepo.applyMaterialization(any(), any(), any(), any()) }
    }

    // ── A6 通知物化顺延（J8：pending 不丢，见面结束后下一轮排干时再落）──

    @Test
    fun 通知物化_见面中顺延_不插消息不清台账() = runTest {
        val convoRepo: ConversationRepository = mockk(relaxed = true)
        val messageRepo: com.situ.aichat.data.repository.MessageRepository = mockk(relaxed = true)
        val dao: NotificationDeliveryDao = mockk(relaxed = true)
        val record = com.situ.aichat.data.local.entity.NotificationDeliveryRecordEntity(
            id = "rec-1", characterId = "A", category = "random", deliveryIdentifier = "d-1",
            requestIdentifier = "req-1", conversationUuid = "conv-A", notificationBody = "在忙吗",
            windowId = "w", windowStartMinute = 0, windowEndMinute = 10, scheduledAt = now,
            deliveredAt = now,
        )
        coEvery { dao.pendingForMaterialization() } returns listOf(record)
        coEvery { convoRepo.get("conv-A") } returns convo("A", inMeeting = true)
        val bridge = StreakNotificationBridgeService(
            context = context, messageRepository = messageRepo, conversationRepository = convoRepo,
            characterRepository = characterRepository, deliveryDao = dao,
            activeConversationStore = mockk(relaxed = true), navigator = mockk(relaxed = true),
        )
        bridge.materializeDeliveredNotifications()
        coVerify(exactly = 0) { messageRepo.upsert(any()) }
        coVerify(exactly = 0) { convoRepo.applyMaterialization(any(), any(), any(), any()) }
        // 台账原样留着 = pending 不丢（见面结束后下一轮回前台照常物化）。
        coVerify(exactly = 0) { dao.update(any()) }
    }

    @Test
    fun 通知物化_非见面_照常落消息() = runTest {
        val convoRepo: ConversationRepository = mockk(relaxed = true)
        val messageRepo: com.situ.aichat.data.repository.MessageRepository = mockk(relaxed = true)
        val dao: NotificationDeliveryDao = mockk(relaxed = true)
        val record = com.situ.aichat.data.local.entity.NotificationDeliveryRecordEntity(
            id = "rec-1", characterId = "A", category = "random", deliveryIdentifier = "d-1",
            requestIdentifier = "req-1", conversationUuid = "conv-A", notificationBody = "在忙吗",
            windowId = "w", windowStartMinute = 0, windowEndMinute = 10, scheduledAt = now,
            deliveredAt = now,
        )
        coEvery { dao.pendingForMaterialization() } returns listOf(record)
        coEvery { convoRepo.get("conv-A") } returns convo("A", inMeeting = false)
        val bridge = StreakNotificationBridgeService(
            context = context, messageRepository = messageRepo, conversationRepository = convoRepo,
            characterRepository = characterRepository, deliveryDao = dao,
            activeConversationStore = mockk(relaxed = true), navigator = mockk(relaxed = true),
        )
        bridge.materializeDeliveredNotifications()
        coVerify(exactly = 1) { messageRepo.upsert(match { it.content == "在忙吗" }) }
        coVerify(exactly = 1) { convoRepo.applyMaterialization("conv-A", "在忙吗", now, any()) }
        coVerify(exactly = 1) { dao.update(any()) }
    }
}
