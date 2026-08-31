package com.situ.aichat.data.remote.llm

import android.util.Log
import com.situ.aichat.data.model.ApiProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

private const val TAG = "ApiBalanceService"

/**
 * Account-balance query result — faithful port of iOS `APIBalanceResult`.
 * Only DeepSeek (CNY total) and OpenRouter (usage/limit) expose balance; others are [Unsupported].
 */
sealed interface ApiBalanceResult {
    data class DeepSeek(val totalBalance: Double) : ApiBalanceResult
    data class OpenRouter(val usage: Double, val limit: Double?, val limitRemaining: Double?) : ApiBalanceResult
    data object Unsupported : ApiBalanceResult
    data object Failed : ApiBalanceResult
}

/**
 * Queries an API provider's account balance — faithful port of iOS `APIBalanceService`.
 * - DeepSeek: `GET {origin}/user/balance` → prefers the CNY entry's total_balance.
 * - OpenRouter: tries `/api/v1/credits` (Management Key), falls back to `/api/v1/key` on 403.
 */
class ApiBalanceService(
    private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun fetchBalance(
        providerType: ApiProviderType,
        baseUrl: String,
        apiKey: String,
    ): ApiBalanceResult = when (providerType) {
        ApiProviderType.DEEPSEEK -> fetchDeepSeek(baseUrl, apiKey)
        ApiProviderType.OPENROUTER -> fetchOpenRouter(baseUrl, apiKey)
        ApiProviderType.ANTHROPIC, ApiProviderType.GEMINI,
        ApiProviderType.OPENAI_COMPATIBLE, ApiProviderType.MINIMAX,
        -> ApiBalanceResult.Unsupported
    }

    // MARK: - DeepSeek

    private suspend fun fetchDeepSeek(baseUrl: String, apiKey: String): ApiBalanceResult {
        val origin = extractOrigin(baseUrl) ?: run {
            Log.w(TAG, "余额查询失败·bad-origin provider=DEEPSEEK code=-")
            return ApiBalanceResult.Failed
        }
        val (code, body) = get(
            "$origin/user/balance",
            mapOf("Accept" to "application/json", "Authorization" to "Bearer $apiKey"),
        )
        if (code != 200 || body == null) {
            Log.w(TAG, "余额查询失败·non-200 provider=DEEPSEEK code=$code")
            return ApiBalanceResult.Failed
        }
        val decoded = runCatching { json.decodeFromString(DeepSeekBalanceResponse.serializer(), body) }
            .getOrNull() ?: run {
                Log.w(TAG, "余额查询失败·decode provider=DEEPSEEK code=$code")
                return ApiBalanceResult.Failed
            }
        val info = decoded.balanceInfos.firstOrNull { it.currency == "CNY" } ?: decoded.balanceInfos.firstOrNull()
        val total = info?.totalBalance?.toDoubleOrNull() ?: run {
            Log.w(TAG, "余额查询失败·decode provider=DEEPSEEK code=$code")
            return ApiBalanceResult.Failed
        }
        return ApiBalanceResult.DeepSeek(total)
    }

    // MARK: - OpenRouter

    private suspend fun fetchOpenRouter(baseUrl: String, apiKey: String): ApiBalanceResult {
        val origin = extractOrigin(baseUrl) ?: run {
            Log.w(TAG, "余额查询失败·bad-origin provider=OPENROUTER code=-")
            return ApiBalanceResult.Failed
        }
        // Prefer the precise account credits (Management Key); fall back to per-key info on 403.
        fetchOpenRouterCredits(origin, apiKey)?.let { return it }
        return fetchOpenRouterKeyInfo(origin, apiKey)
    }

    /** `/api/v1/credits` (Management Key). 200 → result; 403/other/parse-fail → null to trigger fallback. */
    private suspend fun fetchOpenRouterCredits(origin: String, apiKey: String): ApiBalanceResult? {
        val (code, body) = get("$origin/api/v1/credits", mapOf("Authorization" to "Bearer $apiKey"))
        if (code == 403 || code != 200 || body == null) return null
        val decoded = runCatching { json.decodeFromString(OpenRouterCreditsResponse.serializer(), body) }
            .getOrNull() ?: return null
        val remaining = decoded.data.totalCredits - decoded.data.totalUsage
        return ApiBalanceResult.OpenRouter(
            usage = decoded.data.totalUsage,
            limit = decoded.data.totalCredits,
            limitRemaining = remaining,
        )
    }

    /** `/api/v1/key` (ordinary key). */
    private suspend fun fetchOpenRouterKeyInfo(origin: String, apiKey: String): ApiBalanceResult {
        val (code, body) = get("$origin/api/v1/key", mapOf("Authorization" to "Bearer $apiKey"))
        if (code != 200 || body == null) {
            Log.w(TAG, "余额查询失败·non-200 provider=OPENROUTER code=$code")
            return ApiBalanceResult.Failed
        }
        val decoded = runCatching { json.decodeFromString(OpenRouterKeyResponse.serializer(), body) }
            .getOrNull() ?: run {
                Log.w(TAG, "余额查询失败·decode provider=OPENROUTER code=$code")
                return ApiBalanceResult.Failed
            }
        return ApiBalanceResult.OpenRouter(
            usage = decoded.data.usage,
            limit = decoded.data.limit,
            limitRemaining = decoded.data.limitRemaining,
        )
    }

    // MARK: - Helpers

    /** scheme + authority only (drops path/query)；公网 http 升 https（Bearer key 纪律，与聊天路同源白名单）。 */
    internal fun extractOrigin(baseUrl: String): String? {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return null
        val rawScheme = uri.scheme?.lowercase() ?: return null
        val authority = uri.authority ?: return null
        val scheme = if (rawScheme == "http" && LlmHttp.shouldUpgradeInsecureHost(uri.host)) "https" else rawScheme
        return "$scheme://$authority"
    }

    private suspend fun get(url: String, headers: Map<String, String>): Pair<Int, String?> =
        withContext(Dispatchers.IO) {
            val timed = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
            // 请求构造也在 runCatching 内：key 混入非 ASCII 字符（如粘贴带进中文/全角）时 addHeader 抛
            // IllegalArgumentException，此前在 catch 外 = 未捕获闪退（调用链 refreshBalances 无兜底）。
            runCatching {
                val builder = Request.Builder().url(url).get()
                for ((k, v) in headers) builder.addHeader(k, v)
                timed.newCall(builder.build()).execute().use { it.code to it.body.string() }
            }.getOrElse { -1 to null }
        }

    // MARK: - Response DTOs

    @Serializable
    private data class DeepSeekBalanceResponse(
        @SerialName("balance_infos") val balanceInfos: List<BalanceInfo> = emptyList(),
    ) {
        @Serializable
        data class BalanceInfo(
            val currency: String? = null,
            @SerialName("total_balance") val totalBalance: String? = null,
        )
    }

    @Serializable
    private data class OpenRouterCreditsResponse(val data: CreditsData) {
        @Serializable
        data class CreditsData(
            @SerialName("total_credits") val totalCredits: Double,
            @SerialName("total_usage") val totalUsage: Double,
        )
    }

    @Serializable
    private data class OpenRouterKeyResponse(val data: KeyData) {
        @Serializable
        data class KeyData(
            val usage: Double,
            val limit: Double? = null,
            @SerialName("limit_remaining") val limitRemaining: Double? = null,
        )
    }
}
