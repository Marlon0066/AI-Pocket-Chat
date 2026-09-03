package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ChatMessageDto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * 历史时间分割线接入 [PromptBuilder.buildMessages] 的端到端行为测试（chunk2）。
 * 验证：跨天/久隔处插独立 system 横线分割线、连续当天聊天不插、跨天打断 role 合并、
 * 复刻 dump 穿帮场景。时区钉死 Asia/Shanghai（分割线内部用 `ZoneId.systemDefault()`）保证断言确定性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptBuilderTimeDividerTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private lateinit var originalTz: TimeZone

    @Before
    fun pinTimeZone() {
        originalTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTz)
    }

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun inst(y: Int, mo: Int, d: Int, h: Int, mi: Int): Instant =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant()

    private fun msg(role: String, text: String, t: Long) = MessageEntity(
        messageUUID = "m$t",
        conversationUuid = "c1",
        roleRaw = role,
        content = text,
        timestamp = t,
    )

    private fun build(
        messages: List<MessageEntity>,
        now: Instant,
        settings: AppSettings = AppSettings(),
    ): List<ChatMessageDto> {
        val strings = PromptStrings(RuntimeEnvironment.getApplication())
        val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)
        return PromptBuilder.buildMessages(
            character = character,
            sortedMessages = messages,
            userProfile = null,
            appSettings = settings,
            strings = strings,
            now = now,
        )
    }

    /** 历史里所有「时间分割线」消息（整条 = `【时间 · X】`）。 */
    private fun dividers(msgs: List<ChatMessageDto>): List<String> =
        msgs.mapNotNull { it.content }.filter { HistoryTimeDivider.isDivider(it) }.map { it.trim() }

    @Test
    fun dumpCase_insertsOpeningAnchorAndJumpDivider() {
        // 复刻穿帮：昨天下午聊 → 今天凌晨又来问「几点了」。期望两条分割线把时间轴讲清楚：
        // 起始锚（昨天 14:56）+ 跨夜跳变（今天 00:15），LLM 不再把昨天下午当此刻深夜。
        val out = build(
            listOf(
                msg("user", "在吗", ms(2026, 6, 25, 14, 56)),
                msg("assistant", "在的", ms(2026, 6, 25, 14, 57)),
                msg("user", "你看看几点了", ms(2026, 6, 26, 0, 15)),
            ),
            now = inst(2026, 6, 26, 0, 17),
        )
        // 三期起：第二条缝是「新的一场」（跨 1 日 9h18m → 跨夜档）→ 长版注记，按 §4.2 跨日模板逐字反推；
        // 起始锚（prev=null）恒不带注记。
        assertEquals(
            listOf(
                "【时间 · 昨天 14:56】",
                "【时间 · 今天 00:15——以上对话发生在昨天 14:57 前后，距今 1 天；" +
                    "那时说的\"今天\"指6月25日、\"明天\"指6月26日、\"今晚\"指6月25日晚】",
            ),
            dividers(out),
        )
    }

    @Test
    fun continuousChat_today_noDivider() {
        // 全在今天、间隔都 <30 分钟 → 零分割线（不改动连续聊天的 prompt）。
        val out = build(
            listOf(
                msg("user", "早", ms(2026, 6, 26, 9, 0)),
                msg("assistant", "早呀", ms(2026, 6, 26, 9, 1)),
                msg("user", "今天忙吗", ms(2026, 6, 26, 9, 2)),
            ),
            now = inst(2026, 6, 26, 9, 5),
        )
        assertTrue("连续当天聊天不应有任何分割线：${dividers(out)}", dividers(out).isEmpty())
    }

    @Test
    fun crossDay_breaksUserMerge() {
        // 两条 user 跨天 → 分割线在中间打断合并，不应糊成同一个气泡。
        val out = build(
            listOf(
                msg("user", "昨天那条", ms(2026, 6, 25, 14, 0)),
                msg("user", "今天这条", ms(2026, 6, 26, 0, 10)),
            ),
            now = inst(2026, 6, 26, 0, 17),
        )
        val merged = out.any { c ->
            val t = c.content ?: ""
            t.contains("昨天那条") && t.contains("今天这条")
        }
        assertFalse("跨天的两条 user 不应合并成一条：$out", merged)
    }

    @Test
    fun crossDay_lastMessageStrippedEmpty_noDanglingDivider() {
        // 跨天后末条是「长括号旁白」（assistant 非线下被 stripAssistantParentheticalNarration 剥空 → continue）：
        // A1 修复后，本应指向这条的「今天 00:10」分割线不留在末尾悬空，只剩合法的起始锚。
        val out = build(
            listOf(
                msg("user", "在吗", ms(2026, 6, 25, 14, 0)),
                msg("assistant", "在的", ms(2026, 6, 25, 14, 1)),
                msg("assistant", "（她揉了揉眼睛打了个哈欠然后慢慢往沙发上靠过去整个人都困得不行了）", ms(2026, 6, 26, 0, 10)),
            ),
            now = inst(2026, 6, 26, 0, 17),
        )
        assertEquals(listOf("【时间 · 昨天 14:00】"), dividers(out))
    }

    // MARK: - 场边界长版注记（时间感知三期 §3.3 / §4.2）

    /** 带时间词换算的长版注记（短版分割线不含此串）。 */
    private fun regroundings(msgs: List<ChatMessageDto>): List<String> =
        dividers(msgs).filter { it.contains("以上对话发生在") }

    @Test
    fun realCrashCase_dayBeforeYesterdayScene_getsCrossDayRegrounding() {
        // T2-1 复刻用户真实翻车（2026-09-03）：前天 23:10 角色说「刚试了新制服」，今天 02:34 用户开口。
        // 该缝必须是跨日档长版，并明说「距今 2 天」，让模型把那句话的时间坐标系摆正。
        val out = build(
            listOf(
                msg("assistant", "刚才试了新制服，想给你看~", ms(2026, 9, 1, 23, 10)),
                msg("user", "你睡了吗？", ms(2026, 9, 3, 2, 34)),
            ),
            now = inst(2026, 9, 3, 2, 36),
        )
        assertEquals(
            listOf(
                "【时间 · 今天 02:34——以上对话发生在9月1日 周二 23:10 前后，距今 2 天；" +
                    "那时说的\"今天\"指9月1日、\"明天\"指9月2日、\"今晚\"指9月1日晚】",
            ),
            regroundings(out),
        )
    }

    @Test
    fun fiveSceneBoundaries_onlyLastTwoAnnotated() {
        // E7：窗口内 5 个场边界 → 恰 2 条长版注记，且落在**最后两个**缝（更早的仍出短版）。
        val out = build(
            listOf(
                msg("user", "第一天", ms(2026, 8, 24, 10, 0)),
                msg("assistant", "第二天", ms(2026, 8, 26, 10, 0)),
                msg("user", "第三天", ms(2026, 8, 28, 10, 0)),
                msg("assistant", "第四天", ms(2026, 8, 30, 10, 0)),
                msg("user", "第五天", ms(2026, 9, 1, 10, 0)),
                msg("assistant", "第六天", ms(2026, 9, 3, 1, 0)),
            ),
            now = inst(2026, 9, 3, 2, 0),
        )
        val longOnes = regroundings(out)
        assertEquals("恰 2 条长版注记：$longOnes", 2, longOnes.size)
        assertTrue("倒数第二个缝（8月30日 → 9月1日）", longOnes[0].contains("以上对话发生在8月30日"))
        assertTrue("最后一个缝（9月1日 → 9月3日）", longOnes[1].contains("以上对话发生在9月1日"))
        // 更早的三个缝仍是短版（总分割线数 = 起始锚 1 + 5 个缝 = 6）。
        assertEquals(6, dividers(out).size)
    }

    @Test
    fun singleMessageHistory_doesNotCrash() {
        // E16：短期记忆设得极小（窗口内仅 1 条）→ 预扫命中 0 个边界，takeLast(2) 对空表安全。
        val out = build(
            listOf(msg("user", "在吗", ms(2026, 9, 3, 2, 34))),
            now = inst(2026, 9, 3, 2, 36),
        )
        assertTrue("单条历史零长版注记：${regroundings(out)}", regroundings(out).isEmpty())
    }

    @Test
    fun regroundingAtTail_strippedAsDanglingDivider() {
        // E9：长版注记落末尾（其后消息被 normalize 剥空）→ 与短版一样被 A1 悬空清理连带清掉。
        val out = build(
            listOf(
                msg("user", "在吗", ms(2026, 9, 1, 14, 0)),
                msg("assistant", "在的", ms(2026, 9, 1, 14, 1)),
                msg("assistant", "（她揉了揉眼睛打了个哈欠然后慢慢往沙发上靠过去整个人都困得不行了）", ms(2026, 9, 3, 2, 10)),
            ),
            now = inst(2026, 9, 3, 2, 17),
        )
        assertEquals(listOf("【时间 · 9月1日 周二 14:00】"), dividers(out))
    }

    @Test
    fun nonOnlineChatScene_noRegrounding() {
        // E10：非在线聊天（now=null 门控）→ 零分割线、零注记，预扫整体跳过。
        val strings = PromptStrings(RuntimeEnvironment.getApplication())
        val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)
        val out = PromptBuilder.buildMessages(
            character = character,
            sortedMessages = listOf(
                msg("assistant", "刚才试了新制服，想给你看~", ms(2026, 9, 1, 23, 10)),
                msg("user", "你睡了吗？", ms(2026, 9, 3, 2, 34)),
            ),
            userProfile = null,
            appSettings = AppSettings(),
            strings = strings,
            now = inst(2026, 9, 3, 2, 36),
            scene = PromptScene.VOICE_CALL,
        )
        assertTrue("非在线聊天零注记：${regroundings(out)}", regroundings(out).isEmpty())
        assertTrue("非在线聊天零分割线：${dividers(out)}", dividers(out).isEmpty())
    }

    @Test
    fun busyReplyScene_gatesDividerOff() {
        // A5：忙碌回复 / 语音通话等非「普通在线聊天」场景不插分割线（与「仅在线聊天」承诺一致）。
        val strings = PromptStrings(RuntimeEnvironment.getApplication())
        val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)
        val out = PromptBuilder.buildMessages(
            character = character,
            sortedMessages = listOf(
                msg("user", "在吗", ms(2026, 6, 25, 14, 56)),
                msg("user", "你看看几点了", ms(2026, 6, 26, 0, 15)),
            ),
            userProfile = null,
            appSettings = AppSettings(),
            strings = strings,
            now = inst(2026, 6, 26, 0, 17),
            scene = PromptScene.BUSY_REPLY,
        )
        assertTrue("非在线聊天场景应门控关闭分割线：${dividers(out)}", dividers(out).isEmpty())
    }
}
