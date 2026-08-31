package com.situ.aichat.meeting

import com.situ.aichat.data.model.MeetingCandidate
import com.situ.aichat.data.model.MeetingCandidateIntent
import com.situ.aichat.data.model.MeetingConfidence
import com.situ.aichat.data.model.MeetingProposedBy
import com.situ.aichat.data.model.MeetingSource
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.util.JSONExtractor
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 未来约定见面 · 识别抽取扫描骨干（1:1 iOS `Services/MeetingDetectionService.swift`）。
 * 从最近对话识别「是否约定了未来见面」，产出候选（[MeetingCandidate]，**非真理源**）。
 *
 * 全部为纯函数 + 一个可注入 [completionFn] 的 [scanForCandidates]，便于单测、不依赖 Hilt：
 * - [scanTriggerDecision]：冷却判定（节奏纯函数）。
 * - [buildScanPrompt]：扫描提示词（参数注入，**与解析强耦合**——改 JSON 字段须同步改 [parseCandidates]）。
 * - [parseCandidates] / [validate]：宽容解析 + 过滤无效候选（纯函数）。
 * - 喂入「已有待定约定」让 LLM 返回 intent（new/reschedule/cancel/confirm/none），避免重复提同一约定。
 *
 * 注：本块只做**引擎**。触发接线 + 候选→时间解析→查重→确认卡在 Phase 6（候选入库）一起接，
 * 避免「扫到却无卡可确认」的半接线状态。
 */
object MeetingDetectionService {

    /** 扫描温度（低温保稳）。 */
    const val SCAN_TEMPERATURE = 0.2

    /** DeepSeek 偶发空响应时的重试间隔。 */
    private const val EMPTY_RETRY_DELAY_MS = 200L

    private val scanJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // ── 扫描（注入 completionFn：给定 messages + temperature，返回 LLM 原始文本） ──

    /**
     * 调一次 LLM（json_object）识别候选。空响应（DeepSeek 已知 bug）→ 200ms 后重试 1 次。
     * [completionFn] 由调用方（Phase 6 接线处）闭包 config / contextLog，本服务不依赖 LLM client。
     */
    suspend fun scanForCandidates(
        systemPrompt: String,
        completionFn: suspend (messages: List<ChatMessageDto>, temperature: Double) -> String,
    ): List<MeetingCandidate> {
        val messages = listOf(
            ChatMessageDto(role = "system", content = systemPrompt),
            ChatMessageDto(role = "user", content = "请判断最近对话里是否有未来见面的约定，按要求只输出 JSON。"),
        )
        repeat(2) { attempt ->
            val cleaned = stripThinking(completionFn(messages, SCAN_TEMPERATURE))
            if (cleaned.isNotEmpty()) {
                return validate(parseCandidates(cleaned))
            }
            if (attempt == 0) delay(EMPTY_RETRY_DELAY_MS)
        }
        return emptyList()
    }

    // ── 解析（宽容：容忍前后包裹文字 / ```json 围栏 / 缺字段） ──

    fun parseCandidates(text: String): List<MeetingCandidate> {
        val jsonStr = JSONExtractor.extract(text)
        val dto = runCatching { scanJson.decodeFromString(ScanResultDto.serializer(), jsonStr) }.getOrNull()
            ?: return emptyList()
        if (dto.hasMeeting == false) return emptyList()
        val intent = MeetingCandidateIntent.fromRaw(dto.intent)
        if (intent == MeetingCandidateIntent.NONE) return emptyList()
        return listOf(
            MeetingCandidate(
                intent = intent,
                targetAppointmentUuid = dto.targetId.ifEmpty { null },
                isoDateTime = dto.isoDatetime.ifEmpty { null },
                rawWhen = dto.rawWhen,
                proposedBy = MeetingProposedBy.fromRaw(dto.proposedBy),
                source = MeetingSource.EXTRACTION,
                location = dto.location,
                activity = dto.activity,
                invitationText = dto.invitation,
                tensionHint = dto.tensionHint,
                hiddenTensionSeed = dto.hiddenTension,
                confidence = MeetingConfidence.fromRaw(dto.confidence),
            ),
        )
    }

