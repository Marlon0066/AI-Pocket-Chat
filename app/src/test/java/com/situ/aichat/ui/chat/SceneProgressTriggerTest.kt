package com.situ.aichat.ui.chat

import android.util.Log
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.offline.SceneProgressService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * [SceneProgressTrigger] 行为测试（审计 S3 抽协作者标配 T2）——验证线下节拍状态触发「真的按规格触发/不触发」：
 * 非线下不动、阈值不足不生成、达标生成落库并推进计数（同批不重复生成）、失败设冷却不推进计数（冷却期不重试）。
 * 手法：MockK 假仓库；[SceneProgressService.shouldTriggerUpdate] 纯函数放真跑（断言从规格反推），
 * 仅 mockkObject 掉走 LLM 的 generateProgress；scope 用 Unconfined（launch 体同步跑完，确定性断言）。
 */
class SceneProgressTriggerTest {

    private val conversationRepo = mockk<ConversationRepository>(relaxed = true)
    private val messageRepo = mockk<MessageRepository>()
    private val characterRepo = mockk<CharacterRepository>(relaxed = true)
    private val userProfileDao = mockk<UserProfileDao>(relaxed = true)
    private val apiConfigRepo = mockk<ApiConfigRepository>()
    private val contextLog = mockk<ContextLogService>(relaxed = true)
    private lateinit var trigger: SceneProgressTrigger

    private fun convo(offline: Boolean = true, sessionId: String? = "sess-1") = ConversationEntity(
        uuid = "conv-1", title = "t", characterUuid = "c1", creationDate = 0L,
        isInOfflineMode = offline, currentOfflineSessionId = sessionId,
    )

    private fun userMessages(count: Int): List<MessageEntity> = (1..count).map {
        MessageEntity(
            messageUUID = "u$it", conversationUuid = "conv-1", roleRaw = "user",
            content = "消息$it", timestamp = it.toLong(),
        )
    }

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        mockkObject(SceneProgressService)
        coEvery { conversationRepo.get("conv-1") } returns convo()
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.SCENE_PROGRESS) } returns null
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.CHAT) } returns mockk<ApiConfigValues>(relaxed = true)
        trigger = SceneProgressTrigger(
            scope = CoroutineScope(Dispatchers.Unconfined),
            conversationUuid = "conv-1",
            conversationRepo = conversationRepo,
            messageRepo = messageRepo,
            characterRepo = characterRepo,
            userProfileDao = userProfileDao,
            apiConfigRepo = apiConfigRepo,
            contextLog = contextLog,
        )
    }

    @After
    fun tearDown() {
        unmockkObject(SceneProgressService)
        unmockkStatic(Log::class)
    }

    @Test
    fun 非线下会话_不读消息不生成() = runBlocking {
        coEvery { conversationRepo.get("conv-1") } returns convo(offline = false)
        trigger.incrementRoundAndCheck()
        coVerify(exactly = 0) { messageRepo.offlineSessionMessages(any(), any()) }
    }

    @Test
    fun 阈值不足_不生成不落库() = runBlocking {
        coEvery { messageRepo.offlineSessionMessages("conv-1", "sess-1") } returns userMessages(14) // <15（真阈值逻辑）
        trigger.incrementRoundAndCheck()
        coVerify(exactly = 0) { SceneProgressService.generateProgress(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { conversationRepo.updateSceneProgress(any(), any()) }
    }

    @Test
    fun 达标_生成落库_计数推进后同批不重复生成() = runBlocking {
        coEvery { messageRepo.offlineSessionMessages("conv-1", "sess-1") } returns userMessages(15)
        coEvery {
            SceneProgressService.generateProgress(any(), any(), any(), any(), any(), any())
        } returns "节拍原文"
        trigger.incrementRoundAndCheck()
        // 生成结果原样落库（2026-08-31 人设优先微图纸：张力自愈已退役，卡片纯场记）。
        coVerify(exactly = 1) { conversationRepo.updateSceneProgress("conv-1", "节拍原文") }
        // 计数已推进到 15 → 同批（仍 15 条）再触发不足差值，不重复生成。
        trigger.incrementRoundAndCheck()
        coVerify(exactly = 1) { SceneProgressService.generateProgress(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun 生成剥空_视同失败_不空写覆盖旧节拍_设冷却() = runBlocking {
        // 纯思考响应剥净后为空：绝不把空串写库覆盖旧节拍状态，走失败冷却语义（不推进计数、冷却期不重试）。
        coEvery { messageRepo.offlineSessionMessages("conv-1", "sess-1") } returns userMessages(15)
        coEvery {
            SceneProgressService.generateProgress(any(), any(), any(), any(), any(), any())
        } returns ""
        trigger.incrementRoundAndCheck()
        coVerify(exactly = 0) { conversationRepo.updateSceneProgress(any(), any()) }
        trigger.incrementRoundAndCheck()
        coVerify(exactly = 1) { SceneProgressService.generateProgress(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun 生成失败_设冷却不推进计数_冷却期内不重试() = runBlocking {
        coEvery { messageRepo.offlineSessionMessages("conv-1", "sess-1") } returns userMessages(15)
        coEvery {
            SceneProgressService.generateProgress(any(), any(), any(), any(), any(), any())
        } throws RuntimeException("网络失败")
        trigger.incrementRoundAndCheck()
        coVerify(exactly = 0) { conversationRepo.updateSceneProgress(any(), any()) }
        // 冷却已设（lastUpdate=now）→ 立刻再触发被 3min 冷却挡住，不重试（规格：失败不更新 triggerCount、冷却后才重试）。
        trigger.incrementRoundAndCheck()
        coVerify(exactly = 1) { SceneProgressService.generateProgress(any(), any(), any(), any(), any(), any()) }
    }
}
