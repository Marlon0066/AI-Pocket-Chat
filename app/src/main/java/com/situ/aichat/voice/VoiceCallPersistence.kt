package com.situ.aichat.voice

import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.CallRecordData
import com.situ.aichat.data.model.CallRecordJson
import com.situ.aichat.data.model.CallRecordTranscriptEntry
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.prompt.AssistantOutputGate
import com.situ.aichat.prompt.ReplyParser
import com.situ.aichat.prompt.memory.VectorMemoryService
import com.situ.aichat.sticker.StickerTagParser
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists voice-call turns + the aggregate call record (the Android home of iOS
 * `VoiceCallManager+Logging`'s `saveUserMessage` / `saveAIMessage` / `saveCallRecord`), decoupled from
 * any ViewModel (the call runs from a `@Singleton` controller).
 *
 *  - [saveUserMessage] / [saveAiMessage] insert the raw call turns as `plainText` with
 *    `isPartOfVoiceCall = true` (an orthogonal flag = iOS "方案 E") + update the conversation preview.
 *    [saveAiMessage] additionally embeds the AI message and fires the four post-call rounds
 *    ([VoiceCallPostReplyRounds]) — exactly the tail of iOS `saveAIMessage`.
 *  - [saveCallRecord] aggregates the non-empty transcript into ONE `CALL_RECORD_CARD` assistant message
 *    (preview `📞 语音通话`); the per-turn `plainText` messages remain the LLM-visible history (PromptBuilder
 *    excludes the card itself).
 */
@Singleton
class VoiceCallPersistence @Inject constructor(
    private val messageRepo: MessageRepository,
    private val conversationRepo: ConversationRepository,
    private val characterRepo: CharacterRepository,
    private val userProfileDao: UserProfileDao,
    private val settingsRepo: SettingsRepository,
    private val vectorMemory: VectorMemoryService,
    private val postReplyRounds: VoiceCallPostReplyRounds,
) {
    /**
     * Persist the user's recognized utterance (= iOS `saveUserMessage`). Called BEFORE the LLM turn so the
     * history fetch in [VoiceCallTurnService.streamResponse] naturally includes it (no synthetic message).
     */
    suspend fun saveUserMessage(conversationUuid: String, text: String) {
        val now = System.currentTimeMillis()
        val message = MessageEntity(
            messageUUID = UUID.randomUUID().toString(),
            conversationUuid = conversationUuid,
            roleRaw = "user",
            content = text,
            timestamp = now,
            isPartOfVoiceCall = true,
        )
        messageRepo.upsert(message)
        conversationRepo.recordLastMessage(conversationUuid, text.take(PREVIEW_LIMIT), "user", now)
    }

    /**
     * Persist the AI's spoken text (= iOS `saveAIMessage`): sanitize, skip if empty, insert as
     * `isPartOfVoiceCall` plainText, update the preview (sticker tags → `[表情包]`), embed, and fire the
     * four post-call rounds. Returns false when the sanitized text was empty (nothing persisted).
     */
    suspend fun saveAiMessage(conversationUuid: String, characterUuid: String, text: String): Boolean {
        val character = characterRepo.get(characterUuid)
        val normalized = ReplyParser.sanitizeAssistantResponse(text, characterName = character?.name)
        if (normalized.isEmpty()) return false
        // 落库前置闸（图纸 2026-09-01 件①）：本路落库 kind 恒 PLAIN_TEXT，同口径判脏即丢弃
        // （返 false 走调用方既有「空转写」分支，无新分支）。
        if (AssistantOutputGate.shouldDiscard(normalized, MessageKind.PLAIN_TEXT, source = "voiceCall")) return false

        val now = System.currentTimeMillis()
        val message = MessageEntity(
            messageUUID = UUID.randomUUID().toString(),
            conversationUuid = conversationUuid,
            roleRaw = "assistant",
            content = normalized,
            timestamp = now,
            isPartOfVoiceCall = true,
        )
        messageRepo.upsert(message)
        // Preview must replace [sticker:xxx] → [表情包] so a stray sticker tag isn't shown raw in the list
        // (= iOS StickerTagParser.replaceStickerTagsForDisplay).
        val previewSource = StickerTagParser.replaceStickerTagsForDisplay(normalized)
        conversationRepo.recordLastMessage(conversationUuid, previewSource.take(PREVIEW_LIMIT), "assistant", now)

        // = iOS saveAIMessage tail: embed (AI turn only) + memory/structured/growth/relationship rounds.
        vectorMemory.embedMessageIfNeeded(message)
        val settings = settingsRepo.getAppSettings()
        val userName = userProfileDao.get()?.nickname.orEmpty()
        postReplyRounds.onAssistantMessagePersisted(characterUuid, conversationUuid, settings, userName)
        return true
    }

    /**
     * Aggregate the non-empty [transcript] into a single `CALL_RECORD_CARD` message (= iOS `saveCallRecord`).
     * No-op if the transcript has no non-empty lines. Duration is wall-clock `now − start` seconds (≥0).
     */
    suspend fun saveCallRecord(
        conversationUuid: String,
        transcript: List<Pair<String, String>>,
        callStartWallMillis: Long,
        nowWallMillis: Long,
        hadTtsFailure: Boolean = false,
    ) {
        val record = buildCallRecord(transcript, callStartWallMillis, nowWallMillis, hadTtsFailure) ?: return
        val message = MessageEntity(
            messageUUID = UUID.randomUUID().toString(),
            conversationUuid = conversationUuid,
            roleRaw = "assistant",
            content = CallRecordJson.encode(record),
            timestamp = nowWallMillis,
            messageKindRaw = MessageKind.CALL_RECORD_CARD.raw,
        )
        messageRepo.upsert(message)
        conversationRepo.recordLastMessage(conversationUuid, CALL_RECORD_PREVIEW, "assistant", nowWallMillis)
    }

    internal companion object {
        const val PREVIEW_LIMIT = 60 // iOS prefix(60)
        const val CALL_RECORD_PREVIEW = "📞 语音通话" // iOS hardcoded (matches gift/redpacket preview idiom)

        /**
         * Build the call-record data from the transcript + timing (pure, 1:1 iOS `saveCallRecord`):
         * keep only non-blank lines, `duration = max(0, (now − start)/1000)`, `startTime` = ISO-8601 of the
         * start (seconds precision = iOS `ISO8601DateFormatter`). Returns null when no non-blank line exists.
         */
        internal fun buildCallRecord(
            transcript: List<Pair<String, String>>,
            callStartWallMillis: Long,
            nowWallMillis: Long,
            hadTtsFailure: Boolean = false,
        ): CallRecordData? {
            val entries = transcript
                .filter { it.second.trim().isNotEmpty() }
                .map { CallRecordTranscriptEntry(role = it.first, text = it.second) }
            if (entries.isEmpty()) return null
            val duration = ((nowWallMillis - callStartWallMillis) / 1000L).toInt().coerceAtLeast(0)
            return CallRecordData(
                type = "call_record",
                duration = duration,
                startTime = Instant.ofEpochMilli(callStartWallMillis).truncatedTo(ChronoUnit.SECONDS).toString(),
                transcript = entries,
                hadTtsFailure = hadTtsFailure,
            )
        }
    }
}
