package com.situ.aichat.ui.chat

import android.content.Context
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.meeting.MeetingAppointmentStore
import com.situ.aichat.offline.OfflineMeetingService
import com.situ.aichat.offline.OfflineSummaryRetryCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ChatOfflineController 行为测试——验证刀6 线下见面生命周期协作者「真的能用」（不止编译过）。
 *
 * 手法：MockK 假掉 offlineMeetingService/offlineSummaryRetryCoordinator/appContext；infoToast/恢复弹窗用真
 * MutableStateFlow；4 个引擎回调（runAssistantTurn/serialize/cancelActiveTurn/afterOfflineMemorySummary）用计数 spy。
 * serialize spy 在 Unconfined scope 真跑 block（否则 markInviteResponded/触发回合等不会发生）。
 * 覆盖：进页恢复弹窗/摘要重试、接受邀约(有/无 session 门控回合)、拒绝、主动发起、改成邀约不跑回合、取消提示、续场、
 * 退出(打断回合+按 finalize 结果补摘要)、异常恢复结束(隐藏弹窗)、关闭弹窗。
 */
class ChatOfflineControllerTest {

    private lateinit var offlineMeetingService: OfflineMeetingService
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var offlineSummaryRetryCoordinator: OfflineSummaryRetryCoordinator
    private lateinit var meetingAppointmentStore: MeetingAppointmentStore
    private lateinit var proactiveGiftMaintenance: com.situ.aichat.gift.ProactiveGiftMaintenanceService
    private lateinit var conversationRepo: com.situ.aichat.data.repository.ConversationRepository
    private lateinit var appContext: Context
    private lateinit var infoToastFlow: MutableStateFlow<String?>
    private lateinit var recoveryPromptFlow: MutableStateFlow<Boolean>
    private lateinit var controller: ChatOfflineController
    private var runAssistantTurnCount = 0
    private var cancelActiveTurnCount = 0
    private var afterSummaryCount = 0
    private var afterglowScheduledCount = 0
    private var echoScheduledCount = 0

    @Before
    fun setUp() {
        offlineMeetingService = mockk(relaxed = true)
        settingsRepo = mockk(relaxed = true)
        offlineSummaryRetryCoordinator = mockk(relaxed = true)
        meetingAppointmentStore = mockk(relaxed = true)
        proactiveGiftMaintenance = mockk(relaxed = true)
        conversationRepo = mockk(relaxed = true)
        appContext = mockk(relaxed = true)
        every { appContext.getString(any<Int>()) } returns "见面摘要稍后补全的提示"
        infoToastFlow = MutableStateFlow(null)
        recoveryPromptFlow = MutableStateFlow(false)
        runAssistantTurnCount = 0
        cancelActiveTurnCount = 0
        afterSummaryCount = 0
        afterglowScheduledCount = 0
        echoScheduledCount = 0
        val scope = CoroutineScope(Dispatchers.Unconfined)
        controller = ChatOfflineController(
            scope = scope,
            appContext = appContext,
            conversationUuid = "conv-1",
            infoToastFlow = infoToastFlow,
            recoveryPromptVisibleFlow = recoveryPromptFlow,
            messageRepo = mockk(relaxed = true),
            conversationRepo = conversationRepo,
            settingsRepo = settingsRepo,
            offlineMeetingService = offlineMeetingService,
            offlineSummaryRetryCoordinator = offlineSummaryRetryCoordinator,
            meetingAppointmentStore = meetingAppointmentStore,
            runAssistantTurn = { runAssistantTurnCount++ },
            serialize = { block -> scope.launch { block() } }, // 真跑 block（= launchSerializedTurn 在 VM 里 launch 的等价）
            cancelActiveTurn = { cancelActiveTurnCount++ },
            afterOfflineMemorySummary = { afterSummaryCount++ },
            scheduleOfflineAfterglow = { afterglowScheduledCount++ },
            scheduleMeetingMomentEcho = { echoScheduledCount++ },
            proactiveGiftMaintenanceService = proactiveGiftMaintenance,
        )
    }

    // ---- 进页 ----

    @Test
    fun 进页_应弹恢复提示_置可见() {
        // now 是 System.currentTimeMillis() 默认参 → coEvery 必须 any() 匹配它，否则桩录制时刻≠调用时刻（差 1ms）
        // 就不匹配 → relaxed 返 false → flow 不设（刀6 同款「时间默认参须 any()」教训，这处当年漏修，刀8 因执行变慢暴露）。
        coEvery { offlineMeetingService.shouldShowRecoveryPrompt("conv-1", any()) } returns true
        controller.handleChatAppear()
        coVerify { offlineMeetingService.ensureStateConsistency("conv-1") }
        assertTrue(recoveryPromptFlow.value)
    }

