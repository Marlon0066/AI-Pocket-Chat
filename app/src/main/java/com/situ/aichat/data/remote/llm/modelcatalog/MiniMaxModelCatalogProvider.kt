package com.situ.aichat.data.remote.llm.modelcatalog

import com.situ.aichat.data.model.APIModelOption
import com.situ.aichat.data.remote.llm.ApiConfigValues
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * MiniMax model catalog —— 走 `/v1/models`，**实时拉取，无写死清单**
 * （理由与 [DeepSeekModelCatalogProvider] 同：用户 2026-08-28 拍板全部实时拉取）。
 */
class MiniMaxModelCatalogProvider : ModelCatalogProvider {
    override suspend fun fetchModels(
        config: ApiConfigValues,
        client: OkHttpClient,
        json: Json,
    ): List<APIModelOption> {
        if (config.apiKey.trim().isEmpty()) throw ModelCatalogException.MissingApiKey
        val url = openAiStyleModelsUrl(config.baseUrl)
        val body = ModelCatalogHttp.get(client, url, mapOf("Authorization" to "Bearer ${config.apiKey.trim()}"))
        val decoded = runCatching { json.decodeFromString(OpenAiModelsResponse.serializer(), body) }
            .getOrNull() ?: throw ModelCatalogException.InvalidResponse
        return decoded.data.map { APIModelOption(id = it.id, name = it.id, subtitle = it.ownedBy) }
    }
}
