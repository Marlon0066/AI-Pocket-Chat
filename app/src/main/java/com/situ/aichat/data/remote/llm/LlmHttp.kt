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

    /**
     * ⚠️ **与模型拉取路的规则目前不一致**（2026-08-29 登记）：`ModelCatalogUrl` 已升级为「识别任意版本段
     * `/v\d+(alpha|beta)?` + 末尾 `#` 逃生口 + 保留 query」，而本函数仍是旧三分支——`https://host/api/v3`
     * 这类中转在那边能打到 `/api/v3/models`，在这里却拼成 `/api/v3/v1/chat/completions` 而 404。
     * 跨四路归一须单独立项（本函数是全部 LLM 往返的唯一出口，改它要配 T5 + 存量地址穷举）。
     *
     * Normalize a base URL into a full chat/completions endpoint. Accepts:
     * 1) https://host  2) https://host/v1  3) https://host/.../chat/completions
     * Non-local http hosts are auto-upgraded to https (avoids leaking the Bearer key).
     */
    fun buildChatCompletionsUrl(baseUrl: String): String {
        var s = baseUrl.trim()
        if (s.isEmpty()) throw LlmError.InvalidUrl
        val uri = runCatching { URI(s) }.getOrNull() ?: throw LlmError.InvalidUrl
        val scheme = uri.scheme?.lowercase() ?: throw LlmError.InvalidUrl
        if (scheme != "http" && scheme != "https") throw LlmError.InvalidUrl
        if (scheme == "http" && shouldUpgradeInsecureHost(uri.host)) {
            s = "https://" + s.substringAfter("://")
        }
        val trimmed = s.trimEnd('/')
        val pathLower = (runCatching { URI(trimmed).path }.getOrNull() ?: "").lowercase()
        return when {
            pathLower.endsWith("/chat/completions") -> trimmed
            pathLower.endsWith("/v1") -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
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