    @Test
    fun 进页_不需恢复提示_不弹() {
        coEvery { offlineMeetingService.shouldShowRecoveryPrompt("conv-1", any()) } returns false
        controller.handleChatAppear()
        assertFalse(recoveryPromptFlow.value)
    }

    @Test
    fun 进页_摘要重试落回_弹toast() {
        // retryOne(conversationUuid, now=..., bypassBackoff=...) 有默认参数 → 用 any() 匹配后两枚（时间值每次不同）。
        coEvery {
            offlineSummaryRetryCoordinator.retryOne("conv-1", any(), any())
        } returns OfflineSummaryRetryCoordinator.RetryOutcome.FELL_BACK
        controller.handleChatAppear()
        assertNotNull(infoToastFlow.value)
    }

    // ---- 接受 / 拒绝邀约 ----

    @Test
    fun 接受邀约_有session_标记并触发回合() {
        coEvery { offlineMeetingService.acceptOfflineInvite("conv-1", "msg-1") } returns "session-1"
        controller.acceptOfflineInvite("msg-1")
        coVerify { offlineMeetingService.markInviteResponded("msg-1", "accepted") }
        assertEquals(1, runAssistantTurnCount)
    }

    @Test
    fun 接受邀约_无session_不触发回合() {
        coEvery { offlineMeetingService.acceptOfflineInvite("conv-1", "msg-1") } returns null
        controller.acceptOfflineInvite("msg-1")
        coVerify { offlineMeetingService.markInviteResponded("msg-1", "accepted") }
        assertEquals(0, runAssistantTurnCount)
    }

    @Test
    fun 拒绝邀约_仅标记declined不触发() {
        controller.declineOfflineInvite("msg-1")
        coVerify { offlineMeetingService.markInviteResponded("msg-1", "declined") }
        assertEquals(0, runAssistantTurnCount)
    }

    // ---- 主动发起 / 改成邀约 / 取消提示 / 续场 ----

    @Test
    fun 主动发起_有session_触发回合() {
        coEvery { offlineMeetingService.startManualOfflineMeeting("conv-1", "咖啡馆", "喝咖啡") } returns "session-1"
        controller.startManualOfflineMeeting("咖啡馆", "喝咖啡")
        coVerify { offlineMeetingService.startManualOfflineMeeting("conv-1", "咖啡馆", "喝咖啡") }
        assertEquals(1, runAssistantTurnCount)
    }

    @Test
    fun 改成邀约_纯DB改写不跑回合() {
        controller.convertMessageToOfflineInvite("msg-1", "公园", "散步")
        coVerify { offlineMeetingService.convertMessageToOfflineInvite("msg-1", "公园", "散步") }
        assertEquals(0, runAssistantTurnCount) // 1:1 iOS：不跑 LLM 一轮
    }

    @Test
    fun 取消提示_插提示成功_触发回合() {
        coEvery { offlineMeetingService.insertMeetingCancelHint("conv-1") } returns true
        controller.handleMeetingCancelHint()
        assertEquals(1, runAssistantTurnCount)
    }

    @Test
    fun 续场_成功_标记continued并触发回合() {
        coEvery { offlineMeetingService.continueOfflineMeeting("conv-1") } returns true
        controller.continueOfflineMeeting("end-1")
        coVerify { offlineMeetingService.markInviteResponded("end-1", "continued") }
        assertEquals(1, runAssistantTurnCount)
    }

    // ---- 退出 / 异常恢复结束 / 关闭弹窗 ----

    @Test
    fun 退出_打断回合并finalize成功_补常规摘要() {
        coEvery {
            offlineMeetingService.finalizeOfflineMode("conv-1", OfflineMeetingService.ExitReason.USER_ENDED)
        } returns true
        controller.exitOfflineMode()
        assertEquals(1, cancelActiveTurnCount) // 先打断在投递的回合
        coVerify { offlineMeetingService.finalizeOfflineMode("conv-1", OfflineMeetingService.ExitReason.USER_ENDED) }
        assertEquals(1, afterSummaryCount) // finalize 成功 → 补常规记忆摘要
        assertEquals(1, afterglowScheduledCount) // finalize 成功 → 排余温消息 worker（§3.10）
        assertEquals(1, echoScheduledCount) // 卷二 §5④：finalize 成功 → 走一次朋友圈呼应掷点/排程
    }

    /** 卷一 A4b：见面结束成功 → 补跑一次主动送礼维护线（见面期被闸掉的礼物/红包当天补送·E5）。 */
    @Test
    fun 退出_finalize成功_补跑主动送礼维护线() {
        coEvery { offlineMeetingService.finalizeOfflineMode(any(), any()) } returns true
        controller.exitOfflineMode()
        coVerify(exactly = 1) { proactiveGiftMaintenance.runMaintenance(any()) }
    }

