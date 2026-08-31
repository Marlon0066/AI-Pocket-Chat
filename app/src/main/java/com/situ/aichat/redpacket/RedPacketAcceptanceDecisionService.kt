package com.situ.aichat.redpacket

import android.util.Log
import com.situ.aichat.data.local.dao.RedPacketDao
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.RedPacketRecordEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.model.RedPacketAmountCatalog
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.data.remote.llm.ResponseFormatDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.gift.FestivalCalendar
import com.situ.aichat.prompt.AssistantOutputGate
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.util.JSONExtractor
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 角色收红包 LLM 决策服务（1:1 iOS `Services/RedPacketAcceptanceDecisionService.swift`，阶段 5.5 · Sub D.1）。
 *
 * **流程**：用户发红包 → [RedPacketService.sendFromUser] 扣款建 pending Record → 本服务独立 LLM 调用决策「收 or 拒」
 * → 调 [RedPacketService.acceptRedPacket]/[RedPacketService.rejectRedPacket]。和主聊天流互不干扰（独立 system+user prompt，
 * 不进 chat 历史）；角色想说的话靠系统事件 + 下次对话带出，外加可选的 chatReply 延迟插一条 assistant 消息。
 *
 * **4 层稳定性**（对齐 ProactiveGiftLLMService）：① Prompt 严格 schema 约束 ② Schema 校验 retry-with-feedback
 * （[MAX_RETRIES_ON_SCHEMA_FAILURE] 次）③ 网络指数退避 1s→2s→4s（[MAX_RETRIES_ON_NETWORK_FAILURE] 次）
 * ④ **兜底默认收下**（[fallbackDecision] shouldAccept=true，保证用户发出的钱不被吞，不带 chatReply）。
 *
 * **T4 约束**：prompt 只透露金额**分档**（[RedPacketAmountCatalog.tier]），**不露精确数字**。
 *
 * iOS 是 `@MainActor enum`；安卓 `@Singleton`（注 [LlmClient]）。纯逻辑（parseAndValidate/buildPrompt/fallbackDecision/
 * formatDialogueLines）在 companion 单测；decide/decideAndApply 编排走 LLM + Room，靠独立复核 + 真机。
 */
