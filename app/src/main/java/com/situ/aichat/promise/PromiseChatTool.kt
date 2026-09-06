package com.situ.aichat.promise

import com.situ.aichat.data.remote.llm.FunctionDefinitionDto
import com.situ.aichat.data.remote.llm.FunctionParametersDto
import com.situ.aichat.data.remote.llm.ParameterPropertyDto
import com.situ.aichat.data.remote.llm.ToolDefinitionDto
import com.situ.aichat.tooling.ChatTool
import com.situ.aichat.tooling.ChatToolContext
import com.situ.aichat.tooling.MarkerJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.ZoneId

/**
 * 「我们的约定」聊天内记账 · 工具快路（图纸 2026-09-06 约定工具调用化 §3.2/§3.3·照
 * [com.situ.aichat.meeting.FutureMeetingTool] 双轨范式）。模型在**说定约定 / 发现约定有结果的那一轮当场记账**，
 * 不再只靠攒批对账（[PromiseReconciliation] 保留为兜底）。
 *
 * **双轨**：
 * - **工具路**（toolCallingEnabled）：模型调 [TOOL_RECORD] / [TOOL_RESOLVE]，参数经 [fromToolCall] 解码。
 * - **文本暗号路**（降级）：模型在回复末尾附 `[promise]{...json...}`，经 [parseMarkers] 提取并从正文擦除
 *   （用户绝不可见）。
 *
 * **不下发的两种回合**（[toolDefinitions] / [stepFiveGuardPrompt]）：
 * - 线下见面中（`ctx.offlineMeeting`）：见面里说定的走见面回顾便车，职责边界不重叠。
 * - 语音通话（`ctx.voiceCall`）：通话侧无人解析暗号，注入规则只会让 JSON 被 TTS 念出来。
 *
 * ⚠️ **自有强耦合（图纸 §6）**：[FALLBACK_MARKER_RULE] 里的 `[promise]{…}` 格式 ↔ [parseMarkers] / [PromiseToolArgs]
 * 必须同文件共存，改任一侧同步另一侧（`PromiseChatToolTest` 钉字面）。
 * ⚠️ **编号语义**：[TOOL_RESOLVE] 的 `no` = 本轮【我们的约定】注入块里的编号，单源
 * [PromiseInjectionRenderer.numberedOpen]——改那边的排序 / 上限即改本工具语义。
 *
 * 本对象**全纯函数**：不碰 DB / 网络 / 协程（闸门与落库归 `ChatPromiseToolHandler`）。
 */
internal object PromiseChatTool : ChatTool {

    /** 记新约定工具名（锁定·图纸 §3.2）。 */
    const val TOOL_RECORD = "record_promise"

    /** 了结旧约定工具名（锁定·图纸 §3.2）。 */
    const val TOOL_RESOLVE = "resolve_promise"

    /** 文本暗号前缀（锁定·图纸 §3.3-A·与 [FALLBACK_MARKER_RULE] 强耦合）。 */
    const val MARKER = "[promise]"

    /** 单轮新约定落库上限（图纸 §0.②-10·锁定）。 */
    const val RECORD_CAP = 2

    /** 单轮了结落库上限（图纸 §0.②-10·锁定）。 */
    const val RESOLVE_CAP = 3

    fun isPromiseTool(name: String): Boolean = name == TOOL_RECORD || name == TOOL_RESOLVE

    // ── 工具定义（图纸 §3.2 逐字锁定·properties 顺序即 linkedMapOf 顺序） ──

