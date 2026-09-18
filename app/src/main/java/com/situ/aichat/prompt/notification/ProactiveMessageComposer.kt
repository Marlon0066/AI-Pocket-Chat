package com.situ.aichat.prompt.notification

import android.util.Log
import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.model.StructuredMemory
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.ResponseFormatDto
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.notification.ConversationPhase
import com.situ.aichat.notification.ConversationState
import com.situ.aichat.prompt.DirtyMessageDetector
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.prompt.messageLlmSafeText
import com.situ.aichat.sticker.StickerTagParser
import com.situ.aichat.util.DateFormatters
import com.situ.aichat.util.JSONExtractor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * 到点单条主动消息「现做」（主动通知真实感改造 C3）：给定**由头**与**对话状态** → 一条此刻现写的文案。
 *
 * 与被取代的 `DynamicNotificationContentService` 的根本区别：不再排程时批量预烤 + 当日缓存，而是到点
 * 现调一次、只出一条；prompt 带真实对话状态标签（多久没说话 / 对方是否没回），并**允许自然时间表达**
 * （「刚才」「现在」）——因为生成时刻 = 发出时刻（图纸 ⑪ 反转旧规则 8）。
 *
 * 只管「给定由头+状态 → 一条文案」，绝不碰调度与投递（后者在 `ProactiveDeliveryPipeline` / worker 壳）。
 */
