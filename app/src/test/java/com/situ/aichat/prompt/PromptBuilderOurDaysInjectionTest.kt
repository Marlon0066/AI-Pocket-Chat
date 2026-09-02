package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.OurDayEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ChatMessageDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * T2-1（「我们的日子」卷二图纸 §7.2）：`{{我们的日子}}` 接线经真 [PromptBuilder.buildMessages] 管线。断言从 §2.3 不变
 * 清单 + §3.3 / §4.1 / §5 独立反推：① `ourDays` 空 ⇒ 输出逐条字节相等（E40）② 提「上周三」有页 ⇒ 大 system 含块头两行、
 * 块紧随「[你的见面日记]」相框 / 无见面记忆时紧随「[小雨的记忆]」块 ③ 昨天在窗口内不出（E11）④ VOICE_CALL 出、
 * OFFLINE_MEETING 不出（E53 / E54）⑤ 无昵称块头「用户」（E49）⑥ 分段 name / systemModuleType（上下文日志）。
 * qualifiers=zh-rCN：相框 / 记忆标题断言用中文生产文案。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class PromptBuilderOurDaysInjectionTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    /** 2026-09-02 周三 12:00。 */
    private val fixedNow: Instant = Instant.ofEpochMilli(at(2026, 9, 2, 12))
    private val rawDiary = "【见面 · 2026-08-20 15:30 · 公园】\n一次很好的见面。"

    private fun character() = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L, memorySummary = "她喜欢猫。")

    private fun row(dayKey: String, factLine: String) = OurDayEntity(
        uuid = "u-$dayKey", characterUuid = "c1", dayKey = dayKey, factLine = factLine, messageCount = 3,
        createdAtMillis = 0L, updatedAtMillis = 0L,
    )
    private val rows = listOf(row("2026-08-26", "小雨陪对方看了场电影"), row("2026-09-01", "小雨和对方聊到很晚"))

    private fun userMsg(content: String, ts: Long) =
        MessageEntity(messageUUID = "u-$ts", conversationUuid = "conv1", roleRaw = "user", content = content, timestamp = ts)

    private fun build(
        messages: List<MessageEntity>,
        ourDays: List<OurDayEntity>? = null,
        offlineMeetingMemoryText: String = "",
        userProfile: UserProfileEntity? = UserProfileEntity(nickname = "阿澄"),
        scene: PromptScene = PromptScene.ONLINE_CHAT,
        conversation: ConversationEntity? = null,
    ): List<ChatMessageDto> = if (ourDays == null) {
        PromptBuilder.buildMessages(
            character = character(), conversation = conversation, sortedMessages = messages, userProfile = userProfile,
            appSettings = AppSettings(), strings = PromptStrings(RuntimeEnvironment.getApplication()),
            offlineMeetingMemoryText = offlineMeetingMemoryText, scene = scene, now = fixedNow,
        )
    } else {
        PromptBuilder.buildMessages(
            character = character(), conversation = conversation, sortedMessages = messages, userProfile = userProfile,
            appSettings = AppSettings(), strings = PromptStrings(RuntimeEnvironment.getApplication()),
            offlineMeetingMemoryText = offlineMeetingMemoryText, ourDays = ourDays, scene = scene, now = fixedNow,
        )
    }

    private fun head(msgs: List<ChatMessageDto>) = msgs.first { it.role == "system" }.content.orEmpty()
    private fun allText(msgs: List<ChatMessageDto>) = msgs.joinToString("\n") { it.content.orEmpty() }

    private val header1 = "[我们的日子 · 按日期翻到的记录]"

    // ── ① 空 ⇒ 字节级不变 ──

    @Test fun ourDays空_与不传时输出逐条逐字节相等_E40() {
        val msgs = listOf(userMsg("上周三我们聊了什么", at(2026, 9, 2, 11, 50)))
        val baseline = build(msgs)
        assertEquals(baseline, build(msgs, ourDays = emptyList()))
        assertFalse(allText(baseline).contains(header1))
    }

    @Test fun 有行但无一可注入_仍与基线逐字节相等_零分段() {
        // 消息无日期指名；那年今日（2025-09-02 / 2026-08-02）无页 ⇒ 模块产出 "" ⇒ 整块跳过。
        val msgs = listOf(userMsg("今天有点累", at(2026, 9, 2, 11, 50)))
        assertEquals(build(msgs), build(msgs, ourDays = rows))
        val segs = mutableListOf<ContextSegment>()
        PromptBuilder.buildMessages(
            character = character(), sortedMessages = msgs, userProfile = null, appSettings = AppSettings(),
            strings = PromptStrings(RuntimeEnvironment.getApplication()), ourDays = rows, now = fixedNow, segmentSink = segs,
        )
        assertTrue("空产出不产分段", segs.none { it.systemModuleType == SystemModuleType.OUR_DAYS.rawValue })
    }

    // ── ② 位置：紧随见面记忆相框 / 无见面记忆时紧随角色记忆块 ──

    @Test fun 提上周三有页_大system含块头两行_紧随见面日记相框之后() {
        val msgs = listOf(userMsg("上周三我们聊了什么", at(2026, 9, 2, 11, 50)))
        val h = head(build(msgs, ourDays = rows, offlineMeetingMemoryText = rawDiary))
        assertTrue(h.contains(header1))
        assertTrue(h.contains("这是小雨和阿澄当天的记录。同一天若与记忆里的概括有出入，以这里为准。"))
        assertTrue(h.contains("[2026-08-26 周三] 小雨陪对方看了场电影"))
        // 相框模块内容 = 相框行 + "\n\n" + 原日记；块紧随其后 = 原日记末尾 + 模块分隔 "\n\n" + 块头。
        assertTrue("块应紧随见面日记原文之后", h.contains(rawDiary + "\n\n" + header1))
        assertTrue(h.indexOf("[你的见面日记]") < h.indexOf(header1))
    }

    @Test fun 无见面记忆时_块紧随角色记忆块之后() {
        val msgs = listOf(userMsg("上周三我们聊了什么", at(2026, 9, 2, 11, 50)))
        val h = head(build(msgs, ourDays = rows))
        val memIdx = h.indexOf("[小雨的记忆]")
        val blockIdx = h.indexOf(header1)
        assertTrue(memIdx >= 0 && blockIdx > memIdx)
        // 两者之间不夹任何其他模块（模块以 "\n\n" 分隔·记忆块内部只用单换行）。
        val between = h.substring(memIdx, blockIdx)
        assertEquals("记忆块与日子块之间恰一个模块分隔", 1, between.split("\n\n").size - 1)
        assertFalse(h.contains("[你的见面日记]"))
    }

    // ── ③ 窗口排除 ──

    @Test fun 昨天在原文窗口内则不出_E11() {
        val msgs = listOf(userMsg("昨天我们聊了啥", at(2026, 9, 1, 20)))
        val text = allText(build(msgs, ourDays = rows))
        assertFalse(text.contains("[2026-09-01 周二]"))
        assertFalse("无其他可注入行 ⇒ 整块不出", text.contains(header1))
    }

    @Test fun 昨天不在窗口内则出() {
        val msgs = listOf(userMsg("昨天我们聊了啥", at(2026, 9, 2, 11, 50)))
        assertTrue(head(build(msgs, ourDays = rows)).contains("[2026-09-01 周二] 小雨和对方聊到很晚"))
    }

    // ── ④ 场景 ──

    @Test fun 语音通话场景出_E53() {
        val msgs = listOf(userMsg("上周三我们聊了什么", at(2026, 9, 2, 11, 50)))
        assertTrue(head(build(msgs, ourDays = rows, scene = PromptScene.VOICE_CALL)).contains(header1))
    }

    @Test fun 线下见面场景不出_E54() {
        val msgs = listOf(userMsg("上周三我们聊了什么", at(2026, 9, 2, 11, 50)))
        val offline = ConversationEntity(
            uuid = "conv1", title = "t", characterUuid = "c1", creationDate = 0L,
            isInOfflineMode = true, currentOfflineSessionId = "sess-1",
        )
        val text = allText(build(msgs, ourDays = rows, scene = PromptScene.OFFLINE_MEETING, conversation = offline))
        assertFalse(text.contains(header1))
    }

    // ── ⑤ 无昵称 ──

    @Test fun 无昵称块头用户_E49() {
        val msgs = listOf(userMsg("上周三我们聊了什么", at(2026, 9, 2, 11, 50)))
        assertTrue(head(build(msgs, ourDays = rows, userProfile = null)).contains("这是小雨和用户当天的记录。"))
    }

    // ── ⑥ 分段 ──

    @Test fun 分段_name与systemModuleType_落前置区() {
        val msgs = listOf(userMsg("上周三我们聊了什么", at(2026, 9, 2, 11, 50)))
        val result = PromptBuilder.buildMessagesWithSegments(
            character = character(), sortedMessages = msgs, userProfile = null, appSettings = AppSettings(),
            strings = PromptStrings(RuntimeEnvironment.getApplication()), now = fixedNow, ourDays = rows,
        )
        val seg = result.segments.firstOrNull { it.systemModuleType == "ourDays" }
        assertNotNull("应有我们的日子分段", seg)
        assertEquals("我们的日子", seg!!.name)
        assertEquals(ContextSegment.POSITION_PREFIX, seg.position)
        assertTrue(head(result.messages).contains(header1))
    }
}
