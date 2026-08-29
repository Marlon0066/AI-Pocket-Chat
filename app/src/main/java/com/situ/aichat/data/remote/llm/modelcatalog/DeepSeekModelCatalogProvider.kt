package com.situ.aichat.data.remote.llm.modelcatalog

import com.situ.aichat.data.model.APIModelOption
import com.situ.aichat.data.remote.llm.ApiConfigValues
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * DeepSeek model catalog —— 走官方 OpenAI 兼容的 `/v1/models`，**实时拉取，无写死清单**。
 *
 * （历史：这里原本在无 key / 拉取失败时退回一份硬编码模型表，是移植期从 iOS 照搬的拐杖。
 * 用户 2026-08-28 拍板「当前这个 API 里有什么模型就拉什么」——写死的表既会漏掉服务商新上的模型，
 * 又会在接中转站时列出对方根本不存在的 id，还让 Key/地址错误伪装成功。现与其余服务商一致：
 * 拉不到就如实报错，用户可手输模型名，这也是 RikkaHub / Cherry Studio / LobeChat 的一致做法。）
 */
class DeepSeekModelCatalogProvider : ModelCatalogProvider {
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
