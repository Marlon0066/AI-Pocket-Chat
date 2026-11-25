package com.situ.aichat.worldbook

import com.situ.aichat.data.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1：世界书引擎增强单测（1A 默认值、`{{char}}`/`{{user}}` 关键词宏与正则转义、书级 `scanDepth`/`recursiveScanning`、
 * `extraJson` 角色/用户静态匹配源、首轮 `includeNames` 角色名触发）。
 */
class WorldInfoEngineEnhancementTest {

    @Test
    fun `1A全局默认值调优_深度4_递归开启_上限3轮`() {
        val defaults = AppSettings()
        assertEquals(4, defaults.worldInfoScanDepth)
        assertTrue(defaults.worldInfoRecursiveScan)
        assertEquals(3, defaults.worldInfoMaxRecursionSteps)
    }

    @Test
    fun `关键词内char与user宏大小写无关展开_角色名含正则元字符也不崩`() {
        val entries = listOf(
            wbEntry("e1", keys = listOf("{{CHAR}}的剑"), content = "本命飞剑设定"),
            wbEntry("e2", keys = listOf("/{{user}}.*秘境/i"), content = "秘境通行证"),
        )
        val input = WorldInfoActivationInput(
            books = listOf(wbBook("b1")),
            entries = entries,
            messages = listOf(ScanMessage(text = "苏(晚)的剑能劈开阿[檀]探索的秘境吗？", senderName = "阿[檀]")),
            conversationMessageCount = 1,
            conversationUuid = "conv1",
            timedStates = emptyList(),
            vectorMatchedEntryUuids = emptySet(),
            settings = WorldInfoSettings(scanDepth = 4),
            characterName = "苏(晚)",
            userName = "阿[檀]",
        )
        val r = activate(input)
        assertEquals(setOf("e1", "e2"), r.activatedTitles())
    }

    @Test
    fun `书级scanDepth与recursiveScanning生效`() {
        val book = wbBook("b1").copy(scanDepth = 4, recursiveScanning = true)
        val entries = listOf(
            wbEntry("A", keys = listOf("四轮前的暗号"), content = "引出下级词：暗桩"),
            wbEntry("B", keys = listOf("暗桩"), content = "暗桩在城南茶馆"),
        )
        val msgs = listOf("四轮前的暗号", "闲聊一", "闲聊二", "闲聊三").map { ScanMessage(it) }
        val input = WorldInfoActivationInput(
            books = listOf(book),
            entries = entries,
            messages = msgs,
            conversationMessageCount = 4,
            conversationUuid = "conv1",
            timedStates = emptyList(),
            vectorMatchedEntryUuids = emptySet(),
            // 全局设浅窗 + 关递归，验证书级 scanDepth=4 与 recursiveScanning=true 覆盖生效
            settings = WorldInfoSettings(scanDepth = 1, recursiveScan = false, maxRecursionSteps = 0),
        )
        val r = activate(input)
        assertEquals(setOf("A", "B"), r.activatedTitles())
    }

    @Test
    fun `extraJson开启matchCharacterDescription与matchPersonaDescription时静态人设可点火`() {
        val entries = listOf(
            wbEntry("e-char", keys = listOf("太虚剑宗"), content = "宗门戒律").copy(
                extraJson = """{"matchCharacterDescription":true}""",
            ),
            wbEntry("e-user", keys = listOf("药王谷传人"), content = "医术渊源").copy(
                extraJson = """{"extensions":{"match_persona_description":true}}""",
            ),
            wbEntry("e-off", keys = listOf("太虚剑宗"), content = "未开静态匹配源不该在闲聊触发"),
        )
        val input = WorldInfoActivationInput(
            books = listOf(wbBook("b1")),
            entries = entries,
            messages = listOf(ScanMessage("今天天气真好")),
            conversationMessageCount = 1,
            conversationUuid = "conv1",
            timedStates = emptyList(),
            vectorMatchedEntryUuids = emptySet(),
            settings = WorldInfoSettings(scanDepth = 2),
            matchSources = WorldInfoMatchSources(
                characterDescription = "太虚剑宗首席弟子",
                personaDescription = "药王谷传人，擅长炼丹",
            ),
        )
        val r = activate(input)
        assertEquals(setOf("e-char", "e-user"), r.activatedTitles())
    }

    @Test
    fun `首轮无assistant历史时_includeNames仍补入角色名参与匹配`() {
        val entries = listOf(wbEntry("e-self", keys = listOf("沈星回"), content = "沈星回的专属档案"))
        val input = WorldInfoActivationInput(
            books = listOf(wbBook("b1")),
            entries = entries,
            messages = listOf(ScanMessage(text = "你好呀", senderName = "阿檀")),
            conversationMessageCount = 1,
            conversationUuid = "conv1",
            timedStates = emptyList(),
            vectorMatchedEntryUuids = emptySet(),
            settings = WorldInfoSettings(scanDepth = 2, includeNames = true),
            characterName = "沈星回",
            userName = "阿檀",
        )
        assertEquals(setOf("e-self"), activate(input).activatedTitles())
    }
}
