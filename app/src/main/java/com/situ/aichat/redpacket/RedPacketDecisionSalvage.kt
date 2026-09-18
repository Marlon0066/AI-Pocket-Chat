package com.situ.aichat.redpacket

import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.redpacket.RedPacketAcceptanceDecisionService.Companion.CHAT_REPLY_MAX_LEN
import com.situ.aichat.redpacket.RedPacketAcceptanceDecisionService.Companion.REJECTION_REASON_MAX_LEN
import com.situ.aichat.redpacket.RedPacketAcceptanceDecisionService.Decision
import com.situ.aichat.util.JSONExtractor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 红包收 / 拒决定「格式不合格」时怎么兜底（2026-09-18 提示词册核对 D 条）。
 *
 * 背景：[RedPacketAcceptanceDecisionService] 严格校验 LLM 回复，几次都不合格就走兜底「默认收下」。可模型最常见的
 * 不合格恰恰是「明确说了不收，只是漏了 rejectionReason / 口头回复里带了技术 id」——旧兜底把这类**明确的拒收**
 * 一律改判成收下，角色的拒收只要格式有一点毛病就永远不生效。
 *
 * 规则（纯函数·不碰 DB / 金额 / 台账）：看**最近一条**带明确表态（`shouldAccept` 是 JSON 布尔）的失败回复——
 * - 表态 = 拒收 → 照拒收执行，只保留合格字段（拒收理由 ≤[REJECTION_REASON_MAX_LEN]、口头回复 ≤[CHAT_REPLY_MAX_LEN]，
 *   空的 / 带技术 id 的字段整条丢）；
 * - 表态 = 收下，或根本没有明确表态（非 JSON、缺字段、布尔写成字符串、网络全失败）→ 返回 null，仍交
 *   [RedPacketAcceptanceDecisionService.fallbackDecision] 默认收下（旧行为逐字不变）。
 *
 * 两种结局都不吞钱：收下 = 进角色钱包；拒收 = 原路退回用户（[RedPacketService.rejectRedPacket]）。
 */
internal object RedPacketDecisionSalvage {

    /** 残缺拒收决定的内部理由（只进日志，绝不给用户看——见 decideAndApply 的拒收理由取值）。 */
    const val SALVAGE_REASON = "兜底 · 回复格式不合格但明确表态拒收,照拒收执行"

    /** 回复里的明确表态：`shouldAccept` 是 JSON 布尔才算（字符串 "false" 不算）；读不出 → null。 */
    fun explicitAcceptIntent(response: String): Boolean? = decisionJsonObject(response)?.boolField("shouldAccept")

    /**
     * 全部尝试都没过校验（或中途断网）时的残缺决定。[lastExplicitResponse] = 最近一条有明确表态的失败回复
     * （一条都没有 = null）。表态是拒收 → 照拒收（isFromFallback = true）；否则 null（交默认收下）。
     */
    fun salvagedRejection(lastExplicitResponse: String?): Decision? {
        val obj = lastExplicitResponse?.let(::decisionJsonObject) ?: return null
        if (obj.boolField("shouldAccept") != false) return null
        return Decision(
            shouldAccept = false,
            rejectionReason = obj.usableText("rejectionReason", REJECTION_REASON_MAX_LEN),
            reason = SALVAGE_REASON,
            isFromFallback = true,
            chatReply = obj.usableText("chatReply", CHAT_REPLY_MAX_LEN),
        )
    }

    /** 合格才留：去空白后非空、不含技术 id，超长截断（与 parseAndValidate 同口径）；否则 null。 */
    private fun JsonObject.usableText(key: String, maxLen: Int): String? {
        val raw = stringField(key)?.trim()
        if (raw.isNullOrEmpty() || containsTechId(raw)) return null
        return if (raw.length > maxLen) raw.take(maxLen) else raw
    }
}

// ── 与 [RedPacketAcceptanceDecisionService.parseAndValidate] 共用的解析口径（2026-09-18 从其 companion 搬出·只搬不改） ──

private val decisionJson = Json { ignoreUnknownKeys = true }

/** 剥思考标签 → 抽 JSON → 根对象；不是 JSON 对象 → null。 */
internal fun decisionJsonObject(response: String): JsonObject? {
    val cleaned = MemoryService.strippingThinkingTags(response)
    val jsonStr = JSONExtractor.extract(cleaned)
    return runCatching { decisionJson.parseToJsonElement(jsonStr) }.getOrNull() as? JsonObject
}

/** 回给用户的文字不得带技术 id 前缀（红包 / 礼物的内部 id）。 */
internal fun containsTechId(text: String): Boolean = text.contains("red_packet") || text.contains("gift_")

// JSON 字段读取（对齐 iOS `json[key] as? Type` 严格语义：布尔必须是 JSON 布尔、字符串必须是 JSON 字符串）
internal fun JsonObject.boolField(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

internal fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