@Singleton
class RedPacketAcceptanceDecisionService @Inject constructor(
    private val contextLog: ContextLogService,
    private val redPacketService: RedPacketService,
    private val redPacketDao: RedPacketDao,
    private val messageRepo: MessageRepository,
    private val conversationRepo: ConversationRepository,
    private val characterRepo: CharacterRepository,
    private val apiConfigRepo: ApiConfigRepository,
) {

    /** LLM 或兜底层的决策结果（1:1 iOS `Decision`）。 */
    data class Decision(
        val shouldAccept: Boolean,
        /** 拒收时的短理由（≤30 字）；shouldAccept=true 时为 null。 */
        val rejectionReason: String?,
        /** 决策理由（必填，供日志与 rejectionReason 兜底）。 */
        val reason: String,
        /** true=兜底层产出，false=LLM 真的决定了。 */
        val isFromFallback: Boolean,
        /** 角色对红包事件的对话式反应（≤40 字第一人称，可选；兜底层不带）。 */
        val chatReply: String? = null,
    )

    /** 决策上下文（不含精确 amount，只放 amountTier，1:1 iOS `Context`）。 */
    data class Context(
        val characterName: String,
        val personalityDescription: String,
        val speakingStyle: String,
        val amountTier: String,
        val blessingText: String,
        val festivalName: String?,
        val recentDialogueLines: List<String>,
        val relationshipLabel: String?,
    )

    /** 校验错误（1:1 iOS `DecisionError`），[description] 喂回 prompt 做 retry-with-feedback。 */
    sealed class DecisionError(val description: String) {
        class NotValidJSON(detail: String) : DecisionError("JSON 解析失败: $detail")
        class MissingField(detail: String) : DecisionError("字段缺失: $detail")
        class InvalidField(detail: String) : DecisionError("字段无效: $detail")
    }

    /** parseAndValidate 结果（替 iOS `Result<Decision, DecisionError>`）。 */
    sealed interface ParseOutcome {
        data class Success(val decision: Decision) : ParseOutcome
        data class Failure(val error: DecisionError) : ParseOutcome
    }

    // ── 公开 API ──

    /**
     * 决策主入口（**返回一定不为 null**，失败走兜底默认收下）。[config] null = 无 API → 直接兜底。
     */
    suspend fun decide(context: Context, config: ApiConfigValues?): Decision {
        if (config == null) {
            Log.w(TAG, "无可用 API 配置,走兜底 for ${context.characterName}")
            return fallbackDecision()
        }
        tryLLMDecision(context, config)?.let { return it }
        Log.w(TAG, "LLM 决策全失败,走兜底层 for ${context.characterName}")
        return fallbackDecision()
    }

    /**
     * 决策 + 应用（1:1 iOS `decideAndApply`）：组 Context → decide → accept/reject → 若有 chatReply 延迟 1.5s 插
     * 一条 .plainText assistant 消息。**只决策用户→角色的 pending 红包**（接收方=角色 LLM 决定）。
     *
     * @return Decision（调用方日志用）；record/character 缺失或非 pending 时返回兜底并尽力 accept（不吞用户的钱）。
     */
    suspend fun decideAndApply(recordUuid: String, now: Long = System.currentTimeMillis()): Decision {
        val record = redPacketDao.getByUuid(recordUuid)
        if (record == null) {
            Log.w(TAG, "decideAndApply 找不到 record $recordUuid")
            return fallbackDecision()
        }
        val character = characterRepo.get(record.receiverCharacterUUID)
        if (character == null) {
            // 角色缺失也不吞钱：尽力收下（兜底语义）
            Log.w(TAG, "decideAndApply 找不到接收方角色 ${record.receiverCharacterUUID}，兜底收下 record $recordUuid")
            runCatching { redPacketService.acceptRedPacket(recordUuid, now) }
                .onFailure { Log.e(TAG, "兜底收下失败 $recordUuid: ${it.message}") }
            return fallbackDecision()
        }

        val context = buildContext(record, character.name, character.personalityDescription, character.speakingStyle)
        val config = apiConfigRepo.resolveConfigValues(ApiFunction.CHAT)
        val decision = decide(context, config)

        try {
            if (decision.shouldAccept) {
                redPacketService.acceptRedPacket(recordUuid, now)
                Log.i(TAG, "红包 $recordUuid 被 ${character.name} 收下;兜底=${decision.isFromFallback}")
            } else {
                val reason = decision.rejectionReason?.trim().orEmpty()
                val finalReason = reason.ifEmpty { decision.reason }
                redPacketService.rejectRedPacket(recordUuid, finalReason, now)
                Log.i(TAG, "红包 $recordUuid 被 ${character.name} 拒收 · 理由=$finalReason;兜底=${decision.isFromFallback}")
            }
        } catch (e: RedPacketError) {
            // 终态校验/save 失败（如已被并发解决）：记日志不抛，决策已尽力
            Log.w(TAG, "应用红包决策失败 $recordUuid: ${e.message}")
            return decision
        }

        // 兜底层不带 chatReply（LLM 挂了就静默）；有则延迟 1.5s 插一条 assistant 消息
        val reply = decision.chatReply?.trim()
        if (!reply.isNullOrEmpty()) {
            insertCharacterChatReplyWithDelay(record.conversationUuid, reply)
        }
        return decision
    }

    /** 延迟 1.5s 插一条 .plainText assistant 消息承载角色口头反应（真人感）。失败静默记日志，不阻塞主路径。 */
    private suspend fun insertCharacterChatReplyWithDelay(conversationUuid: String, replyText: String) {
        // 落库前置闸（图纸 2026-09-01 件①）：本函数是纯聊天口头反应路，落库 kind 恒 PLAIN_TEXT，判脏即整条不插。
        // 金额 / 台账 / 状态机 / 反伪造闸全在本函数之外，零碰。
        if (AssistantOutputGate.shouldDiscard(replyText, MessageKind.PLAIN_TEXT, source = "redPacket")) return
        if (CHAT_REPLY_INSERTION_DELAY_MS > 0) delay(CHAT_REPLY_INSERTION_DELAY_MS)
        val conversation = conversationRepo.get(conversationUuid)
        if (conversation == null) {
            Log.w(TAG, "插角色 chatReply 失败:找不到 conversation $conversationUuid")
            return
        }
        val ts = System.currentTimeMillis()
        messageRepo.upsert(
            MessageEntity(
                messageUUID = UUID.randomUUID().toString(),
                conversationUuid = conversationUuid,
                roleRaw = "assistant",
                content = replyText,
                timestamp = ts,
                messageKindRaw = MessageKind.PLAIN_TEXT.raw,
            ),
        )
        conversationRepo.recordLastMessage(conversationUuid, replyText.take(60), "assistant", ts)
    }

    // ── Context 构造 ──

    /** 组装决策 Context（金额→分档 T4 不露数字、节日→中文名、最近 6 条非系统对话、当前关系）。 */
    private suspend fun buildContext(
        record: RedPacketRecordEntity,
        characterName: String,
        personalityDescription: String,
        speakingStyle: String,
    ): Context {
        val recent = messageRepo.recentChronological(record.conversationUuid, RECENT_FETCH_LIMIT)
        return Context(
            characterName = characterName,
            personalityDescription = personalityDescription,
            speakingStyle = speakingStyle,
            amountTier = RedPacketAmountCatalog.tier(record.amount),
            blessingText = record.blessingText,
            festivalName = resolveFestivalName(record.festivalId),
            recentDialogueLines = formatDialogueLines(recent, characterName),
            relationshipLabel = characterRepo.currentRelationship(record.receiverCharacterUUID)?.trim()?.ifEmpty { null },
        )
    }

    /** LLM 决策链：schema-retry-with-feedback（最多 [MAX_RETRIES_ON_SCHEMA_FAILURE] 次再试）。 */
    private suspend fun tryLLMDecision(context: Context, config: ApiConfigValues): Decision? {
        var previousError: String? = null
        for (attempt in 0..MAX_RETRIES_ON_SCHEMA_FAILURE) {
            val (system, user) = buildPrompt(context, previousError)
            val response = callLLMWithBackoff(system, user, context.characterName, config) ?: return null // 网络全失败 → 兜底
            when (val outcome = parseAndValidate(response)) {
                is ParseOutcome.Success -> return outcome.decision
                is ParseOutcome.Failure -> {
                    previousError = outcome.error.description
                    Log.i(TAG, "Schema 校验失败 (第 ${attempt + 1} 次):${outcome.error.description}")
                }
            }
        }
        return null
    }

    /** 网络指数退避 1s→2s→4s（[MAX_RETRIES_ON_NETWORK_FAILURE] 次）。 */
    private suspend fun callLLMWithBackoff(system: String, user: String, characterName: String, config: ApiConfigValues): String? {
        val messages = listOf(
            ChatMessageDto(role = "system", content = system),
            ChatMessageDto(role = "user", content = user),
        )
        for (attempt in 0 until MAX_RETRIES_ON_NETWORK_FAILURE) {
            try {
                return contextLog.completion(
                    source = LogSource.RED_PACKET_DECISION,
                    characterName = characterName,
                    config = config,
                    messages = messages,
                    temperature = DECISION_TEMPERATURE,
                    responseFormat = ResponseFormatDto(type = "json_object"),
                )
            } catch (e: Exception) {
                Log.i(TAG, "LLM 调用失败 (第 ${attempt + 1} 次):${e.message}")
                if (attempt < MAX_RETRIES_ON_NETWORK_FAILURE - 1) {
                    delay((2.0.pow(attempt) * 1000).toLong())
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "RedPacketDecision"

        /** Schema 校验失败 retry-with-feedback 最多次数（1:1 iOS）。 */
        const val MAX_RETRIES_ON_SCHEMA_FAILURE = 2

        /** 网络错误指数退避重试最多次数（1:1 iOS）。 */
        const val MAX_RETRIES_ON_NETWORK_FAILURE = 3

        /** 决策温度（适度随机出个性，1:1 iOS 0.7）。 */
        const val DECISION_TEMPERATURE = 0.7

        /** chatReply 插 assistant 消息前延迟（真人感「收下后停顿再回话」，1:1 iOS 1.5s）。 */
        const val CHAT_REPLY_INSERTION_DELAY_MS = 1500L

        /** 取最近对话候选条数（再过滤系统卡片后截 6 条）。 */
        private const val RECENT_FETCH_LIMIT = 30

        private val json = Json { ignoreUnknownKeys = true }

        /** 兜底（Layer 4）：LLM 全失败默认收下（保证用户发出的钱不被吞），不带 chatReply（1:1 iOS `fallbackDecision`）。 */
        fun fallbackDecision(): Decision = Decision(
            shouldAccept = true,
            rejectionReason = null,
            reason = "兜底 · LLM 决策失败,默认收下以保证用户发出的钱不被吞",
            isFromFallback = true,
            chatReply = null,
        )

        /**
         * 严格校验 LLM 响应（1:1 iOS `parseAndValidate`）：strip think → JSONExtractor → shouldAccept(Bool)+reason(非空)
         * 必填；chatReply 必填 ≤40 截断不含 red_packet/gift_；shouldAccept=false 时 rejectionReason 必填 ≤30 截断。
         */
        fun parseAndValidate(response: String): ParseOutcome {
            val cleaned = MemoryService.strippingThinkingTags(response)
            val jsonStr = JSONExtractor.extract(cleaned)
            val obj = runCatching { json.parseToJsonElement(jsonStr) }.getOrNull() as? JsonObject
                ?: return ParseOutcome.Failure(DecisionError.NotValidJSON("JSON 根对象不是 dict"))

            val shouldAccept = obj.boolField("shouldAccept")
                ?: return ParseOutcome.Failure(DecisionError.MissingField("shouldAccept 必须是 Bool"))
            val reason = obj.stringField("reason")?.trim()
            if (reason.isNullOrEmpty()) {
                return ParseOutcome.Failure(DecisionError.MissingField("reason 必须是非空字符串"))
            }

            val rawRejection = obj.stringField("rejectionReason")?.trim()

            // chatReply 必填 · 40 字硬截断 · 不得含技术 id
            val rawReply = obj.stringField("chatReply")?.trim()
            if (rawReply.isNullOrEmpty()) {
                return ParseOutcome.Failure(DecisionError.MissingField("chatReply 必填(≤40 字,第一人称回用户一句话)"))
            }
            if (rawReply.contains("red_packet") || rawReply.contains("gift_")) {
                return ParseOutcome.Failure(DecisionError.InvalidField("chatReply 不得含技术 id 前缀"))
            }
            val cappedReply = if (rawReply.length > 40) rawReply.take(40) else rawReply

            return if (shouldAccept) {
                ParseOutcome.Success(
                    Decision(shouldAccept = true, rejectionReason = null, reason = reason, isFromFallback = false, chatReply = cappedReply),
                )
            } else {
                if (rawRejection.isNullOrEmpty()) {
                    return ParseOutcome.Failure(DecisionError.InvalidField("shouldAccept=false 时 rejectionReason 必填(≤30 字)"))
                }
                if (rawRejection.contains("red_packet") || rawRejection.contains("gift_")) {
                    return ParseOutcome.Failure(DecisionError.InvalidField("rejectionReason 不得含技术 id 前缀"))
                }
                val capped = if (rawRejection.length > 30) rawRejection.take(30) else rawRejection
                ParseOutcome.Success(
                    Decision(shouldAccept = false, rejectionReason = capped, reason = reason, isFromFallback = false, chatReply = cappedReply),
                )
            }
        }

        /** 节日 id → 中文名（查不到返回原始 id，LLM 也能从 id 推语义；空 → null，1:1 iOS `resolveFestivalName`）。 */
        fun resolveFestivalName(festivalId: String?): String? {
            val id = festivalId?.trim()
            if (id.isNullOrEmpty()) return null
            return FestivalCalendar.festivalById(id)?.name ?: id
        }

        /**
         * 最近对话格式化（1:1 iOS `fetchRecentDialogueLines`）：过滤系统卡片只留 plainText/systemHint 非空 → 取最近 6 条
         * → 时间升序 → 「用户:…」/「<角色>:…」前缀，单条截 60 字 + "…"。纯函数（输入按时间升序的消息列表）。
         */
        fun formatDialogueLines(chronological: List<MessageEntity>, characterName: String): List<String> {
            return chronological
                .filter { msg ->
                    when (MessageKind.fromRaw(msg.messageKindRaw)) {
                        MessageKind.PLAIN_TEXT, MessageKind.SYSTEM_HINT -> msg.content.trim().isNotEmpty()
                        else -> false // 红包/礼物/通话/系统事件等不进「对话氛围」段
                    }
                }
                .takeLast(6)
                .map { msg ->
                    val role = if (msg.roleRaw == "user") "用户" else characterName
                    val raw = msg.content.trim()
                    val truncated = if (raw.length > 60) raw.take(60) + "…" else raw
                    "$role:$truncated"
                }
        }

        /**
         * 构造 system + user prompt（1:1 iOS `buildPrompt`）：金额只透露分档不露数字；[previousError] 非 null 时末尾追加
         * 「上次出错提示」做 retry-with-feedback。
         */
        fun buildPrompt(context: Context, previousError: String? = null): Pair<String, String> {
            val sys = mutableListOf<String>()
            sys.add("你是「${context.characterName}」。")
            if (context.personalityDescription.isNotEmpty()) sys.add("性格:${context.personalityDescription}")
            if (context.speakingStyle.isNotEmpty()) sys.add("说话风格:${context.speakingStyle}")
            sys.add("")
            sys.add("## 任务")
            sys.add("用户刚发给你一个红包。决定是否收下,并像真人一样**马上回用户一句话**(chatReply),让对话自然衔接。")
            sys.add("")
            sys.add("## 决策要点")
            sys.add("1. 看自己的性格和当前关系:熟悉亲近的人发的红包一般会收下,陌生/刚认识的可能不收")
            sys.add("2. 看红包档位:档位越高越慎重;重要节日/纪念日收下合理,日常随手发的珍贵档位可能要拒")
            sys.add("3. 看祝福语:有心意的祝福更容易收下,没理由的大额红包可能要拒")
            sys.add("4. 考虑自己当下心情 / 最近对话氛围:吵架冷战时可能拒收")
            sys.add("5. 大多数情况下应当收下(默认选择),拒收要有明确理由")
            sys.add("")
            sys.add("## 输出格式(严格 JSON,不要 markdown 代码块)")
            sys.add("""{"shouldAccept": <true/false>, "rejectionReason": <拒收时的短理由(≤30 字) 或 null>, "reason": "<决策理由,必填>", "chatReply": "<回用户一句话,≤40 字,第一人称>"}""")
            sys.add("")
            sys.add("## 硬性约束")
            sys.add("- shouldAccept=false 时 rejectionReason 必填(≤30 字,第一人称,贴合说话风格)")
            sys.add("- shouldAccept=true 时 rejectionReason 为 null")
            sys.add("- reason 字段必填,不论收或拒都要写清楚理由(供内部日志)")
            sys.add("- rejectionReason 不得含技术 id 或系统格式字符")
            sys.add("- **chatReply 必填**(≤40 字):用你自己的语气回用户一句话。")
            sys.add("  · 收下时可以感谢/撒娇/调侃,贴合当前氛围(例:「谢谢啦,刚好情人节❤️」)")
            sys.add("  · 拒收时可以委婉解释理由(例:「太贵重啦,收不起」)")
            sys.add("  · 禁止:出现技术 id、\"系统\"字样、重复引用 reason/rejectionReason 原文")
            sys.add("  · 禁止:提及具体金额数字(用户想不想让你知道金额由红包气泡决定,不要在这里泄漏)")
            if (previousError != null) {
                sys.add("")
                sys.add("## ⚠️ 上次尝试出错")
                sys.add(previousError)
                sys.add("请严格按格式重新输出。")
            }

            val usr = mutableListOf<String>()
            usr.add("## 红包信息")
            usr.add("- 金额档位:${context.amountTier}")
            if (context.blessingText.isNotEmpty()) usr.add("- 祝福语:「${context.blessingText}」") else usr.add("- 祝福语:(没有写)")
            val festival = context.festivalName
            if (!festival.isNullOrEmpty()) usr.add("- 节日:$festival") else usr.add("- 节日:(非节日,日常发送)")
            context.relationshipLabel?.takeIf { it.isNotEmpty() }?.let { usr.add("- 当前关系:$it") }
            usr.add("")
            usr.add("## 最近对话")
            if (context.recentDialogueLines.isEmpty()) {
                usr.add("(暂无对话,或是第一次聊)")
            } else {
                for (line in context.recentDialogueLines) usr.add("- $line")
            }
            usr.add("")
            usr.add("现在请决定:是否收下这个红包?输出 JSON。")

            return sys.joinToString("\n") to usr.joinToString("\n")
        }

        // ── JSON 字段读取（对齐 iOS `json[key] as? Type` 严格语义） ──
        private fun JsonObject.boolField(key: String): Boolean? =
            (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

        private fun JsonObject.stringField(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
