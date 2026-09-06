package com.situ.aichat.prompt.diary

import android.content.Context
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.MilestoneDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.local.entity.OpenLoopEntity
import com.situ.aichat.data.local.entity.OpenLoopType
import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.DiaryRepository
import com.situ.aichat.data.repository.OpenLoopRepository
import com.situ.aichat.data.repository.PromiseRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.util.DateFormatters
import com.situ.aichat.util.LocaleManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId

/**
 * T1+T2：交换日记（R4·契约 §2 F1）。笔友挑选纯函数 + 提示词哨兵序 + 解锁服务链
 * （幂等/解锁门/落库形态）——断言从规格独立反推。
 */
class DiaryExchangeTest {

    // MARK: - T1 自动笔友挑选（纯函数）

    private fun msg(conv: String, ts: Long) = mockk<MessageEntity>(relaxed = true) {
        every { conversationUuid } returns conv
        every { timestamp } returns ts
    }

    @Test fun `auto penpal - most messages wins, tie broken by latest message`() {
        val convToChar = mapOf("cv1" to "A", "cv2" to "B")
        // A 2 条 vs B 1 条 → A。
        assertEquals(
            "A",
            DiaryExchangeService.pickAutoPenpalUuid(
                listOf(msg("cv1", 10), msg("cv1", 20), msg("cv2", 30)),
                convToChar,
            ),
        )
        // 平手（各 1 条）→ 最近消息者 B。
        assertEquals(
            "B",
            DiaryExchangeService.pickAutoPenpalUuid(listOf(msg("cv1", 10), msg("cv2", 30)), convToChar),
        )
        // 解析不到角色的会话被忽略；全解析不到 → null。
        assertNull(DiaryExchangeService.pickAutoPenpalUuid(listOf(msg("ghost", 10)), emptyMap()))
    }

    @Test fun `auto penpal - same character across two conversations accumulates`() {
        val convToChar = mapOf("cv1" to "A", "cv2" to "A", "cv3" to "B")
        assertEquals(
            "A",
            DiaryExchangeService.pickAutoPenpalUuid(
                listOf(msg("cv1", 10), msg("cv2", 20), msg("cv3", 99)),
                convToChar,
            ),
        )
    }

    // MARK: - T1 提示词哨兵序

    private fun sentinelStrings() = DiaryExchangePromptStrings(
        intro = "intro<%1\$s|%2\$s>",
        setup = "setup<%1\$s>",
        task = "task<%1\$s,%1\$s>",   // 双 %1$s：验位置参数复用（真资源「和 %1$s 的相处」靠此）
        reqHeader = "REQH",
        reqSelf = "RS",
        reqStyle = "RSt<%1\$s>",      // reqStyle 现填 userName（真资源「对 %1$s 的在意」）
        reqWords = "RW",
        reqMoment = "RM",
        reqNotSocial = "RNS",
        reqNoPeek = "RNP",
        reqNoAi = "RNA",
        moodHeader = "MH",
        chatHeader = "CH",
        scheduleHeader = "SH",
        personaFrame = "PF",
        aboutUserHeader = "AU<%1\$s>",
        relationshipHeader = "RH<%1\$s>",
        phaseLine = "PL<%1\$s>",
        phaseNames = "P0|P1|P2|P3|P4",
        milestoneLine = "ML<%1\$s,%2\$s>",
        memoryHeader = "MEMH",
        currentTime = "CT<%1\$s>",
        outputOnly = "OO",
        moodOutputRule = "MOR",
        userMessage = "UM",
        userFallback = "FB",
    )

