package com.situ.aichat.data.remote.llm.modelcatalog

import com.situ.aichat.data.model.APIModelOption
import com.situ.aichat.data.remote.llm.ApiConfigValues
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.net.URI
import java.net.URLEncoder

/**
 * Gemini model catalog — faithful port of iOS GeminiModelCatalogProvider.
 * Uses the native `/v1beta/models?key=` endpoint (not the OpenAI-compat layer); keeps only models
 * that support `generateContent` and strips the `models/` id prefix.
 */
class GeminiModelCatalogProvider : ModelCatalogProvider {
    override suspend fun fetchModels(
        config: ApiConfigValues,
        client: OkHttpClient,
        json: Json,
    ): List<APIModelOption> {
        if (config.apiKey.trim().isEmpty()) throw ModelCatalogException.MissingApiKey
        val url = buildModelsUrl(config.baseUrl, config.apiKey)
        val body = ModelCatalogHttp.get(client, url, emptyMap())
        val decoded = runCatching { json.decodeFromString(Response.serializer(), body) }
            .getOrNull() ?: throw ModelCatalogException.InvalidResponse
        return decoded.models
            .filter { it.supportedGenerationMethods.contains("generateContent") }
            .map {
                val id = it.name.replace("models/", "")
                APIModelOption(id = id, name = it.displayName ?: id, subtitle = it.description)
            }
    }

    /**
     * 归一到原生 `/v1beta/models` 后挂上 `?key=`。路径归一走单源 [ModelCatalogUrl]；
     * `stripTail=openai` 复刻既有纪律：OpenAI 兼容层只认 Bearer，原生路径用 query key，故剥掉该段。
     */
    private fun buildModelsUrl(baseUrl: String, apiKey: String): String {
        val normalized = ModelCatalogUrl.modelsUrl(
            baseUrl,
            defaultVersion = listOf("v1beta"),
            stripTail = setOf("openai"),
        )
        val uri = runCatching { URI(normalized) }.getOrNull() ?: throw ModelCatalogException.InvalidUrl
        val keptQuery = (uri.query ?: "").split("&").filter { it.isNotEmpty() && !it.startsWith("key=") }
        val query = (keptQuery + "key=${URLEncoder.encode(apiKey, "UTF-8")}").joinToString("&")
        return "${uri.scheme}://${uri.authority}${uri.path.orEmpty()}?$query"
    }

    @Serializable
    private data class Response(val models: List<Item> = emptyList())

    @Serializable
    private data class Item(
        val name: String,
        val displayName: String? = null,
        val description: String? = null,
        val supportedGenerationMethods: List<String> = emptyList(),
    )
}
