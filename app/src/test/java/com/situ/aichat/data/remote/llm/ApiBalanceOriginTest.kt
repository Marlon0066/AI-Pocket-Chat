package com.situ.aichat.data.remote.llm

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T1 · 余额路 origin 提取（[ApiBalanceService.extractOrigin]）。规格 = 图纸
 * docs/handoff/2026-08-31-跨四路LLM-URL归一.md §3/E4：
 * 只取 scheme + authority（丢路径与 query），公网 http 升 https（Bearer key 绝不明文出公网），
 * 内网/本机保留 http，非法输入返 null。升级白名单与聊天路同源（[LlmHttp.shouldUpgradeInsecureHost]）。
 */
class ApiBalanceOriginTest {

    private val service = ApiBalanceService(OkHttpClient(), Json)

    @Test
    fun httpsBaseUrl_dropsPath() {
        assertEquals("https://api.deepseek.com", service.extractOrigin("https://api.deepseek.com/v1"))
    }

    @Test
    fun publicHttp_upgradedToHttps() {
        assertEquals("https://public.example.com", service.extractOrigin("http://public.example.com/x"))
    }

    @Test
    fun privateLanHttp_keepsHttpAndPort() {
        assertEquals("http://192.168.1.10:8080", service.extractOrigin("http://192.168.1.10:8080"))
    }

    @Test
    fun openRouterDefault_dropsApiV1Path() {
        assertEquals("https://openrouter.ai", service.extractOrigin("https://openrouter.ai/api/v1"))
    }

    @Test
    fun queryAndTrailingSpaces_dropped() {
        assertEquals("https://relay.example.com", service.extractOrigin("  https://relay.example.com/v1?token=x  "))
    }

    @Test
    fun invalidInputs_returnNull() {
        assertNull(service.extractOrigin("not a url"))
        assertNull(service.extractOrigin(""))
    }
}
