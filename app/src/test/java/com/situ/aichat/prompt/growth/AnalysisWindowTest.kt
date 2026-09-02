package com.situ.aichat.prompt.growth

import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.entity.MessageEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1-5/6/7/8（活人感内核卷零图纸 §7.2）：成长分析取材窗口从「最近 200 条」改「按轮切」。
 *
 * 断言从图纸 §3.4 规格独立反推：
 * 窗口 = `{lastAnalysisDate 之后的全部}` ∪ `{之前的最后 4 轮}`；一轮以 `roleRaw == "user"` 为轮首；
 * fresh 超 300 条截断**保留最近**；首次分析（since=null）leadIn 空。
 */
class AnalysisWindowTest {

    private val messageDao = mockk<MessageDao>()

    private fun service() = GrowthAnalysisService(
        contextLog = mockk(relaxed = true), conversationDao = mockk(relaxed = true),
        messageDao = messageDao, scheduleDao = mockk(relaxed = true),
    )

    private fun msg(ts: Long, role: String = "user", content: String = "c$ts", offline: Boolean = false) = MessageEntity(
        messageUUID = "m$ts$role", conversationUuid = "conv-1", roleRaw = role,
        content = content, timestamp = ts, isOfflineMode = offline,
        offlineSessionId = if (offline) "sess-1" else null,
    )

    /** DAO 恒返回**倒序**（真 @Query 是 ORDER BY timestamp DESC），确保被测函数自己会排回升序。 */
    private fun stubDao(messages: List<MessageEntity>) {
        coEvery { messageDao.recentForCharacterAnalysis("c1", any()) } returns messages.sortedByDescending { it.timestamp }
    }

    /** 构造 [rounds] 轮，每轮 1 条 user + [aiPerRound] 条 assistant，时间戳自 [startTs] 起递增。 */
    private fun buildRounds(rounds: Int, aiPerRound: Int, startTs: Long = 1_000L): List<MessageEntity> {
        val out = mutableListOf<MessageEntity>()
        var ts = startTs
        repeat(rounds) {
            out.add(msg(ts++, role = "user"))
            repeat(aiPerRound) { out.add(msg(ts++, role = "assistant")) }
        }
        return out
    }

    // MARK: - T1-5 首次分析 / 零新内容 / 久别爆聊 / 时钟回拨

    @Test fun `首次分析 - leadIn 空且 fresh 为全部`() = runTest {
        val all = buildRounds(rounds = 5, aiPerRound = 2)
        stubDao(all)
        val window = service().collectAnalysisWindow("c1", sinceMillis = null)
        assertTrue(window.leadIn.isEmpty())
        assertEquals(all.size, window.fresh.size)
        assertEquals(all.map { it.timestamp }, window.fresh.map { it.timestamp }) // 升序
    }

    @Test fun `零新消息 - fresh 为空（调用方据此跳过 LLM）`() = runTest {
        val all = buildRounds(rounds = 5, aiPerRound = 2)
        stubDao(all)
        val window = service().collectAnalysisWindow("c1", sinceMillis = all.last().timestamp)
        assertTrue(window.fresh.isEmpty())
    }

    @Test fun `时钟回拨 - lastAnalysisDate 在未来则 fresh 为空且不崩`() = runTest {
        val all = buildRounds(rounds = 5, aiPerRound = 2)
        stubDao(all)
        val window = service().collectAnalysisWindow("c1", sinceMillis = all.last().timestamp + 10_000_000L)
        assertTrue(window.fresh.isEmpty())
    }

    @Test fun `久别爆聊 - fresh 超 300 条截断保留最近`() = runTest {
        // 560 条新消息（1 轮 user + 1 条 ai = 280 轮），since 落在最早一条之前
        val all = buildRounds(rounds = 280, aiPerRound = 1)
        assertEquals(560, all.size)
        stubDao(all)
        val window = service().collectAnalysisWindow("c1", sinceMillis = all.first().timestamp - 1)
        assertEquals(300, window.fresh.size)
        assertEquals(all.last().timestamp, window.fresh.last().timestamp)          // 保留的是最近端
        assertEquals(all[all.size - 300].timestamp, window.fresh.first().timestamp)
    }

    // MARK: - T1-6 lastNRounds 三态

    @Test fun `lastNRounds - 足 4 轮时只取末 4 轮`() {
        val six = buildRounds(rounds = 6, aiPerRound = 2) // 每轮 3 条
        val tail = lastNRounds(six, 4)
        assertEquals(12, tail.size)
        assertEquals(4, tail.count { it.roleRaw == "user" })
        assertEquals(six.takeLast(12).map { it.timestamp }, tail.map { it.timestamp })
    }

    @Test fun `lastNRounds - 只有 2 轮时返回全部`() {
        val two = buildRounds(rounds = 2, aiPerRound = 3)
        assertEquals(two.map { it.timestamp }, lastNRounds(two, 4).map { it.timestamp })
    }

