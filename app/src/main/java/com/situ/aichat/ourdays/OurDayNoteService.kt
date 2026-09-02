package com.situ.aichat.ourdays

import android.util.Log
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.LlmClient
import com.situ.aichat.data.remote.llm.ResponseFormatDto
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.prompt.memory.MemoryService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 手记生成（卷一图纸 §3.4 锁定 · V-3）：只做「装配提示词 → 一次调用（Z-8）→ 解析校验」，**不落库**（落库全在
 * [OurDayCoordinator]）。空响应 200ms 重试 1 次；截断（[LlmClient.isLengthTruncated]）视同失败；异常 / 解析失败返 null。
 * Logcat 只打失败原因，绝不打手记正文（§9.5）。时区只取注入的 [Clock]（§9.4）。
 */
@Singleton
class OurDayNoteService @Inject constructor(
    private val contextLog: ContextLogService,
    private val clock: Clock,
) {
    suspend fun generate(
        character: CharacterEntity,
        userNickname: String,
        dayKey: String,
        facts: OurDayFacts,
        dayMessages: List<MessageEntity>,
        config: ApiConfigValues,
    ): NoteResult? {
        val userCallName = OurDayNotePrompt.userCallName(userNickname)
        val userRefName = OurDayNotePrompt.userRefName(userNickname)
        val dateCn = OurDayKey.dateCn(dayKey)
        val weekdayCn = OurDayKey.weekdayCn(dayKey)
        val system = OurDayNotePrompt.buildSystem(character.name, userCallName, userRefName, dateCn, weekdayCn)
        val user = OurDayNotePrompt.buildUser(
            dateCn = dateCn,
            weekdayCn = weekdayCn,
            personality = character.personalityDescription,
            factsText = OurDayFactsRenderer.render(facts, character.name, userRefName, clock.zone),
            conversationText = OurDayNotePrompt.conversationExcerpt(dayMessages, userRefName, character.name),
        )
        val messages = listOf(
            ChatMessageDto(role = "system", content = system),
            ChatMessageDto(role = "user", content = user),
        )

        for (attempt in 1..2) {
            var finishReason: String? = null
            val raw = try {
                contextLog.completion(
                    source = LogSource.OUR_DAYS,
                    characterName = character.name,
                    config = config,
                    messages = messages,
                    temperature = TEMPERATURE,
                    maxTokens = MAX_TOKENS,
                    responseFormat = ResponseFormatDto(type = "json_object"),
                    onFinishReason = { finishReason = it },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "日子手记生成失败: ${e.message}")
                return null
            }
            if (LlmClient.isLengthTruncated(finishReason)) return null
            val candidate = MemoryService.strippingThinkingTags(raw)
            if (candidate.isNotEmpty()) {
                return when (val p = OurDayNoteParser.parse(candidate)) {
                    is NoteParse.Success -> p.result
                    is NoteParse.Failure -> {
                        Log.w(TAG, "日子手记解析失败: ${p.reason}")
                        null
                    }
                }
            }
            if (attempt < 2) delay(EMPTY_RETRY_DELAY_MS)
        }
        return null
    }

    companion object {
        private const val TAG = "OurDays"
        /** Z-8 锁定：JSON 精度与手记温度的折中。 */
        const val TEMPERATURE = 0.6
        /** Z-8 锁定：非空才启用 LlmClient 撞限 ×3 升额。 */
        const val MAX_TOKENS = 1000
        /** Z-8 锁定：空响应重试 1 次的间隔。 */
        const val EMPTY_RETRY_DELAY_MS = 200L
    }
}
