package com.situ.aichat.redpacket

import com.situ.aichat.data.local.dao.RedPacketDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.RedPacketRecordEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.diagnostics.ContextLogService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 红包收 / 拒决策的兜底编排（2026-09-18 提示词册核对 D 条·钱路相邻）。
 *
 * 旧行为：几次校验都不合格 → 一律默认收下，明确的拒收只要格式有毛病就永远不生效。
 * 新规则（期望从规格反推）：最近一条**有明确布尔表态**的失败回复是拒收 → 照拒收；表态收下 / 没表态 / 断网 → 默认收下。
 * 校验与 retry-with-feedback 本身不放松（照样重试满、照样把出错提示喂回去）；金额与台账全在被假掉的
 * [RedPacketService] 之内，本测只断言「最终调了收还是拒、传了什么理由」。
 *
 * 手法（T2）：真跑 [RedPacketAcceptanceDecisionService.decide] / [RedPacketAcceptanceDecisionService.decideAndApply]，
 * LLM 出口按脚本逐次返回（null = 这次抛网络异常），`runTest` 虚拟时钟吃掉退避与 1.5s 插话延迟。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RedPacketDecisionFallbackTest {

    private val contextLog = mockk<ContextLogService>()
    private val redPacketService = mockk<RedPacketService>(relaxed = true)
    private val redPacketDao = mockk<RedPacketDao>()
    private val messageRepo = mockk<MessageRepository>(relaxed = true)
    private val conversationRepo = mockk<ConversationRepository>(relaxed = true)
    private val characterRepo = mockk<CharacterRepository>(relaxed = true)
    private val apiConfigRepo = mockk<ApiConfigRepository>()
    private val config = mockk<ApiConfigValues>(relaxed = true)
    private val prompts = mutableListOf<List<ChatMessageDto>>()

    private val service = RedPacketAcceptanceDecisionService(
        contextLog = contextLog,
        redPacketService = redPacketService,
        redPacketDao = redPacketDao,
        messageRepo = messageRepo,
        conversationRepo = conversationRepo,
        characterRepo = characterRepo,
        apiConfigRepo = apiConfigRepo,
    )

    private val ctx = RedPacketAcceptanceDecisionService.Context(
        characterName = "小雨", personalityDescription = "", speakingStyle = "",
        amountTier = "大额", blessingText = "", festivalName = null,
        recentDialogueLines = emptyList(), relationshipLabel = null,
    )

    /** 明确拒收，但漏了必填的 rejectionReason（最常见的不合格形态）。 */
    private val rejectNoReason = """{"shouldAccept":false,"reason":"内部判断:和他还不熟","chatReply":"先不收哈"}"""

    /** 明确收下，但漏了必填的 chatReply。 */
    private val acceptNoReply = """{"shouldAccept":true,"reason":"关系不错"}"""

    /**
     * 逐次返回脚本里的回复；null = 这次抛网络异常。脚本用完还被调 → 抛 AssertionError（是 Error 不是 Exception，
     * 服务里的 `catch (e: Exception)` 吞不掉，多调一次必然让测试红，而不是被当成「又断网了」）。
     */
    private fun llmScript(vararg replies: String?) {
        val queue = ArrayDeque(replies.toList())
        coEvery {
            contextLog.completion(any(), any(), any(), capture(prompts), any(), any(), any(), any(), any())
        } coAnswers {
            if (queue.isEmpty()) throw AssertionError("LLM 被多调了一次")
            queue.removeFirst() ?: throw IOException("断网")
        }
    }

    private fun callCount(n: Int) = coVerify(exactly = n) {
        contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
    }

    @Test
    fun 三次都是缺理由的明确拒收_照拒收_且重试与出错提示照旧() = runTest {
        llmScript(rejectNoReason, rejectNoReason, rejectNoReason)
        val d = service.decide(ctx, config)

        assertFalse("明确拒收必须照拒收", d.shouldAccept)
        assertTrue(d.isFromFallback)
        assertNull(d.rejectionReason)
        assertEquals("先不收哈", d.chatReply)
        callCount(3) // 校验不放松：照样重试满
        assertTrue("第二次起要把出错提示喂回去", prompts[1].first().content.orEmpty().contains("上次尝试出错"))
    }

    @Test
    fun 三次都不是JSON_没有明确表态_默认收下() = runTest {
        llmScript("我不想收", "嗯……", "还是算了吧")
        val d = service.decide(ctx, config)

        assertTrue("无明确表态 → 默认收下（旧行为不变）", d.shouldAccept)
        assertTrue(d.isFromFallback)
        assertNull("默认收下不插话", d.chatReply)
    }

    @Test
    fun 先拒收后改口收下_以最近表态为准_默认收下() = runTest {
        llmScript(rejectNoReason, acceptNoReply, "not json")
        val d = service.decide(ctx, config)

        assertTrue("最近一次明确表态是收下 → 默认收下", d.shouldAccept)
        assertTrue(d.isFromFallback)
    }

    @Test
    fun 拒收残缺后断网_照拒收() = runTest {
        llmScript(rejectNoReason, null, null, null)
        val d = service.decide(ctx, config)

        assertFalse("断网前已明确拒收 → 照拒收", d.shouldAccept)
        callCount(4) // 1 次残缺回复 + 第二轮 3 次网络尝试
    }

    @Test
    fun 拒收残缺后重试合格收下_以合格回复为准() = runTest {
        llmScript(rejectNoReason, """{"shouldAccept":true,"reason":"想了想还是收","chatReply":"那我收下啦"}""")
        val d = service.decide(ctx, config)

        assertTrue(d.shouldAccept)
        assertFalse("合格回复不是兜底", d.isFromFallback)
        assertEquals("那我收下啦", d.chatReply)
    }

    @Test
    fun decideAndApply_残缺拒收_真退回_理由不外露内部判断_口头回复照插() = runTest {
        coEvery { redPacketDao.getByUuid("rp1") } returns RedPacketRecordEntity(
            uuid = "rp1", conversationUuid = "conv-1", receiverCharacterUUID = "c1", amount = 520,
        )
        coEvery { characterRepo.get("c1") } returns CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)
        coEvery { characterRepo.currentRelationship("c1") } returns null
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.CHAT) } returns config
        coEvery { messageRepo.recentChronological("conv-1", any()) } returns emptyList()
        coEvery { conversationRepo.get("conv-1") } returns ConversationEntity(
            uuid = "conv-1", title = "", characterUuid = "c1", creationDate = 0L,
        )
        llmScript(rejectNoReason, rejectNoReason, rejectNoReason)

        val d = service.decideAndApply("rp1", now = 1_000L)

        assertFalse(d.shouldAccept)
        coVerify(exactly = 1) { redPacketService.rejectRedPacket("rp1", "", 1_000L) } // 空理由 = 「红包被退回」
        coVerify(exactly = 0) { redPacketService.acceptRedPacket(any(), any()) }
        val inserted = slot<MessageEntity>()
        coVerify(exactly = 1) { messageRepo.upsert(capture(inserted)) }
        assertEquals("角色的口头回复照插", "先不收哈", inserted.captured.content)
    }
}
