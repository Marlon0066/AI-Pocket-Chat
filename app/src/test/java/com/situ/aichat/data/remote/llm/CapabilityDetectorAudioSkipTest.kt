package com.situ.aichat.data.remote.llm

import com.situ.aichat.data.model.ApiProviderType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 音频探针 provider 跳过表的 T1（图纸 2026-08-31 E9）。
 *
 * 为什么单钉 ANTHROPIC：Claude 原生 API 没有「音频输入」这个内容类型，官方 OpenAI 兼容层对
 * `input_audio` 明文「Ignored」——**静默剥掉却照样返 200**，探针于是把「不支持」读成「支持」。
 * 探针改掉 max_tokens 方言后这条假阳性更容易撞上，故把「恒 0 且不发网络」钉死在这里。
 *
 * 手法：用**真** [CapabilityDetector] 实例（跳过分支在发请求之前就 return，不会真联网）；
 * baseUrl 故意给一个不可解析的地址——**万一**哪天有人把跳过分支删了，测试会因连接失败落 -1 而变红，
 * 而不是悄悄放行（不是「断言不发网络」的替身，是它的反证保险）。
 */
class CapabilityDetectorAudioSkipTest {

    private val detector = CapabilityDetector(OkHttpClient(), Json { ignoreUnknownKeys = true })

    private fun values(provider: ApiProviderType) = ApiConfigValues(
        providerType = provider,
        apiKey = "",
        baseUrl = "https://probe-must-not-be-sent.invalid/v1",
        modelName = "claude-opus-5",
    )

    @Test
    fun `Anthropic 音频探针恒判不支持且不发请求`() = runBlocking {
        assertEquals(0, detector.detectAudioInputSupport(values(ApiProviderType.ANTHROPIC)))
    }

    @Test
    fun `DeepSeek 与 MiniMax 的既有跳过不变`() = runBlocking {
        assertEquals(0, detector.detectAudioInputSupport(values(ApiProviderType.DEEPSEEK)))
        assertEquals(0, detector.detectAudioInputSupport(values(ApiProviderType.MINIMAX)))
    }
}
