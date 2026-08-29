package com.situ.aichat.offline

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.OfflineMeetingMemoryRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.notification.Notifier
import com.situ.aichat.proactive.ProactiveReplyDeliverer
import com.situ.aichat.prompt.memory.VectorMemoryService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [OfflineAfterglowService] 行为测试（T2-3·图纸 §7）——「见面后余温消息真能安全生成/落库/通知」（不止编译过）。
 *
 * 手法：MockK 假掉全部依赖 + [OfflineAfterglowPromptAssembler]（上下文装配另测/真机验，本类只验服务编排）；
 * db.withTransaction 走 mockkStatic 同步跑 block；Notifier/ProcessLifecycleOwner 走 mockkObject/mockkStatic
 * （默认 App 后台 → notify 可达）。覆盖：四道守卫各自短路 · E7 见面后用户已聊(守卫③) · E8 两次含【静默放弃 ·
 * 正常路径 coVerify 落库+通知各一次 · 重试后成功 · 超 120/思考标签剥离。
 */
class OfflineAfterglowServiceTest {

    private lateinit var context: Context
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var characterRepo: CharacterRepository
    private lateinit var memoryRepo: OfflineMeetingMemoryRepository
    private lateinit var apiConfigRepo: ApiConfigRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var messageRepo: MessageRepository
    private lateinit var vectorMemory: VectorMemoryService
    private lateinit var contextLog: ContextLogService
    private lateinit var db: AppDatabase
    private lateinit var assembler: OfflineAfterglowPromptAssembler
    private lateinit var userProfileDao: UserProfileDao
    private lateinit var service: OfflineAfterglowService

    private val character = CharacterEntity(uuid = "char", name = "小雨", creationDate = 0L)

    private fun convo(offline: Boolean = false) =
        ConversationEntity(uuid = "conv", title = "t", characterUuid = "char", creationDate = 0L, isInOfflineMode = offline)

    private fun row(endedAt: Long = 1_000L) = OfflineMeetingMemoryEntity(
        uuid = "r1", characterUuid = "char", conversationUuid = "conv", sessionId = "sess",
        startedAtMillis = 500L, endedAtMillis = endedAt, location = "咖啡馆", summary = "一起喝了咖啡，聊得很开心",
        createdAtMillis = 0L, updatedAtMillis = 0L,
    )

    private fun settings(afterglow: Boolean = true, notif: Boolean = true) =
        AppSettings(offlineAfterglowEnabled = afterglow, notificationsEnabled = notif)

    private fun lifecycleOwner(foreground: Boolean): LifecycleOwner {
        val lc = mockk<Lifecycle>()
        every { lc.currentState } returns if (foreground) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
        return mockk { every { lifecycle } returns lc }
    }

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        conversationRepo = mockk(relaxed = true)
        characterRepo = mockk(relaxed = true)
        memoryRepo = mockk(relaxed = true)
        apiConfigRepo = mockk(relaxed = true)
        settingsRepo = mockk(relaxed = true)
        messageRepo = mockk(relaxed = true)
        vectorMemory = mockk(relaxed = true)
        contextLog = mockk(relaxed = true)
        db = mockk()
        assembler = mockk()
        userProfileDao = mockk(relaxed = true) // 默认 get()=null → userName 兜底「用户」（既有用例不受影响）

        mockkStatic("androidx.room.RoomDatabaseKt") // Room withTransaction 扩展函数可桩（block 同步跑）
        coEvery { db.withTransaction<Unit>(any()) } coAnswers { secondArg<suspend () -> Unit>().invoke() }
        mockkObject(Notifier)
        every { Notifier.post(any(), any()) } returns Unit
        mockkObject(ProcessLifecycleOwner.Companion)
        every { ProcessLifecycleOwner.get() } returns lifecycleOwner(foreground = false) // App 后台 → notify 可达