    // ── 校验：过滤无效候选（不按 confidence 丢——交确认闸门决定，最大化「不漏真约定」） ──

    fun validate(candidates: List<MeetingCandidate>): List<MeetingCandidate> =
        candidates.filter { c ->
            when (c.intent) {
                MeetingCandidateIntent.NONE -> false
                // cancel/confirm 必须指向已有约定
                MeetingCandidateIntent.CANCEL, MeetingCandidateIntent.CONFIRM -> c.targetAppointmentUuid != null
                // new/reschedule 至少要有「时间或内容」之一，否则是空壳
                MeetingCandidateIntent.NEW, MeetingCandidateIntent.RESCHEDULE -> {
                    val hasTime = !c.isoDateTime.isNullOrEmpty() || c.rawWhen.isNotEmpty()
                    val hasWhat = c.activity.isNotEmpty() || c.location.isNotEmpty()
                    hasTime || hasWhat
                }
            }
        }

    // ── 冷却判定（纯函数；in-memory 冷却状态由调用方持有） ──

    sealed interface ScanTriggerDecision {
        /** 触发扫描。 */
        object Trigger : ScanTriggerDecision

        /** 上次失败后短冷却中（剩余秒）。 */
        data class SkipFailureCooldown(val remainingSeconds: Long) : ScanTriggerDecision

        /** 轮数不够。 */
        object SkipBelowRounds : ScanTriggerDecision

        /** 成功冷却中（已过秒）。 */
        data class SkipCooldown(val elapsedSeconds: Long) : ScanTriggerDecision
    }

    /**
     * 触发判定。节奏：每 ≥[minRounds] 轮 + （距上次扫描 ≥[successCooldownSeconds] 或 已积 ≥[countTrack] 轮）；
     * 失败后 [failureCooldownSeconds] 短冷却。
     */
    fun scanTriggerDecision(
        roundsSinceLastScan: Int,
        lastScanMillis: Long?,
        lastFailureMillis: Long?,
        nowMillis: Long,
        minRounds: Int = 4,
        successCooldownSeconds: Long = 600,
        countTrack: Int = 12,
        failureCooldownSeconds: Long = 300,
    ): ScanTriggerDecision {
        if (lastFailureMillis != null) {
            val elapsed = (nowMillis - lastFailureMillis) / 1000
            if (elapsed < failureCooldownSeconds) {
                return ScanTriggerDecision.SkipFailureCooldown(failureCooldownSeconds - elapsed)
            }
        }
        if (roundsSinceLastScan < minRounds) return ScanTriggerDecision.SkipBelowRounds
        if (lastScanMillis == null) return ScanTriggerDecision.Trigger
        val elapsed = (nowMillis - lastScanMillis) / 1000
        return if (elapsed >= successCooldownSeconds || roundsSinceLastScan >= countTrack) {
            ScanTriggerDecision.Trigger
        } else {
            ScanTriggerDecision.SkipCooldown(elapsed)
        }
    }

    // ── 提示词（参数注入，不硬编码运行时数据；中文固定，与 parseCandidates 强耦合） ──

    /** 已有待定约定的精简描述（喂 LLM 判 intent，避免重复提同一约定）。 */
    data class ExistingAppointmentBrief(val uuid: String, val whenText: String, val activity: String)