    @Test fun `exchange prompt - full section order, optional sections omitted when empty`() {
        val zone = ZoneId.of("UTC")
        val now = 1_700_000_000_000L
        val full = DiaryExchangePromptBuilder.build(
            strings = sentinelStrings(),
            characterName = "Nova", personality = "warm", systemPrompt = "SP", userName = "U",
            nowMillis = now, zone = zone,
            moodLine = "😊 开心", chatSummary = "CHAT", scheduleSummary = "SCHED",
        )
        // 默认 enrichment 全空 → 无丰富化段（另有 enrichment 专测）；currentTime 含周几。
        val dateStr = DateFormatters.yearMonthDayHourMinuteWithWeekday(now, zone)
        assertEquals(
            listOf(
                "intro<Nova|warm>", "setup<SP>", "",
                "task<U,U>", "",
                "MH", "😊 开心", "",
                "CH", "CHAT", "",
                "SH", "SCHED", "",
                "CT<$dateStr>", "",
                "REQH", "RS", "RSt<U>", "RW", "RM", "RNS", "RNP", "RNA", "",   // 要求段·底部·RSt 填 userName
                "OO", "MOR",
            ).joinToString("\n"),
            full,
        )
        val minimal = DiaryExchangePromptBuilder.build(
            strings = sentinelStrings(),
            characterName = "Nova", personality = "warm", systemPrompt = "", userName = "U",
            nowMillis = now, zone = zone,
            moodLine = "", chatSummary = "", scheduleSummary = "",
        )
        assertEquals(
            listOf(
                "intro<Nova|warm>", "",
                "task<U,U>", "",
                "CT<$dateStr>", "",
                "REQH", "RS", "RSt<U>", "RW", "RM", "RNS", "RNP", "RNA", "",
                "OO", "MOR",
            ).joinToString("\n"),
            minimal,
        )
    }

    // T1-3（图纸 §7·2026-09-05）：角色卡形参默认空 ⇒ 装配与该形参引入前**逐字相同**（B1/B6 回归钉）。
    @Test fun `exchange prompt - 不传角色卡时段序与形参引入前逐字相同`() {
        val zone = ZoneId.of("UTC")
        val now = 1_700_000_000_000L
        fun build(card: String? = null) = if (card == null) {
            DiaryExchangePromptBuilder.build(
                strings = sentinelStrings(),
                characterName = "Nova", personality = "warm", systemPrompt = "SP", userName = "U",
                nowMillis = now, zone = zone,
                moodLine = "😊", chatSummary = "CHAT", scheduleSummary = "SCHED",
            )
        } else {
            DiaryExchangePromptBuilder.build(
                strings = sentinelStrings(),
                characterName = "Nova", personality = "warm", systemPrompt = "SP", userName = "U",
                nowMillis = now, zone = zone,
                moodLine = "😊", chatSummary = "CHAT", scheduleSummary = "SCHED",
                characterCard = card,
            )
        }
        val dateStr = DateFormatters.yearMonthDayHourMinuteWithWeekday(now, zone)
        // 段序从图纸 §B3 独立反推：身份 → 设定 → 任务 → 心情 → 聊天 → 日程 → 时间 → 要求 → 输出规则。
        assertEquals(
            listOf(
                "intro<Nova|warm>", "setup<SP>", "",
                "task<U,U>", "",
                "MH", "😊", "",
                "CH", "CHAT", "",
                "SH", "SCHED", "",
                "CT<$dateStr>", "",
                "REQH", "RS", "RSt<U>", "RW", "RM", "RNS", "RNP", "RNA", "",
                "OO", "MOR",
            ).joinToString("\n"),
            build(),
        )
        assertEquals("显式传空卡片 = 不传", build(), build(""))
        // 显式传空覆盖 map 同样逐字相同（chunk 2 新形参的零变化钉）。
        assertEquals(
            build(),
            DiaryExchangePromptBuilder.build(
                strings = sentinelStrings(),
                characterName = "Nova", personality = "warm", systemPrompt = "SP", userName = "U",
                nowMillis = now, zone = zone,
                moodLine = "😊", chatSummary = "CHAT", scheduleSummary = "SCHED",
                overrides = emptyMap(),
            ),
        )
        // 有卡片时：插在 intro 之后、setup 之前（**插入**不重排）。
        val withCard = build("CARD1\nCARD2").lines()
        assertEquals(listOf("intro<Nova|warm>", "CARD1", "CARD2", "setup<SP>", ""), withCard.take(5))
    }