    @Test fun `lastNRounds - 无 user 消息时返回空（无从切轮）`() {
        val onlyAi = listOf(msg(1, "assistant"), msg(2, "assistant"), msg(3, "assistant"))
        assertTrue(lastNRounds(onlyAi, 4).isEmpty())
    }

    @Test fun `lastNRounds - 轮首之前的角色连发不被算进上一轮`() {
        // assistant 连发 3 条 → 属同一轮；末 1 轮 = 最后那条 user 起的全部
        val messages = listOf(
            msg(1, "user"), msg(2, "assistant"), msg(3, "assistant"),
            msg(4, "user"), msg(5, "assistant"), msg(6, "assistant"), msg(7, "assistant"),
        )
        assertEquals(listOf(4L, 5L, 6L, 7L), lastNRounds(messages, 1).map { it.timestamp })
    }

    // MARK: - T1-6b 前置段接进窗口

    @Test fun `leadIn 取上次分析之前的末 4 轮`() = runTest {
        val all = buildRounds(rounds = 10, aiPerRound = 2) // 30 条，每轮 3 条
        stubDao(all)
        val since = all[17].timestamp // 前 6 轮（18 条）算旧
        val window = service().collectAnalysisWindow("c1", sinceMillis = since)
        assertEquals(4, window.leadIn.count { it.roleRaw == "user" })
        assertEquals(12, window.leadIn.size)
        assertEquals(12, window.fresh.size)
        assertEquals(24, window.all.size)
        assertTrue("leadIn 必须全在 since 之前", window.leadIn.all { it.timestamp <= since })
        assertTrue("fresh 必须全在 since 之后", window.fresh.all { it.timestamp > since })
    }

    @Test fun `older 段不足 4 轮时 leadIn 就是那几轮`() = runTest {
        val all = buildRounds(rounds = 5, aiPerRound = 2)
        stubDao(all)
        val since = all[5].timestamp // 前 2 轮（6 条）算旧
        val window = service().collectAnalysisWindow("c1", sinceMillis = since)
        assertEquals(2, window.leadIn.count { it.roleRaw == "user" })
        assertEquals(6, window.leadIn.size)
    }

    // MARK: - T1-7 ⭐ 与「AI 回复条数」设置解耦（这条正是换窗口的理由）

    @Test fun `同样 30 轮 - 回复条数 2 与 6 的 fresh 轮数相同条数不同`() = runTest {
        val a = buildRounds(rounds = 30, aiPerRound = 2) // 每轮 3 条 → 90
        val b = buildRounds(rounds = 30, aiPerRound = 6) // 每轮 7 条 → 210
        assertEquals(90, a.size)
        assertEquals(210, b.size)

        stubDao(a)
        val wa = service().collectAnalysisWindow("c1", sinceMillis = a.first().timestamp - 1)
        stubDao(b)
        val wb = service().collectAnalysisWindow("c1", sinceMillis = b.first().timestamp - 1)

        assertEquals(30, wa.fresh.count { it.roleRaw == "user" })
        assertEquals(30, wb.fresh.count { it.roleRaw == "user" }) // 轮数相同 ⇐ 取材与设置解耦
        assertEquals(90, wa.fresh.size)
        assertEquals(210, wb.fresh.size)                          // 条数不同
    }

    // MARK: - T1-8 线下剥标签与旧函数逐字节一致

    @Test fun `线下行剥标签结果与 collectMessagesForAnalysis 完全一致`() = runTest {
        val offline = msg(100, "assistant", content = "[叙述]他站起身[对话]走吧", offline = true)
        val online = msg(101, "user", content = "[叙述]这是线上原文不该被动", offline = false)
        stubDao(listOf(offline, online))

        val viaWindow = service().collectAnalysisWindow("c1", sinceMillis = null).all

        // 同一批消息喂旧函数（per-conversation 路）
        val conversationDao = mockk<com.situ.aichat.data.local.dao.ConversationDao>()
        coEvery { conversationDao.getByCharacter("c1") } returns listOf(
            com.situ.aichat.data.local.entity.ConversationEntity(uuid = "conv-1", title = "t", characterUuid = "c1", creationDate = 0L),
        )
        coEvery { messageDao.recentForAnalysis("conv-1", any()) } returns listOf(offline, online)
        val viaLegacy = GrowthAnalysisService(
            contextLog = mockk(relaxed = true), conversationDao = conversationDao,
            messageDao = messageDao, scheduleDao = mockk(relaxed = true),
        ).collectMessagesForAnalysis("c1")

        assertEquals(viaLegacy, viaWindow)
        assertTrue("线下行标签必须被剥掉", viaWindow.first { it.isOfflineMode }.content.none { it == '[' })
        assertEquals("线上行必须字节不变", online.content, viaWindow.first { !it.isOfflineMode }.content)
    }
}
