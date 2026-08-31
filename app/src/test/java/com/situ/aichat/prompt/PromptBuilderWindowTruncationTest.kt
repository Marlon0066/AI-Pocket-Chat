package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 历史窗口截断核心算法 T1（批3 3-11·此前零覆盖）：[truncateToRecentRounds] 的轮计数 / 特殊块整段算 1 轮 /
 * 块内条数上限 / 半回合边界（窗口最旧端是 assistant=已知既有行为，钉住防漂移），[calculateEffectiveMemoryLength]
 * 动态扩窗钳位，[prepareFilteredRecentMessages] 的原文通道退役（普通聊天只注入见面总结）+ 记忆改造二期见面去重
 * （见面模式窗口只保留本场见面原文·旧场排除·sessionId null 兜底·E1/E15）。断言从规格反推，非照搬实现。
 */
class PromptBuilderWindowTruncationTest {

    private var ts = 0L

    private fun msg(
        role: String,
        id: String,
        call: Boolean = false,
        offline: Boolean = false,
        session: String? = null,
        timestamp: Long = ++ts,
        /** 留痕改造 2026-08-31 新增（默认 plain_text = 既有例行为逐字不变）。 */
        kind: MessageKind = MessageKind.PLAIN_TEXT,
    ) = MessageEntity(
        messageUUID = id,
        conversationUuid = "c1",
        roleRaw = role,
        content = "内容$id",
        timestamp = timestamp,
        isPartOfVoiceCall = call,
        isOfflineMode = offline,
        offlineSessionId = session,
        messageKindRaw = kind.raw,
    )

    private fun ids(list: List<MessageEntity>) = list.map { it.messageUUID }

    @Test
    fun `maxRounds为0返回空`() {
        assertEquals(emptyList<MessageEntity>(), truncateToRecentRounds(listOf(msg("user", "u1")), maxRounds = 0))
    }

    @Test
    fun `按轮截断_保最新N轮_窗口最旧端半回合为既有行为`() {
        // 4 轮完整对话（u a × 4），maxRounds=3。
        val messages = listOf(
            msg("user", "u1"), msg("assistant", "a1"),
            msg("user", "u2"), msg("assistant", "a2"),
            msg("user", "u3"), msg("assistant", "a3"),
            msg("user", "u4"), msg("assistant", "a4"),
        )
        val kept = truncateToRecentRounds(messages, maxRounds = 3)
        // 已知既有行为：达到 maxRounds 时把边界那条 assistant 收进来后停——窗口最旧端是「半个回合」（a1 无 u1）。
        assertEquals(listOf("a1", "u2", "a2", "u3", "a3", "u4", "a4"), ids(kept))
    }

    @Test
    fun `通话整段算1轮_maxRounds1时保留块加末轮`() {
        val messages = listOf(
            msg("user", "u1"), msg("assistant", "a1"),
            msg("user", "c1", call = true), msg("assistant", "c2", call = true),
            msg("user", "c3", call = true), msg("assistant", "c4", call = true),
            msg("user", "u2"), msg("assistant", "a2"),
        )
        val kept = truncateToRecentRounds(messages, maxRounds = 1)
        // 通话块=1 轮：数满即停，更早的 u1/a1 裁掉；块本身与其后的最新聊天保留。
        assertEquals(listOf("c1", "c2", "c3", "c4", "u2", "a2"), ids(kept))
    }

    @Test
    fun `特殊块内条数上限_只保最近4xMaxRounds条并报截断`() {
        val callMsgs = (1..6).map { msg(if (it % 2 == 1) "user" else "assistant", "c$it", call = true) }
        val tail = listOf(msg("user", "u9"), msg("assistant", "a9"))
        val notes = mutableListOf<String>()
        val kept = truncateToRecentRounds(callMsgs + tail, maxRounds = 1, onTruncation = { notes.add(it) })
        // specialBlockLimit = 1×4 = 4 → 只留块内最近 4 条（c3..c6），并产出省略提示。
        assertEquals(listOf("c3", "c4", "c5", "c6", "u9", "a9"), ids(kept))
        assertTrue(notes.single().contains("通话") && notes.single().contains("4"))
    }

