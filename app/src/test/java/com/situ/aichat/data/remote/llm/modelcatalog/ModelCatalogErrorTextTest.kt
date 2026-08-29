package com.situ.aichat.data.remote.llm.modelcatalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [sanitizeCatalogErrorMessage] —— 错误文案上屏前脱敏。
 * 背景：Gemini 把 key 拼在 URL query 里、OkHttp 异常消息常带完整 URL、部分网关的错误体会回显
 * 请求头（含 Authorization），而这些消息旧实现原样渲染到下拉菜单里。
 */
class ModelCatalogErrorTextTest {

    private val key = "sk-abcdef1234567890"

    @Test
    fun `消息里出现的 apiKey 被抹掉`() {
        val out = sanitizeCatalogErrorMessage("invalid key: $key", key)
        assertFalse(out.contains(key))
        assertTrue(out.contains("***"))
    }

    @Test
    fun `URL 里的 key query 被抹掉`() {
        val out = sanitizeCatalogErrorMessage(
            "failed GET https://generativelanguage.googleapis.com/v1beta/models?key=AIzaSyTOPSECRET123",
            apiKey = "",
        )
        assertFalse(out.contains("AIzaSyTOPSECRET123"))
        assertTrue(out.contains("key=***"))
    }

    @Test
    fun `token 与 api_key 参数同样抹掉`() {
        val out = sanitizeCatalogErrorMessage("x?token=abcdefgh12345&api_key=zzzzzzzzzz", apiKey = "")
        assertFalse(out.contains("abcdefgh12345"))
        assertFalse(out.contains("zzzzzzzzzz"))
    }

    @Test
    fun `回显的 Bearer 串被抹掉`() {
        val out = sanitizeCatalogErrorMessage("HTTP 401 {\"headers\":{\"authorization\":\"Bearer sk-9f8e7d6c5b4a\"}}", apiKey = "")
        assertFalse(out.contains("sk-9f8e7d6c5b4a"))
        assertTrue(out.contains("Bearer ***"))
    }

    @Test
    fun `普通错误原样保留`() {
        assertEquals("拉取模型列表失败：HTTP 404", sanitizeCatalogErrorMessage("拉取模型列表失败：HTTP 404", key))
    }

    @Test
    fun `空消息给出兜底文案`() {
        assertEquals("拉取模型列表失败", sanitizeCatalogErrorMessage(null, key))
        assertEquals("拉取模型列表失败", sanitizeCatalogErrorMessage("   ", key))
    }

    @Test
    fun `过短的 key 不参与替换以免误伤正文`() {
        // 8 位以下的「key」多半是用户随手填的占位，全局替换会把正文里的普通词也打码
        val out = sanitizeCatalogErrorMessage("model abc not found", apiKey = "abc")
        assertEquals("model abc not found", out)
    }

    @Test
    fun `超长消息截断`() {
        val out = sanitizeCatalogErrorMessage("x".repeat(500), key)
        assertEquals(200, out.length)
    }
}
