package com.situ.aichat.data.remote.llm.modelcatalog

import com.situ.aichat.data.model.APIModelOption
import com.situ.aichat.data.remote.llm.ApiConfigValues
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Anthropic model catalog — 原生 `/v1/models`。
 * 官方响应自带 `capabilities.image_input.supported`（视觉能力**权威元数据**，与 OpenRouter 的
 * `architecture.input_modalities` 并列为仅有的两家）——直接读它，省掉一次带图探针请求。
 */
class AnthropicModelCatalogProvider : ModelCatalogProvider {
    override suspend fun fetchModels(
        config: ApiConfigValues,
        client: OkHttpClient,
        json: Json,
    ): List<APIModelOption> {
        if (config.apiKey.trim().isEmpty()) throw ModelCatalogException.MissingApiKey
        val url = ModelCatalogUrl.modelsUrl(config.baseUrl, defaultVersion = listOf("v1"))
        val body = ModelCatalogHttp.get(
            client,
            url,
            mapOf("x-api-key" to config.apiKey, "anthropic-version" to "2023-06-01"),
        )
        val decoded = runCatching { json.decodeFromString(Response.serializer(), body) }
            .getOrNull() ?: throw ModelCatalogException.InvalidResponse
        return decoded.data.map {
            APIModelOption(
                id = it.id,
                name = it.displayName ?: it.id,
                subtitle = it.type,
                supportsVision = it.capabilities?.imageInput?.supported,
            )
        }
    }

    @Serializable
    private data class Response(val data: List<Item> = emptyList())

    @Serializable
    private data class Item(
        val id: String,
        @SerialName("display_name") val displayName: String? = null,
        val type: String? = null,
        val capabilities: Capabilities? = null,
    )

    @Serializable
    private data class Capabilities(
        @SerialName("image_input") val imageInput: Supported? = null,
    )

    @Serializable
    private data class Supported(val supported: Boolean? = null)
}
