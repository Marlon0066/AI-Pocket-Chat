package com.situ.aichat.tts

import com.situ.aichat.tts.provider.TtsResolvedProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TtsService pure resolution logic. Assertions reverse-derived from iOS `resolvedProvider` +
 * `buildMiniMaxOverridesFromRawValues` (TTSService.swift).
 */
class TtsServiceTest {

    // MARK: - buildMiniMaxOverridesFromRawValues

    @Test
    fun `fixed emotion is clamped to the model`() {
        val o = TtsService.buildMiniMaxOverridesFromRawValues("happy", 1.0, 0, "speech-2.8-hd")
        assertEquals("happy", o.emotion)
        assertEquals(1.0, o.speed, 0.0)
        assertEquals(0, o.pitch)
    }

    @Test
    fun `fixed unsupported emotion degrades to calm`() {
        // whisper unsupported on 2.8 → calm; supported on 2.6 → whisper
        assertEquals("calm", TtsService.buildMiniMaxOverridesFromRawValues("whisper", 1.0, 0, "speech-2.8-hd").emotion)
        assertEquals("whisper", TtsService.buildMiniMaxOverridesFromRawValues("whisper", 1.0, 0, "speech-2.6-hd").emotion)
    }

    @Test
    fun `auto plus mood emoji auto-maps, auto without mood is null`() {
        assertEquals("happy", TtsService.buildMiniMaxOverridesFromRawValues("auto", 1.0, 0, "speech-2.8-hd", "😊").emotion)
        assertNull(TtsService.buildMiniMaxOverridesFromRawValues("auto", 1.0, 0, "speech-2.8-hd", "🤔").emotion) // thinking
        assertNull(TtsService.buildMiniMaxOverridesFromRawValues("auto", 1.0, 0, "speech-2.8-hd", null).emotion)
    }

    @Test
    fun `empty emotionRaw behaves like auto`() {
        assertEquals("happy", TtsService.buildMiniMaxOverridesFromRawValues("", 1.0, 0, "speech-2.8-hd", "😊").emotion)
        assertNull(TtsService.buildMiniMaxOverridesFromRawValues("", 1.0, 0, "speech-2.8-hd", null).emotion)
    }

    @Test
    fun `speed and pitch are clamped to MiniMax ranges`() {
        val hi = TtsService.buildMiniMaxOverridesFromRawValues("happy", 3.0, 20, "speech-2.8-hd")
        assertEquals(2.0, hi.speed, 0.0)
        assertEquals(12, hi.pitch)
        val lo = TtsService.buildMiniMaxOverridesFromRawValues("happy", 0.1, -20, "speech-2.8-hd")
        assertEquals(0.5, lo.speed, 0.0)
        assertEquals(-12, lo.pitch)
    }

    // MARK: - resolvedProvider

    @Test
    fun `system with empty voice is none, with voice is system`() {
        assertEquals(
            TtsResolvedProvider.None,
            TtsService.resolvedProvider(TtsVoiceProfile(voiceIdentifier = ""), TtsConfiguration(TtsProviderType.SYSTEM), "key"),
        )
        val r = TtsService.resolvedProvider(TtsVoiceProfile(voiceIdentifier = "zh-CN-x"), TtsConfiguration(TtsProviderType.SYSTEM), "")
        assertTrue(r is TtsResolvedProvider.System)
        assertEquals("zh-CN-x", (r as TtsResolvedProvider.System).voiceIdentifier)
    }

    @Test
    fun `null config defaults to system`() {
        val r = TtsService.resolvedProvider(TtsVoiceProfile(voiceIdentifier = "v"), config = null, apiKey = "k")
        assertEquals("v", (r as TtsResolvedProvider.System).voiceIdentifier)
    }

    @Test
    fun `minimax fully configured resolves to remote with overrides and default baseurl`() {
        val r = TtsService.resolvedProvider(
            profile = TtsVoiceProfile(remoteVoiceID = "vid", ttsEmotionRaw = "happy"),
            config = TtsConfiguration(providerType = TtsProviderType.MINIMAX, modelName = "speech-2.8-hd", baseURL = ""),
            apiKey = "k",
        )
        assertTrue(r is TtsResolvedProvider.Remote)
        r as TtsResolvedProvider.Remote
        assertEquals("vid", r.voiceId)
        assertEquals("https://api.minimaxi.com/v1/t2a_v2", r.config.baseUrl)
        assertEquals("happy", r.config.miniMaxVoiceOverrides?.emotion)
    }

    @Test
    fun `remote is none when key model or voice missing`() {
        val base = TtsConfiguration(providerType = TtsProviderType.MINIMAX, modelName = "speech-2.8-hd")
        val profile = TtsVoiceProfile(remoteVoiceID = "vid")
        assertEquals(TtsResolvedProvider.None, TtsService.resolvedProvider(profile, base, apiKey = ""))
        assertEquals(TtsResolvedProvider.None, TtsService.resolvedProvider(TtsVoiceProfile(remoteVoiceID = ""), base, apiKey = "k"))
        assertEquals(
            TtsResolvedProvider.None,
            TtsService.resolvedProvider(profile, base.copy(modelName = ""), apiKey = "k"),
        )
    }

    @Test
    fun `volink uses default baseurl and has no minimax overrides`() {
        val r = TtsService.resolvedProvider(
            profile = TtsVoiceProfile(remoteVoiceID = "v"),
            config = TtsConfiguration(providerType = TtsProviderType.VOLINK, modelName = "x", baseURL = ""),
            apiKey = "k",
        )
        assertTrue(r is TtsResolvedProvider.Remote)
        r as TtsResolvedProvider.Remote
        assertEquals("https://api.volink.org/v1/tts/speech", r.config.baseUrl)
        assertNull(r.config.miniMaxVoiceOverrides)
    }

    // MARK: - B-1 全局默认音色兜底

    @Test
    fun `B1 system falls back to defaultSystemVoiceIdentifier when character voice is blank`() {
        val config = TtsConfiguration(
            providerType = TtsProviderType.SYSTEM,
            defaultSystemVoiceIdentifier = "zh-CN-default",
        )
        val fallback = TtsService.resolvedProvider(TtsVoiceProfile(voiceIdentifier = ""), config, "")
        assertTrue(fallback is TtsResolvedProvider.System)
        assertEquals("zh-CN-default", (fallback as TtsResolvedProvider.System).voiceIdentifier)

        val override = TtsService.resolvedProvider(TtsVoiceProfile(voiceIdentifier = "zh-CN-char"), config, "")
        assertEquals("zh-CN-char", (override as TtsResolvedProvider.System).voiceIdentifier)
    }

    @Test
    fun `B1 remote falls back to defaultRemoteVoiceID when character remote voice is blank`() {
        val config = TtsConfiguration(
            providerType = TtsProviderType.MINIMAX,
            modelName = "speech-2.8-hd",
            defaultRemoteVoiceID = "global-vid",
        )
        val fallback = TtsService.resolvedProvider(TtsVoiceProfile(remoteVoiceID = ""), config, apiKey = "k")
        assertTrue(fallback is TtsResolvedProvider.Remote)
        assertEquals("global-vid", (fallback as TtsResolvedProvider.Remote).voiceId)

        val override = TtsService.resolvedProvider(TtsVoiceProfile(remoteVoiceID = "char-vid"), config, apiKey = "k")
        assertEquals("char-vid", (override as TtsResolvedProvider.Remote).voiceId)
    }
}
