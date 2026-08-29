package com.situ.aichat.data.remote.llm.modelcatalog

import com.situ.aichat.data.model.APIModelOption
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.remote.llm.ApiConfigValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Model-catalog providers — faithful port of iOS `Services/ModelCatalog/`.
 * Each provider fetches the available models for one provider type (GET /models or native
 * variants). Stateless; OkHttp client + Json passed in (mirrors iOS passing the URLSession).
 */
/**
 * 每家一个实现，**一律实时拉取**（无任何写死的模型清单——用户 2026-08-28 拍板：
 * 当前这个 API 里有什么模型就拉什么）。拉不到就抛 [ModelCatalogException]，由上层如实报错。
 */
interface ModelCatalogProvider {
    suspend fun fetchModels(config: ApiConfigValues, client: OkHttpClient, json: Json): List<APIModelOption>
}

object ModelCatalogProviderFactory {
    fun make(providerType: ApiProviderType): ModelCatalogProvider = when (providerType) {
        ApiProviderType.ANTHROPIC -> AnthropicModelCatalogProvider()
        ApiProviderType.GEMINI -> GeminiModelCatalogProvider()
        ApiProviderType.DEEPSEEK -> DeepSeekModelCatalogProvider()
        ApiProviderType.OPENROUTER -> OpenRouterModelCatalogProvider()
        ApiProviderType.MINIMAX -> MiniMaxModelCatalogProvider()
        ApiProviderType.OPENAI_COMPATIBLE -> OpenAiCompatibleModelCatalogProvider()
    }
}

/** Injectable wrapper (provided in NetworkModule) so VMs can fetch without touching OkHttp directly. */
class ModelCatalogService(
    private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun fetchModels(config: ApiConfigValues): List<APIModelOption> =
        ModelCatalogProviderFactory.make(config.providerType).fetchModels(config, client, json)
}

/** User-facing catalog errors (messages mirror iOS APIModelCatalogError; shown in the picker status). */
sealed class ModelCatalogException(message: String) : Exception(message) {
    data object InvalidUrl : ModelCatalogException("模型列表接口地址无效") {
        private fun readResolve(): Any = InvalidUrl
    }
    data object MissingApiKey : ModelCatalogException("请先填写 API Key 再拉取模型列表") {
        private fun readResolve(): Any = MissingApiKey
    }
    data object InvalidResponse : ModelCatalogException("模型列表响应格式无法识别") {
        private fun readResolve(): Any = InvalidResponse
    }
    class HttpStatus(val code: Int, val body: String) : ModelCatalogException(
        body.trim().let { s ->
            if (s.isEmpty()) "拉取模型列表失败：HTTP $code" else "拉取模型列表失败：HTTP $code，${s.take(160)}"
        },
    )
}

/** Minimal authenticated GET that returns the body string, throwing [ModelCatalogException] on non-200. */
object ModelCatalogHttp {
    suspend fun get(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        timeoutSec: Long = 20,
    ): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).get()
        for ((k, v) in headers) builder.addHeader(k, v)
        val timed = client.newBuilder().callTimeout(timeoutSec, TimeUnit.SECONDS).build()
        timed.newCall(builder.build()).execute().use { resp ->
            val body = runCatching { resp.body.string() }.getOrNull().orEmpty()
            if (resp.code != 200) throw ModelCatalogException.HttpStatus(resp.code, body)
            body
        }
    }
}

/**
 * OpenAI 风格 `/v1/models` 端点（OpenAICompatible / DeepSeek / MiniMax 共用）。
 * 归一规则见 [ModelCatalogUrl]（全服务商单源）。
 */
internal fun openAiStyleModelsUrl(baseUrl: String): String =
    ModelCatalogUrl.modelsUrl(baseUrl, defaultVersion = listOf("v1"))

/**
 * 错误文案脱敏 —— 上屏前必经。服务端错误体会回显请求头（可能含 Authorization），Gemini 的 key 又拼在
 * query 里、OkHttp 异常消息常带完整 URL；两者叠加会把密钥渲染进用户可见提示。这里：
 * ① 整段抹掉出现的 apiKey；② 抹掉 URL 里的 key/token/api_key 等敏感 query 值；③ 抹掉 Bearer 串。
 * 原始异常全文只进 Logcat。
 */
internal fun sanitizeCatalogErrorMessage(raw: String?, apiKey: String): String {
    var s = raw?.trim().orEmpty()
    if (s.isEmpty()) return "拉取模型列表失败"
    val key = apiKey.trim()
    if (key.length >= 8) s = s.replace(key, REDACTED)
    s = SENSITIVE_QUERY.replace(s) { m -> "${m.groupValues[1]}=$REDACTED" }
    s = BEARER.replace(s, "Bearer $REDACTED")
    return s.take(200)
}

private const val REDACTED = "***"
private val SENSITIVE_QUERY = Regex("(?i)\\b(key|api[_-]?key|token|access[_-]?token)=([^&\\s\"']+)")
private val BEARER = Regex("(?i)Bearer\\s+[A-Za-z0-9._\\-]{8,}")

// Shared OpenAI-style /models response ({data:[{id, owned_by}]}) — used by
// OpenAICompatible / DeepSeek / MiniMax providers.
@Serializable
internal data class OpenAiModelsResponse(val data: List<OpenAiModelItem> = emptyList())

@Serializable
internal data class OpenAiModelItem(
    val id: String,
    @SerialName("owned_by") val ownedBy: String? = null,
)