    @Test
    fun `isIncluded排除的消息不进结果但仍占角色位`() {
        val messages = listOf(
            msg("user", "u1"), msg("assistant", "a1"),
            msg("user", "u2"), msg("assistant", "a2"),
        )
        val kept = truncateToRecentRounds(messages, maxRounds = 10, isIncluded = { it.messageUUID != "u2" })
        assertEquals(listOf("u1", "a1", "a2"), ids(kept))
    }

    @Test
    fun `动态扩窗_未总结轮数封顶基准两倍`() {
        val settings = AppSettings() // 默认 shortTermMemoryLength=20
        val base = settings.shortTermMemoryLength
        assertEquals(base, calculateEffectiveMemoryLength(settings, 0))
        assertEquals(base + 10, calculateEffectiveMemoryLength(settings, 10))
        assertEquals(base * 2, calculateEffectiveMemoryLength(settings, 10_000))
    }

    @Test
    fun `原文通道退役_非线下时见面原文一律不进窗口（T2-4）`() {
        // §3.6 原文通道退役：普通聊天只注入见面【总结】（{{见面记忆}} 宏），见面原文消息一律不进窗口。
        val now = Instant.ofEpochMilli(10L * 86_400_000L)
        val fresh = msg("assistant", "m-fresh", offline = true, timestamp = now.toEpochMilli() - 3_600_000L)
        val stale = msg("assistant", "m-stale", offline = true, timestamp = now.toEpochMilli() - 3L * 86_400_000L)
        val chat = listOf(
            msg("user", "u1", timestamp = now.toEpochMilli() - 60_000L),
            msg("assistant", "a1", timestamp = now.toEpochMilli() - 50_000L),
        )
        val (filtered, _, _) = prepareFilteredRecentMessages(
            sortedMessages = (listOf(stale, fresh) + chat).sortedBy { it.timestamp },
            appSettings = AppSettings(),
            isCurrentlyInOfflineMode = false,
            currentOfflineSessionId = null,
            unsummarizedRoundsOutsideBaseWindow = 0,
            now = now,
        )
        val keptIds = ids(filtered)
        assertTrue("见面原文消息不应进窗口（新旧都不）", "m-fresh" !in keptIds && "m-stale" !in keptIds)
        assertFalse("结果不含任何 isOfflineMode 消息", filtered.any { it.isOfflineMode })
        assertTrue("普通聊天消息仍在", "u1" in keptIds && "a1" in keptIds)
    }

    @Test
    fun `见面中去重_只保本场见面原文_旧场排除_普通与通话保留（T1-6·E1）`() {
        // 记忆改造二期见面去重（有意行为变化）：见面模式窗口只保留本场（offlineSessionId==current）见面原文，
        // 旧场见面原文排除（旧见面知识由【见面 · 】档案卡承担）；普通聊天（isOfflineMode=false）与通话消息一律保留。
        val chat1 = msg("user", "u1")
        val call1 = msg("assistant", "call1", call = true)
        val old1 = msg("assistant", "old1", offline = true, session = "s0")
        val cur1 = msg("assistant", "cur1", offline = true, session = "s1")
        val cur2 = msg("assistant", "cur2", offline = true, session = "s1")
        val (filtered, _, _) = prepareFilteredRecentMessages(
            sortedMessages = listOf(chat1, call1, old1, cur1, cur2).sortedBy { it.timestamp },
            appSettings = AppSettings(),
            isCurrentlyInOfflineMode = true,
            currentOfflineSessionId = "s1",
            unsummarizedRoundsOutsideBaseWindow = 0,
            now = Instant.ofEpochMilli(0),
        )
        val keptIds = ids(filtered)
        assertTrue("本场见面原文应保留", "cur1" in keptIds && "cur2" in keptIds)
        assertFalse("旧场见面原文应排除", "old1" in keptIds)
        assertTrue("普通聊天与通话消息应保留", "u1" in keptIds && "call1" in keptIds)
    }