        // 默认 = 全守卫通过的正常路径起点（各用例按需覆盖）。
        coEvery { settingsRepo.getAppSettings() } returns settings()
        coEvery { conversationRepo.get("conv") } returns convo()
        coEvery { characterRepo.get("char") } returns character
        coEvery { memoryRepo.bySessionId("sess") } returns row()
        coEvery { messageRepo.latestVisibleMessage("conv") } returns null
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.CHAT) } returns mockk(relaxed = true)
        coEvery { assembler.assemble(any(), any(), any(), any()) } returns
            listOf(ChatMessageDto(role = "system", content = "人设"))

        // 投递器用真实现例（依赖全 mock）——落库/分段/通知行为连同服务编排一起验。
        val deliverer = ProactiveReplyDeliverer(context, conversationRepo, messageRepo, vectorMemory, db)
        service = OfflineAfterglowService(
            conversationRepo, characterRepo, memoryRepo, apiConfigRepo, settingsRepo,
            messageRepo, contextLog, deliverer, assembler, userProfileDao,
        )
    }

    @After fun tearDown() = unmockkAll()

    private fun run() = runBlocking { service.maybeGenerate("conv", "char", "sess") }

    // completion 有默认参 → 生产侧经 completion$default 路由，MockK 记录的是 9 参虚方法调用；
    // 故 stub/verify 必须显式给全 9 个匹配器（否则 block 里又生成 $default 调用 → "Missing mocked calls"）。
    private fun stubLlm(vararg replies: String) {
        coEvery {
            contextLog.completion(eq(LogSource.OFFLINE_AFTERGLOW), any(), any(), any(), any(), any(), any(), any(), any())
        } returnsMany replies.toList()
    }

    private fun verifyCompletion(times: Int) =
        coVerify(exactly = times) {
            contextLog.completion(eq(LogSource.OFFLINE_AFTERGLOW), any(), any(), any(), any(), any(), any(), any(), any())
        }

    // ── 四道守卫各自短路 ──

    @Test fun guard1_disabled_skipsEntirely() {
        coEvery { settingsRepo.getAppSettings() } returns settings(afterglow = false)
        run()
        coVerify(exactly = 0) { conversationRepo.get(any()) } // 守卫①在会话查询之前短路
        verifyCompletion(0)
        coVerify(exactly = 0) { messageRepo.upsert(any()) }
    }

    @Test fun guard2_conversationMissing_skips() {
        coEvery { conversationRepo.get("conv") } returns null
        run()
        coVerify(exactly = 0) { memoryRepo.bySessionId(any()) } // 守卫②在取行之前短路
        verifyCompletion(0)
    }

    @Test fun guard2_stillInOfflineMode_skips() {
        coEvery { conversationRepo.get("conv") } returns convo(offline = true)
        run()
        coVerify(exactly = 0) { memoryRepo.bySessionId(any()) }
        verifyCompletion(0)
    }

    /**
     * 卷二 G2 顺移（图纸 §7 预裁决）：守卫④「无行」不再是静默跳过，而是判**摘要未熟**→ 返回 DEFER_SUMMARY
     * 交 worker 30 分钟后再看一眼。不发消息的旧断言原样保留，只把「了结方式」这一条断言顺移。
     */
    @Test fun guard4_rowMissing_defersForSummary() {
        coEvery { memoryRepo.bySessionId("sess") } returns null
        val outcome = run()
        assertEquals(OfflineAfterglowService.AfterglowOutcome.DEFER_SUMMARY, outcome)
        coVerify(exactly = 0) { messageRepo.latestVisibleMessage(any()) } // 守卫④在守卫③之前短路
        verifyCompletion(0)
        coVerify(exactly = 0) { messageRepo.upsert(any()) }
    }

    // ── E7：见面结束后用户已回来聊了（守卫③命中）→ 静默跳过 ──

    @Test fun e7_userChattedAfterMeeting_skipsSilently() {
        coEvery { memoryRepo.bySessionId("sess") } returns row(endedAt = 1_000L)
        // 见面后用户又聊了 5 轮 → 最新可见消息晚于离场标记时刻。
        coEvery { messageRepo.latestVisibleMessage("conv") } returns
            MessageEntity(messageUUID = "m", conversationUuid = "conv", roleRaw = "user", content = "又聊起别的", timestamp = 6_000L)
        run()
        verifyCompletion(0)
        coVerify(exactly = 0) { messageRepo.upsert(any()) }
        verify(exactly = 0) { Notifier.post(any(), any()) }
    }

    @Test fun guard3_latestAtMarkerEnd_proceeds() {
        // 离场标记时刻 == endedAtMillis（无后续聊天）→ 不算「聊了别的」→ 正常生成。
        coEvery { memoryRepo.bySessionId("sess") } returns row(endedAt = 1_000L)
        coEvery { messageRepo.latestVisibleMessage("conv") } returns
            MessageEntity(messageUUID = "end", conversationUuid = "conv", roleRaw = "assistant", content = "（离场）", timestamp = 1_000L)
        stubLlm("今天真开心，下次还想一起去～")
        run()
        verifyCompletion(1)
        coVerify(exactly = 1) { messageRepo.upsert(any()) }
    }

    // ── E8：两次输出都含【 → 静默放弃（无消息/无通知） ──

    @Test fun e8_bracketedTwice_givesUpSilently() {
        stubLlm("【叙述】走在回家的路上", "【对话】「今天真开心」")
        run()
        verifyCompletion(2) // 首次失败 → 重试 1 次
        coVerify(exactly = 0) { messageRepo.upsert(any()) }
        verify(exactly = 0) { Notifier.post(any(), any()) }
    }

    // ── 正常路径：落库 + 通知各一次 ──

    @Test fun happyPath_persistsAndNotifiesOnce() {
        stubLlm("今天在咖啡馆聊得好开心，回味了一路～")
        run()
        verifyCompletion(1)
        coVerify(exactly = 1) {
            messageRepo.upsert(match { it.roleRaw == "assistant" && !it.isOfflineMode && it.content == "今天在咖啡馆聊得好开心，回味了一路～" })
        }
        coVerify(exactly = 1) { conversationRepo.applyMaterialization(conversationUuid = eq("conv"), preview = any(), timestamp = any(), markReadNow = eq(false)) }
        verify(exactly = 1) { Notifier.post(any(), any()) }
    }

    @Test fun retryThenSucceeds_persistsOnce() {
        stubLlm("【叙述】不合格", "今天真的很开心呀") // 首次含【失败 → 第二次合格
        run()
        verifyCompletion(2)
        coVerify(exactly = 1) { messageRepo.upsert(any()) }
        verify(exactly = 1) { Notifier.post(any(), any()) }
    }

    @Test fun foreground_persistsButNoNotification() {
        every { ProcessLifecycleOwner.get() } returns lifecycleOwner(foreground = true) // App 前台 = 列表红点即提示
        stubLlm("今天真开心～")
        run()
        coVerify(exactly = 1) { messageRepo.upsert(any()) } // 消息照落
        verify(exactly = 0) { Notifier.post(any(), any()) } // 前台不弹
    }

    @Test fun over120Chars_rejectedThenGivesUp() {
        val long = "太".repeat(121)
        stubLlm(long, long)
        run()
        verifyCompletion(2)
        coVerify(exactly = 0) { messageRepo.upsert(any()) }
    }

    @Test fun thinkingTagsStripped_thenValid_persists() {
        stubLlm("<think>我该说点什么呢</think>今天真开心呀～") // 剥思考标签后合格
        run()
        verifyCompletion(1)
        coVerify(exactly = 1) { messageRepo.upsert(match { it.content == "今天真开心呀～" }) }
    }

    // ── 2026-07-07 修订①：system 指令带完整真实时间锚点（日期/星期/时段词·以真实时刻为准） ──

    @Test fun systemInstruction_carriesRealTimeAnchors() {
        val sent = mutableListOf<List<ChatMessageDto>>()
        coEvery {
            contextLog.completion(eq(LogSource.OFFLINE_AFTERGLOW), any(), any(), capture(sent), any(), any(), any(), any(), any())
        } returns "今天真开心～"
        run()
        val instruction = sent.last().last { it.role == "system" }.content.orEmpty()
        // 完整当前时刻行（TimeAnchorFormatter.formatCurrentMoment 口径：现在：yyyy-MM-dd 周X HH:mm（时段））
        assert(instruction.contains("现在：")) { instruction }
        assert(Regex("""现在：\d{4}-\d{2}-\d{2} 周[日一二三四五六] \d{2}:\d{2}（(清晨|上午|中午|下午|晚上|深夜)）""").containsMatchIn(instruction)) { instruction }
        // 见面起止均带相对日 + 时段词锚点（startedAt/endedAt=epoch 起点附近 → 「M月D日 周X HH:mm（时段）」形态）
        assert(Regex("""你们.+（(清晨|上午|中午|下午|晚上|深夜)）在咖啡馆见了面，.+（(清晨|上午|中午|下午|晚上|深夜)）结束""").containsMatchIn(instruction)) { instruction }
        // 防带偏指令在场
        assert(instruction.contains("以这个真实时刻为准")) { instruction }
    }

    // ── 图纸一·B6：余温指令用真实用户名（人称=角色仍「你」+ 用户名·空名兜底「用户」） ──

    @Test fun systemInstruction_addressesUserByName() {
        coEvery { userProfileDao.get() } returns UserProfileEntity(nickname = "小明")
        val sent = mutableListOf<List<ChatMessageDto>>()
        coEvery {
            contextLog.completion(eq(LogSource.OFFLINE_AFTERGLOW), any(), any(), capture(sent), any(), any(), any(), any(), any())
        } returns "今天真开心～"
        run()
        val instruction = sent.last().last { it.role == "system" }.content.orEmpty()
        assert(instruction.contains("请你主动给小明发一条见面后的余温消息")) { instruction }
        assert(!instruction.contains("请你主动给用户发")) { instruction }
    }

    @Test fun systemInstruction_noNickname_fallsBackToUser() {
        coEvery { userProfileDao.get() } returns null // 无昵称 → 兜底「用户」= 与旧行为一致
        val sent = mutableListOf<List<ChatMessageDto>>()
        coEvery {
            contextLog.completion(eq(LogSource.OFFLINE_AFTERGLOW), any(), any(), capture(sent), any(), any(), any(), any(), any())
        } returns "今天真开心～"
        run()
        val instruction = sent.last().last { it.role == "system" }.content.orEmpty()
        assert(instruction.contains("请你主动给用户发一条见面后的余温消息")) { instruction }
    }

    // ── 2026-07-07 修订②：与普通聊天同口径分段落库（多气泡·时间戳严格递增·预览取末段） ──

    @Test fun multiParagraphReply_splitsIntoMultipleBubbles() {
        stubLlm("咦，你那边是傍晚了吧\n\n下午的咖啡真的很香，现在想起来还在回味\n\n你到家了记得跟我说一声呀")
        run()
        val stored = mutableListOf<MessageEntity>()
        coVerify(atLeast = 2) { messageRepo.upsert(capture(stored)) }
        assert(stored.size >= 3) { "空行段落应各自成泡，实得 ${stored.size} 条：${stored.map { it.content }}" }
        assert(stored.none { it.content.contains("\n\n") }) { stored.map { it.content }.toString() }
        // 时间戳严格递增（顺序稳定）
        assert(stored.zipWithNext().all { (a, b) -> a.timestamp < b.timestamp }) { stored.map { it.timestamp }.toString() }
        // 会话预览取末段
        coVerify(exactly = 1) {
            conversationRepo.applyMaterialization(
                conversationUuid = eq("conv"),
                preview = match { it.contains("到家了记得跟我说一声") },
                timestamp = eq(stored.last().timestamp),
                markReadNow = eq(false),
            )
        }
        verify(exactly = 1) { Notifier.post(any(), any()) } // 多段仍只弹一条通知
    }

    @Test fun shortSingleSentence_staysOneBubble() {
        stubLlm("今天真的很开心呀")
        run()
        coVerify(exactly = 1) { messageRepo.upsert(match { it.content == "今天真的很开心呀" }) }
    }
}