    fun buildScanPrompt(
        conversationText: String,
        existing: List<ExistingAppointmentBrief>,
        characterName: String,
        userName: String,
        nowText: String,
        // 图纸 2026-08-31 C3：近期已赴约的约定（防幽灵——旧事重提不算新约定）。默认空 = 输出与既往**字节级一致**
        // （可选尾参范式·回归钉测试锁定）。刻意不给 id：终态不可作 reschedule/cancel/confirm 的 target。
        recentlyHonored: List<ExistingAppointmentBrief> = emptyList(),
    ): String {
        val charName = characterName.ifEmpty { "AI 角色" }
        val uName = userName.ifEmpty { "用户" }
        val existingBlock = if (existing.isEmpty()) {
            "（当前没有待定的约定）"
        } else {
            existing.joinToString("\n") { "- id=${it.uuid} | 时间：${it.whenText} | 活动：${it.activity}" }
        }
        // 非空时整块自带前导空行拼在待定块之后；空时为空串 → 模板输出零变化。
        val honoredBlock = if (recentlyHonored.isEmpty()) {
            ""
        } else {
            "\n\n【近期已赴约的见面】（下面这些约定**已经见过面、圆满结束**。最近对话里再聊到同一件事，" +
                "是在回味旧事而不是新约定——不要为它们输出 new；除此以外没有新约定就输出 {\"intent\":\"none\"}）\n" +
                recentlyHonored.joinToString("\n") { "- 时间：${it.whenText} | 活动：${it.activity}（已赴约）" }
        }
        return """
            你是一个严格的信息抽取器。判断 ${uName} 和 ${charName} 在最近这段对话里，是否**明确约定了未来某天**线下见面。

            当前时间：${nowText}

            【判定标准】
            - 只算**确定的约定**：双方都明确同意，或一方提议、另一方答应。
            - **排除客套寒暄**（如「改天约」「有空再说」「下次吧」）——这些不是约定。
            - **排除当下立刻见面**（那是另一套流程）。这里只处理**未来某天**的约定。
            - 也要识别对【已有待定约定】的：改期(reschedule)、取消(cancel)、确认(confirm)。

            【已有待定约定】
            ${existingBlock}${honoredBlock}

            【最近对话】
            ${conversationText}

            【输出】只输出一个 JSON 对象，不要任何额外文字或解释：
            {
              "intent": "new | reschedule | cancel | confirm | none",
              "target_id": "（reschedule/cancel/confirm 时填上面某条 id，否则空字符串）",
              "iso_datetime": "（依据当前时间推算的具体时间，ISO8601 带时区，如 2026-06-27T15:00:00+08:00；只到天则给当天 19:00）",
              "raw_when": "（对话里原话的时间说法，如 周六下午）",
              "location": "（地点，没有就空字符串）",
              "activity": "（一起做什么，没有就空字符串）",
              "invitation": "（${charName}口吻的一句邀约，可空）",
              "tension_hint": "（≤12字、给用户看的隐晦暗示，可空）",
              "hidden_tension": "（一句${charName}藏着的小心事，用户不可见，可空）",
              "proposed_by": "character | user",
              "confidence": "high | medium | low"
            }
            没有任何未来约定时，输出 {"intent":"none"}。
        """.trimIndent()
    }

    // ── 内部 ──

    /** 剥 DeepSeek <think> 思考标签后 trim（与记忆抽取一致；解析只认 JSON 部分）。 */
    private fun stripThinking(text: String): String =
        text.replace(Regex("(?is)<think>.*?</think>"), "")
            .replace(Regex("(?is)<thinking>.*?</thinking>"), "")
            .trim()

    @Serializable
    private data class ScanResultDto(
        val intent: String = "none",
        @SerialName("target_id") val targetId: String = "",
        @SerialName("iso_datetime") val isoDatetime: String = "",
        @SerialName("raw_when") val rawWhen: String = "",
        val location: String = "",
        val activity: String = "",
        val invitation: String = "",
        @SerialName("tension_hint") val tensionHint: String = "",
        @SerialName("hidden_tension") val hiddenTension: String = "",
        @SerialName("proposed_by") val proposedBy: String = "character",
        val confidence: String = "medium",
        @SerialName("has_meeting") val hasMeeting: Boolean? = null,
    )
}
