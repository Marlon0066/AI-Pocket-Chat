package com.situ.aichat.offline

import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T2-2（图纸 2026-09-06 见面窗口与节拍卡七件 §7·MockK 纯 JVM·照 `MeetingEndMoodTest` 构造）：
 * 「再待一会儿」散场硬闸的**执行层**。断言从 §5 E10/E11/E12 规格独立反推：
 * 续场同事务置闸 3 + 插续场 hint；闸未放开时结束动作整条丢弃（不插卡、不改预览）；闸放开后照常。
 */
class OfflineEndHoldTest {

    @Before fun setUp() = mockkStatic("androidx.room.RoomDatabaseKt")
    @After fun tearDown() = unmockkAll()

    /** 事务壳直通（泛型擦除后只有一个 withTransaction，见面结束路返回 Unit、续场路返回 Boolean，共用同一桩）。 */
    private fun db(): AppDatabase = mockk<AppDatabase>().also { database ->
        coEvery { database.withTransaction<Any?>(any()) } coAnswers { secondArg<suspend () -> Any?>().invoke() }
    }

    private fun convo(holdTurns: Int) = ConversationEntity(
        uuid = "conv1", title = "t", characterUuid = "char1", creationDate = 0L,
        isInOfflineMode = true, currentOfflineSessionId = "sess-1", offlineEndHoldTurns = holdTurns,
    )

    private fun service(convoRepo: ConversationRepository, messageRepo: MessageRepository) = OfflineMeetingService(
        db(), messageRepo, convoRepo, mockk<CharacterRepository>(relaxed = true),
        mockk<ScheduleDao>(relaxed = true), mockk<UserProfileDao>(relaxed = true),
    )

    /** E10：点「再待一会儿」→ 同事务置闸 3 + 落续场 systemHint。 */
    @Test
    fun 续场置硬闸三轮并插续场提示() = runBlocking {
        val convoRepo = mockk<ConversationRepository>(relaxed = true)
        coEvery { convoRepo.get("conv1") } returns convo(holdTurns = 0)
        val messageRepo = mockk<MessageRepository>(relaxed = true)
        val hint = slot<MessageEntity>()
        coEvery { messageRepo.upsert(capture(hint)) } returns Unit

        assertTrue(service(convoRepo, messageRepo).continueOfflineMeeting("conv1"))

        coVerify(exactly = 1) { convoRepo.setOfflineEndHold("conv1", 3) }
        assertEquals(OfflineMeetingService.END_HOLD_TURNS, 3)
        assertEquals(MessageKind.SYSTEM_HINT.raw, hint.captured.messageKindRaw)
        assertTrue("续场 hint 落在本场见面流里", hint.captured.isOfflineMode && hint.captured.offlineSessionId == "sess-1")
    }

    /** E11：闸未放开（hold=2）→ 结束动作整条丢弃：不插结束卡、不改会话预览。 */
    @Test
    fun 硬闸期结束动作被丢弃() = runBlocking {
        val convoRepo = mockk<ConversationRepository>(relaxed = true)
        coEvery { convoRepo.get("conv1") } returns convo(holdTurns = 2)
        val messageRepo = mockk<MessageRepository>(relaxed = true)

        service(convoRepo, messageRepo).handleEndMeeting("conv1", endAction(), emotionTag = "😌")

        coVerify(exactly = 0) { messageRepo.upsert(any()) }
        coVerify(exactly = 0) { convoRepo.recordLastMessage(any(), any(), any(), any()) }
    }

    /** E12：闸放开（hold=0）→ 照常插结束确认卡 + 刷预览（既有行为）。 */
    @Test
    fun 闸放开后结束动作照常落库() = runBlocking {
        val convoRepo = mockk<ConversationRepository>(relaxed = true)
        coEvery { convoRepo.get("conv1") } returns convo(holdTurns = 0)
        val messageRepo = mockk<MessageRepository>(relaxed = true)
        val card = slot<MessageEntity>()
        coEvery { messageRepo.upsert(capture(card)) } returns Unit

        service(convoRepo, messageRepo).handleEndMeeting("conv1", endAction(), emotionTag = "😌")

        coVerify(exactly = 1) { messageRepo.upsert(any()) }
        coVerify(exactly = 1) { convoRepo.recordLastMessage("conv1", any(), "assistant", any()) }
        assertEquals(MessageKind.OFFLINE_END_CARD.raw, card.captured.messageKindRaw)
    }

    private fun endAction() = OfflineMeetingAction(
        action = OfflineMeetingActionType.END_MEETING,
        farewell = "下次再约",
        finalMood = "满足",
    )
}
