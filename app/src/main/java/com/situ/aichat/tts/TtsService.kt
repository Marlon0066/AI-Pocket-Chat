package com.situ.aichat.tts

import android.content.Context
import android.util.Log
import com.situ.aichat.data.remote.LazyOkHttpClient
import com.situ.aichat.tts.pricing.TtsCostEstimate
import com.situ.aichat.tts.pricing.TtsCostEstimator
import com.situ.aichat.tts.pricing.TtsUsageProvider
import com.situ.aichat.tts.pricing.TtsUsageTracker
import com.situ.aichat.tts.provider.CompatibleTtsProvider
import com.situ.aichat.tts.provider.MiniMaxCatalog
import com.situ.aichat.tts.provider.MiniMaxTtsProvider
import com.situ.aichat.tts.provider.MiniMaxVoiceOverrides
import com.situ.aichat.tts.provider.MoodEmojiToMiniMaxEmotion
import com.situ.aichat.tts.provider.TtsRemoteConfigValues
import com.situ.aichat.tts.provider.TtsRemoteException
import com.situ.aichat.tts.provider.TtsRemoteModelOption
import com.situ.aichat.tts.provider.TtsRemoteProvider
import com.situ.aichat.tts.provider.TtsRemoteVoiceOption
import com.situ.aichat.tts.provider.TtsResolvedProvider
import kotlinx.serialization.json.Json

/**
 * TTS routing/resolution layer (1:1 iOS `TTSService` + `+RemoteTTS`). Resolves which provider/voice
 * to use for a character + config, builds MiniMax voice overrides, dispatches remote synthesis, and
 * logs usage. System (on-device Android TextToSpeech) synthesis is added in the per-message-playback
 * chunk; this layer covers resolution + remote synthesis + catalog fetch + pricing.
 *
 * Provided as a singleton via `NetworkModule` (same pattern as `LlmClient`). The pure resolution /
 * override / registry functions live in the companion so they're unit-testable without a Context.
 */
