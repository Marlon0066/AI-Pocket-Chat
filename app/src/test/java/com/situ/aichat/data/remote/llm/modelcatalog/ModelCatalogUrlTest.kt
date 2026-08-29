package com.situ.aichat.data.remote.llm.modelcatalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 模型列表 URL 归一单源 [ModelCatalogUrl] 的行为锁定。
 *
 * 断言分两类：
 * ① **回归表**——存量用户已保存的 baseUrl（裸域名 / `…/v1` / `…/v1/chat/completions` / `…/v1/models`）
 *    产出必须与归一前的四份旧实现字节一致，否则会把别人能用的配置改坏；
 * ② **修复表**——旧实现的确切 bug（Anthropic 不认 `/v1/messages`、非 v1 版本段被重复追加、query 丢失、
 *    明文 http 不升级），逐条钉死新行为。
 */
class ModelCatalogUrlTest {

    private val v1 = listOf("v1")

    // ---------- ① 回归：OpenAI 系（含 DeepSeek / MiniMax 共用的 openAiStyleModelsUrl） ----------

    @Test
    fun `裸域名补 v1 models`() {
        assertEquals("https://api.openai.com/v1/models", ModelCatalogUrl.modelsUrl("https://api.openai.com", v1))
    }

    @Test
    fun `已带 v1 只补 models`() {
        assertEquals("https://api.openai.com/v1/models", ModelCatalogUrl.modelsUrl("https://api.openai.com/v1", v1))
    }

    @Test
    fun `末尾斜杠不影响`() {
        assertEquals("https://api.openai.com/v1/models", ModelCatalogUrl.modelsUrl("https://api.openai.com/v1/", v1))
    }

    @Test
    fun `已是 models 端点保持不变`() {
        assertEquals("https://h.com/v1/models", ModelCatalogUrl.modelsUrl("https://h.com/v1/models", v1))
    }

    @Test
    fun `chat completions 端点归一到同级 models`() {
        assertEquals(
            "https://h.com/v1/models",
            ModelCatalogUrl.modelsUrl("https://h.com/v1/chat/completions", v1),
        )
    }

    @Test
    fun `中转站子路径补版本段`() {
        assertEquals("https://h.com/proxy/v1/models", ModelCatalogUrl.modelsUrl("https://h.com/proxy", v1))
    }

    // ---------- ① 回归：OpenRouter（默认版本段 api-v1） ----------

    @Test
    fun `openrouter 裸域名补 api v1 models`() {
        val r = ModelCatalogUrl.modelsUrl("https://openrouter.ai", listOf("api", "v1"))
        assertEquals("https://openrouter.ai/api/v1/models", r)
    }

    @Test
    fun `openrouter 已带 api v1`() {
        val r = ModelCatalogUrl.modelsUrl("https://openrouter.ai/api/v1", listOf("api", "v1"))
        assertEquals("https://openrouter.ai/api/v1/models", r)
    }

    // ---------- ① 回归：Gemini（默认 v1beta·剥 /openai 段） ----------

    @Test
    fun `gemini 裸域名补 v1beta models`() {
        val r = ModelCatalogUrl.modelsUrl(
            "https://generativelanguage.googleapis.com",
            listOf("v1beta"),
            stripTail = setOf("openai"),
        )
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models", r)
    }

    @Test
    fun `gemini 的 openai 兼容层被剥掉走原生路径`() {
        val r = ModelCatalogUrl.modelsUrl("https://h.com/v1beta/openai", listOf("v1beta"), setOf("openai"))
        assertEquals("https://h.com/v1beta/models", r)
    }

    @Test
    fun `gemini openai chat completions 也归一到原生 models`() {
        val r = ModelCatalogUrl.modelsUrl("https://h.com/v1beta/openai/chat/completions", listOf("v1beta"), setOf("openai"))
        assertEquals("https://h.com/v1beta/models", r)
    }

    // ---------- ② 修复：Anthropic 的 /v1/messages ----------

    @Test
    fun `anthropic messages 端点归一到 models 而非叠加`() {
        // 旧实现：https://api.anthropic.com/v1/messages/v1/models（拼出不存在的路径）
        assertEquals(
            "https://api.anthropic.com/v1/models",
            ModelCatalogUrl.modelsUrl("https://api.anthropic.com/v1/messages", v1),
        )
    }

    // ---------- ② 修复：非 v1 版本段不再被重复追加 ----------

    @Test
    fun `v2 版本段直接补 models 不再叠 v1`() {
        // 旧实现：https://h.com/v2/v1/models
        assertEquals("https://h.com/v2/models", ModelCatalogUrl.modelsUrl("https://h.com/v2", v1))
    }

    @Test
    fun `v1beta 版本段在 openai 系下也识别`() {
        assertEquals("https://h.com/v1beta/models", ModelCatalogUrl.modelsUrl("https://h.com/v1beta", v1))
    }

    // ---------- ② 修复：query 不再被丢弃 ----------

    @Test
    fun `query 参数保留`() {
        // 旧实现只取 scheme+authority+path，token 会整个丢掉 → 中转站鉴权失败
        assertEquals("https://h.com/v1/models?token=abc", ModelCatalogUrl.modelsUrl("https://h.com/v1?token=abc", v1))
    }

    // ---------- ② 新增：# 结尾 = 原样使用（Cherry Studio 社区约定） ----------

    @Test
    fun `井号结尾原样使用不追加路径`() {
        assertEquals("https://h.com/custom/path", ModelCatalogUrl.modelsUrl("https://h.com/custom/path#", v1))
    }

    @Test
    fun `井号结尾也保留 query`() {
        assertEquals("https://h.com/m?k=1", ModelCatalogUrl.modelsUrl("https://h.com/m?k=1#", v1))
    }

    // ---------- ② 修复：http 升 https（内网豁免） ----------

    @Test
    fun `公网明文 http 升级为 https`() {
        assertEquals("https://h.com/v1/models", ModelCatalogUrl.modelsUrl("http://h.com/v1", v1))
    }

    @Test
    fun `内网地址保持 http`() {
        assertEquals("http://192.168.1.9:1234/v1/models", ModelCatalogUrl.modelsUrl("http://192.168.1.9:1234/v1", v1))
    }

    @Test
    fun `localhost 保持 http`() {
        assertEquals("http://localhost:8080/v1/models", ModelCatalogUrl.modelsUrl("http://localhost:8080", v1))
    }

    // ---------- 非法输入 ----------

    @Test
    fun `空地址抛 InvalidUrl`() {
        assertThrows(ModelCatalogException.InvalidUrl::class.java) { ModelCatalogUrl.modelsUrl("   ", v1) }
    }

    @Test
    fun `非 http 协议抛 InvalidUrl`() {
        assertThrows(ModelCatalogException.InvalidUrl::class.java) { ModelCatalogUrl.modelsUrl("ftp://h.com/v1", v1) }
    }

    @Test
    fun `缺少协议抛 InvalidUrl`() {
        assertThrows(ModelCatalogException.InvalidUrl::class.java) { ModelCatalogUrl.modelsUrl("api.openai.com/v1", v1) }
    }
}
