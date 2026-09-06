package com.situ.aichat.prompt.memory

import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 场内前情提要协调（记忆改造二期·部件⑤·图纸 §3.2·T1-3/4 + T2-4/5/6/7）。companion 纯函数断言从锁定规格
 * 独立反推；MockK 整链假掉六依赖，formatMessages（真·脱敏收口）在 Robolectric 下跑（android DateFormat）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InSceneRecapCoordinatorTest {

    // ══════════ T1-3 recapDecision（判定纯函数）══════════

    @Test fun `recapDecision null when count not above retained (E5)`() {
        assertNull("count≤retained → 不生成", InSceneRecapCoordinator.recapDecision(count = 8, retained = 80, coveredCount = 0))
        assertNull("count==retained → 不生成", InSceneRecapCoordinator.recapDecision(count = 80, retained = 80, coveredCount = 0))
    }

    @Test fun `recapDecision null when dropped covered enough`() {
        // dropped=10 ≤ covered=15 → 覆盖足够，不生成。
        assertNull(InSceneRecapCoordinator.recapDecision(count = 90, retained = 80, coveredCount = 15))
    }

    /** E8（图纸 2026-09-06 七件 §5）：保留数 == 素材条数（原文一条没丢）→ 恒不生成，无论素材多长。 */
    @Test fun `recapDecision null when retained equals count (E8)`() {
        assertNull(InSceneRecapCoordinator.recapDecision(count = 150, retained = 150, coveredCount = 0))
        assertEquals(50, InSceneRecapCoordinator.recapDecision(count = 150, retained = 140, coveredCount = 0)?.cutIndex)
    }

    @Test fun `recapDecision cutIndex is min of count and dropped plus 40`() {
        // dropped=10 > covered=5 → cutIndex=min(90, 10+40)=50。
        assertEquals(50, InSceneRecapCoordinator.recapDecision(90, 80, 5)?.cutIndex)
        // dropped=50 → cutIndex=min(130, 90)=90。
        assertEquals(90, InSceneRecapCoordinator.recapDecision(130, 80, 0)?.cutIndex)
        // dropped+40 超过 count（小 cap）→ cutIndex 钳到 count：dropped=10, min(20, 50)=20。
        assertEquals(20, InSceneRecapCoordinator.recapDecision(20, 10, 0)?.cutIndex)
    }

    // ══════════ T1-4 trailingCallBlock / currentCallBlockKey ══════════

    private fun callMsg(id: String, ts: Long, call: Boolean) =
        MessageEntity(messageUUID = id, conversationUuid = "conv1", roleRaw = "user", content = "c$id", timestamp = ts, isPartOfVoiceCall = call)

    @Test fun `trailingCallBlock takes trailing consecutive call run`() {
        val list = listOf(
            callMsg("a", 1, false),
            callMsg("b", 2, true),   // 中间通话（被后面非通话打断 → 不算尾块）
            callMsg("c", 3, false),
            callMsg("d", 4, true),   // 尾部连续通话段起
            callMsg("e", 5, true),
        )
        val block = InSceneRecapCoordinator.trailingCallBlock(list)
        assertEquals(listOf("d", "e"), block.map { it.messageUUID })
    }

    @Test fun `trailingCallBlock empty when history empty or no trailing call`() {
        assertTrue(InSceneRecapCoordinator.trailingCallBlock(emptyList()).isEmpty())
        assertTrue(InSceneRecapCoordinator.trailingCallBlock(listOf(callMsg("a", 1, true), callMsg("b", 2, false))).isEmpty())
    }

    @Test fun `currentCallBlockKey is call prefix plus first ts of trailing block`() {
        val list = listOf(callMsg("a", 10, false), callMsg("d", 40, true), callMsg("e", 50, true))
        assertEquals("call:40", InSceneRecapCoordinator.currentCallBlockKey(list))
        assertNull(InSceneRecapCoordinator.currentCallBlockKey(emptyList()))
        assertNull(InSceneRecapCoordinator.currentCallBlockKey(listOf(callMsg("a", 10, false))))
    }

    /**
     * T2-6（图纸 2026-09-06 七件 §4.1 逐字锁定·两态）：整串在此**重新打字**为字面量，与实现比对
     * ——实现改一个字（含标点与全半角）即挂。
     */
    @Test fun `buildRecapPrompt matches locked text in both old-recap states`() {
        val head = "你是剧情记录员。下面是一场正在进行的线下见面里较早部分的对话记录"
        val body = "。请把这些内容浓缩成一段新的前情提要，供角色在后续对话里回忆用。第三人称、按时间顺序，" +
            "提到两人时用「夏晴子」「小明」的名字（不要写「用户」「角色」）。按下面三个小节写，每节一到三句，没有内容的小节写「无」：\n" +
            "经过：发生了什么、去了哪、聊到的要点\n" +
            "说定的事：双方答应的、约好的、承诺的\n" +
            "情绪走向：两人情绪怎么变化、现在停在什么状态\n" +
            "总长不超过 800 字。只输出这三个小节（含小节名），不要额外标题、解释或前后缀。"

        val without = InSceneRecapCoordinator.buildRecapPrompt("线下见面", oldRecap = "", chunkText = "记录A", charName = "夏晴子", userName = "小明")
        assertEquals(head + body + "\n\n较早部分的记录：\n记录A", without)
        assertFalse("空 oldRecap 不含已有前情提要块", without.contains("已有前情提要"))
        assertFalse("空 oldRecap 首行不含追加从句", without.contains("以及此前已写好的前情提要"))

        val withOld = InSceneRecapCoordinator.buildRecapPrompt("线下见面", oldRecap = "旧提要", chunkText = "记录A", charName = "夏晴子", userName = "小明")
        assertEquals(head + "，以及此前已写好的前情提要" + body + "\n\n已有前情提要：\n旧提要\n\n较早部分的记录：\n记录A", withOld)

        // 场景词插值位（通话侧共用同一串）。
        val call = InSceneRecapCoordinator.buildRecapPrompt("语音通话", oldRecap = "", chunkText = "记录B", charName = "团子", userName = "阿珍")
        assertTrue(call.startsWith("你是剧情记录员。下面是一场正在进行的语音通话里较早部分的对话记录。"))
        assertTrue("命名要求含双名字", call.contains("提到两人时用「团子」「阿珍」的名字（不要写「用户」「角色」）"))
    }

    @Test fun `recap header literal unchanged (E5)`() {
        // 强耦合红线：【前情提要】注入标题 ↔ DirtyMessageDetector·本图纸零碰。
        assertEquals("【前情提要】", InSceneRecapCoordinator.RECAP_HEADER)
    }

    // ══════════ T2-4/5/6/7 MockK 整链 ══════════

    private lateinit var conversationRepo: ConversationRepository
    private lateinit var characterRepo: CharacterRepository
    private lateinit var messageRepo: MessageRepository
    private lateinit var apiConfigRepo: ApiConfigRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var contextLog: ContextLogService
    private lateinit var userProfileDao: UserProfileDao
    private lateinit var coordinator: InSceneRecapCoordinator

    private val config = mockk<ApiConfigValues>(relaxed = true)
    private val promptSlot = slot<List<ChatMessageDto>>()

    @Before fun setUp() {
        conversationRepo = mockk(relaxed = true)
        characterRepo = mockk(relaxed = true)
        messageRepo = mockk(relaxed = true)
        apiConfigRepo = mockk(relaxed = true)
        settingsRepo = mockk(relaxed = true)
        contextLog = mockk(relaxed = true)
        userProfileDao = mockk(relaxed = true)
        coordinator = InSceneRecapCoordinator(conversationRepo, characterRepo, messageRepo, apiConfigRepo, settingsRepo, contextLog, userProfileDao)

        coEvery { characterRepo.get(any()) } returns CharacterEntity(uuid = "c1", name = "小满", creationDate = 0L)
        coEvery { apiConfigRepo.resolveConfigValues(any()) } returns config
        // 默认 shortTermMemoryLength=11 → 通话保留上限 44（>40，令 cutIndex 可小于 count）；见面走字符预算（见 [meetingMsgs]）。
        coEvery { settingsRepo.getAppSettings() } returns AppSettings(shortTermMemoryLength = 11)
        coEvery { contextLog.completion(any(), any(), any(), capture(promptSlot), any(), any(), any(), any(), any()) } returns "压缩后的前情提要"
    }

    private fun convo(key: String = "", text: String = "", until: Long = 0L, sessionId: String? = "s1") =
        ConversationEntity(
            uuid = "conv1", title = "t", characterUuid = "c1", creationDate = 0L,
            isInOfflineMode = true, currentOfflineSessionId = sessionId,
            inSceneRecapSessionKey = key, inSceneRecapText = text, inSceneRecapUntilMillis = until,
        )

    /**
     * 一场见面消息：timestamp = 1..n（cutTs 即 cutIndex 的序号），可选 kind。
     * 每条 [CJK_PER_MSG] 字：见面块改按字符预算保留后（图纸 2026-09-06 七件 §3.B/F），
     * 20,000 预算 ÷ 450 = 44 条 → 与本组既有例的保留数 44 同值，下方 dropped=6 / cutIndex=46 的算式原样成立。
     */
    private fun meetingMsgs(n: Int, kind: String = MessageKind.PLAIN_TEXT.raw) =
        (1..n).map { i ->
            MessageEntity(
                messageUUID = "m$i", conversationUuid = "conv1",
                roleRaw = if (i % 2 == 1) "user" else "assistant", content = "内容$i".padEnd(CJK_PER_MSG, '字'),
                timestamp = i.toLong(), isOfflineMode = true, offlineSessionId = "s1", messageKindRaw = kind,
            )
        }

    /** 见面例每条消息的字数（见 [meetingMsgs]）。 */
    private val CJK_PER_MSG = 450

    // ── T2-4 见面路径整链 ──

    @Test fun `meeting over cap generates and writes column-level with correct text key cutTs`() = runTest {
        coEvery { conversationRepo.get("conv1") } returns convo()
        coEvery { messageRepo.offlineSessionMessages("conv1", "s1") } returns meetingMsgs(50)

        coordinator.checkMeetingRecap("conv1")

        // count=50, retained=44（20,000 预算 ÷ 450 字）, dropped=6, covered=0 → cutIndex=min(50, 46)=46 → cutTs=material[45].ts=46。
        coVerify(exactly = 1) { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { conversationRepo.updateInSceneRecap("conv1", "压缩后的前情提要", "s1", 46L) }
    }

    @Test fun `meeting below cap does not call LLM (E5)`() = runTest {
        coEvery { conversationRepo.get("conv1") } returns convo()
        coEvery { messageRepo.offlineSessionMessages("conv1", "s1") } returns meetingMsgs(40) // 40×450=18,000 < 预算 → 一条没丢

        coordinator.checkMeetingRecap("conv1")

        coVerify(exactly = 0) { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { conversationRepo.updateInSceneRecap(any(), any(), any(), any()) }
    }

    @Test fun `meeting guard skips when not in offline mode or null session`() = runTest {
        coEvery { conversationRepo.get("conv1") } returns convo(sessionId = null)
        coordinator.checkMeetingRecap("conv1")
        coVerify(exactly = 0) { messageRepo.offlineSessionMessages(any(), any()) }
        coVerify(exactly = 0) { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    // ── T2-5 空返回 / 超长 / chunk 空白 ──

    @Test fun `empty LLM response is discarded, no write (E6)`() = runTest {
        coEvery { conversationRepo.get("conv1") } returns convo()
        coEvery { messageRepo.offlineSessionMessages("conv1", "s1") } returns meetingMsgs(50)
        coEvery { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns "   "

        coordinator.checkMeetingRecap("conv1")

        coVerify(exactly = 1) { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { conversationRepo.updateInSceneRecap(any(), any(), any(), any()) }
    }

    /** E22（图纸 2026-09-06 七件 §5）：超 MAX_RECAP_CJK=1600 的返回 → 丢弃，旧值与水位不动。 */
    @Test fun `over-long LLM response is discarded, no write (E22)`() = runTest {
        coEvery { conversationRepo.get("conv1") } returns convo()
        coEvery { messageRepo.offlineSessionMessages("conv1", "s1") } returns meetingMsgs(50)
        coEvery { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns "国".repeat(1601)

        coordinator.checkMeetingRecap("conv1")

        coVerify(exactly = 0) { conversationRepo.updateInSceneRecap(any(), any(), any(), any()) }
    }

    /** E23：801–1600 字（提示词软上限 800 之上、硬防线之内）→ 照常落库。 */
    @Test fun `response above soft limit but under hard guard is accepted (E23)`() = runTest {
        val text = "国".repeat(801)
        coEvery { conversationRepo.get("conv1") } returns convo()
        coEvery { messageRepo.offlineSessionMessages("conv1", "s1") } returns meetingMsgs(50)
        coEvery { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns text

        coordinator.checkMeetingRecap("conv1")

        coVerify(exactly = 1) { conversationRepo.updateInSceneRecap("conv1", text, "s1", 46L) }
    }

    @Test fun `blank chunk skips LLM entirely (E14)`() = runTest {
        // 素材全为通话记录卡 → messageLlmSafeText 恒 null → formatMessages 为空白 → 不调 LLM、不写库。
        coEvery { conversationRepo.get("conv1") } returns convo()
        coEvery { messageRepo.offlineSessionMessages("conv1", "s1") } returns
            meetingMsgs(50, kind = MessageKind.CALL_RECORD_CARD.raw)

        coordinator.checkMeetingRecap("conv1")

        coVerify(exactly = 0) { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { conversationRepo.updateInSceneRecap(any(), any(), any(), any()) }
    }

    // ── T2-6 key 不匹配 → oldRecap 不入提示词、整组覆写（E7）──

    @Test fun `mismatched key drops old recap from prompt and overwrites whole group (E7)`() = runTest {
        // 库里挂着旧场（key=OLD）的旧提要与旧水位；本场 key=s1 不匹配 → oldRecap 不入提示词、covered=0、整组覆写。
        coEvery { conversationRepo.get("conv1") } returns convo(key = "OLD", text = "旧场的提要正文", until = 999L)
        coEvery { messageRepo.offlineSessionMessages("conv1", "s1") } returns meetingMsgs(50)

        coordinator.checkMeetingRecap("conv1")

        val prompt = promptSlot.captured.single().content.orEmpty()
        assertFalse("旧提要正文不得进提示词", prompt.contains("旧场的提要正文"))
        assertFalse("不得含已有前情提要块", prompt.contains("已有前情提要"))
        // covered=0（key 不匹配）→ dropped=6 → cutIndex=46 → 整组覆写为本场 key。
        coVerify(exactly = 1) { conversationRepo.updateInSceneRecap("conv1", "压缩后的前情提要", "s1", 46L) }
    }

    @Test fun `matched key feeds old recap into prompt`() = runTest {
        coEvery { conversationRepo.get("conv1") } returns convo(key = "s1", text = "上次的提要", until = 0L)
        coEvery { messageRepo.offlineSessionMessages("conv1", "s1") } returns meetingMsgs(50)

        coordinator.checkMeetingRecap("conv1")

        val prompt = promptSlot.captured.single().content.orEmpty()
        assertTrue("key 匹配 → 旧提要入提示词", prompt.contains("已有前情提要：\n上次的提要"))
    }

    // ── T2-7 单飞 + 冷却（E11）──

    @Test fun `single-flight skips concurrent second call (E11)`() = runTest {
        coEvery { conversationRepo.get("conv1") } returns convo()
        coEvery { messageRepo.offlineSessionMessages("conv1", "s1") } returns meetingMsgs(50)
        coEvery { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            delay(50); "压缩后的前情提要"
        }

        val j1 = launch { coordinator.checkMeetingRecap("conv1") }
        val j2 = launch { coordinator.checkMeetingRecap("conv1") }
        j1.join(); j2.join()

        coVerify(exactly = 1) { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `cooldown skips a second sequential call within 120s (E11)`() = runTest {
        coEvery { conversationRepo.get("conv1") } returns convo()
        coEvery { messageRepo.offlineSessionMessages("conv1", "s1") } returns meetingMsgs(50)

        coordinator.checkMeetingRecap("conv1") // 首次尝试记冷却
        coordinator.checkMeetingRecap("conv1") // 120s 内 → 冷却跳过

        coVerify(exactly = 1) { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    // ── 通话路径整链（call key 写回）──

    @Test fun `call path writes recap keyed by call block key`() = runTest {
        coEvery { conversationRepo.get("conv1") } returns convo(sessionId = null) // 通话与见面态无关
        // 50 条尾部连续通话消息，timestamp 1..50。
        val calls = (1..50).map { i ->
            MessageEntity(messageUUID = "v$i", conversationUuid = "conv1", roleRaw = if (i % 2 == 1) "user" else "assistant", content = "话$i", timestamp = i.toLong(), isPartOfVoiceCall = true)
        }
        coEvery { messageRepo.recentChronological("conv1", 500) } returns calls

        coordinator.checkCallRecap("conv1")

        // callKey = "call:1"（尾块首条 ts）；cutTs=46（同 meeting 数学）。
        coVerify(exactly = 1) { conversationRepo.updateInSceneRecap("conv1", "压缩后的前情提要", "call:1", 46L) }
    }
}
