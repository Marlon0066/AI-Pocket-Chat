package com.situ.aichat.data.remote.llm.modelcatalog

import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.remote.llm.ApiConfigValues
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 看门测试：**任何服务商都不得内置写死的模型清单**（用户 2026-08-28 拍板
 * 「当前这个 API 里有什么模型，就直接都拉取到让用户自己选」）。
 *
 * 用「无 API Key」这个不触网的入口验：从前 DeepSeek / MiniMax 在这里会**返回一份硬编码列表**
 * （移植期从 iOS 照搬的拐杖），其余四家则抛 MissingApiKey。现在六家行为一致——拿不到就如实报错，
 * 绝不无中生有地列出模型。这条一旦变红，说明有人又把兜底清单加回来了。
 */
class NoHardcodedModelListTest {

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private fun values(type: ApiProviderType) = ApiConfigValues(
        providerType = type,
        apiKey = "",
        baseUrl = type.defaultBaseUrl,
        modelName = "",
    )

    @Test
    fun `六家服务商在无 key 时一律报错而非返回内置清单`() = runTest {
        for (type in ApiProviderType.entries) {
            val provider = ModelCatalogProviderFactory.make(type)
            assertThrows(
                "服务商 $type 在无 key 时没有报错——它可能又内置了写死的模型清单",
                ModelCatalogException.MissingApiKey::class.java,
            ) {
                kotlinx.coroutines.runBlocking { provider.fetchModels(values(type), client, json) }
            }
        }
    }
}