    // T1-4（图纸 §7·E9/C2）：四项覆盖各自落在要求段的对应行——人称/文风**整行替换**、字数进 `%1$s`、
    // 补充规则逐行追加在要求段末尾（空行跳过）；其余要求行、只输出正文、MOOD 尾行一字不动。
    @Test fun `exchange prompt - 四项覆盖分别落在要求段对应行`() {
        val zone = ZoneId.of("UTC")
        val now = 1_700_000_000_000L
        // 字数哨兵带占位（真资源 2026-09-05 起也带 %1$s）。
        val strings = sentinelStrings().copy(reqWords = "RW<%1\$s>")
        fun build(overrides: Map<String, String>) = DiaryExchangePromptBuilder.build(
            strings = strings,
            characterName = "Nova", personality = "warm", systemPrompt = "", userName = "U",
            nowMillis = now, zone = zone,
            moodLine = "", chatSummary = "", scheduleSummary = "",
            overrides = overrides,
        )
        // 无覆盖：字数行用默认常量 1000，人称/文风走默认串。
        val plain = build(emptyMap()).lines()
        val reqStart = plain.indexOf("REQH")
        assertEquals(listOf("REQH", "RS", "RSt<U>", "RW<1000>", "RM", "RNS", "RNP", "RNA", "", "OO", "MOR"), plain.drop(reqStart))

        val custom = build(
            mapOf(
                DiaryPromptField.NARRATIVE_PERSON.raw to "用「我」写你自己",
                DiaryPromptField.STYLE_HINT.raw to "克制一点，别抒情",
                DiaryPromptField.WORD_COUNT_RANGE.raw to "1500",
                DiaryPromptField.EXTRA_RULES.raw to "别写天气\n\n  多写手上的动作  ",
            ),
        ).lines()
        val customStart = custom.indexOf("REQH")
        assertEquals(
            listOf(
                "REQH",
                "- 用「我」写你自己",        // 人称行整行替换（前缀补 "- "）
                "- 克制一点，别抒情",        // 文风行整行替换
                "RW<1500>",                  // 字数进占位
                "RM", "RNS", "RNP", "RNA",   // 其余四条要求行原样
                "- 别写天气",                // 补充规则逐行追加·空行跳过·两端 trim
                "- 多写手上的动作",
                "",
                "OO", "MOR",                 // 只输出正文 + MOOD 尾行位置与文字不动
            ),
            custom.drop(customStart),
        )
    }

    @Test fun `exchange prompt - enrichment sections inject after task, before today material (丰富化)`() {
        val zone = ZoneId.of("UTC")
        val now = 1_700_000_000_000L
        val out = DiaryExchangePromptBuilder.build(
            strings = sentinelStrings(),
            characterName = "Nova", personality = "warm", systemPrompt = "SP", userName = "U",
            nowMillis = now, zone = zone,
            moodLine = "😊", chatSummary = "CHAT", scheduleSummary = "SCHED",
            enrichment = DiaryExchangeEnrichment(
                personaFrame = "PF", aboutUser = "BIO", relationship = "REL",
                memory = "MEM", promiseBlock = "PROMISE", loopBlock = "LOOP",
            ),
        )
        val dateStr = DateFormatters.yearMonthDayHourMinuteWithWeekday(now, zone)
        assertEquals(
            listOf(
                "intro<Nova|warm>", "setup<SP>", "PF", "",   // ③ 人设框定紧跟身份/设定
                "task<U,U>", "",
                "AU<U>", "BIO", "",                          // D 关于TA（段头填 userName）
                "RH<U>", "REL", "",                          // B 关系
                "MEMH", "MEM", "",                           // A 记忆
                "PROMISE", "",                               // C 约定块（渲染器自带段标题·直接注入）
                "LOOP", "",                                  // C 惦记块
                "MH", "😊", "",
                "CH", "CHAT", "",
                "SH", "SCHED", "",
                "CT<$dateStr>", "",
                "REQH", "RS", "RSt<U>", "RW", "RM", "RNS", "RNP", "RNA", "",
                "OO", "MOR",
            ).joinToString("\n"),
            out,
        )
    }

    // 卷四 T2-6 ④（图纸 §4.5 / §2.2）：意图块在惦记块之后、心情段之前；空 ⇒ 不含、与不传逐字节相同。
    @Test fun `卷四 intent block sits after loop block and before mood header, empty omitted`() {
        val zone = ZoneId.of("UTC")
        val now = 1_700_000_000_000L
        fun build(enrichment: DiaryExchangeEnrichment) = DiaryExchangePromptBuilder.build(
            strings = sentinelStrings(),
            characterName = "Nova", personality = "warm", systemPrompt = "", userName = "U",
            nowMillis = now, zone = zone,
            moodLine = "😊", chatSummary = "", scheduleSummary = "",
            enrichment = enrichment,
        )
        val dateStr = DateFormatters.yearMonthDayHourMinuteWithWeekday(now, zone)
        assertEquals(
            listOf(
                "intro<Nova|warm>", "",
                "task<U,U>", "",
                "LOOP", "",
                "INTENT1\nINTENT2", "",          // E 意图块：惦记之后、心情之前
                "MH", "😊", "",
                "CT<$dateStr>", "",
                "REQH", "RS", "RSt<U>", "RW", "RM", "RNS", "RNP", "RNA", "",
                "OO", "MOR",
            ).joinToString("\n"),
            build(DiaryExchangeEnrichment(loopBlock = "LOOP", intentBlock = "INTENT1\nINTENT2")),
        )
        val without = build(DiaryExchangeEnrichment(loopBlock = "LOOP"))
        assertEquals(without, build(DiaryExchangeEnrichment(loopBlock = "LOOP", intentBlock = "")))
        assertFalse(without.contains("INTENT"))
    }

