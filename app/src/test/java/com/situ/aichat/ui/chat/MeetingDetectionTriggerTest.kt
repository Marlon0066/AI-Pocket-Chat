package com.situ.aichat.ui.chat

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MeetingCandidate
import com.situ.aichat.data.model.MeetingCandidateIntent
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.meeting.MeetingAppointmentStore
import com.situ.aichat.meeting.MeetingProposalCoordinator
import com.situ.aichat.meeting.MeetupNotificationService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * 识别扫描触发行为测（8d-3a）：节奏判定 → 扫描(mock LLM·无需真 key) → 入库候选 → 记成功/失败冷却 + 守卫。
 * Unconfined scope 让 fire-and-forget launch 内联跑完（mock 的 suspend 即刻返回·无真挂起）。
 * 纯逻辑(decision/parse/validate/prompt)由 MeetingDetectionServiceTest 覆盖；本测专验**接线 + 守卫**。
 */
class MeetingDetectionTriggerTest {

    private val character = CharacterEntity(uuid = "c1", name = "团子", creationDate = 0L)

    private fun convo(offline: Boolean = false, lastScan: Long? = null) =
        ConversationEntity(uuid = "conv1", title = "", characterUuid = "c1", creationDate = 0L, isInOfflineMode = offline, lastMeetingScanSuccessDate = lastScan)

    /** 4+ 轮（countRounds=user 消息数）的最近对话。 */
    private fun fourRounds(): List<MessageEntity> = (1..8).map { i ->
        MessageEntity(
            messageUUID = "m$i", conversationUuid = "conv1",
            roleRaw = if (i % 2 == 1) "user" else "assistant",
            content = if (i % 2 == 1) "周六一起看展吧$i" else "好呀$i",
            timestamp = i.toLong(), messageKindRaw = "plain_text",
        )
    }

    private fun trigger(
        conv: ConversationRepository,
        msg: MessageRepository,
        store: MeetingAppointmentStore,
        coord: MeetingProposalCoordinator,
        log: ContextLogService,
        meetup: MeetupNotificationService = mockk(relaxed = true),
    ) = MeetingDetectionTrigger(CoroutineScope(Dispatchers.Unconfined), "conv1", conv, msg, store, coord, meetup, log)