    @Test
    fun `见面中sessionId为null_全部见面原文出窗_普通保留_兜底不崩（T1-6·E15）`() {
        // 异常见面态 currentOfflineSessionId==null：去重谓词兜底为「全部见面原文出窗」，等价普通分支语义，不崩。
        val chat1 = msg("user", "u1")
        val meet1 = msg("assistant", "off1", offline = true, session = "s1")
        val (filtered, _, _) = prepareFilteredRecentMessages(
            sortedMessages = listOf(chat1, meet1).sortedBy { it.timestamp },
            appSettings = AppSettings(),
            isCurrentlyInOfflineMode = true,
            currentOfflineSessionId = null,
            unsummarizedRoundsOutsideBaseWindow = 0,
            now = Instant.ofEpochMilli(0),
        )
        val keptIds = ids(filtered)
        assertFalse("sessionId null：见面原文一律出窗", "off1" in keptIds)
        assertTrue("普通聊天仍保留", "u1" in keptIds)
    }

    @Test
    fun `留痕改造_普通聊天保留邀约卡与离场标记_无kind见面原文仍出窗（T2-1）`() {
        // 有意行为变化①：邀约卡（非 offline 消息）与离场标记（isOfflineMode=true + kind）放行进窗口，
        // 由历史装配改写成 [系统记录：…] 留痕行；见面对话原文（无 kind）照旧一律出窗——语义收窄，非退回原文通道。
        val invite = msg("assistant", "invite", kind = MessageKind.OFFLINE_INVITE_CARD)
        val meetingRaw = msg("assistant", "raw", offline = true, session = "s1")
        val markerEnd = msg("assistant", "end", offline = true, session = "s1", kind = MessageKind.OFFLINE_MARKER_END)
        val chat = msg("user", "u1")
        val (filtered, _, _) = prepareFilteredRecentMessages(
            sortedMessages = listOf(invite, meetingRaw, markerEnd, chat).sortedBy { it.timestamp },
            appSettings = AppSettings(),
            isCurrentlyInOfflineMode = false,
            currentOfflineSessionId = null,
            unsummarizedRoundsOutsideBaseWindow = 0,
            now = Instant.ofEpochMilli(0),
        )
        val keptIds = ids(filtered)
        assertTrue("邀约卡应留在窗口（供改写成留痕行）", "invite" in keptIds)
        assertTrue("离场标记应留在窗口（供改写成留痕行）", "end" in keptIds)
        assertFalse("见面对话原文仍一律出窗", "raw" in keptIds)
        assertTrue("普通聊天消息仍在", "u1" in keptIds)
    }

    @Test
    fun `留痕改造_见面中旧场离场标记仍出窗（T2-1·E6）`() {
        // 见面【中】分支零改：旧场消息（含离场标记）由 session 过滤挡在窗外，行为与现状一致；
        // 本场离场标记本不该存在于进行中的见面，故此处只钉旧场。
        val oldEnd = msg("assistant", "old-end", offline = true, session = "s0", kind = MessageKind.OFFLINE_MARKER_END)
        val curRaw = msg("assistant", "cur", offline = true, session = "s1")
        val chat = msg("user", "u1")
        val (filtered, _, _) = prepareFilteredRecentMessages(
            sortedMessages = listOf(oldEnd, curRaw, chat).sortedBy { it.timestamp },
            appSettings = AppSettings(),
            isCurrentlyInOfflineMode = true,
            currentOfflineSessionId = "s1",
            unsummarizedRoundsOutsideBaseWindow = 0,
            now = Instant.ofEpochMilli(0),
        )
        val keptIds = ids(filtered)
        assertFalse("见面中：旧场离场标记不应进窗口", "old-end" in keptIds)
        assertTrue("本场见面原文仍保留", "cur" in keptIds)
        assertTrue("普通聊天仍保留", "u1" in keptIds)
    }
}
