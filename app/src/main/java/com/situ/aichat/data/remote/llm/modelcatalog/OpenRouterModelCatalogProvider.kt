package com.situ.aichat.data.remote.llm.modelcatalog

import com.situ.aichat.data.model.APIModelOption
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.LlmHttp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * OpenRouter model catalog。
 * 官方响应的 `architecture.input_modalities` 含 `"image"` = **视觉能力权威元数据**（各家 models 接口里
 * 只有 OpenRouter 与 Anthropic 给出该信息）——读它即可免跑带图探针。
 */
class OpenRouterModelCatalogProvider : ModelCatalogProvider {
    override suspend fun fetchModels(
        config: ApiConfigValues,
        client: OkHttpClient,
        json: Json,
    ): List<APIModelOption> {
        if (config.apiKey.trim().isEmpty()) throw ModelCatalogException.MissingApiKey
        val url = ModelCatalogUrl.modelsUrl(config.baseUrl, defaultVersion = listOf("api", "v1"))
        val body = ModelCatalogHttp.get(client, url, LlmHttp.authHeaders(config))
        val decoded = runCatching { json.decodeFromString(Response.serializer(), body) }
            .getOrNull() ?: throw ModelCatalogException.InvalidResponse
        return decoded.data.map {
            APIModelOption(
                id = it.id,
                name = it.name ?: it.id,
                subtitle = it.subtitle(),
                supportedParameters = it.supportedParameters,
                supportsVision = it.visionFromModalities(),
            )
        }
    }

    @Serializable
    private data class Response(val data: List<Item> = emptyList())

    @Serializable
    private data class Item(
        val id: String,
        val name: String? = null,
        val description: String? = null,
        @SerialName("context_length") val contextLength: Int? = null,
        @SerialName("supported_parameters") val supportedParameters: List<String>? = null,
        val architecture: Architecture? = null,
    ) {
        /** null = 该条目没给模态信息（不下结论）；true/false = 权威判定。 */
        fun visionFromModalities(): Boolean? {
            val modalities = architecture?.inputModalities ?: return null
            if (modalities.isEmpty()) return null
            return modalities.any { it.equals("image", ignoreCase = true) }
        }

        fun subtitle(): String? {
            val parts = buildList {
                contextLength?.let { add("${formatContextLength(it)} ctx") }
                if (visionFromModalities() == true) add("视觉")
                if (supportsReasoningControl()) add("reasoning")
            }
            if (parts.isNotEmpty()) return parts.joinToString(" · ")
            return description?.takeIf { it.isNotEmpty() }
        }

        private fun supportsReasoningControl(): Boolean {
            val p = supportedParameters ?: return false
            return p.contains("reasoning") || p.contains("reasoning.max_tokens") || p.contains("reasoning.effort")
        }

        private fun formatContextLength(value: Int): String = when {
            value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0).replace(".0", "")
            value >= 1_000 -> "%.0fK".format(value / 1_000.0)
            else -> "$value"
        }
    }

    @Serializable
    private data class Architecture(
        @SerialName("input_modalities") val inputModalities: List<String>? = null,
    )
}