    @Test fun `formatRelationship - phase line maps by index, recent milestones appended, empties omitted (B)`() {
        val zone = ZoneId.of("UTC")
        val s = sentinelStrings()   // phaseNames="P0|P1|P2|P3|P4"·phaseLine="PL<%1$s>"·milestoneLine="ML<%1$s,%2$s>"
        fun m(name: String, at: Long) =
            MilestoneEntity(uuid = "u$at", characterUuid = "c1", relationshipName = name, establishedDate = at)
        // stability(idx=2) + 4 里程碑(升序) → 阶段行 PL<P2> + 最近 3 条(takeLast)。
        val out = DiaryExchangeService.formatRelationship(
            "stability",
            listOf(m("网友", 0L), m("朋友", 86_400_000L), m("好朋友", 172_800_000L), m("恋人", 259_200_000L)),
            s, zone,
        )
        val lines = out.lines()
        assertEquals("PL<P2>", lines[0])                     // 阶段名按 PHASE_ORDER 固定序索引
        assertEquals(4, lines.size)                          // 阶段行 + 最近 3 条里程碑
        assertTrue("最近里程碑在内", out.contains("恋人"))
        assertFalse("最早的被截掉（只取最近 3）", out.contains("网友"))
        // 未知/空 phase → 无阶段行；无里程碑 → 空。
        assertTrue(DiaryExchangeService.formatRelationship("unknown", emptyList(), s, zone).isEmpty())
        assertFalse(DiaryExchangeService.formatRelationship(null, listOf(m("恋人", 0L)), s, zone).contains("PL<"))
    }

    // MARK: - T2 解锁服务链（MockK 全假）

    private val context = mockk<Context>(relaxed = true)
    private val contextLog = mockk<ContextLogService>()
    private val apiConfigRepo = mockk<ApiConfigRepository>()
    private val diaryRepository = mockk<DiaryRepository>(relaxed = true)
    private val messageDao = mockk<MessageDao>()
    private val conversationDao = mockk<ConversationDao>()
    private val characterDao = mockk<CharacterDao>()
    private val userProfileDao = mockk<UserProfileDao>()
    private val settingsRepo = mockk<SettingsRepository>()
    private val scheduleDao = mockk<ScheduleDao>()
    private val milestoneDao = mockk<MilestoneDao>()
    private val promiseRepository = mockk<PromiseRepository>()
    private val openLoopRepository = mockk<OpenLoopRepository>()
    private val service = DiaryExchangeService(
        context = context, contextLog = contextLog, apiConfigRepo = apiConfigRepo,
        diaryRepository = diaryRepository, messageDao = messageDao, conversationDao = conversationDao,
        characterDao = characterDao, userProfileDao = userProfileDao, settingsRepo = settingsRepo,
        scheduleDao = scheduleDao, milestoneDao = milestoneDao, promiseRepository = promiseRepository,
        openLoopRepository = openLoopRepository,
    )

    private val penpalChar = CharacterEntity(uuid = "c1", name = "小满", creationDate = 0L)

    /** 真实体消息（聊天摘要收口吃 content/roleRaw；relaxed mock 的空 content 会被过滤掉）。 */
    private fun chatMsg(role: String, content: String, ts: Long) = MessageEntity(
        messageUUID = "m$ts", conversationUuid = "cv1", roleRaw = role, content = content,
        timestamp = ts, messageKindRaw = MessageKind.PLAIN_TEXT.raw,
    )

