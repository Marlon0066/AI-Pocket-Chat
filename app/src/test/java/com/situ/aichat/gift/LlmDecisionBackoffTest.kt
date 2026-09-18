package com.situ.aichat.gift

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.ProactiveGiftContext
import com.situ.aichat.data.model.ProactiveGiftTrigger
import com.situ.aichat.data.model.ProactiveGiftTriggerType
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.redpacket.RedPacketAcceptanceDecisionService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 主动送礼 / 红包决策的**网络退避真实节奏**（2026-09-18 提示词册核对 B 条）：两处类注释曾写「1s→2s→4s」，
 * 代码实为「最多共调 3 次、间隔 1s → 2s、最后一次失败不等」。本测试钉代码真值，防注释再漂。
 *
 * 期望从规格独立反推：3 次调用发生在虚拟时刻 0 / 1000 / 3000 ms；全失败后决策立即回兜底（总耗时 = 3000 ms，
 * 不会再多等一个 4 s）。手法：`runTest` 虚拟时钟 + MockK 让 LLM 出口每次都抛网络异常，并记下每次被调的时刻。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LlmDecisionBackoffTest {

    private val config = mockk<ApiConfigValues>(relaxed = true)

    /** LLM 出口恒抛网络异常，并按虚拟时刻记下每次调用。 */
    private fun TestScope.failingContextLog(callTimes: MutableList<Long>): ContextLogService {
        val contextLog = mockk<ContextLogService>()
        coEvery {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            callTimes += testScheduler.currentTime
            throw IOException("断网")
        }
        return contextLog
    }

    @Test
    fun 主动送礼_网络全失败_共调3次_间隔1s再2s_随后走兜底() = runTest {
        val callTimes = mutableListOf<Long>()
        val service = ProactiveGiftLLMService(failingContextLog(callTimes))
        val trigger = ProactiveGiftTrigger(ProactiveGiftTriggerType.MISSING_YOU, "想你", "test", 0L)
        val start = testScheduler.currentTime

        val decision = service.decide(
            context = ProactiveGiftContext(
                characterUUID = "c1", characterName = "小雨", occupation = "程序员",
                candidateTriggers = listOf(trigger), daysSinceLastProactiveGift = 10,
                economicTier = null, monthlySalary = 10000, coinBalance = 5000,
                relationshipLabel = "朋友", recentMoodSummary = "green",
            ),
            trigger = trigger,
            candidates = listOfNotNull(GiftCatalog.find("gift_oden")),
            character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L),
            config = config,
        )

        assertEquals("三次调用的虚拟时刻应为 0 / 1000 / 3000", listOf(0L, 1000L, 3000L), callTimes.map { it - start })
        assertEquals("最后一次失败后不再等待", 3000L, testScheduler.currentTime - start)
        assertTrue("网络全失败应走兜底层", decision.isFromFallback)
    }

    @Test
    fun 红包决策_网络全失败_共调3次_间隔1s再2s_随后默认收下() = runTest {
        val callTimes = mutableListOf<Long>()
        val service = RedPacketAcceptanceDecisionService(
            contextLog = failingContextLog(callTimes),
            redPacketService = mockk(relaxed = true),
            redPacketDao = mockk(relaxed = true),
            messageRepo = mockk(relaxed = true),
            conversationRepo = mockk(relaxed = true),
            characterRepo = mockk(relaxed = true),
            apiConfigRepo = mockk(relaxed = true),
        )
        val start = testScheduler.currentTime

        val decision = service.decide(
            RedPacketAcceptanceDecisionService.Context(
                characterName = "小雨", personalityDescription = "", speakingStyle = "",
                amountTier = "小额", blessingText = "", festivalName = null,
                recentDialogueLines = emptyList(), relationshipLabel = null,
            ),
            config,
        )

        assertEquals("三次调用的虚拟时刻应为 0 / 1000 / 3000", listOf(0L, 1000L, 3000L), callTimes.map { it - start })
        assertEquals("最后一次失败后不再等待", 3000L, testScheduler.currentTime - start)
        assertTrue("网络全失败应默认收下", decision.shouldAccept && decision.isFromFallback)
    }
}
