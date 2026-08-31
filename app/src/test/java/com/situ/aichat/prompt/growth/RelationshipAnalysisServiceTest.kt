package com.situ.aichat.prompt.growth

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.diagnostics.ContextLogService
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 关系分析提示词第三人称指名（图纸一·B1·T2-B1）。断言从图纸 §3 B1 / §9 锁定串独立反推：
 * ① 规则 9 用真实用户名（不再是「因为用户说」）；② reason 描述含双名字命名要求；
 * ③ 对话记录段按真名标注说话人（喂名字生效·formatMessages 传名字）；④ userName="" 回退「用户」。
 *
 * 注：不做「systemPrompt 完全不含『用户』」的粗断言——「用户名：」字段标签与「不要写「用户」」指令都合法保留该子串；
 * 故只精确断言「作为称呼的裸『用户』」（旧规则 9「因为用户说」）已消失。
 * Robolectric：安卓依赖保留（formatTimestamp 自 2026-09-01 件⑤起改 Locale.ROOT 纯 JVM）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelationshipAnalysisServiceTest {

    private val service = RelationshipAnalysisService(mockk<ContextLogService>(relaxed = true))

    private fun userMsg(content: String) = MessageEntity(
        messageUUID = "m1", conversationUuid = "conv1", roleRaw = "user", content = content,
        timestamp = 1_700_000_000_000L, messageKindRaw = "plain_text",
    )

    private fun charMsg(content: String) = MessageEntity(
        messageUUID = "a1", conversationUuid = "conv1", roleRaw = "assistant", content = content,
        timestamp = 1_700_000_001_000L, messageKindRaw = "plain_text",
    )

    private fun systemAndUser(userName: String): Pair<String, String> = service.buildAnalysisPrompt(
        messages = listOf(userMsg("今天好开心"), charMsg("我也是呀")),
        characterName = "夏晴子",
        currentRelationship = "普通朋友",
        currentPhase = null,
        quality = RelationshipQuality(),
        milestones = emptyList(),
        userName = userName,
    )

    @Test fun withUserName_rule9AndReasonUseName_conversationNamed() {
        val (system, user) = systemAndUser("小明")
        // ① 规则 9 用真实用户名；旧「因为用户说」的裸称呼消失。
        assertTrue("规则9 用真名", system.contains("9. 不要因为小明说了一句浪漫的话"))
        assertFalse("旧裸称呼消失", system.contains("不要因为用户说了一句浪漫的话"))
        // ② reason 描述含双名字命名要求（§9 锁定串）。
        assertTrue(
            "reason 命名要求",
            system.contains("提到两人时用「夏晴子」「小明」的名字，不要写「用户」「角色」"),
        )
        // ③ 对话记录段说话人用真名（formatMessages 传名字生效）。
        assertTrue("对话记录用真名", user.contains("小明：") && user.contains("夏晴子："))
        assertFalse("对话记录无「用户：」", user.contains("用户："))
        assertFalse("对话记录无「角色：」", user.contains("角色："))
    }

    @Test fun blankUserName_fallsBackToUser() {
        val (system, user) = systemAndUser("")
        // ④ 空名兜底「用户」（= 与今天一致，不空白）。
        assertTrue("规则9 回退用户", system.contains("9. 不要因为用户说了一句浪漫的话"))
        assertTrue("对话记录回退「用户：」", user.contains("用户："))
        assertTrue("角色名仍生效", user.contains("夏晴子："))
    }
}