    @Before fun setUp() {
        mockkObject(LocaleManager)
        every { LocaleManager.wrap(any()) } returns context
        every { context.getString(any()) } returns "s"
        coEvery { settingsRepo.getAppSettings() } returns AppSettings()
        coEvery { userProfileDao.get() } returns null
        coEvery { characterDao.getByUuid("c1") } returns penpalChar
        coEvery { characterDao.getAll() } returns listOf(penpalChar)
        coEvery { messageDao.messagesInRange(any(), any(), any()) } returns listOf(msg("cv1", 100))
        // 笔友聊天摘要改为每角色各自取；默认空（不关心聊天内容的用例走这条）。
        coEvery { messageDao.messagesForCharacterInRange(any(), any(), any(), any()) } returns emptyList()
        coEvery { conversationDao.getByUuid("cv1") } returns mockk<ConversationEntity>(relaxed = true) {
            every { characterUuid } returns "c1"
        }
        coEvery { scheduleDao.scheduleFor(any(), any()) } returns null
        // 丰富化依赖默认空（不关心丰富化的用例走这条·enrichment 各段自动省略）。
        coEvery { milestoneDao.getForCharacter(any()) } returns emptyList()
        coEvery { promiseRepository.injectableForCharacter(any(), any()) } returns emptyList()
        coEvery { openLoopRepository.openLoopsForCharacter(any()) } returns emptyList()
        coEvery { apiConfigRepo.resolveConfigValues(any()) } returns mockk<ApiConfigValues>()
        coEvery {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "这是小满写的今天的日记。\nMOOD: 🥰"
    }

    @After fun tearDown() = unmockkObject(LocaleManager)

    @Test fun `unlock happy path - letter lands published with author and parsed mood`(): Unit = runBlocking {
        coEvery { diaryRepository.exchangeEntryInRange(any(), any()) } returns null
        coEvery { diaryRepository.hasPublishedUserDiaryInRange(any(), any()) } returns true
        val saved = slot<DiaryEntryEntity>()
        coEvery { diaryRepository.upsert(capture(saved)) } returns Unit

        val result = service.unlockToday()

        assertTrue(result is DiaryExchangeService.UnlockResult.Success)
        assertEquals("c1", saved.captured.authorCharacterUuid)
        assertEquals("小满", saved.captured.authorNameSnapshot)             // R6-3① 落作者名快照（角色被删仍可署名）
        assertEquals("这是小满写的今天的日记。", saved.captured.content)   // MOOD 尾行已剥
        assertEquals("🥰", saved.captured.moodEmoji)                      // TA 的心情驱动信笺色
        assertEquals("exchange", saved.captured.triggerTypeRaw)
        assertEquals(false, saved.captured.isDraft)                       // 信生来就是已发布
        assertEquals("openToAI", saved.captured.visibilityRaw)
    }

    @Test fun `chat summary in letter labels character as 我 and user by name`(): Unit = runBlocking {
        // 2026-07-13 统一标注：TA 执笔的信里，角色自己=「我」、用户=用户名（作者恒「我」·防第三人称漂移）。
        every { context.getString(R.string.diary_role_me) } returns "我"
        every { context.getString(R.string.diary_chat_line) } returns "[%1\$s] %2\$s：%3\$s"
        coEvery { userProfileDao.get() } returns UserProfileEntity(nickname = "小明")
        // resolvePenpal 用 messagesInRange 选出笔友 c1；聊天摘要用每角色各自取（messagesForCharacterInRange）。
        val chat = listOf(
            chatMsg(role = "user", content = "那个，你睡了吗？", ts = 1L),
            chatMsg(role = "assistant", content = "嗯……在睡觉啦，被你吵醒了", ts = 2L),
        )
        coEvery { messageDao.messagesInRange(any(), any(), any()) } returns chat
        coEvery { messageDao.messagesForCharacterInRange("c1", any(), any(), any()) } returns chat
        coEvery { diaryRepository.exchangeEntryInRange(any(), any()) } returns null
        coEvery { diaryRepository.hasPublishedUserDiaryInRange(any(), any()) } returns true
        val sent = slot<List<ChatMessageDto>>()
        coEvery {
            contextLog.completion(any(), any(), any(), capture(sent), any(), any(), any(), any(), any())
        } returns "这是小满写的今天的日记。\nMOOD: 🥰"

        service.unlockToday()

        val system = sent.captured.first().content.orEmpty()
        assertTrue("角色消息应标「我」", system.contains("我：嗯……在睡觉啦"))
        assertTrue("用户消息应标用户名", system.contains("小明：那个，你睡了吗？"))
        assertFalse("用户消息绝不可标「我」", system.contains("我：那个"))
    }

    @Test fun `enrichment reaches prompt - memory, user bio, milestone wired through unlockToday (丰富化接线)`(): Unit = runBlocking {
        // 2026-07-13 丰富化接线守卫：memorySummary(直接字段)/bio/里程碑 经 unlockToday 真装进 system prompt。
        every { context.getString(R.string.diary_exchange_about_user) } returns "## 关于 %1\$s"
        every { context.getString(R.string.diary_exchange_memory_header) } returns "## 你还记得的"
        every { context.getString(R.string.diary_exchange_relationship_header) } returns "## 你和 %1\$s 的关系"
        every { context.getString(R.string.diary_exchange_milestone_line) } returns "%1\$s 起，你们成为%2\$s。"
        coEvery { userProfileDao.get() } returns UserProfileEntity(nickname = "小明", bio = "我是个爱猫的人")
        coEvery { characterDao.getByUuid("c1") } returns
            CharacterEntity(uuid = "c1", name = "小满", creationDate = 0L, memorySummary = "你俩上周去了海边")
        coEvery { milestoneDao.getForCharacter("c1") } returns listOf(
            MilestoneEntity(uuid = "m1", characterUuid = "c1", relationshipName = "恋人", establishedDate = 0L),
        )
        coEvery { promiseRepository.injectableForCharacter("c1", any()) } returns listOf(
            PromiseEntity(uuid = "p1", characterUuid = "c1", content = "周末一起去看海", createdAtMillis = 0L, updatedAtMillis = 0L),
        )
        // C 惦记块（复核 R1 补钉）：open 无到期 → selectLoopsForInjection(lastAssistantTime=null)=今天首轮 → 取最新 open。
        every { context.getString(R.string.pb_loop_head) } returns "## 你心里还惦记的事"
        every { context.getString(R.string.pb_loop_line, *anyVararg()) } returns "- 你还惦记着：想去的那家咖啡店"
        coEvery { openLoopRepository.openLoopsForCharacter("c1") } returns listOf(
            OpenLoopEntity(
                uuid = "l1", conversationUuid = "cv1", characterUuid = "c1",
                content = "想去的那家咖啡店", typeRaw = OpenLoopType.USER_EVENT, createdAt = 0L,
            ),
        )
        coEvery { diaryRepository.exchangeEntryInRange(any(), any()) } returns null
        coEvery { diaryRepository.hasPublishedUserDiaryInRange(any(), any()) } returns true
        val sent = slot<List<ChatMessageDto>>()
        coEvery {
            contextLog.completion(any(), any(), any(), capture(sent), any(), any(), any(), any(), any())
        } returns "日记。\nMOOD: 😌"

        service.unlockToday()

        val system = sent.captured.first().content.orEmpty()
        assertTrue("D 用户 bio 段", system.contains("## 关于 小明") && system.contains("我是个爱猫的人"))
        assertTrue("A 记忆段", system.contains("## 你还记得的") && system.contains("你俩上周去了海边"))
        assertTrue("B 关系段(里程碑)", system.contains("## 你和 小明 的关系") && system.contains("成为恋人"))
        assertTrue("C 约定块经渲染器进 prompt", system.contains("【我们的约定】") && system.contains("周末一起去看海"))
        assertTrue(
            "C 惦记块经扫描服务进 prompt（首轮语义取最新 open）",
            system.contains("## 你心里还惦记的事") && system.contains("想去的那家咖啡店"),
        )
    }

    // T2-1（图纸 §7·E5/E6）：住址优先取**当天日程行**的城市（加入世界后 = 世界城名），无日程行才回落角色卡。
    @Test fun `角色卡住址 - 日程行城市压过角色卡城市，无日程行时回落`(): Unit = runBlocking {
        every { context.getString(R.string.diary_exchange_city) } returns "你住在%1\$s。"
        coEvery { characterDao.getByUuid("c1") } returns
            CharacterEntity(uuid = "c1", name = "小满", creationDate = 0L, cityName = "上海")
        coEvery { diaryRepository.exchangeEntryInRange(any(), any()) } returns null
        coEvery { diaryRepository.hasPublishedUserDiaryInRange(any(), any()) } returns true
        coEvery { scheduleDao.eventsForSchedule(any()) } returns emptyList()
        coEvery { scheduleDao.scheduleFor("c1", any()) } returns CharacterDailyScheduleEntity(
            uuid = "sc1", characterUuid = "c1", date = 0L, cityName = "云野镇",
        )
        val sent = slot<List<ChatMessageDto>>()
        coEvery {
            contextLog.completion(any(), any(), any(), capture(sent), any(), any(), any(), any(), any())
        } returns "日记。\nMOOD: 😌"

        service.unlockToday()

        val withSchedule = sent.captured.first().content.orEmpty()
        assertTrue("住址应取日程行的世界城名", withSchedule.contains("你住在云野镇。"))
        assertFalse("绝不写角色卡上的旧城市", withSchedule.contains("你住在上海。"))

        // 当天无日程行 → 回落角色卡上的城市。
        coEvery { scheduleDao.scheduleFor("c1", any()) } returns null
        service.unlockToday()
        val withoutSchedule = sent.captured.first().content.orEmpty()
        assertTrue("无日程行时回落角色卡城市", withoutSchedule.contains("你住在上海。"))
    }

    @Test fun `unlock is idempotent - existing letter returned without any LLM call`(): Unit = runBlocking {
        val existing = DiaryEntryEntity(uuid = "ex", content = "已有的信", timestamp = 1, authorCharacterUuid = "c1")
        coEvery { diaryRepository.exchangeEntryInRange(any(), any()) } returns existing

        val result = service.unlockToday()

        assertTrue(result is DiaryExchangeService.UnlockResult.Success)
        assertEquals("ex", (result as DiaryExchangeService.UnlockResult.Success).entry.uuid)
        coVerify(exactly = 0) {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { diaryRepository.upsert(any()) }
    }

    @Test fun `unlock gated - unpublished day yields NotReady and nothing persists`(): Unit = runBlocking {
        coEvery { diaryRepository.exchangeEntryInRange(any(), any()) } returns null
        coEvery { diaryRepository.hasPublishedUserDiaryInRange(any(), any()) } returns false

        val result = service.unlockToday()

        assertTrue(result is DiaryExchangeService.UnlockResult.NotReady)
        coVerify(exactly = 0) { diaryRepository.upsert(any()) }
    }

    @Test fun `state machine - unlocked, no chat, need publish, ready`(): Unit = runBlocking {
        // 已有信 → Unlocked。
        coEvery { diaryRepository.exchangeEntryInRange(any(), any()) } returns
            DiaryEntryEntity(uuid = "ex", content = "信", timestamp = 1, authorCharacterUuid = "c1")
        assertTrue(service.stateForToday() is DiaryExchangeService.State.Unlocked)
        // 无信 + 今天没聊过（有角色）→ NoChatToday。
        coEvery { diaryRepository.exchangeEntryInRange(any(), any()) } returns null
        coEvery { messageDao.messagesInRange(any(), any(), any()) } returns emptyList()
        assertTrue(service.stateForToday() is DiaryExchangeService.State.NoChatToday)
        // 一个角色都没有 → Hidden。
        coEvery { characterDao.getAll() } returns emptyList()
        assertTrue(service.stateForToday() is DiaryExchangeService.State.Hidden)
        // 有聊天 + 未发布 → NeedPublish（带笔友名）。
        coEvery { characterDao.getAll() } returns listOf(penpalChar)
        coEvery { messageDao.messagesInRange(any(), any(), any()) } returns listOf(msg("cv1", 100))
        coEvery { diaryRepository.hasPublishedUserDiaryInRange(any(), any()) } returns false
        val need = service.stateForToday()
        assertTrue(need is DiaryExchangeService.State.NeedPublish)
        assertEquals("小满", (need as DiaryExchangeService.State.NeedPublish).characterName)
        // 已发布 → ReadyToUnlock。
        coEvery { diaryRepository.hasPublishedUserDiaryInRange(any(), any()) } returns true
        assertTrue(service.stateForToday() is DiaryExchangeService.State.ReadyToUnlock)
    }

    @Test fun `fixed penpal - no chat with them today means not resolvable`(): Unit = runBlocking {
        // 固定笔友 c2，但今天只和 c1 聊过 → NoChatToday（固定不轮换）。
        coEvery { settingsRepo.getAppSettings() } returns AppSettings(diaryExchangePartnerUuid = "c2")
        coEvery { characterDao.getByUuid("c2") } returns CharacterEntity(uuid = "c2", name = "阿桃", creationDate = 0L)
        coEvery { diaryRepository.exchangeEntryInRange(any(), any()) } returns null
        assertTrue(service.stateForToday() is DiaryExchangeService.State.NoChatToday)
    }
}
