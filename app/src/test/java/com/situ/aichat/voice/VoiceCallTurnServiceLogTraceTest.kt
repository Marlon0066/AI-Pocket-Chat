package com.situ.aichat.voice

import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.LlmClient
import com.situ.aichat.data.remote.llm.LlmRequestCapture
import com.situ.aichat.data.remote.llm.StreamToken
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.diagnostics.LogTrace
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T2-6（时间感知四期·图纸三 §3.11 / E16）：通话一轮 → 成功记录收到本轮 trace（有 turnId、无锚点）与本次的捕获元素，
 * 且收流时捕获元素确实在上下文里（LlmClient 首发点才拿得到）。Robolectric：提示词装配真读字符串资源。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceCallTurnServiceLogTraceTest {

    private val contextLog = mockk<ContextLogService>(relaxed = true)
    private val llmClient = mockk<LlmClient>()
    private val config = ApiConfigValues(providerType = ApiProviderType.DEEPSEEK, apiKey = "k", baseUrl = "https://api.deepseek.com/v1", modelName = "m")

    private val conversationRepo = mockk<ConversationRepository>(relaxed = true).also {
        coEvery { it.get("conv-1") } returns ConversationEntity(uuid = "conv-1", title = "t", characterUuid = "c1", creationDate = 0L)
    }
    private val characterRepo = mockk<CharacterRepository>(relaxed = true).also {
        coEvery { it.get("c1") } returns CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)
        coEvery { it.getMilestones(any()) } returns emptyList()
    }
    private val userProfileDao = mockk<UserProfileDao>(relaxed = true).also { coEvery { it.get() } returns null }

    private fun service() = VoiceCallTurnService(
        context = RuntimeEnvironment.getApplication(),
        messageRepo = mockk(relaxed = true) { coEvery { recentChronological(any(), any()) } returns emptyList() },
        conversationRepo = conversationRepo,
        characterRepo = characterRepo,
        offlineMeetingMemoryRepository = mockk(relaxed = true) { coEvery { renderedForInjection(any()) } returns "" },
        ourDayRepository = mockk(relaxed = true) { coEvery { injectableForCharacter(any()) } returns emptyList() },
        apiConfigRepo = mockk(relaxed = true) { coEvery { resolveConfigValues(ApiFunction.VOICE_CALL) } returns config },
        settingsRepo = mockk(relaxed = true) {
            coEvery { getAppSettings() } returns AppSettings(vectorSearchThreshold = 0, calendarIntegrationEnabled = false, petSystemEnabled = false)
        },
        stickerRepo = mockk(relaxed = true),
        petRepo = mockk(relaxed = true),
        petInventoryPromptService = mockk(relaxed = true),
        userProfileDao = userProfileDao,
        scheduleDao = mockk(relaxed = true) { coEvery { scheduleFor(any(), any()) } returns null },
        calendarReader = mockk(relaxed = true),
        vectorMemory = mockk(relaxed = true),
        memoryService = mockk(relaxed = true),
        momentChatContextService = mockk(relaxed = true) { coEvery { buildMomentContext(any(), any(), any(), any(), any()) } returns null },
        economicStateService = mockk(relaxed = true) { coEvery { resolveChatState(any(), any()) } returns null },
        giftDao = mockk(relaxed = true),
        ttsService = mockk(relaxed = true),
        ttsConfigRepo = mockk(relaxed = true),
        llmClient = llmClient,
        contextLog = contextLog,
        worldBookPromptService = mockk {
            coEvery { activateForTurn(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns null
        },
    )

    @Test
    fun 通话一轮_成功记录带trace与本次捕获_收流时捕获在上下文里() = runBlocking {
        var captureWhileStreaming: LlmRequestCapture? = null
        every { llmClient.streamChat(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns flow {
            captureWhileStreaming = currentCoroutineContext()[LlmRequestCapture]
            emit(StreamToken.Content("嗯，在呢"))
        }
        val trace = slot<LogTrace?>()
        val capture = slot<LlmRequestCapture?>()

        service().streamResponse("conv-1", "c1", "在吗") { }

        verify(timeout = 2000) {
            contextLog.recordSuccess(eq(LogSource.VOICE_CALL), any(), any(), any(), any(), any(), any(), any(), any(), captureNullable(trace), captureNullable(capture))
        }
        val t = trace.captured!!
        assertEquals("conv-1", t.conversationUuid)
        assertEquals("c1", t.characterUuid)
        assertNotNull(t.turnId)
        assertNull("通话没有用户消息锚点（E16）", t.anchorMessageUuid)
        assertNotNull(capture.captured)
        assertSame("收流时上下文里的捕获 = 记录收到的捕获", capture.captured, captureWhileStreaming)
    }
}
