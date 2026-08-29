package com.situ.aichat.data.repository

import com.situ.aichat.data.local.dao.ApiConfigDao
import com.situ.aichat.data.local.entity.ApiConfigEntity
import com.situ.aichat.security.ApiKeyStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [ApiConfigRepository.storedApiKey] 的 T2（api-key-prefill·MockK）：编辑屏预填走
 * uuid → 实体.apiKeyId → 加密库明文 的两跳链路；配置不存在 / 从未存过 key 都退空串
 * （编辑屏拿空串 = 不预填，行为同旧版）。
 */
class ApiConfigStoredKeyTest {

    private val dao: ApiConfigDao = mockk(relaxed = true)
    private val keyStore: ApiKeyStore = mockk(relaxed = true)
    private lateinit var repo: ApiConfigRepository

    private val entity = ApiConfigEntity(
        uuid = "cfg-1",
        providerName = "DeepSeek",
        apiKeyId = "key-id-1",
        baseURL = "https://api.deepseek.com",
        modelName = "deepseek-v4-flash",
        creationDate = 0L,
    )

    @Before
    fun setUp() {
        repo = ApiConfigRepository(
            dao = dao,
            keyStore = keyStore,
            capabilityDetector = mockk(relaxed = true),
            balanceService = mockk(relaxed = true),
            functionRouter = mockk(relaxed = true),
        )
    }

    @Test
    fun returnsPlaintextKeyViaApiKeyId() = runTest {
        coEvery { dao.getByUuid("cfg-1") } returns entity
        coEvery { keyStore.get("key-id-1") } returns "sk-secret"
        assertEquals("sk-secret", repo.storedApiKey("cfg-1"))
    }

    @Test
    fun missingConfigReturnsEmpty() = runTest {
        coEvery { dao.getByUuid("gone") } returns null
        assertEquals("", repo.storedApiKey("gone"))
    }

    @Test
    fun missingStoredKeyReturnsEmpty() = runTest {
        coEvery { dao.getByUuid("cfg-1") } returns entity
        coEvery { keyStore.get("key-id-1") } returns null
        assertEquals("", repo.storedApiKey("cfg-1"))
    }
}