    /** finalize 失败（本就不在见面）→ 不补跑，避免无谓 LLM 评估。 */
    @Test
    fun 退出_finalize返回false_不补跑维护线() {
        coEvery { offlineMeetingService.finalizeOfflineMode(any(), any()) } returns false
        controller.exitOfflineMode()
        coVerify(exactly = 0) { proactiveGiftMaintenance.runMaintenance(any()) }
    }

    @Test
    fun 退出_finalize返回false_仍打断但不补摘要() {
        coEvery { offlineMeetingService.finalizeOfflineMode(any(), any()) } returns false
        controller.exitOfflineMode()
        assertEquals(1, cancelActiveTurnCount)
        assertEquals(0, afterSummaryCount)
        assertEquals(0, afterglowScheduledCount) // finalize 失败 → 不排余温 worker
        assertEquals(0, echoScheduledCount) // 也不掷呼应帖的点
    }

    @Test
    fun 异常恢复结束_finalize并隐藏弹窗() {
        recoveryPromptFlow.value = true
        coEvery {
            offlineMeetingService.finalizeOfflineMode("conv-1", OfflineMeetingService.ExitReason.USER_ABORTED)
        } returns true
        controller.endMeetingFromRecovery()
        coVerify { offlineMeetingService.finalizeOfflineMode("conv-1", OfflineMeetingService.ExitReason.USER_ABORTED) }
        assertFalse(recoveryPromptFlow.value)
    }

    @Test
    fun 关闭恢复弹窗_仅隐藏不触发() {
        recoveryPromptFlow.value = true
        controller.dismissOfflineRecoveryPrompt()
        assertFalse(recoveryPromptFlow.value)
        assertEquals(0, runAssistantTurnCount)
    }

    // ---- Phase 10 到点赴约 ----

    private fun appt(
        scheduledAt: Long,
        status: String = "confirmed",
        granularity: String = "exact",
    ) = MeetingAppointmentEntity(
        uuid = "appt-1",
        conversationUuid = "conv-1",
        status = status,
        scheduledAt = scheduledAt,
        timeGranularity = granularity,
        location = "咖啡馆",
        activity = "喝咖啡",
        hiddenTensionSeed = "有点紧张",
    )

    @Test
    fun 赴约_宽限窗口内_进沉浸并markHonored并触发回合() {
        val now = System.currentTimeMillis()
        coEvery { meetingAppointmentStore.get("appt-1") } returns appt(now - 60_000) // 1 分钟前到点·exact 3h 窗内
        coEvery { offlineMeetingService.startFromAppointment("conv-1", "咖啡馆", "喝咖啡", "有点紧张") } returns "session-1"
        controller.arriveAtAppointment("appt-1")
        assertEquals(1, cancelActiveTurnCount) // 复核 MED：进沉浸前先打断在投递回合(防赴约被 serialize 丢弃)
        coVerify { offlineMeetingService.startFromAppointment("conv-1", "咖啡馆", "喝咖啡", "有点紧张") }
        coVerify { meetingAppointmentStore.markHonored("appt-1", "session-1", any()) }
        assertEquals(1, runAssistantTurnCount)
    }

    /** 进不去且会话**不在**见面（罕见：会话缺失等）→ 不 honored、不开场（原行为保持）。 */
    @Test
    fun 赴约_startReturnsNull且非见面_不markHonored不触发() {
        val now = System.currentTimeMillis()
        coEvery { meetingAppointmentStore.get("appt-1") } returns appt(now - 60_000)
        coEvery { offlineMeetingService.startFromAppointment(any(), any(), any(), any()) } returns null
        coEvery { conversationRepo.get("conv-1") } returns null
        controller.arriveAtAppointment("appt-1")
        assertEquals(1, cancelActiveTurnCount) // 窗内已打断；startFromAppointment 返 null → 不 honored/不开场
        coVerify(exactly = 0) { meetingAppointmentStore.markHonored(any(), any(), any()) }
        assertEquals(0, runAssistantTurnCount)
    }

