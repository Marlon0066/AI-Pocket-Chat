package com.situ.aichat.data.remote.llm

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 探针请求的 `max_tokens` **参数方言**：OpenAI 推理系（gpt-5.x / o1 / o3 / o4 系）在 chat completions
 * 上拒收 `max_tokens`，报 400「Unsupported parameter: 'max_tokens' is not supported with this model.
 * Use 'max_completion_tokens' instead.」——探针若不认这一句，会把「参数名不对」误读成「模型不支持
 * 视觉 / 音频 / 工具」并永久写进检测结果（用户可见后果 = 发图按钮该出现却不出现）。
 *
 * 策略「点名才重试」（不直接换参数名）：老参数名 `max_tokens` 兼容面最大（大量中转 / 自建服务不识新名），
 * 只有 400 报文点名时才用同值的 `max_completion_tokens` 重试**恰一次**。与「max_tokens 撞顶自愈」
 * （`LlmClient` 400 点名才动）同一哲学，且谓词故意与撞顶场景区分：撞顶报文（"max_tokens is too large"）
 * 不含 unsupported / max_completion_tokens 字样 → 不触发本重试，两者互不打架。
 *
 * 职责边界：本对象只管「参数名方言」，**不做退避重试**（那是
 * [com.situ.aichat.data.remote.llm.tooldetection.ToolDetectionHttp.requestWithRetry] 的活），两层互不认识。
 * 只用于**探针**路；聊天 / 故事 / 通知等非探针请求一律不接（见图纸 2026-08-31 §9⑤）。
 */
internal object ProbeMaxTokensDialect {

    private const val KEY_MAX_TOKENS = "max_tokens"
    private const val KEY_MAX_COMPLETION_TOKENS = "max_completion_tokens"

    /**
     * 400 且报文点名参数方言才为真。422 / 200 / 无报文一律 false；
     * 撞顶类 400（"max_tokens is too large"）不含 unsupported / max_completion_tokens 关键词 → false。
     */
    fun isParamRejection(statusCode: Int, body: String?): Boolean {
        if (statusCode != 400 || body == null) return false
        return body.contains(KEY_MAX_COMPLETION_TOKENS, ignoreCase = true) ||
            (body.contains(KEY_MAX_TOKENS, ignoreCase = true) && body.contains("unsupported", ignoreCase = true))
    }

    /** 无 `max_tokens` 键则原样返回；有则去掉它、以**同值**加 `max_completion_tokens`，其余键原样。 */
    fun swapParam(body: JsonObject): JsonObject {
        val value = body[KEY_MAX_TOKENS] ?: return body
        val swapped = LinkedHashMap<String, JsonElement>(body.size)
        for ((k, v) in body) {
            if (k == KEY_MAX_TOKENS) continue
            swapped[k] = v
        }
        swapped[KEY_MAX_COMPLETION_TOKENS] = value
        return JsonObject(swapped)
    }

    /**
     * 发一次；命中 [isParamRejection] 则以 [swapParam] 后的报文**恰重发一次**并返回第二次结果
     * （不三试——重试后仍 400 说明模型真不支持这项能力）。
     *
     * lambda 注入（[statusOf] / [bodyTextOf] / [send]）便于单测，且让本对象与具体 HTTP 出口解耦：
     * `CapabilityDetector.post` 与 `ToolDetectionHttp.jsonRequest` 两种响应类型都能用同一份编排。
     */
    suspend fun <R> postWithFallback(
        body: JsonObject,
        statusOf: (R) -> Int,
        bodyTextOf: (R) -> String?,
        send: suspend (JsonObject) -> R,
    ): R {
        val first = send(body)
        if (!isParamRejection(statusOf(first), bodyTextOf(first))) return first
        return send(swapParam(body))
    }
}
