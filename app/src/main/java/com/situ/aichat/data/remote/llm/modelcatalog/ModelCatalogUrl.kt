package com.situ.aichat.data.remote.llm.modelcatalog

import com.situ.aichat.data.remote.llm.LlmHttp
import java.net.URI

/**
 * 模型列表端点 URL 归一 —— **全部服务商唯一实现**（原先 OpenAI 系 / Anthropic / Gemini / OpenRouter
 * 四处各写一份近似逻辑，行为互不对等：Anthropic 不认 `/v1/messages`、非 v1 版本段被重复追加、query 被丢弃）。
 *
 * 规则（采 Cherry Studio 现行三段式社区事实标准 + 本项目既有归一行为）：
 * 1. **末尾 `#` → 去 `#` 后原样使用**（中转站逃生口：路径非标准时用户自填完整地址）。
 * 2. 剥已知对话端点尾巴（`/chat/completions`、Anthropic `/messages`）与调用方指定的 [stripTail] 段。
 * 3. 末段已是 `models` → 保持（不再追加版本）。
 * 4. 末段是版本段（`v1` / `v2` / `v1beta` / `v1alpha`…）→ 直接追加 `models`。
 * 5. 否则 → 追加 [defaultVersion] + `models`。
 *
 * ✅ **聊天路已同规则**（2026-08-31 归一·图纸 docs/handoff/2026-08-31-跨四路LLM-URL归一.md）：
 * [LlmHttp.buildChatCompletionsUrl] 现在同样识别任意版本段、剥末尾 `#`、保留 query——
 * `https://host/api/v3` 这类中转两路都打得通（此前拉取路正常、聊天却 404）。
 * **版本段正则单源落户 [LlmHttp.VERSION_SEGMENT]**，本文件引用它，别再写第二份；
 * 两侧任一方改规则都必须先看另一侧。
 *
 * query / fragment 原样保留（旧实现整段丢弃，`https://host/v1?token=x` 这类中转会失效）；
 * 非内网 http 主机升 https（与聊天路径 [LlmHttp.buildChatCompletionsUrl] 同一纪律，旧拉取路径没有）。
 */
internal object ModelCatalogUrl {

    fun modelsUrl(
        baseUrl: String,
        defaultVersion: List<String>,
        stripTail: Set<String> = emptySet(),
    ): String {
        var raw = baseUrl.trim()
        if (raw.isEmpty()) throw ModelCatalogException.InvalidUrl
        // 规则 1：末尾 '#' = 用户声明「地址已完整，别再拼」。
        val literal = raw.endsWith("#")
        if (literal) raw = raw.trimEnd('#').trim()

        val uri = runCatching { URI(raw) }.getOrNull() ?: throw ModelCatalogException.InvalidUrl
        val rawScheme = uri.scheme?.lowercase() ?: throw ModelCatalogException.InvalidUrl
        if (rawScheme != "http" && rawScheme != "https") throw ModelCatalogException.InvalidUrl
        val authority = uri.authority ?: throw ModelCatalogException.InvalidUrl
        val scheme = if (rawScheme == "http" && LlmHttp.shouldUpgradeInsecureHost(uri.host)) "https" else rawScheme

        if (literal) {
            return rebuild(scheme, authority, uri.path.orEmpty().trimEnd('/'), uri.query, uri.fragment)
        }

        val segs = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }.toMutableList()
        stripChatEndpoint(segs)
        // 已是 models 结尾时先摘下，好让 stripTail（如 Gemini 的 /openai）作用于它前面的段。
        val hadModels = segs.lastOrNull()?.equals(MODELS, ignoreCase = true) == true
        if (hadModels) segs.removeAt(segs.lastIndex)
        while (segs.isNotEmpty() && segs.last().lowercase() in stripTail) segs.removeAt(segs.lastIndex)

        val lastIsVersion = segs.lastOrNull()?.let { LlmHttp.VERSION_SEGMENT.matches(it) } == true
        if (!lastIsVersion && !hadModels) segs.addAll(defaultVersion)
        segs.add(MODELS)

        return rebuild(scheme, authority, "/" + segs.joinToString("/"), uri.query, uri.fragment)
    }

    /** 剥对话端点尾巴，使 `.../v1/chat/completions`、`.../v1/messages` 都能归一到同级 models。 */
    private fun stripChatEndpoint(segs: MutableList<String>) {
        val last = segs.lastOrNull()?.lowercase() ?: return
        val prev = segs.getOrNull(segs.lastIndex - 1)?.lowercase()
        when {
            last == "completions" && prev == "chat" -> repeat(2) { segs.removeAt(segs.lastIndex) }
            last == "messages" -> segs.removeAt(segs.lastIndex) // Anthropic 原生对话端点
            last == "responses" -> segs.removeAt(segs.lastIndex) // OpenAI Responses API
        }
    }

    private fun rebuild(scheme: String, authority: String, path: String, query: String?, fragment: String?): String {
        val q = if (query.isNullOrEmpty()) "" else "?$query"
        val f = if (fragment.isNullOrEmpty()) "" else "#$fragment"
        return "$scheme://$authority$path$q$f"
    }

    private const val MODELS = "models"
}
