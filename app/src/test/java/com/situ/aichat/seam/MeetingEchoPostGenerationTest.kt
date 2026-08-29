package com.situ.aichat.seam

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.MomentTriggerType
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.MomentRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.moments.MomentGenerationService
import com.situ.aichat.moments.MomentInteractionService
import com.situ.aichat.moments.MomentNewPostNotifier
import com.situ.aichat.offline.MeetingMomentEchoPlanner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-K5（卷二 §5④·图纸 §3.3/§7）：**见面朋友圈呼应帖的生成与发布**行为测试。
 *
 * 覆盖：确定性 uuid + AUTO_DRAFT 落帖 / 排延迟互动 / 通知随设置开关 / M3 灵感模板逐字（本测**重新打字**，
 * 不引生产常量）/ J9 校验矩阵（昵称·「用户」·超长·含【 → 带反馈重写一次 → 仍违规返回 null）。
 * Robolectric 只为 [MomentPromptStrings] 需要的真资源。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeetingEchoPostGenerationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private lateinit var contextLog: ContextLogService
    private lateinit var momentRepo: MomentRepository
    private lateinit var interactionService: MomentInteractionService
    private lateinit var newPostNotifier: MomentNewPostNotifier
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var userProfileDao: UserProfileDao
    private lateinit var characterRepo: CharacterRepository

    private val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)

    private val row = OfflineMeetingMemoryEntity(
        uuid = "r1", characterUuid = "c1", conversationUuid = "conv", sessionId = "sess-1",
        startedAtMillis = now - 4 * 3600_000L, endedAtMillis = now - 3 * 3600_000L,
        location = "临江咖啡馆", summary = "一起喝了咖啡，聊得很开心",
        sourceRaw = "llm", createdAtMillis = 0L, updatedAtMillis = 0L,
    )

    @Before
    fun setUp() {
        contextLog = mockk(relaxed = true)
        momentRepo = mockk(relaxed = true)
        interactionService = mockk(relaxed = true)
        newPostNotifier = mockk(relaxed = true)
        settingsRepo = mockk(relaxed = true)
        userProfileDao = mockk(relaxed = true)
        characterRepo = mockk(relaxed = true)
        coEvery { settingsRepo.getAppSettings() } returns
            AppSettings(scheduleSystemEnabled = false, momentNewPostNotificationEnabled = true)
        coEvery { userProfileDao.get() } returns UserProfileEntity(nickname = "阿泽")
        coEvery { momentRepo.recentPostsForCharacter(any(), any()) } returns emptyList()
        coEvery { momentRepo.recentUserPosts(any()) } returns emptyList()
    }

    @After fun tearDown() = unmockkAll()

    private fun service() = MomentGenerationService(
        context = context, contextLog = contextLog, apiConfigRepo = mockk<ApiConfigRepository>(relaxed = true),
        characterRepo = characterRepo, momentRepo = momentRepo, scheduleDao = mockk(relaxed = true),
        sleepChecker = mockk(relaxed = true), settingsRepo = settingsRepo, interactionService = interactionService,
        backgroundScheduler = mockk(relaxed = true), giftQueue = mockk(relaxed = true),
        petShopQueue = mockk(relaxed = true), petRepository = mockk(relaxed = true),
        newPostNotifier = newPostNotifier, userProfileDao = userProfileDao,
        conversationDao = mockk(relaxed = true),
    )

    private fun stubLlm(vararg replies: String) {
        coEvery {
            contextLog.completion(eq(LogSource.MOMENT_POST), any(), any(), any(), any(), any(), any(), any(), any())
        } returnsMany replies.toList()
    }

    private fun captureSystemPrompts(reply: String): MutableList<List<ChatMessageDto>> {
        val sent = mutableListOf<List<ChatMessageDto>>()
        coEvery {
            contextLog.completion(eq(LogSource.MOMENT_POST), any(), any(), capture(sent), any(), any(), any(), any(), any())
        } returns reply
        return sent
    }

    private fun generate() = runBlocking {
        service().generateEchoPost(character, mockk<ApiConfigValues>(relaxed = true), row, now, zone)
    }

    private fun verifyCompletion(times: Int) = coVerify(exactly = times) {
        contextLog.completion(eq(LogSource.MOMENT_POST), any(), any(), any(), any(), any(), any(), any(), any())
    }

    // ══════ 落帖身份与副作用 ══════

    /** 合格正文 → 确定性 uuid（moment:echo:{sessionId}）+ AUTO_DRAFT + 自动生成标记落库。 */
    @Test fun 合格正文_确定性uuid落帖并排互动() {
        stubLlm("江边的风把咖啡香吹了一路，这个下午值得记很久。")

        val post = generate()

        assertNotNull(post)
        val expectedUuid = UUID.nameUUIDFromBytes("moment:echo:sess-1".toByteArray()).toString()
        assertEquals(expectedUuid, post!!.uuid)
        assertEquals(MeetingMomentEchoPlanner.echoPostUuid("sess-1"), post.uuid)
        assertEquals(MomentTriggerType.AUTO_DRAFT.raw, post.triggerTypeRaw)
        assertEquals("character", post.authorTypeRaw)
        assertEquals("c1", post.characterUuid)
        assertEquals(now, post.timestamp)
        assertTrue(post.isAutoGenerated)
        assertNull("呼应帖不挂礼物", post.relatedGiftId)
        val stored = slot<MomentPostEntity>()
        coVerify(exactly = 1) { momentRepo.upsert(capture(stored)) }
        assertEquals(expectedUuid, stored.captured.uuid)
        coVerify(exactly = 1) { interactionService.scheduleGeneratedPostInteraction(expectedUuid) }
    }

    /** 通知开关开 → 推一条「新动态」（单元素表）。 */
    @Test fun 通知开关开_推单元素新动态() {
        stubLlm("这一杯的余味，好像还留在傍晚里。")

        val post = generate()

        val posts = slot<List<MomentPostEntity>>()
        coVerify(exactly = 1) { newPostNotifier.notifyNewPosts(capture(posts), eq(now), eq(zone)) }
        assertEquals(1, posts.captured.size)
        assertEquals(post!!.uuid, posts.captured.first().uuid)
    }

    /** 通知开关关 → 帖照落、通知零调用。 */
    @Test fun 通知开关关_落帖但零通知() {
        coEvery { settingsRepo.getAppSettings() } returns
            AppSettings(scheduleSystemEnabled = false, momentNewPostNotificationEnabled = false)
        stubLlm("这一杯的余味，好像还留在傍晚里。")

        assertNotNull(generate())

        coVerify(exactly = 1) { momentRepo.upsert(any()) }
        coVerify(exactly = 0) { newPostNotifier.notifyNewPosts(any(), any(), any()) }
    }

    // ══════ M3 灵感模板逐字 ══════

    /**
     * M3：灵感段逐字（本例的期望串**重新打字**自图纸 §3.3，不引生产常量）；dayLabel 与余温同源
     * （[com.situ.aichat.offline.OfflineAfterglowService.anchorLabel]），故只断言其余部分与占位填充。
     */
    @Test fun 灵感段_M3模板逐字进系统提示词() {
        val sent = captureSystemPrompts("江边的风把咖啡香吹了一路。")

        generate()

        val systemPrompt = sent.last().first { it.role == "system" }.content.orEmpty()
        val dayLabel = com.situ.aichat.offline.OfflineAfterglowService.anchorLabel(
            row.startedAtMillis, java.time.Instant.ofEpochMilli(now), zone,
        )
        val expected = "（灵感：${dayLabel}你和阿泽在临江咖啡馆见了一面——一起喝了咖啡，聊得很开心。" +
            "想发一条朋友圈纪念这份心情。含蓄程度随你的性格：内敛就只写意象和氛围、一个字不提见面；" +
            "外露可以提\"和重要的人\"，但不写名字。绝不出现\"阿泽\"和\"用户\"字样，" +
            "不复述见面里的私密细节，不加话题标签，一到两句话。）"
        // 逐字包含（灵感段自带的「（中午）」等全角括号让切片不可靠，故直接整段比对）。
        assertTrue("灵感段应逐字出现在系统提示词里。\n期望：$expected\n实得：$systemPrompt", systemPrompt.contains(expected))
    }

    /** 昵称空白 → 灵感里称呼降为「TA」，绝不直呼「用户」。 */
    @Test fun 昵称空白_称呼降为TA() {
        coEvery { userProfileDao.get() } returns UserProfileEntity(nickname = "  ")
        val sent = captureSystemPrompts("傍晚的光很好，心情也是。")

        generate()

        val systemPrompt = sent.last().first { it.role == "system" }.content.orEmpty()
        assertTrue(systemPrompt.contains("你和TA在临江咖啡馆见了一面"))
        assertTrue(systemPrompt.contains("绝不出现\"TA\"和\"用户\"字样"))
    }

    /** 昵称恰为「用户」→ 同样降为「TA」。 */
    @Test fun 昵称恰为用户_同样降为TA() {
        coEvery { userProfileDao.get() } returns UserProfileEntity(nickname = "用户")
        val sent = captureSystemPrompts("傍晚的光很好，心情也是。")

        generate()

        assertTrue(sent.last().first { it.role == "system" }.content.orEmpty().contains("你和TA在临江咖啡馆见了一面"))
    }

    // ══════ J9 校验矩阵 ══════

    /** 首条写出了昵称 → 带反馈重写一次；第二条合格 → 落帖。 */
    @Test fun j9_昵称命中_重写一次后合格() {
        stubLlm("今天和阿泽在江边坐了很久，风很软。", "今天在江边坐了很久，风很软，值得记很久。")

        val post = generate()

        assertNotNull(post)
        verifyCompletion(2)
        coVerify(exactly = 1) { momentRepo.upsert(any()) }
    }

    /** 重写时把违规原因拼在灵感尾部（retry-with-feedback 形态）。 */
    @Test fun j9_重写时带上违规原因() {
        val sent = captureSystemPrompts("今天和阿泽在江边坐了很久。") // 两次都命中昵称 → 两次调用都可捕获

        generate()

        assertEquals(2, sent.size)
        val second = sent.last().first { it.role == "system" }.content.orEmpty()
        assertTrue("第二次应带反馈：$second", second.contains("上一条不合格：写出了对方的名字"))
        assertTrue(second.contains("请重写"))
    }

    /** 两次都写昵称 → 静默放弃：不落库、不排互动、不通知。 */
    @Test fun j9_昵称两次命中_静默放弃() {
        stubLlm("今天和阿泽在江边坐了很久。", "阿泽今天笑得很好看。")

        assertNull(generate())

        verifyCompletion(2)
        coVerify(exactly = 0) { momentRepo.upsert(any()) }
        coVerify(exactly = 0) { interactionService.scheduleGeneratedPostInteraction(any()) }
        coVerify(exactly = 0) { newPostNotifier.notifyNewPosts(any(), any(), any()) }
    }

    /** 出现「用户」字样 → 违规。 */
    @Test fun j9_用户字样命中_静默放弃() {
        stubLlm("今天和用户一起喝了咖啡，很开心的一天。", "陪用户走了很远的路，风很软。")
        assertNull(generate())
        coVerify(exactly = 0) { momentRepo.upsert(any()) }
    }

    /** 超过 140 字 → 违规（139/140 合格，141 不合格：边界从 M5 独立反推）。 */
    @Test fun j9_超长边界_140合格141违规() {
        stubLlm("好".repeat(140))
        assertNotNull("恰 140 字应合格", generate())

        tearDown()
        setUp()
        stubLlm("好".repeat(141), "好".repeat(141))
        assertNull("141 字应违规", generate())
    }

    /** 含 [ 或 【 的标签腔 → 违规。 */
    @Test fun j9_标签括号命中_静默放弃() {
        stubLlm("[叙述]今天的咖啡很香，风也温柔。", "【环境】江边的风很软，心情也是。")
        assertNull(generate())
        coVerify(exactly = 0) { momentRepo.upsert(any()) }
    }

    /** 空正文（LLM 全空）→ 违规，静默放弃。 */
    @Test fun j9_空正文_静默放弃() {
        stubLlm("", "", "", "")
        assertNull(generate())
        coVerify(exactly = 0) { momentRepo.upsert(any()) }
    }

    /** 单字昵称不设硬闸（误伤面太大）：正文含该字仍可发。 */
    @Test fun j9_单字昵称不设硬闸() {
        coEvery { userProfileDao.get() } returns UserProfileEntity(nickname = "泽")
        stubLlm("泽光落在江面上，这个下午很值得记住。")
        assertNotNull(generate())
    }

    /** D-2 复核裁决（R1）：摘要以句号收尾时灵感段不得出现「。。」——清洗在输入侧，M3 模板字面不动。 */
    @Test fun d2_摘要句号收尾_灵感段无双句号() {
        val text = com.situ.aichat.moments.MeetingMomentEchoContent.inspiration(
            dayLabel = "今天 15:00（下午）", callName = "小北",
            location = "临江咖啡馆", summary = "一起喝了咖啡，聊得很开心。",
        )
        assertTrue(text.contains("聊得很开心。想发一条朋友圈纪念这份心情"))
        assertTrue(!text.contains("。。"))
    }
}