    /**
     * 卷一 D1a（拍板⑪）：进不去是因为**已经在见面中**（用户正是在赴这场约）→ 补 markHonored 链当前 sessionId，
     * 不能放着让爽约扫描判「你没来」；已在见面故不再重复开场。
     */
    @Test
    fun 赴约_已在见面中_补markHonored不重复开场() {
        val now = System.currentTimeMillis()
        coEvery { meetingAppointmentStore.get("appt-1") } returns appt(now - 60_000)
        coEvery { offlineMeetingService.startFromAppointment(any(), any(), any(), any()) } returns null
        coEvery { conversationRepo.get("conv-1") } returns ConversationEntity(
            uuid = "conv-1", title = "t", characterUuid = "c1", creationDate = 0L,
            isInOfflineMode = true, currentOfflineSessionId = "sess-live",
        )
        controller.arriveAtAppointment("appt-1")
        coVerify(exactly = 1) { meetingAppointmentStore.markHonored("appt-1", "sess-live", any()) }
        assertEquals(0, runAssistantTurnCount)
    }

    /** 脏态（旗标 true 而 sessionId 空）→ 仍判已赴约，sessionId 传空串（fail-closed）。 */
    @Test
    fun 赴约_见面脏态_markHonored空sessionId() {
        val now = System.currentTimeMillis()
        coEvery { meetingAppointmentStore.get("appt-1") } returns appt(now - 60_000)
        coEvery { offlineMeetingService.startFromAppointment(any(), any(), any(), any()) } returns null
        coEvery { conversationRepo.get("conv-1") } returns ConversationEntity(
            uuid = "conv-1", title = "t", characterUuid = "c1", creationDate = 0L,
            isInOfflineMode = true, currentOfflineSessionId = null,
        )
        controller.arriveAtAppointment("appt-1")
        coVerify(exactly = 1) { meetingAppointmentStore.markHonored("appt-1", "", any()) }
    }

    @Test
    fun 赴约_过宽限_不进沉浸_交爽约扫描() {
        val now = System.currentTimeMillis()
        coEvery { meetingAppointmentStore.get("appt-1") } returns appt(now - 4 * 3600_000L) // exact 4h 前·超 3h 宽限
        controller.arriveAtAppointment("appt-1")
        assertEquals(0, cancelActiveTurnCount) // 过宽限：守卫先返回，不打断、不进沉浸
        coVerify(exactly = 0) { offlineMeetingService.startFromAppointment(any(), any(), any(), any()) }
        coVerify(exactly = 0) { meetingAppointmentStore.markHonored(any(), any(), any()) }
        assertEquals(0, runAssistantTurnCount)
    }

    @Test
    fun 赴约_非confirmed_不进沉浸() {
        val now = System.currentTimeMillis()
        coEvery { meetingAppointmentStore.get("appt-1") } returns appt(now - 60_000, status = "proposed")
        controller.arriveAtAppointment("appt-1")
        coVerify(exactly = 0) { offlineMeetingService.startFromAppointment(any(), any(), any(), any()) }
        assertEquals(0, runAssistantTurnCount)
    }

    @Test
    fun 赴约_约定不存在_noop() {
        coEvery { meetingAppointmentStore.get("missing") } returns null
        controller.arriveAtAppointment("missing")
        coVerify(exactly = 0) { offlineMeetingService.startFromAppointment(any(), any(), any(), any()) }
        assertEquals(0, runAssistantTurnCount)
    }

    // ────────────────── D3 时间感知重进（2026-07-07）──────────────────

    @Test
    fun 恢复弹窗_继续见面_插归来hint并触发一拍() {
        coEvery { offlineMeetingService.offlineAwayMs("conv-1", any()) } returns 30 * 60_000L
        coEvery { offlineMeetingService.insertReturnAfterAwayHint(any(), any()) } returns true
        recoveryPromptFlow.value = true
        controller.continueMeetingFromRecovery()
        assertFalse(recoveryPromptFlow.value)
        coVerify { offlineMeetingService.insertReturnAfterAwayHint("conv-1", 30L) }
        assertEquals(1, runAssistantTurnCount)
    }

    @Test
    fun 恢复弹窗_继续见面_时长取不到只关弹窗() {
        coEvery { offlineMeetingService.offlineAwayMs("conv-1", any()) } returns null
        recoveryPromptFlow.value = true
        controller.continueMeetingFromRecovery()
        assertFalse(recoveryPromptFlow.value)
        coVerify(exactly = 0) { offlineMeetingService.insertReturnAfterAwayHint(any(), any()) }
        assertEquals(0, runAssistantTurnCount)
    }

    @Test
    fun 进页_弹恢复弹窗时记录离开时长() {
        coEvery { offlineMeetingService.shouldShowRecoveryPrompt("conv-1", any()) } returns true
        coEvery { offlineMeetingService.offlineAwayMs("conv-1", any()) } returns 4 * 60 * 60_000L
        controller.handleChatAppear()
        assertTrue(recoveryPromptFlow.value)
        assertEquals(4 * 60 * 60_000L, controller.recoveryAwayMs.value)
    }
}
