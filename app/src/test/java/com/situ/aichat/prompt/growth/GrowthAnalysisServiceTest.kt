package com.situ.aichat.prompt.growth

import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.PersonalitySpectrum
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
 * 成长分析提示词第三人称指名（图纸「审计盲区补扫」·B1·T2-B1）。断言从图纸 §3 B1 / §9① 锁定串独立反推：
 * ① 指令用真实用户名（不再是「与用户的关系发展」）；② 角色信息字段行用真名；③ 输出「注意」段含 summary/narrative
 * 双名字命名要求；④ 对话记录段按真名标注说话人（喂名字生效·formatMessages 传名字）；⑤ userName="" 回退「用户」。
 *
 * 注：不做「systemPrompt 完全不含『用户』」的粗断言——「用户名：」字段标签与命名要求里的「不要写「用户」」都合法保留
 * 该子串；故只精确断言「作为关系表述的『与用户的关系发展』」（旧指令）已消失、对话记录段裸「用户：」已消失。
 * Robolectric：安卓依赖保留（formatTimestamp 自 2026-09-01 件⑤起改 Locale.ROOT 纯 JVM）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GrowthAnalysisServiceTest {

    private val service = GrowthAnalysisService(
        contextLog = mockk<ContextLogService>(relaxed = true),
        conversationDao = mockk<ConversationDao>(relaxed = true),
        messageDao = mockk<MessageDao>(relaxed = true),
        scheduleDao = mockk<ScheduleDao>(relaxed = true),
    )

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
        spectrum = PersonalitySpectrum(),
        quality = RelationshipQuality(),
        interests = emptyList(),
        userName = userName,
        scheduleAnalysis = "",
    )

    @Test fun withUserName_instructionFieldAndOutputUseName_conversationNamed() {
        val (system, user) = systemAndUser("小明")
        // ① 指令用真实用户名；旧「与用户的关系发展」消失。
        assertTrue("指令用真名", system.contains("评估角色的性格变化、与小明的关系发展和兴趣变化"))
        assertFalse("旧指令表述消失", system.contains("与用户的关系发展"))
        // ② 角色信息字段行用真名。
        assertTrue("字段行用真名", system.contains("用户名：小明"))
        // ③ 输出「注意」段含双名字命名要求（§9① 锁定串）。
        assertTrue(
            "输出命名要求",
            system.contains("用「夏晴子」「小明」的名字，不要写「用户」「角色」"),
        )
        // ④ 对话记录段说话人用真名（formatMessages 传名字生效）。
        assertTrue("对话记录用真名", user.contains("小明：") && user.contains("夏晴子："))
        assertFalse("对话记录无「用户：」", user.contains("用户："))
        assertFalse("对话记录无「角色：」", user.contains("角色："))
    }

    @Test fun blankUserName_fallsBackToUser() {
        val (system, user) = systemAndUser("")
        // ⑤ 空名兜底「用户」（= 与今天字节一致，不空白）。
        assertTrue("指令回退用户", system.contains("评估角色的性格变化、与用户的关系发展和兴趣变化"))
        assertTrue("字段行回退用户", system.contains("用户名：用户"))
        assertTrue("对话记录回退「用户：」", user.contains("用户："))
        assertTrue("角色名仍生效", user.contains("夏晴子："))
    }
}