    val recordDefinition: ToolDefinitionDto = ToolDefinitionDto(
        type = "function",
        function = FunctionDefinitionDto(
            name = TOOL_RECORD,
            description = """
                Record a promise that you and the user have JUST clearly settled in this conversation: something one side promised to do for the other, or something you two agreed to do together. Call it in the same turn the agreement is made.
                Boundaries:
                - Both sides must have clearly agreed. Casual mentions with no follow-up ("改天吧" / "有空再说") are NOT promises.
                - NOT for meeting in person on a future day — use propose_future_meeting for that.
                - NOT for money (红包 / 转账 / 给多少钱 / 送多贵的礼物) — never record those.
                - NOT for long-term habits or standing requests ("以后都叫我宝贝") — only concrete one-time things that can be done and finished.
                - Do not re-record something already listed under 【我们的约定】, even if worded differently.
                Keep chatting naturally in your reply; the app records silently. Never describe the recording in your reply text.
            """.trimIndent(),
            parameters = FunctionParametersDto(
                type = "object",
                properties = linkedMapOf(
                    "content" to ParameterPropertyDto("string", "One sentence, third person, at most 40 Chinese characters. Use both names, never 「用户」 or 「角色」. e.g. 周六小满和阿川一起去看展"),
                    "due" to ParameterPropertyDto("string", "yyyy-MM-dd if a concrete date was settled, computed from the current time given in context; empty string if no date was settled."),
                    "evidence" to ParameterPropertyDto("string", "The exact sentence from this conversation (yours or the user's) that settles the promise, copied verbatim."),
                ),
                required = listOf("content", "evidence"),
            ),
        ),
    )

    val resolveDefinition: ToolDefinitionDto = ToolDefinitionDto(
        type = "function",
        function = FunctionDefinitionDto(
            name = TOOL_RESOLVE,
            description = """
                Mark one promise from the 【我们的约定】 list as fulfilled or cancelled, when this conversation gives clear evidence that it has been done, or that it has been called off. Call it in the same turn the evidence appears.
                Boundaries:
                - `no` is the number in front of that promise in the 【我们的约定】 list. Only use numbers that appear in the list.
                - Only with clear evidence in this conversation. If unsure, do not call — leave it open.
                - A future in-person meeting being kept is handled elsewhere; do not call this for it.
                Keep chatting naturally in your reply; never describe the marking in your reply text.
            """.trimIndent(),
            parameters = FunctionParametersDto(
                type = "object",
                properties = linkedMapOf(
                    "no" to ParameterPropertyDto("integer", "The number in front of the promise in the 【我们的约定】 list."),
                    "status" to ParameterPropertyDto("string", "fulfilled = it was done; cancelled = it was called off and will not happen.", listOf("fulfilled", "cancelled")),
                    "evidence" to ParameterPropertyDto("string", "The exact sentence from this conversation that proves it, copied verbatim."),
                ),
                required = listOf("no", "status", "evidence"),
            ),
        ),
    )

    /** 见面中不下发（职责边界）；其余回合两个工具都下发。 */
    override fun toolDefinitions(ctx: ChatToolContext): List<ToolDefinitionDto> =
        if (ctx.offlineMeeting) emptyList() else listOf(recordDefinition, resolveDefinition)

    /** 仅「暗号路且非通话」贡献降级规则；工具路靠 tools 数组、通话侧无人解析（图纸 §0.②-7）。 */
    override fun stepFiveGuardPrompt(ctx: ChatToolContext): String? =
        if (!ctx.toolCallingEnabled && !ctx.voiceCall) FALLBACK_MARKER_RULE else null

    // ── 工具路：tool_call 参数 → 动作 ──

    /**
     * 工具调用 arguments JSON → 动作；未知工具名 / JSON 非法 / 空壳（content 空、no ≤ 0）→ null
     * （**不计 parsingFailed、不抛**·识别侧宁漏勿错，与约见面同款）。
     */
    fun fromToolCall(name: String, argumentsJson: String, zone: ZoneId = ZoneId.systemDefault()): PromiseToolAction? {
        if (!isPromiseTool(name)) return null
        val args = runCatching { lenientJson.decodeFromString(PromiseToolArgs.serializer(), argumentsJson) }
            .getOrNull() ?: return null
        return actionFrom(name, args, zone)
    }

    // ── 文本暗号路：`[promise]{...}` → 动作 + 从正文擦除 ──

