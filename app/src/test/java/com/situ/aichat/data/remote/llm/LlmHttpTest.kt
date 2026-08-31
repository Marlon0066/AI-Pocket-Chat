package com.situ.aichat.data.remote.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H3#1 测试网 · LlmHttp（URL 归一化 + http→https 自动升级·**安全分支**：Bearer key 绝不明文
 * 发往公网 http）。规格：三形输入（裸 host / …/v1 / …/chat/completions）都归一到完整端点；
 * 尾斜杠剥除；公网 http 升 https，本机/私网/.local/IPv6 唯一本地保留 http（自托管场景）；
 * 空串/非法/非 http(s) scheme 抛 InvalidUrl。
 */
class LlmHttpTest {

    // MARK: - 三形归一

    @Test
    fun bareHost_appendsV1ChatCompletions() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://api.example.com"),
        )
    }

    @Test
    fun v1Suffix_appendsChatCompletions() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://api.example.com/v1"),
        )
    }

    @Test
    fun fullEndpoint_passedThrough() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://api.example.com/v1/chat/completions"),
        )
    }

    @Test
    fun trailingSlashes_trimmed() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://api.example.com/v1/"),
        )
    }

    @Test
    fun customPrefixPath_keptAndExtended() {
        // 中转站常见自定义前缀：…/api → …/api/v1/chat/completions。
        assertEquals(
            "https://relay.example.com/api/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://relay.example.com/api"),
        )
    }

    // MARK: - 安全分支：http→https 升级

    @Test
    fun publicHttpHost_upgradedToHttps() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("http://api.example.com"),
        )
    }

    @Test
    fun localAndPrivateHosts_keepHttp() {
        val keep = listOf(
            "http://localhost:8080",
            "http://127.0.0.1:1234",
            "http://10.0.0.5",
            "http://192.168.1.10:11434",
            "http://172.16.0.1",
            "http://172.31.255.254",
            "http://myserver.local",
        )
        for (url in keep) {
            assertTrue("url=$url", LlmHttp.buildChatCompletionsUrl(url).startsWith("http://"))
        }
    }

    @Test
    fun nearMissPrivateRanges_stillUpgraded() {
        // 172.32.x 不在 172.16-31 私网段；11.x 不是 10.x —— 防「看着像私网」误放行。
        assertTrue(LlmHttp.buildChatCompletionsUrl("http://172.32.0.1").startsWith("https://"))
        assertTrue(LlmHttp.buildChatCompletionsUrl("http://11.0.0.1").startsWith("https://"))
    }

    @Test
    fun shouldUpgrade_fullTable() {
        // 公网/未知 → 升级（true）；本机/私网族 → 保留（false）。
        assertTrue(LlmHttp.shouldUpgradeInsecureHost("api.example.com"))
        assertTrue(LlmHttp.shouldUpgradeInsecureHost(null))
        assertTrue(LlmHttp.shouldUpgradeInsecureHost(""))
        assertFalse(LlmHttp.shouldUpgradeInsecureHost("localhost"))
        assertFalse(LlmHttp.shouldUpgradeInsecureHost("LOCALHOST"))
        assertFalse(LlmHttp.shouldUpgradeInsecureHost("::1"))
        assertFalse(LlmHttp.shouldUpgradeInsecureHost("nas.local"))
        assertFalse(LlmHttp.shouldUpgradeInsecureHost("127.0.0.1"))
        assertFalse(LlmHttp.shouldUpgradeInsecureHost("10.1.2.3"))
        assertFalse(LlmHttp.shouldUpgradeInsecureHost("192.168.0.1"))
        assertFalse(LlmHttp.shouldUpgradeInsecureHost("172.16.0.1"))
        assertFalse(LlmHttp.shouldUpgradeInsecureHost("172.31.9.9"))
        // IPv6 唯一本地地址（fc00::/7）。
        assertFalse(LlmHttp.shouldUpgradeInsecureHost("fd12:3456::1"))
        assertFalse(LlmHttp.shouldUpgradeInsecureHost("fc00::1"))
    }

    // MARK: - 非法输入

    @Test
    fun invalidInputs_throwInvalidUrl() {
        assertThrows(LlmError.InvalidUrl::class.java) { LlmHttp.buildChatCompletionsUrl("") }
        assertThrows(LlmError.InvalidUrl::class.java) { LlmHttp.buildChatCompletionsUrl("   ") }
        assertThrows(LlmError.InvalidUrl::class.java) { LlmHttp.buildChatCompletionsUrl("ftp://example.com") }
        assertThrows(LlmError.InvalidUrl::class.java) { LlmHttp.buildChatCompletionsUrl("api.example.com") } // 无 scheme
        assertThrows(LlmError.InvalidUrl::class.java) { LlmHttp.buildChatCompletionsUrl("http://[bad url") }
    }

    // MARK: - §5.1 存量地址形态穷举表（图纸 docs/handoff/2026-08-31-跨四路LLM-URL归一.md）
    //
    // 每行一例，行号 = 表行号。「不变」= 归一前后逐字节相同（存量配置绝不许被改坏）；
    // 「修复」= 旧规则拼出 404/畸形串的实证形态。断言值从表（规格）反推，不照抄实现输出。

    @Test
    fun row01_bareHost_unchanged() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://api.example.com"),
        )
    }

    @Test
    fun row02_v1Suffix_unchanged() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://api.example.com/v1"),
        )
    }

    @Test
    fun row03_v1TrailingSlash_unchanged() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://api.example.com/v1/"),
        )
    }

    @Test
    fun row04_fullEndpoint_unchanged() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://api.example.com/v1/chat/completions"),
        )
    }

    @Test
    fun row05_customPrefix_unchanged() {
        assertEquals(
            "https://relay.example.com/api/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://relay.example.com/api"),
        )
    }

    @Test
    fun row06_arkApiV3_fixed() {
        // 火山方舟：旧规则拼成 …/api/v3/v1/chat/completions → 404。
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://ark.cn-beijing.volces.com/api/v3"),
        )
    }

    @Test
    fun row07_bigmodelPaasV4_fixed() {
        // 智谱 GLM：旧规则拼成 …/api/paas/v4/v1/chat/completions → 404。
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://open.bigmodel.cn/api/paas/v4"),
        )
    }

    @Test
    fun row08_geminiDefaultFullEndpoint_unchanged() {
        // GEMINI 出厂 baseUrl 已是完整端点。
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            LlmHttp.buildChatCompletionsUrl(
                "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            ),
        )
    }

    @Test
    fun row09_geminiOpenAiBase_fixed() {
        // Gemini 兼容层惯例段：末段 openai 非版本段，旧规则再补 /v1 拼错。
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://generativelanguage.googleapis.com/v1beta/openai"),
        )
    }

    @Test
    fun row10_queryPreservedAfterPath_fixed() {
        // 旧规则把后缀拼进 query 尾巴（…/v1?token=x/chat/completions）= 畸形串。
        assertEquals(
            "https://relay.example.com/v1/chat/completions?token=x",
            LlmHttp.buildChatCompletionsUrl("https://relay.example.com/v1?token=x"),
        )
    }

    @Test
    fun row11_nonV1FullEndpoint_unchanged() {
        assertEquals(
            "https://host.example.com/api/v3/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://host.example.com/api/v3/chat/completions"),
        )
    }

    @Test
    fun row12_messagesTail_fixed() {
        // Anthropic 原生端点误填进来 → 剥回基座再拼（对称自愈）。
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://api.example.com/v1/messages"),
        )
    }

    @Test
    fun row13_responsesTail_fixed() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://api.example.com/v1/responses"),
        )
    }

    @Test
    fun row14_modelsTailWithHashEscape_fixed() {
        // 拉取路的 '#' 逃生口地址被填进聊天路：剥 '#' + 剥 models 后照常规则拼。
        assertEquals(
            "https://relay.example.com/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://relay.example.com/v1/models#"),
        )
    }

    @Test
    fun row15_v2Version_intentionalFix() {
        // 有意变化（修复向）：旧输出 …/v2/v1/chat/completions，与同一用户的拉取路 …/v2/models 早已不一致。
        assertEquals(
            "https://relay.example.com/v2/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://relay.example.com/v2"),
        )
    }

    @Test
    fun row16_privateLanKeepsHttpAndPort_unchanged() {
        assertEquals(
            "http://192.168.1.10:11434/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("http://192.168.1.10:11434/v1"),
        )
    }

    @Test
    fun row17_publicHttpUpgraded_unchanged() {
        assertEquals(
            "https://public.example.com/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("http://public.example.com/v1"),
        )
    }

    @Test
    fun row18_anthropicDefault_unchanged() {
        assertEquals(
            "https://api.anthropic.com/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://api.anthropic.com"),
        )
    }

    @Test
    fun row19_deepseekDefault_unchanged() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://api.deepseek.com/v1"),
        )
    }

    @Test
    fun row20_openrouterDefault_unchanged() {
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://openrouter.ai/api/v1"),
        )
    }

    @Test
    fun row21_minimaxDefault_unchanged() {
        assertEquals(
            "https://api.minimaxi.com/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://api.minimaxi.com/v1"),
        )
    }

    @Test
    fun row22_upperCaseVersionSegment_fixed() {
        // 版本段匹配忽略大小写，但路径段原样保留（不许顺手小写化）。
        assertEquals(
            "https://relay.example.com/API/V3/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://relay.example.com/API/V3"),
        )
    }

    @Test
    fun row23_bareHashEscape_fixed() {
        assertEquals(
            "https://relay.example.com/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://relay.example.com/v1#"),
        )
    }

    @Test
    fun row24_fragmentDiscarded_fixed() {
        // fragment 本就不随 HTTP 请求发送 → 丢弃（旧规则把后缀拼进 fragment 尾巴）。
        assertEquals(
            "https://relay.example.com/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://relay.example.com/v1#foo"),
        )
    }

    // MARK: - E2：只有 '#' 的输入（剥后空 / 无 authority）

    @Test
    fun hashOnlyInputs_throwInvalidUrl() {
        assertThrows(LlmError.InvalidUrl::class.java) { LlmHttp.buildChatCompletionsUrl("#") }
        assertThrows(LlmError.InvalidUrl::class.java) { LlmHttp.buildChatCompletionsUrl("https://#") }
    }
}
