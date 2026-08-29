package com.situ.aichat.seam

import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.pet.PetChatBubbleService
import com.situ.aichat.world.live.WorldVisitGreeter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卷一 C6「宠物 + 世界」行为测试（图纸 §7 T2-C6）：见面中不插宠物气泡（A3）、不落世界到达开场白
 * （A5·J6「本次跳过不补发」且**不占用幂等位**）；非见面路径原行为对照（N1）。
 */
class PetAndWorldMeetingGateTest {

    private fun convo(inMeeting: Boolean) = ConversationEntity(
        uuid = "conv-1", title = "t", characterUuid = "c1", creationDate = 0L,
        isInOfflineMode = inMeeting, currentOfflineSessionId = if (inMeeting) "sess-1" else null,
    )

    // ── A3 宠物气泡 ──

    private fun petService(inMeeting: Boolean, dao: ConversationDao, messageDao: MessageDao, repo: ConversationRepository): PetChatBubbleService {
        coEvery { dao.latestActiveForCharacter("c1") } returns convo(inMeeting)
        coEvery { messageDao.countPetMessagesSince(any(), any()) } returns 0
        return PetChatBubbleService(dao, messageDao, repo)
    }

    @Test
    fun 宠物气泡_见面中_不插不顶预览() = runTest {
        val dao: ConversationDao = mockk(relaxed = true)
        val messageDao: MessageDao = mockk(relaxed = true)
        val repo: ConversationRepository = mockk(relaxed = true)
        val service = petService(inMeeting = true, dao = dao, messageDao = messageDao, repo = repo)
        assertFalse(service.notifyConsumableUsed("小饼干", "c1"))
        coVerify(exactly = 0) { messageDao.upsert(any()) }
        coVerify(exactly = 0) { repo.recordLastMessage(any(), any(), any(), any()) }
    }

    @Test
    fun 宠物气泡_非见面_照常插入() = runTest {
        val dao: ConversationDao = mockk(relaxed = true)
        val messageDao: MessageDao = mockk(relaxed = true)
        val repo: ConversationRepository = mockk(relaxed = true)
        val service = petService(inMeeting = false, dao = dao, messageDao = messageDao, repo = repo)
        assertTrue(service.notifyEquipped("金色小皇冠", "c1"))
        coVerify(exactly = 1) { messageDao.upsert(match { it.isPetMessage }) }
        coVerify(exactly = 1) { repo.recordLastMessage("conv-1", any(), "assistant", any()) }
    }

    // ── A5 世界来访到达开场白 ──

    @Test
    fun 世界开场白_见面中_跳过且不占幂等位() = runTest {
        val convoRepo: ConversationRepository = mockk(relaxed = true)
        val messageRepo: MessageRepository = mockk(relaxed = true)
        coEvery { convoRepo.getOrCreateForCharacter("c1", "小晚") } returns "conv-1"
        coEvery { convoRepo.get("conv-1") } returns convo(inMeeting = true)
        WorldVisitGreeter(convoRepo, messageRepo).greetArrival("c1", "小晚", "c1:1000", 5_000L)
        coVerify(exactly = 0) { messageRepo.upsert(any()) }
        coVerify(exactly = 0) { convoRepo.applyMaterialization(any(), any(), any(), any()) }
        // 幂等位未被占用的证据：闸在 messageRepo.get(openerUuid) 之前早退 → 连查都没查过。
        coVerify(exactly = 0) { messageRepo.get(any()) }
    }

    @Test
    fun 世界开场白_非见面_照常落开场() = runTest {
        val convoRepo: ConversationRepository = mockk(relaxed = true)
        val messageRepo: MessageRepository = mockk(relaxed = true)
        coEvery { convoRepo.getOrCreateForCharacter("c1", "小晚") } returns "conv-1"
        coEvery { convoRepo.get("conv-1") } returns convo(inMeeting = false)
        coEvery { messageRepo.get(any()) } returns null
        WorldVisitGreeter(convoRepo, messageRepo).greetArrival("c1", "小晚", "c1:1000", 5_000L)
        coVerify(exactly = 1) { messageRepo.upsert(match { it.content in WorldVisitGreeter.OPENERS }) }
        coVerify(exactly = 1) { convoRepo.applyMaterialization("conv-1", any(), 5_000L, false) }
    }
}