    @Test fun scan_triggers_ingestsNewCandidate_recordsSuccess() {
        val conv = mockk<ConversationRepository>(relaxed = true)
        val msg = mockk<MessageRepository>(relaxed = true)
        val store = mockk<MeetingAppointmentStore>(relaxed = true)
        val coord = mockk<MeetingProposalCoordinator>(relaxed = true)
        val log = mockk<ContextLogService>()
        coEvery { conv.get("conv1") } returns convo()
        coEvery { msg.recentVisibleChronological("conv1", any()) } returns fourRounds()
        coEvery { store.activeForCharacter("c1") } returns emptyList()
        coEvery { log.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            """{"intent":"new","raw_when":"周六下午","activity":"看展","location":"美术馆","proposed_by":"user","confidence":"high"}"""

        trigger(conv, msg, store, coord, log).checkAndTrigger(character, mockk(relaxed = true), "用户")

        coVerify {
            coord.ingestCandidate(
                match { it.intent == MeetingCandidateIntent.NEW && it.activity == "看展" }, "c1", "conv1", any(), any(),
            )
        }
        coVerify { conv.recordMeetingScanResult("conv1", true, any()) }
    }

    @Test fun scan_withCandidates_reschedulesMeetupNotifications() {
        val conv = mockk<ConversationRepository>(relaxed = true)
        val msg = mockk<MessageRepository>(relaxed = true)
        val store = mockk<MeetingAppointmentStore>(relaxed = true)
        val coord = mockk<MeetingProposalCoordinator>(relaxed = true)
        val log = mockk<ContextLogService>()
        val meetup = mockk<MeetupNotificationService>(relaxed = true)
        coEvery { conv.get("conv1") } returns convo()
        coEvery { msg.recentVisibleChronological("conv1", any()) } returns fourRounds()
        coEvery { store.activeForCharacter("c1") } returns emptyList()
        coEvery { log.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            """{"intent":"new","raw_when":"周六下午","activity":"看展","location":"美术馆","proposed_by":"user","confidence":"high"}"""

        trigger(conv, msg, store, coord, log, meetup).checkAndTrigger(character, mockk(relaxed = true), "用户")

        // 复核 HIGH：识别入库后刷到点通知（识别来的 confirm/改期/取消改了 confirmed 约定也排得上闹钟）。
        coVerify { meetup.rescheduleAll(any()) }
    }

    @Test fun fastPath_reschedulesMeetupNotifications() {
        val conv = mockk<ConversationRepository>(relaxed = true)
        val msg = mockk<MessageRepository>(relaxed = true)
        val store = mockk<MeetingAppointmentStore>(relaxed = true)
        val coord = mockk<MeetingProposalCoordinator>(relaxed = true)
        val log = mockk<ContextLogService>(relaxed = true)
        val meetup = mockk<MeetupNotificationService>(relaxed = true)
        coEvery { conv.get("conv1") } returns convo()

        trigger(conv, msg, store, coord, log, meetup)
            .ingestFastPath(listOf(MeetingCandidate(intent = MeetingCandidateIntent.NEW, rawWhen = "周六", activity = "看展")), character)

        coVerify { meetup.rescheduleAll(any()) }
    }

    /**
     * 图纸 2026-08-31 C3：扫描提示词 ①每条消息带说出时刻（治「昨天的『明天』被当今天说的」）
     * ②近 7 天已赴约约定进【近期已赴约的见面】块（旧事重提不算新约）。
     */
    @Test fun scan_promptCarriesTimestampsAndRecentlyHonoredBlock() {
        val conv = mockk<ConversationRepository>(relaxed = true)
        val msg = mockk<MessageRepository>(relaxed = true)
        val store = mockk<MeetingAppointmentStore>(relaxed = true)
        val coord = mockk<MeetingProposalCoordinator>(relaxed = true)
        val log = mockk<ContextLogService>()
        val promptSlot = slot<List<ChatMessageDto>>()
        coEvery { conv.get("conv1") } returns convo()
        coEvery { msg.recentVisibleChronological("conv1", any()) } returns fourRounds()
        coEvery { store.activeForCharacter("c1") } returns emptyList()
        coEvery { store.recentlyHonoredForCharacter("c1", any(), any()) } returns listOf(
            MeetingAppointmentEntity(
                uuid = "h1", characterUuid = "c1", conversationUuid = "conv1", status = "honored",
                scheduledAt = 1_000L, timeGranularity = "dayOnly", activity = "买裙子", outcomeAt = 2_000L,
            ),
        )
        coEvery {
            log.completion(any(), any(), any(), capture(promptSlot), any(), any(), any(), any(), any())
        } returns """{"intent":"none"}"""

        trigger(conv, msg, store, coord, log).checkAndTrigger(character, mockk(relaxed = true), "用户")

        val system = promptSlot.captured.first { it.role == "system" }.content.orEmpty()
        assertTrue("消息带时刻前缀", system.contains("] 用户：周六一起看展吧1"))
        assertTrue("已赴约块在场", system.contains("【近期已赴约的见面】"))
        assertTrue("已赴约条目带活动", system.contains("活动：买裙子（已赴约）"))
    }

    @Test fun scan_offlineMode_skips() {
        val conv = mockk<ConversationRepository>(relaxed = true)
        val msg = mockk<MessageRepository>(relaxed = true)
        val store = mockk<MeetingAppointmentStore>(relaxed = true)
        val coord = mockk<MeetingProposalCoordinator>(relaxed = true)
        val log = mockk<ContextLogService>(relaxed = true)
        coEvery { conv.get("conv1") } returns convo(offline = true)
        coEvery { msg.recentVisibleChronological("conv1", any()) } returns fourRounds()

        trigger(conv, msg, store, coord, log).checkAndTrigger(character, mockk(relaxed = true), "用户")

        coVerify(exactly = 0) { log.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { coord.ingestCandidate(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { conv.recordMeetingScanResult(any(), any(), any()) }
    }

    @Test fun scan_belowMinRounds_skips() {
        val conv = mockk<ConversationRepository>(relaxed = true)
        val msg = mockk<MessageRepository>(relaxed = true)
        val store = mockk<MeetingAppointmentStore>(relaxed = true)
        val coord = mockk<MeetingProposalCoordinator>(relaxed = true)
        val log = mockk<ContextLogService>(relaxed = true)
        coEvery { conv.get("conv1") } returns convo()
        // 仅 2 个 user 消息 → countRounds=2 < minRounds(4) → 不扫。
        coEvery { msg.recentVisibleChronological("conv1", any()) } returns fourRounds().take(3)

        trigger(conv, msg, store, coord, log).checkAndTrigger(character, mockk(relaxed = true), "用户")

        coVerify(exactly = 0) { log.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { coord.ingestCandidate(any(), any(), any(), any(), any()) }
    }

    @Test fun scan_completionThrows_recordsFailure_noIngest() {
        val conv = mockk<ConversationRepository>(relaxed = true)
        val msg = mockk<MessageRepository>(relaxed = true)
        val store = mockk<MeetingAppointmentStore>(relaxed = true)
        val coord = mockk<MeetingProposalCoordinator>(relaxed = true)
        val log = mockk<ContextLogService>()
        coEvery { conv.get("conv1") } returns convo()
        coEvery { msg.recentVisibleChronological("conv1", any()) } returns fourRounds()
        coEvery { store.activeForCharacter("c1") } returns emptyList()
        coEvery { log.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("network")

        trigger(conv, msg, store, coord, log).checkAndTrigger(character, mockk(relaxed = true), "用户")

        coVerify(exactly = 0) { coord.ingestCandidate(any(), any(), any(), any(), any()) }
        coVerify { conv.recordMeetingScanResult("conv1", false, any()) }
    }

    @Test fun fastPath_ingestsEachCandidate() {
        val conv = mockk<ConversationRepository>(relaxed = true)
        val coord = mockk<MeetingProposalCoordinator>(relaxed = true)
        coEvery { conv.get("conv1") } returns convo() // 非线下
        val t = trigger(conv, mockk(relaxed = true), mockk(relaxed = true), coord, mockk(relaxed = true))

        t.ingestFastPath(
            listOf(
                MeetingCandidate(intent = MeetingCandidateIntent.NEW, rawWhen = "周六", activity = "看展"),
                MeetingCandidate(intent = MeetingCandidateIntent.NEW, rawWhen = "周日", activity = "吃饭"),
            ),
            character,
        )

        coVerify(exactly = 2) { coord.ingestCandidate(any(), "c1", "conv1", any(), any()) }
    }

    @Test fun fastPath_offlineMode_skips() {
        val conv = mockk<ConversationRepository>(relaxed = true)
        val coord = mockk<MeetingProposalCoordinator>(relaxed = true)
        coEvery { conv.get("conv1") } returns convo(offline = true)
        val t = trigger(conv, mockk(relaxed = true), mockk(relaxed = true), coord, mockk(relaxed = true))

        t.ingestFastPath(listOf(MeetingCandidate(intent = MeetingCandidateIntent.NEW, rawWhen = "周六", activity = "看展")), character)

        coVerify(exactly = 0) { coord.ingestCandidate(any(), any(), any(), any(), any()) }
    }

    @Test fun scan_crossCharacterTarget_notIngested_butSuccess() {
        // LLM 给的 target_id 指向**别的角色**的约定 → 守卫拦下不入库（防串 id），但扫描算成功。
        val conv = mockk<ConversationRepository>(relaxed = true)
        val msg = mockk<MessageRepository>(relaxed = true)
        val store = mockk<MeetingAppointmentStore>(relaxed = true)
        val coord = mockk<MeetingProposalCoordinator>(relaxed = true)
        val log = mockk<ContextLogService>()
        coEvery { conv.get("conv1") } returns convo()
        coEvery { msg.recentVisibleChronological("conv1", any()) } returns fourRounds()
        coEvery { store.activeForCharacter("c1") } returns emptyList()
        coEvery { store.get("other-appt") } returns MeetingAppointmentEntity(uuid = "other-appt", characterUuid = "OTHER", status = "confirmed")
        coEvery { log.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            """{"intent":"cancel","target_id":"other-appt"}"""

        trigger(conv, msg, store, coord, log).checkAndTrigger(character, mockk(relaxed = true), "用户")

        coVerify(exactly = 0) { coord.ingestCandidate(any(), any(), any(), any(), any()) }
        coVerify { conv.recordMeetingScanResult("conv1", true, any()) }
    }
}
