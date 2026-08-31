package com.situ.aichat.offline

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.diagnostics.ContextLogService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SceneProgressService.generateProgress] 剥净内联 <think>（T2·MockK 假 LLM）。
 * 非流式 completion 不剥思考标签，而节拍状态会写回 `currentSceneProgress` 并由 PromptBuilder
 * 逐轮注入线下叙事 system prompt——不剥净则思考文本持久化且每轮回喂。
 * 与 [SceneProgressServiceTest]（纯 JUnit）分文件：formatMessages 依赖 android DateFormat，需 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SceneProgressGenerateProgressTest {

    @Test
    fun `generateProgress剥净think标签`() = runBlocking {
        val contextLog = mockk<ContextLogService>()
        coEvery {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "<think>先梳理节点。</think>allow_end: false\n地点: 江边咖啡馆"
        val msg = MessageEntity(
            messageUUID = "m1", conversationUuid = "c1", roleRaw = "user", content = "你来了",
            timestamp = 1_700_000_000_000L, messageKindRaw = "plain_text",
        )
        val result = SceneProgressService.generateProgress(
            messages = listOf(msg),
            characterName = "小琳",
            userName = "阿哲",
            locationHint = "江边咖啡馆",
            config = mockk<ApiConfigValues>(relaxed = true),
            contextLog = contextLog,
        )
        assertEquals("allow_end: false\n地点: 江边咖啡馆", result)
    }
}
