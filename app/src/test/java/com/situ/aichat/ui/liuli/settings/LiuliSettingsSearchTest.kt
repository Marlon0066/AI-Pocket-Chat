package com.situ.aichat.ui.liuli.settings

import com.situ.aichat.ui.settings.hub.settingsMatches
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1：设置主页搜索过滤（原卷四 A-4 · 卷一搬至 ui.settings.hub，断言一字不改）。
 */
class LiuliSettingsSearchTest {

    @Test fun 空词与纯空白词全显() {
        assertTrue(settingsMatches("", "外观", "表情包管理"))
        assertTrue(settingsMatches("   ", "外观"))
        assertTrue(settingsMatches("\t\n", "外观"))
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
        assertFalse(settingsMatches("阈值", null, null))
    }
}
