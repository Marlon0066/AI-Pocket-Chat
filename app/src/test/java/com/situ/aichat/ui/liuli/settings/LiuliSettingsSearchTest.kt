package com.situ.aichat.ui.liuli.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1：设置主页搜索过滤（图纸 2026-09-06 卷四 A-4 · §5 E7）。
 *
 * 期望从 A-4 那一行独立反推：trim · 空词全显 · `contains(ignoreCase)` · null 文本跳过 ·
 * 一条命中即整组显。
 */
class LiuliSettingsSearchTest {

    @Test fun 空词与纯空白词全显() {
        assertTrue(settingsMatches("", "外观", "表情包管理"))
        assertTrue(settingsMatches("   ", "外观"))
        assertTrue(settingsMatches("\t\n", "外观"))
        // 一条文本都没有时，空词照样全显（组标题自己也算显）。
        assertTrue(settingsMatches(""))
    }

    @Test fun 词要先去首尾空白再比() {
        assertTrue(settingsMatches("  外观  ", "外观"))
        assertFalse(settingsMatches("  外观x  ", "外观"))
    }

    @Test fun 子串命中且忽略大小写() {
        assertTrue(settingsMatches("观", "外观"))
        assertTrue(settingsMatches("api", "API 配置"))
        assertTrue(settingsMatches("API", "api 配置"))
        assertFalse(settingsMatches("蓝牙", "外观", "表情包管理"))
    }

    @Test fun 任一条命中即算命中且null文本跳过() {
        assertTrue(settingsMatches("阈值", "记忆", "保留量 / 检索阈值"))
        assertTrue(settingsMatches("记忆", "记忆", null))
        assertFalse(settingsMatches("阈值", "记忆", null))
        // 全 null（该组各行都没副标题）时不命中，不该崩。
        assertFalse(settingsMatches("阈值", null, null))
    }
}
