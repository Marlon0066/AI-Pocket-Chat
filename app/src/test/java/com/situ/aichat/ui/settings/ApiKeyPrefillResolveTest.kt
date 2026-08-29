package com.situ.aichat.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [resolveNewApiKey] 的 T1（api-key-prefill）：编辑屏预填已存 key 后，保存时只有「真改了」才把
 * 新值传给仓库层——仓库把非空 newApiKey 一律当 key 变更并重置重跑能力检测，比对不做对就会
 * 每次保存白跑一轮联网检测。断言从该契约独立反推。
 */
class ApiKeyPrefillResolveTest {

    @Test
    fun blankInputKeepsStoredKey() {
        assertNull(resolveNewApiKey("", storedKey = "sk-old"))
        assertNull(resolveNewApiKey("   ", storedKey = "sk-old"))
        assertNull(resolveNewApiKey("", storedKey = "")) // 从未存过 key、也没填 → 仍保持
    }

    @Test
    fun unchangedPrefilledKeyKeepsStoredKey() {
        assertNull(resolveNewApiKey("sk-old", storedKey = "sk-old"))
        assertNull(resolveNewApiKey("  sk-old  ", storedKey = "sk-old")) // 前后空白不算改动
    }

    @Test
    fun changedKeyReturnsTrimmedNewValue() {
        assertEquals("sk-new", resolveNewApiKey("sk-new", storedKey = "sk-old"))
        assertEquals("sk-new", resolveNewApiKey("  sk-new  ", storedKey = "sk-old"))
        assertEquals("sk-first", resolveNewApiKey("sk-first", storedKey = "")) // 首次补填也算变更
    }

    @Test
    fun caseAndSubstringDifferencesCountAsChange() {
        assertEquals("SK-OLD", resolveNewApiKey("SK-OLD", storedKey = "sk-old")) // key 大小写敏感
        assertEquals("sk-ol", resolveNewApiKey("sk-ol", storedKey = "sk-old"))
    }
}
