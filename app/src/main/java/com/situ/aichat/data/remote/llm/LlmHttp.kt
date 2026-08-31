package com.situ.aichat.data.remote.llm

import com.situ.aichat.data.model.ApiProviderType
import java.net.URI

/**
 * Shared OpenAI-compatible HTTP helpers — mirrors the static `LLMService.buildURL` /
 * `LLMService.requestHeaders` in iOS. Used by both [LlmClient] (chat) and
 * [CapabilityDetector] (capability probes) so URL normalization and auth headers stay
 * identical across both paths.
 */
object LlmHttp {

    /** 版本段：v1 / v2 / v1beta / v1alpha…（大小写不敏感）。与模型拉取路同根单源
     *（[com.situ.aichat.data.remote.llm.modelcatalog.ModelCatalogUrl] 引用本值，别再写第二份）。 */
    internal val VERSION_SEGMENT = Regex("^v\\d+(?:alpha|beta)?$", RegexOption.IGNORE_CASE)

    /**
     * 把 baseUrl 归一成完整的 chat/completions 端点 —— **全部 LLM 往返（聊天 / 能力探针 / 工具探测）
     * 的唯一出口**。
     *
     * 规则（与模型拉取路 [com.situ.aichat.data.remote.llm.modelcatalog.ModelCatalogUrl] **同根规则**·
     * 2026-08-31 归一，图纸 docs/handoff/2026-08-31-跨四路LLM-URL归一.md）：
     * 1. 末尾 `#` → **剥掉后照常规则拼**（`#` 是拉取路的「地址已完整」逃生口，对聊天路无此语义：
     *    同一份 baseUrl 不可能同时原样充当 models 端点与 chat 端点）。
     * 2. 末两段已是 `chat/completions` → 原样使用。
     * 3. 否则先剥「另一路端点」的末段尾巴（`models` / `messages` / `responses`，只剥末段、只剥一次），
     *    让误填的完整地址自愈回基座。
     * 4. 末段是版本段（[VERSION_SEGMENT]：`v1` / `v2` / `v1beta`…）或 Gemini 兼容层惯例段 `openai`
     *    （`…/v1beta/openai`）→ 追加 `chat/completions`；否则追加 `v1/chat/completions`。
     * 5. query 归位到路径之后并保留、fragment 丢弃（HTTP 本就不发送 fragment）；非内网 http 主机
     *    升 https（Bearer key 绝不明文发往公网）。
     */
    fun buildChatCompletionsUrl(baseUrl: String): String {
        var s = baseUrl.trim()
        if (s.isEmpty()) throw LlmError.InvalidUrl
        // 末尾 '#' 是模型拉取路的「地址已完整」逃生口；聊天路的语义 = 剥掉后照常规则拼——
        // 同一份 baseUrl 不可能同时原样充当 models 端点与 chat 端点。
        s = s.trimEnd('#').trim()
        if (s.isEmpty()) throw LlmError.InvalidUrl
        val uri = runCatching { URI(s) }.getOrNull() ?: throw LlmError.InvalidUrl
        val rawScheme = uri.scheme?.lowercase() ?: throw LlmError.InvalidUrl
        if (rawScheme != "http" && rawScheme != "https") throw LlmError.InvalidUrl
        val authority = uri.authority ?: throw LlmError.InvalidUrl
        val scheme = if (rawScheme == "http" && shouldUpgradeInsecureHost(uri.host)) "https" else rawScheme

        val segs = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }.toMutableList()
        val lastTwoAreChat = segs.size >= 2 &&
            segs[segs.lastIndex - 1].equals("chat", ignoreCase = true) &&
            segs.last().equals("completions", ignoreCase = true)
        if (!lastTwoAreChat) {
            // 对称自愈：把「另一路端点的完整地址」剥回基座（只剥末段、只剥一次）。
            when (segs.lastOrNull()?.lowercase()) {
                "models", "messages", "responses" -> segs.removeAt(segs.lastIndex)
            }
            val last = segs.lastOrNull()?.lowercase()
            when {
                last != null && VERSION_SEGMENT.matches(last) -> segs.addAll(listOf("chat", "completions"))
                // Gemini 兼容层惯例段（…/v1beta/openai）：末段非版本段但已是端点基座。
                last == "openai" -> segs.addAll(listOf("chat", "completions"))
                else -> segs.addAll(listOf("v1", "chat", "completions"))
            }
        }
        // query 归位到路径之后（旧实现把后缀拼进 query/fragment 尾巴是畸形）；fragment 本就不上线，丢弃。
        val q = uri.query?.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        return "$scheme://$authority/${segs.joinToString("/")}$q"
    }

    /** Bearer auth + OpenRouter's X-Title (mirrors iOS requestHeaders). */
    fun authHeaders(config: ApiConfigValues): Map<String, String> = buildMap {
        put("Authorization", "Bearer ${config.apiKey}")
        if (config.providerType == ApiProviderType.OPENROUTER) put("X-Title", "AIChat")
    }

    /** Keep http (no https upgrade) only for localhost / private LAN / unique-local IPv6. */
    fun shouldUpgradeInsecureHost(host: String?): Boolean {
        val h = host?.lowercase()
        if (h.isNullOrEmpty()) return true
        if (h == "localhost" || h == "::1" || h.endsWith(".local")) return false
        val octets = h.split(".")
        if (octets.size == 4) {
            val first = octets[0].toIntOrNull()
            val second = octets[1].toIntOrNull()
            if (first != null && second != null) {
                if (first == 127 || first == 10 || (first == 192 && second == 168)) return false
                if (first == 172 && second in 16..31) return false
            }
        }
        if (h.startsWith("fc") || h.startsWith("fd")) return false
        return true
    }
}
