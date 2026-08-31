package com.situ.aichat.meeting

import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.FutureMeetingChangeData
import com.situ.aichat.data.model.FutureMeetingChangeJson
import com.situ.aichat.data.model.FutureMeetingProposalData
import com.situ.aichat.data.model.FutureMeetingProposalJson
import com.situ.aichat.data.model.MeetingCandidate
import com.situ.aichat.data.model.MeetingCandidateIntent
import com.situ.aichat.data.model.MeetingTimeGranularity
import com.situ.aichat.data.repository.MessageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

/**
 * 入库协调器行为测：确认闸门（new 才插卡）/ 查重不重复建卡 / 四路分派 / 手动直 confirmed 回执卡 / 确认卡动作。
 * store 与 messageRepo 用 MockK 假掉，断言真理源调用 + 确认卡消息内容（脱敏·绝不裸 JSON）。
 */
class MeetingProposalCoordinatorTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = 1_750_000_000_000L

    private fun appt(uuid: String = "a", status: String = "proposed") =
        MeetingAppointmentEntity(uuid = uuid, status = status)

    @Test fun ingest_new_passesGate_insertsProposalCard() = runBlocking {
        val store = mockk<MeetingAppointmentStore>()
        val repo = mockk<MessageRepository>(relaxed = true)
        // C3：识别路查重换含已赴约版（手动路仍旧版·见 startManual 测试）。
        coEvery { store.findDuplicateForCharacterIncludingHonored(any(), any(), any(), any()) } returns null
        coEvery { store.createProposed(any(), any(), "c1", "conv1", any()) } returns appt("a1")
        val coord = MeetingProposalCoordinator(store, repo)

        val result = coord.ingestCandidate(
            MeetingCandidate(intent = MeetingCandidateIntent.NEW, rawWhen = "周六", activity = "看电影"),
            "c1", "conv1", zone, now,
        )

        assertEquals("a1", result?.uuid)
        // 插了一张待确认确认卡（assistant·结构化 kind·content 是脱敏 JSON·绝不裸内容）
        coVerify {
            repo.upsert(
                match {
                    it.messageKindRaw == "future_meeting_proposal" &&
                        it.roleRaw == "assistant" &&
                        it.conversationUuid == "conv1" &&
                        FutureMeetingProposalJson.parse(it.content)?.let { d ->
                            d.appointmentUuid == "a1" && d.activity == "看电影" && d.responded == null
                        } == true
                },
            )
        }
    }

    @Test fun ingest_new_duplicate_noCardNoCreate() = runBlocking {
        val store = mockk<MeetingAppointmentStore>()
        val repo = mockk<MessageRepository>(relaxed = true)
        coEvery { store.findDuplicateForCharacterIncludingHonored(any(), any(), any(), any()) } returns appt("dup")
        val coord = MeetingProposalCoordinator(store, repo)

        val result = coord.ingestCandidate(
            MeetingCandidate(intent = MeetingCandidateIntent.NEW, rawWhen = "周六", activity = "看电影"),
            "c1", "conv1", zone, now,
        )
        assertNull(result)
        coVerify(exactly = 0) { store.createProposed(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repo.upsert(any()) }
    }

    @Test fun ingest_none_returnsNull() = runBlocking {
        val store = mockk<MeetingAppointmentStore>()
        val repo = mockk<MessageRepository>(relaxed = true)
        assertNull(
            MeetingProposalCoordinator(store, repo)
                .ingestCandidate(MeetingCandidate(intent = MeetingCandidateIntent.NONE), "c1", "conv1", zone, now),
        )
        coVerify(exactly = 0) { repo.upsert(any()) }
    }

    @Test fun ingest_cancel_proposedTarget_directCancel() = runBlocking {
        // proposed 仍在确认闸门内 → 直接取消（不过变更确认卡）。
        val store = mockk<MeetingAppointmentStore>()
        val repo = mockk<MessageRepository>(relaxed = true)
        coEvery { store.get("appt-9") } returns appt("appt-9", status = "proposed")
        coEvery { store.cancel("appt-9", now) } returns appt("appt-9", status = "cancelled")
        val coord = MeetingProposalCoordinator(store, repo)
        val r = coord.ingestCandidate(
            MeetingCandidate(intent = MeetingCandidateIntent.CANCEL, targetAppointmentUuid = "appt-9"),
            "c1", "conv1", zone, now,
        )
        assertEquals("cancelled", r?.status)
        coVerify { store.cancel("appt-9", now) }
        coVerify(exactly = 0) { repo.upsert(any()) } // 未插任何卡
    }

    @Test fun ingest_cancel_confirmedTarget_insertsChangeCard_noMutate() = runBlocking {
        // 决策①：已确认约定的取消**不直接动真理源**，插「变更确认卡」(cancel·待确认)。
        val store = mockk<MeetingAppointmentStore>()
        val repo = mockk<MessageRepository>(relaxed = true)
        coEvery { store.get("appt-c") } returns appt("appt-c", status = "confirmed")
        coEvery { repo.messagesByKind("conv1", "future_meeting_change") } returns emptyList()
        val coord = MeetingProposalCoordinator(store, repo)

        val r = coord.ingestCandidate(
            MeetingCandidate(intent = MeetingCandidateIntent.CANCEL, targetAppointmentUuid = "appt-c", invitationText = "这次先算了"),
            "c1", "conv1", zone, now,
        )

        assertNull(r) // 真理源未变
        coVerify(exactly = 0) { store.cancel(any(), any()) }
        coVerify {
            repo.upsert(
                match {
                    it.messageKindRaw == "future_meeting_change" &&
                        FutureMeetingChangeJson.parse(it.content)?.let { d ->
                            d.appointmentUuid == "appt-c" && d.changeKind == FutureMeetingChangeData.KIND_CANCEL &&
                                d.responded == null && d.reason == "这次先算了"
                        } == true
                },
            )
        }
    }

    @Test fun ingest_reschedule_proposedTarget_directReschedule() = runBlocking {
        val store = mockk<MeetingAppointmentStore>()
        val repo = mockk<MessageRepository>(relaxed = true)
        coEvery { store.get("appt-p") } returns appt("appt-p", status = "proposed")
        coEvery { store.reschedule(eq("appt-p"), any()) } returns appt("appt-p", status = "proposed")
        val coord = MeetingProposalCoordinator(store, repo)

        coord.ingestCandidate(
            MeetingCandidate(intent = MeetingCandidateIntent.RESCHEDULE, targetAppointmentUuid = "appt-p", isoDateTime = "2025-06-21T15:00"),
            "c1", "conv1", zone, now,
        )
        coVerify { store.reschedule(eq("appt-p"), any()) }
        coVerify(exactly = 0) { repo.upsert(any()) }
    }

    @Test fun ingest_reschedule_confirmedTarget_insertsChangeCard_noMutate() = runBlocking {
        val store = mockk<MeetingAppointmentStore>()
        val repo = mockk<MessageRepository>(relaxed = true)
        coEvery { store.get("appt-rc") } returns appt("appt-rc", status = "confirmed")
        coEvery { repo.messagesByKind("conv1", "future_meeting_change") } returns emptyList()
        val coord = MeetingProposalCoordinator(store, repo)

        val r = coord.ingestCandidate(
            MeetingCandidate(intent = MeetingCandidateIntent.RESCHEDULE, targetAppointmentUuid = "appt-rc", isoDateTime = "2025-06-21T15:00"),
            "c1", "conv1", zone, now,
        )
        assertNull(r)
        coVerify(exactly = 0) { store.reschedule(any(), any()) }
        coVerify {
            repo.upsert(
                match {
                    FutureMeetingChangeJson.parse(it.content)?.let { d ->
                        d.changeKind == FutureMeetingChangeData.KIND_RESCHEDULE && d.newScheduledAtMillis != null && d.responded == null
                    } == true
                },
            )
        }
    }

    @Test fun ingest_change_dedup_skipsIfPendingExists() = runBlocking {
        // 同会话已有「同约定 + 同变更类型 + 待确认」的卡 → 不重复插（扫描每轮重测同意图不堆卡）。
        val store = mockk<MeetingAppointmentStore>()
        val repo = mockk<MessageRepository>(relaxed = true)
        coEvery { store.get("appt-d") } returns appt("appt-d", status = "confirmed")
        val pending = MessageEntity(
            messageUUID = "m-old", conversationUuid = "conv1", roleRaw = "assistant",
            content = FutureMeetingChangeJson.encode(
                FutureMeetingChangeData(appointmentUuid = "appt-d", changeKind = FutureMeetingChangeData.KIND_CANCEL, responded = null),
            ),
            timestamp = now, messageKindRaw = "future_meeting_change",
        )
        coEvery { repo.messagesByKind("conv1", "future_meeting_change") } returns listOf(pending)
        val coord = MeetingProposalCoordinator(store, repo)

        coord.ingestCandidate(
            MeetingCandidate(intent = MeetingCandidateIntent.CANCEL, targetAppointmentUuid = "appt-d"),
            "c1", "conv1", zone, now,
        )
        coVerify(exactly = 0) { repo.upsert(any()) } // 去重命中 → 不插新卡
    }

    @Test fun applyChangeFromCard_reschedule_appliesAndConfirms_marksApplied() = runBlocking {
        val store = mockk<MeetingAppointmentStore>()
        val repo = mockk<MessageRepository>(relaxed = true)
        val msg = MessageEntity(
            messageUUID = "m1", conversationUuid = "conv1", roleRaw = "assistant",
            content = FutureMeetingChangeJson.encode(
                FutureMeetingChangeData(
                    appointmentUuid = "appt-x", changeKind = FutureMeetingChangeData.KIND_RESCHEDULE,
                    newScheduledAtMillis = now + 172_800_000L, newGranularity = MeetingTimeGranularity.EXACT.raw,
                ),
            ),
            timestamp = now, messageKindRaw = "future_meeting_change",
        )
        coEvery { repo.get("m1") } returns msg
        coEvery { store.reschedule(eq("appt-x"), any()) } returns appt("appt-x", status = "confirmed")
        coEvery { store.confirm("appt-x", now) } returns appt("appt-x", status = "confirmed")
        val coord = MeetingProposalCoordinator(store, repo)

        val r = coord.applyChangeFromCard("m1", now)
        assertEquals("confirmed", r?.status)
        coVerify { store.reschedule(eq("appt-x"), any()) }
        coVerify { store.confirm("appt-x", now) }
        coVerify { repo.upsert(match { FutureMeetingChangeJson.parse(it.content)?.responded == FutureMeetingChangeData.RESPONDED_APPLIED }) }
    }

    @Test fun applyChangeFromCard_cancel_cancels_marksApplied() = runBlocking {
        val store = mockk<MeetingAppointmentStore>()
        val repo = mockk<MessageRepository>(relaxed = true)
        val msg = MessageEntity(
            messageUUID = "m2", conversationUuid = "conv1", roleRaw = "assistant",
            content = FutureMeetingChangeJson.encode(
                FutureMeetingChangeData(appointmentUuid = "appt-y", changeKind = FutureMeetingChangeData.KIND_CANCEL),
            ),
            timestamp = now, messageKindRaw = "future_meeting_change",
        )
        coEvery { repo.get("m2") } returns msg
        coEvery { store.cancel("appt-y", now) } returns appt("appt-y", status = "cancelled")
        val coord = MeetingProposalCoordinator(store, repo)

        assertEquals("cancelled", coord.applyChangeFromCard("m2", now)?.status)
        coVerify { store.cancel("appt-y", now) }
        coVerify { repo.upsert(match { FutureMeetingChangeJson.parse(it.content)?.responded == FutureMeetingChangeData.RESPONDED_APPLIED }) }
    }

    @Test fun keepChangeFromCard_marksKept_noMutate() = runBlocking {
        val store = mockk<MeetingAppointmentStore>()
        val repo = mockk<MessageRepository>(relaxed = true)
        val msg = MessageEntity(
            messageUUID = "m3", conversationUuid = "conv1", roleRaw = "assistant",
            content = FutureMeetingChangeJson.encode(
                FutureMeetingChangeData(appointmentUuid = "appt-z", changeKind = FutureMeetingChangeData.KIND_CANCEL),
            ),
            timestamp = now, messageKindRaw = "future_meeting_change",
        )
        coEvery { repo.get("m3") } returns msg
        val coord = MeetingProposalCoordinator(store, repo)

        coord.keepChangeFromCard("m3")
        coVerify(exactly = 0) { store.cancel(any(), any()) }
        coVerify(exactly = 0) { store.reschedule(any(), any()) }
        coVerify { repo.upsert(match { FutureMeetingChangeJson.parse(it.content)?.responded == FutureMeetingChangeData.RESPONDED_KEPT }) }
    }

    @Test fun ingest_cancel_noTarget_null() = runBlocking {
        val store = mockk<MeetingAppointmentStore>()
        val repo = mockk<MessageRepository>(relaxed = true)
        assertNull(
            MeetingProposalCoordinator(store, repo)
                .ingestCandidate(MeetingCandidate(intent = MeetingCandidateIntent.CANCEL), "c1", "conv1", zone, now),
        )
    }

    @Test fun startManual_directConfirmed_acceptedReceiptCard() = runBlocking {
        val store = mockk<MeetingAppointmentStore>()
        val repo = mockk<MessageRepository>(relaxed = true)
        coEvery { store.findDuplicateForCharacter(any(), any(), any(), any()) } returns null
        coEvery { store.createConfirmedManually(any(), "c1", "conv1", "猫咖", "撸猫", now) } returns appt("m1", status = "confirmed")
        val coord = MeetingProposalCoordinator(store, repo)

        val r = coord.startManual("c1", "conv1", scheduledAtMillis = now + 86_400_000L, granularity = MeetingTimeGranularity.EXACT, location = "猫咖", activity = "撸猫", zone = zone, nowMillis = now)

        assertEquals("confirmed", r?.status)
        // 回执卡：responded=accepted（无按钮）
        coVerify {
            repo.upsert(
                match { FutureMeetingProposalJson.parse(it.content)?.responded == FutureMeetingProposalData.RESPONDED_ACCEPTED },
            )
        }
    }

    @Test fun confirmAndDecline_fromCard_delegateToStore() = runBlocking {
        val store = mockk<MeetingAppointmentStore>()
        val repo = mockk<MessageRepository>(relaxed = true)
        coEvery { store.confirm("a1", now) } returns appt("a1", "confirmed")
        coEvery { store.cancel("a2", now) } returns appt("a2", "cancelled")
        val coord = MeetingProposalCoordinator(store, repo)

        assertEquals("confirmed", coord.confirmFromCard("a1", now)?.status)
        assertEquals("cancelled", coord.declineFromCard("a2", now)?.status)
    }
}