@Singleton
class ProactiveMessageComposer @Inject constructor(
    private val contextLog: ContextLogService,
    private val scheduleDao: ScheduleDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val userProfileDao: UserProfileDao,
    private val characterRepository: CharacterRepository,
) {

    /**
     * 现做一条主动消息。返回 null = 生成失败（无 config / 无 key / 网络异常 / 解析失败），调用方决定重试或走兜底链。
     * @param occasion 由头描述（排程时定下的「因为什么事想找你」）。
     */
    suspend fun compose(
        character: CharacterEntity,
        occasion: String,
        state: ConversationState,
        config: ApiConfigValues?,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        if (config == null || config.apiKey.isEmpty() || config.baseUrl.isEmpty() || config.modelName.isEmpty()) {
            return null
        }
        val userName = (userProfileDao.get()?.nickname ?: "").ifEmpty { "用户" }
        val memory = StructuredMemory.decode(character.structuredMemoryJSON)
        val relationship = characterRepository.currentRelationship(character.uuid)
        val schedule = scheduleDao.scheduleFor(character.uuid, DateFormatters.startOfDayMillis(now, zone))
        val weatherInfo: String? = schedule?.weatherCondition?.takeIf { it.isNotEmpty() }?.let { condition ->
            val emoji = schedule.weatherEmoji ?: ""
            var info = "$emoji$condition"
            val high = schedule.temperatureHigh
            val low = schedule.temperatureLow
            if (high != null && low != null) info += "，${low.roundToInt()}~${high.roundToInt()}°C"
            info
        }
        val conversations = conversationDao.getByCharacter(character.uuid)
        val snippet = buildRecentSnippet(conversations.map { it.uuid }, userName, character.name)

        val system = composeSystemPrompt(character, userName, relationship, memory)
        val user = composeUserPrompt(state, weatherInfo, snippet, memory, occasion)

        val raw = runCatching { callLlm(system, user, character.name, config) }.getOrElse {
            Log.e(TAG, "主动消息现做失败：${character.name} - ${it.message}")
            return null
        }
        return parseSingle(raw)
    }

    /** 最近 8 条对话片段（每条≤80 字），过滤系统 / 空 / 脏消息，结构化卡走 [messageLlmSafeText] 脱敏。 */
    private suspend fun buildRecentSnippet(
        conversationUuids: List<String>,
        userName: String,
        characterName: String,
    ): String? {
        if (conversationUuids.isEmpty()) return null
        val raw = conversationUuids.flatMap { messageDao.recentForAnalysis(it, 24) }
        return formatRecentSnippet(raw, userName, characterName)
    }

    private suspend fun callLlm(system: String, user: String, characterName: String, config: ApiConfigValues): String {
        val messages = listOf(
            ChatMessageDto(role = "system", content = system),
            ChatMessageDto(role = "user", content = user),
        )
        return contextLog.completion(
            source = LogSource.DYNAMIC_NOTIFICATION,
            characterName = characterName,
            config = config,
            messages = messages,
            temperature = 0.8,
            maxTokens = 200,
            responseFormat = ResponseFormatDto(type = "json_object"),
        )
    }

    companion object {
        private const val TAG = "ProactiveComposer"
        private const val ROLE_USER = "user"

        /** 由头缺失（升级前烤的老闹钟无 EXTRA_OCCASION）时的兜底由头（图纸 E18 锁定）。 */
        const val FALLBACK_OCCASION = "想起对方，找个话题聊聊"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

        @Serializable
        private data class SingleMessageWrapper(val message: String)

        // MARK: - Prompt 装配（§3.6 锁定）

        internal fun composeSystemPrompt(
            character: CharacterEntity,
            userName: String,
            relationship: String?,
            memory: StructuredMemory,
        ): String = buildList {
            add("你是${character.name}。你现在想给${userName}发一条手机消息。")
            add("")
            add("你的信息：")
            if (character.gender.isNotEmpty()) add("- 性别：${character.gender}")
            if (character.occupation.isNotEmpty()) add("- 身份：${character.occupation}")
            if (character.personalityDescription.isNotEmpty()) add("- 性格：${character.personalityDescription}")
            if (character.speakingStyle.isNotEmpty()) add("- 说话风格：${character.speakingStyle}")
            relationship?.let { add("- 你和${userName}的关系：$it") }
            memory.nicknameFromChar.ifEmpty { null }?.let { add("- 你叫TA：$it") }
            memory.nicknameToChar.ifEmpty { null }?.let { add("- TA叫你：$it") }
            add("")
            add(composeRules())
        }.joinToString("\n")

        /** 规则五条（§9 逐字锁定）。规则 4 = ⑪ 拍板对旧「禁止相对时间词」的反转。 */
        internal fun composeRules(): String = listOf(
            "规则：",
            "1. 像发微信一样自然，绝对不能有系统通知的感觉",
            "2. 只写一条，10-40 个字",
            "3. 不要用 emoji，不用\"亲爱的\"",
            "4. 现在就是你发出这条消息的时刻，可以自然使用\"刚才\"\"现在\"等时间表达",
            "5. 只输出 JSON 对象：{\"message\":\"你要发的那条消息\"}",
        ).joinToString("\n")

        internal fun composeUserPrompt(
            state: ConversationState,
            weatherInfo: String?,
            recentSnippet: String?,
            memory: StructuredMemory,
            occasion: String,
        ): String = buildList {
            add(composeStateLine(state))
            weatherInfo?.let { add("今天天气：$it") }
            recentSnippet?.let { add("最近聊过：\n$it") }
            val memoryText = composeStructuredMemory(memory)
            if (memoryText.isNotEmpty()) add(memoryText)
            add("你现在想说话的由头：$occasion\n围绕这个由头，结合上面的状态和最近聊过的内容，自然地说一句。")
        }.filter { it.isNotEmpty() }.joinToString("\n\n")

        /**
         * 状态段（§3.6 逐字锁定模板）。`{X}` = 距上次说话小时数（整数除法）；`{dPhrase}` = 距最后一条用户消息的
         * 天数短语——有天数则「N天」，null（用户从未发过消息）则「很久」，整体替换而非只替数字
         * （§3.6 R1 修订：旧口径把「很久」替进「{d}天」会产出病句「很久天」）。
         */
        internal fun composeStateLine(state: ConversationState): String {
            val hours = (state.minutesSinceLastMessage ?: 0L) / 60
            val dPhrase = state.daysSinceLastUserMessage?.let { "${it}天" } ?: "很久"
            val base = when (state.phase) {
                // HOT/AFTERGLOW 并入 SAME_DAY（图纸 §3.6 R1 修订）：Pipeline 在两相位先行 Drop，
                // 此分支为 when 穷举兜底、数学不可达。
                ConversationPhase.HOT,
                ConversationPhase.AFTERGLOW,
                ConversationPhase.SAME_DAY,
                -> "你们今天聊过天，距上次说话大约${hours}小时。"
                ConversationPhase.OVERNIGHT -> "你们昨天聊过天。"
                ConversationPhase.NORMAL -> "你们已经${dPhrase}没说话了。"
                ConversationPhase.DISTANT_EARLY,
                ConversationPhase.DISTANT_LATE,
                ConversationPhase.LONG_ABSENCE,
                -> if (state.minutesSinceLastMessage == null) {
                    "你们还没怎么聊过天，这是你主动开启的问候。"
                } else {
                    "你们已经${dPhrase}没说话了，对方一直没有回你，语气要克制、不要埋怨，轻轻地表达想念就好。"
                }
            }
            return if (state.unansweredProactiveCount == 1) {
                "$base\n你上一条消息对方还没回，这条要有分寸，别催促，可以体贴地表示\"不着急回\"。"
            } else {
                base
            }
        }

        /** 结构化记忆七行（照原 `DynamicNotificationContentService.composeStructuredMemory` 逐字搬）。 */
        internal fun composeStructuredMemory(memory: StructuredMemory): String = buildList {
            memory.insideJoke.ifEmpty { null }?.let { add("你们的梗：$it") }
            memory.importantPromise.ifEmpty { null }?.let { add("你们的约定：$it") }
            memory.sharedLikes.ifEmpty { null }?.let { add("共同喜好：$it") }
            memory.deepestChat.ifEmpty { null }?.let { add("最深刻的对话：$it") }
            memory.impressionOfUser.ifEmpty { null }?.let { add("你对TA的印象：$it") }
            memory.learnedPhrase.ifEmpty { null }?.let { add("你学会的口头禅：$it") }
            memory.comfortStyle.ifEmpty { null }?.let { add("你的安慰方式：$it") }
        }.joinToString("\n")

        /**
         * 把取回的最近消息装配成「最近聊过」片段（最多 8 条·正序·每条≤80 字）：脏消息丢弃；结构化卡走单一事实源
         * [messageLlmSafeText] 脱敏（礼物金币 / 红包信封金额永不露·通话 / 线下卡 → 丢弃）；表情包标签 → [表情包]。
         * 纯函数（DB 取数留给调用方·便于单测）。逐字迁自 `DynamicNotificationContentService`（C6a 删原件）。
         */
        internal fun formatRecentSnippet(
            messages: List<MessageEntity>,
            userName: String,
            characterName: String,
        ): String? {
            val safe = messages.mapNotNull { msg ->
                val kind = MessageKind.fromRaw(msg.messageKindRaw)
                if (DirtyMessageDetector.isDirty(msg.content, kind)) return@mapNotNull null
                messageLlmSafeText(msg)?.let { text -> msg to text }
            }
            if (safe.isEmpty()) return null
            // 跨会话取全局最近 24 条（降序）→ 反转回正序取最后 8 条
            val ordered = safe.sortedByDescending { it.first.timestamp }.take(24)
                .sortedBy { it.first.timestamp }.takeLast(8)
            return ordered.joinToString("\n") { (msg, text) ->
                val role = if (msg.roleRaw == ROLE_USER) userName else characterName
                "$role：${StickerTagParser.replaceStickerTagsForDisplay(text).take(80)}"
            }
        }

        // MARK: - 解析（§3.6 锁定）

        /**
         * 解析单条：`{"message":"…"}`（原文，或经 [JSONExtractor] 剥代码围栏 / 取首尾花括号）→ 失败回退首个非空、
         * 非围栏行；think 标签前置剥离；末尾走 [cleanSingleResponse]。围栏行必须跳过：未闭合围栏时 extract 原样
         * 返回，「```json」会被当成通知正文发出去（2026-09-18 修）。
         */
        internal fun parseSingle(raw: String): String? {
            val cleaned = MemoryService.strippingThinkingTags(raw).trim()
            if (cleaned.isEmpty()) return null
            val wrapped = listOf(cleaned, JSONExtractor.extract(cleaned)).firstNotNullOfOrNull { candidate ->
                runCatching { json.decodeFromString(SingleMessageWrapper.serializer(), candidate) }.getOrNull()?.message
            }
            val candidate = wrapped
                ?: cleaned.lineSequence().firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("```") }
            return candidate?.let { cleanSingleResponse(it) }
        }

        /** 清理单条文案：去首尾引号（直引号 / 中文引号），空或 >60 字判为无效。纯函数（字数上限 50→60 = 图纸 §9）。 */
        internal fun cleanSingleResponse(text: String): String? {
            var cleaned = text.trim()
            val quoted = (cleaned.startsWith("\"") && cleaned.endsWith("\"")) ||
                (cleaned.startsWith("“") && cleaned.endsWith("”"))
            if (quoted && cleaned.length >= 2) {
                cleaned = cleaned.substring(1, cleaned.length - 1).trim()
            }
            if (cleaned.isEmpty() || cleaned.length > 60) return null
            return cleaned
        }
    }
}
