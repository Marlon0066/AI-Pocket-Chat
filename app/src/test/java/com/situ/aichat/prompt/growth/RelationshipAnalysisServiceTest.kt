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
 * 关系分析提示词第三人称指名（图纸一·B1·T2-B1）+ reason 文体一节
 * （《2026-09-03 关系历程注入根治》§3 件 5a/5b/5c·图纸一 §9①B1 锁定串已随之修订）。
 * 断言从两份图纸的逐字规格独立反推：
 * ① 规则 9 用真实用户名（不再是「因为用户说」）；② reason 文体节四条规矩 + 正反例 + 旧条目免模仿行
 *    + JSON 新描述在场，且**旧锁定串已消失**；
 * ③ 对话记录段按真名标注说话人（喂名字生效·formatMessages 传名字）；④ userName="" 回退「用户」。
 *
 * 「双名字 + 禁「用户」「角色」」的原意由件 5a 规矩 2 承接并加强（从 JSON 一行内的附注升格为独立一节 + 正反例），
 * 故 ② 的断言换了落点、没换要求。空昵称兜底「用户」有意保留（该图纸 §3 件 5d 明写不动），故 ④ 原样。
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
        // ② 件 5a：reason 文体一节的四条规矩与正反例逐字在场。
        assertTrue("reason 节标题", system.contains("## 关于 reason（变化原因怎么写）"))
        assertTrue(
            "开场点明双消费者",
            system.contains("这句话夏晴子本人会读到，也会显示给用户看。要像在说一件真实发生过的事，不是写系统报告。"),
        )
        assertTrue(
            "规矩1 只写发生了什么",
            system.contains("1. 只写**发生了什么**。不要写\"关系从 X 变成 Y\"、\"进入 Y 阶段\"——关系变成什么系统另有记录，不用你复述。"),
        )
        assertTrue(
            "规矩2 双名字 + 禁四词",
            system.contains("2. 两个人都用名字：「夏晴子」「小明」。不要出现\"用户\"\"角色\"\"AI\"\"系统\"。"),
        )
        assertTrue(
            "规矩3 要具体",
            system.contains("3. 要具体。写得出细节就写细节；不要写\"确认了彼此的心意归属\"\"感情得到升华\"这类放在谁身上都成立的空话。"),
        )
        assertTrue("规矩4 一句话 40 字", system.contains("4. 一句话，40 字以内。"))
        assertTrue("正例", system.contains("✅ 夏晴子说出了一直没敢提的那件事，小明没有回避，认真接住了。"))
        assertTrue(
            "反例1 空话 + 复述关系变化",
            system.contains("❌ 双方在信任试探中确认了彼此的心意归属，关系从热恋进入更成熟的坦诚沟通阶段。（全是空话，还在复述关系变化）"),
        )
        assertTrue(
            "反例2 监控日志腔",
            system.contains("❌ 对话中涉及多个亲密话题，用户明确提出邀约。（像在写监控日志，还写了\"用户\"）"),
        )
        // ②b 件 5b：切断自我强化循环（旧报告体历史条目不当范本）。
        assertTrue(
            "旧条目免模仿行",
            system.contains("（以上是历史记录。其中旧条目的写法可能不符合下面对 reason 的要求，不必模仿。）"),
        )
        // ②c 件 5c：JSON 示例行改指上节，旧 §9 锁定串消失。
        assertTrue(
            "JSON reason 新描述",
            system.contains("\"reason\": \"见上节要求：写发生了什么，两人用名字，具体，40 字内\""),
        )
        assertFalse(
            "旧 §9 锁定串已消失",
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