class TtsService(
    private val http: LazyOkHttpClient,
    private val json: Json,
    private val appContext: Context,
    private val systemEngine: SystemTtsEngine,
) {

    /**
     * Resolve provider/voice for [profile] + [config] and synthesize [text] to audio bytes (1:1 iOS
     * `TTSService.synthesize`). Dispatches to on-device system TTS (WAV) or a remote provider (MP3);
     * returns null when nothing usable is configured or synthesis fails. [moodEmoji] (from the reply's
     * `[mood:]` tag) drives MiniMax auto-emotion when the character's emotion is `auto`. Chat path uses
     * `allowDefaultVoice=false` so an unconfigured character stays silent (= iOS).
     */
    suspend fun synthesize(
        text: String,
        profile: TtsVoiceProfile?,
        config: TtsConfiguration?,
        apiKey: String,
        moodEmoji: String? = null,
    ): ByteArray? {
        return when (val resolved = resolvedProvider(profile, config, apiKey, moodEmoji)) {
            is TtsResolvedProvider.System ->
                systemEngine.synthesize(text, resolved.voiceIdentifier, allowDefaultVoice = false)
            is TtsResolvedProvider.Remote ->
                synthesizeRemote(text, resolved.voiceId, resolved.config)
            TtsResolvedProvider.None -> {
                Log.d(TAG, "TTS 跳过·无可用语音配置 providerType=${config?.providerType?.raw ?: "system"}")
                null
            }
        }
    }

    /** System TTS synthesis passthrough (preview uses `allowDefaultVoice=true` to fall back to zh-CN). */
    suspend fun synthesizeSystem(text: String, voiceIdentifier: String, allowDefaultVoice: Boolean): ByteArray? =
        systemEngine.synthesize(text, voiceIdentifier, allowDefaultVoice)

    /** Installed Chinese system voices for the system-voice picker (best quality first, 1:1 iOS). */
    suspend fun availableSystemVoices(): List<SystemVoiceOption> = systemEngine.availableChineseVoices()

    /**
     * Remote synthesis (1:1 iOS `synthesizeRemote`): returns audio bytes, or null on any failure
     * (the error is logged; callers fall back to a toast). Usage is logged only after a successful
     * MiniMax synthesis (other providers aren't metered yet).
     */
    suspend fun synthesizeRemote(text: String, voiceId: String, config: TtsRemoteConfigValues): ByteArray? {
        val provider = remoteProvider(config.providerType) ?: run {
            Log.e(TAG, "remote TTS failed: no provider for type=${config.providerType.raw}")
            return null
        }
        return try {
            val data = provider.synthesize(text, voiceId, config, http.get(), json)
            if (data.isEmpty()) return null
            if (config.providerType == TtsProviderType.MINIMAX) {
                TtsUsageTracker.log(appContext, text.length, TtsUsageProvider.MINIMAX)
            }
            data
        } catch (e: Exception) {
            Log.e(
                TAG,
                "remote TTS failed: provider=${config.providerType.raw} model=${config.modelName} host=${config.baseUrl} cause=${e.message}",
            )
            null
        }
    }

    /** Voice list for the config UI: built-in list when available, else a live fetch. Throws on error. */
    suspend fun fetchRemoteVoices(config: TtsRemoteConfigValues): List<TtsRemoteVoiceOption> {
        val provider = remoteProvider(config.providerType) ?: throw TtsRemoteException.InvalidUrl
        provider.builtInVoices(config.modelName)?.let { return it }
        return provider.fetchVoices(config, http.get(), json)
    }

    /** Model list for the config UI: built-in list when available, else a live fetch. Throws on error. */
    suspend fun fetchRemoteModels(config: TtsRemoteConfigValues): List<TtsRemoteModelOption> {
        val provider = remoteProvider(config.providerType) ?: throw TtsRemoteException.InvalidUrl
        provider.builtInModels()?.let { return it }
        return provider.fetchModels(config, http.get(), json)
    }

    /** MiniMax usage + projected monthly cost snapshot (for the TTS settings screen). */
    fun miniMaxUsageEstimate(modelId: String): TtsCostEstimate =
        TtsCostEstimator.estimate(TtsUsageTracker.snapshot(appContext, TtsUsageProvider.MINIMAX), modelId)

    companion object {
        private const val TAG = "TtsService"

        /**
         * Resolve the provider/voice for a character (1:1 iOS `resolvedProvider`). [apiKey] is supplied
         * by the caller (read from [com.situ.aichat.security.ApiKeyStore] by `config.apiKeyId`) so this
         * stays pure and unit-testable. Returns [TtsResolvedProvider.None] when nothing usable is
         * configured (empty system voice, or any of key/baseURL/model/voice missing for remote).
         */
        fun resolvedProvider(
            profile: TtsVoiceProfile?,
            config: TtsConfiguration?,
            apiKey: String,
            moodEmoji: String? = null,
        ): TtsResolvedProvider {
            val providerType = config?.providerType ?: TtsProviderType.SYSTEM
            return when (providerType) {
                TtsProviderType.SYSTEM -> {
                    val systemVoice = (profile?.voiceIdentifier ?: "").trim()
                        .ifEmpty { (config?.defaultSystemVoiceIdentifier ?: "").trim() }
                    if (systemVoice.isEmpty()) TtsResolvedProvider.None
                    else TtsResolvedProvider.System(systemVoice)
                }
                TtsProviderType.VOLINK,
                TtsProviderType.OPENAI,
                TtsProviderType.CUSTOM_OPENAI_COMPATIBLE,
                TtsProviderType.MINIMAX -> {
                    if (config == null) return TtsResolvedProvider.None
                    val remoteVoice = (profile?.remoteVoiceID ?: "").trim()
                        .ifEmpty { config.defaultRemoteVoiceID.trim() }
                    val effectiveBaseUrl = config.baseURL.trim().ifEmpty { providerType.defaultBaseUrl }
                    val overrides = if (providerType == TtsProviderType.MINIMAX && profile != null) {
                        buildMiniMaxOverridesFromRawValues(
                            emotionRaw = profile.ttsEmotionRaw,
                            speed = profile.ttsSpeed,
                            pitch = profile.ttsPitch,
                            modelName = config.modelName,
                            moodEmoji = moodEmoji,
                        )
                    } else {
                        null
                    }
                    val remoteConfig = TtsRemoteConfigValues(
                        providerType = providerType,
                        providerName = config.providerName,
                        apiKey = apiKey,
                        baseUrl = effectiveBaseUrl,
                        modelName = config.modelName,
                        responseFormat = config.responseFormat,
                        miniMaxVoiceOverrides = overrides,
                    )
                    if (remoteConfig.apiKey.isEmpty() ||
                        remoteConfig.baseUrl.isEmpty() ||
                        remoteConfig.modelName.isEmpty() ||
                        remoteVoice.isEmpty()
                    ) {
                        TtsResolvedProvider.None
                    } else {
                        TtsResolvedProvider.Remote(voiceId = remoteVoice, config = remoteConfig)
                    }
                }
            }
        }

        /**
         * Whether a usable voice is configured for this character (1:1 iOS `hasAvailableVoice`). Drives the
         * chat voice-reply plan: no voice → replies stay text. [apiKey] supplied by the caller (from
         * [com.situ.aichat.security.ApiKeyStore]) so this stays pure.
         */
        fun hasAvailableVoice(profile: TtsVoiceProfile?, config: TtsConfiguration?, apiKey: String): Boolean =
            when (val resolved = resolvedProvider(profile, config, apiKey)) {
                is TtsResolvedProvider.System -> resolved.voiceIdentifier.isNotEmpty()
                is TtsResolvedProvider.Remote -> resolved.voiceId.isNotEmpty()
                TtsResolvedProvider.None -> false
            }

        /** Provider registry by type (1:1 iOS `remoteProvider(for:)`). */
        fun remoteProvider(providerType: TtsProviderType): TtsRemoteProvider? = when (providerType) {
            TtsProviderType.OPENAI,
            TtsProviderType.VOLINK,
            TtsProviderType.CUSTOM_OPENAI_COMPATIBLE -> CompatibleTtsProvider(providerType)
            TtsProviderType.MINIMAX -> MiniMaxTtsProvider()
            TtsProviderType.SYSTEM -> null
        }

        /**
         * Build MiniMax voice overrides from raw character fields (1:1 iOS
         * `buildMiniMaxOverridesFromRawValues`). Pure (no DB / Context). Emotion priority: (1) fixed
         * `ttsEmotionRaw` (non-auto/non-empty) → clamp; (2) auto + moodEmoji → auto-map then clamp;
         * (3) else null. speed→[0.5,2.0], pitch→[-12,12].
         */
        fun buildMiniMaxOverridesFromRawValues(
            emotionRaw: String,
            speed: Double,
            pitch: Int,
            modelName: String,
            moodEmoji: String? = null,
        ): MiniMaxVoiceOverrides {
            val capability = MiniMaxCatalog.capability(modelName)
            val trimmedRaw = emotionRaw.trim().lowercase()
            val resolvedEmotion: String? = if (trimmedRaw.isNotEmpty() && trimmedRaw != "auto") {
                capability.supportedEmotions.clamped(emotionRaw)
            } else {
                val auto = MoodEmojiToMiniMaxEmotion.emotionFor(moodEmoji)
                if (auto != null) capability.supportedEmotions.clamped(auto) else null
            }
            return MiniMaxVoiceOverrides(
                emotion = resolvedEmotion,
                speed = speed.coerceIn(0.5, 2.0),
                pitch = pitch.coerceIn(-12, 12),
            )
        }
    }
}