    /**
     * 提取并擦除回复里的 `[promise]{json}` 暗号，返回 (清理后正文, 动作列表)。用户绝不可见标记原文
     * （逐字照 [com.situ.aichat.meeting.FutureMeetingTool.parseProposalMarkers] 结构·JSON 用配平花括号扫描
     * [MarkerJson.matchingBraceEnd]，容忍值里含 `}`）。裸标记 / 非法 JSON / 未知 action 一律仍擦除、不建动作。
     */
    fun parseMarkers(response: String, zone: ZoneId = ZoneId.systemDefault()): Pair<String, List<PromiseToolAction>> {
        val actions = ArrayList<PromiseToolAction>()
        val removeRanges = ArrayList<IntRange>()
        for (m in MARKER_REGEX.findAll(response)) {
            val open = response.indexOf('{', m.range.last + 1)
            val close = if (open >= 0) MarkerJson.matchingBraceEnd(response, open) else -1
            if (open < 0 || close < 0) {
                // 标记后无合法 JSON：仍擦掉裸标记，绝不让 [promise] 泄露给用户。
                removeRanges.add(m.range)
                continue
            }
            removeRanges.add(m.range.first..close)
            val args = runCatching {
                lenientJson.decodeFromString(PromiseToolArgs.serializer(), response.substring(open, close + 1))
            }.getOrNull() ?: continue
            val toolName = when (args.action.trim().lowercase()) {
                ACTION_RECORD -> TOOL_RECORD
                ACTION_RESOLVE -> TOOL_RESOLVE
                else -> continue
            }
            actionFrom(toolName, args, zone)?.let { actions.add(it) }
        }
        var cleanText = response
        for (range in removeRanges.asReversed()) {
            if (range.last < cleanText.length) cleanText = cleanText.removeRange(range)
        }
        return cleanText.trim() to actions
    }

    /** 文本暗号降级规则（图纸 §3.3-A 逐字锁定·与 [parseMarkers] 格式强耦合）。 */
    const val FALLBACK_MARKER_RULE = """【约定记账】
你和对方在这段聊天里刚刚明确说定了一件具体的事（一方答应对方要做的事，或两人约好一起做的事），或者清单【我们的约定】里某一条在这段聊天里有了明确结果（做成了 / 不做了），请在这条回复的**最末尾**附一行标记（对方看不到，App 用它记账）：
新约定：[promise]{"action":"record","content":"一句话概括，第三人称，用两人的名字，不超过40字","due":"能确定具体日期就写 yyyy-MM-dd，否则留空","evidence":"这段聊天里说定这件事的那句原话，逐字抄"}
有结果：[promise]{"action":"resolve","no":清单里那条前面的编号,"status":"fulfilled 或 cancelled","evidence":"这段聊天里能证明的那句原话，逐字抄"}
规则：① 双方明确说定才算，「改天吧」「有空再说」不算；② 未来某天线下见面走【约定未来见面】的标记，不用这个；③ 发红包、转账、给多少钱不算约定；④ 「以后都要…」这类长期习惯不记，只记能做完的具体事；⑤ 清单里已有的事（哪怕措辞不同）不要再记；⑥ 拿不准就不附；⑦ 标记必须是合法 JSON，正常聊天内容照常写在前面，一件事一行标记，最多两行。"""

    // ── 内部 ──

    private const val ACTION_RECORD = "record"
    private const val ACTION_RESOLVE = "resolve"

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val MARKER_REGEX = Regex("""\[promise\]""")

    /** 工具参数 / 文本暗号 JSON → 动作（共用）。空壳（content 空 / no ≤ 0）→ null。 */
    private fun actionFrom(toolName: String, args: PromiseToolArgs, zone: ZoneId): PromiseToolAction? = when (toolName) {
        TOOL_RECORD -> {
            val content = args.content.trim()
            if (content.isEmpty()) null
            else PromiseToolAction.Record(content, PromiseReconciliation.parseDue(args.due, zone), args.evidence)
        }
        TOOL_RESOLVE ->
            if (args.no <= 0) null
            else PromiseToolAction.Resolve(args.no, args.status.trim().lowercase(), args.evidence)
        else -> null
    }

    /** ⚠️ 与 [FALLBACK_MARKER_RULE] 的 JSON 形状强耦合（`action` 仅暗号路用，工具路由工具名决定动作）。 */
    @Serializable
    private data class PromiseToolArgs(
        val action: String = "",
        val content: String = "",
        val due: String? = null,
        val no: Int = 0,
        val status: String = "",
        val evidence: String = "",
    )
}
