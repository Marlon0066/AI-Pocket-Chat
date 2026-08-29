package com.situ.aichat.seam

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.MomentRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.moments.MomentInteractionService
import com.situ.aichat.moments.MomentNewPostNotifier
import com.situ.aichat.moments.MomentPendingInteractionStore
import com.situ.aichat.notification.Notifier
import com.situ.aichat.prompt.schedule.CharacterSleepChecker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 卷一 C7「朋友圈」行为测试（图纸 §7 T2-C6/C7）：见面中的角色不互动（B1·待互动队列**保留不消费**），
 * 朋友圈通知在 App 前台不弹（C1·2-5b「App 内红点就够」同源），非前台/非见面照常（N1）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MomentsMeetingGateTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val now = 1_800_000_000_000L

    private lateinit var conversationDao: ConversationDao
    private lateinit var messageDao: com.situ.aichat.data.local.dao.MessageDao
    private lateinit var momentRepo: MomentRepository
    private lateinit var characterRepo: CharacterRepository
    private lateinit var interactionService: MomentInteractionService

    private fun convo(inMeeting: Boolean, charUuid: String = "c1") = ConversationEntity(
        uuid = "conv-$charUuid", title = "t", characterUuid = charUuid, creationDate = 0L,
        isInOfflineMode = inMeeting, currentOfflineSessionId = if (inMeeting) "sess-1" else null,
    )

    @Before
    fun setUp() {
        mockkObject(Notifier)
        every { Notifier.postNewMomentPost(any(), any(), any(), any(), any(), any()) } returns Unit
        every { Notifier.postMergedMomentPosts(any(), any(), any(), any()) } returns Unit
        mockkObject(ProcessLifecycleOwner.Companion)
        conversationDao = mockk(relaxed = true)
        messageDao = mockk(relaxed = true)
        momentRepo = mockk(relaxed = true)
        characterRepo = mockk(relaxed = true)
        val settingsRepo: SettingsRepository = mockk(relaxed = true)
        coEvery { settingsRepo.getAppSettings() } returns AppSettings(scheduleSystemEnabled = false)
        val apiConfigRepo: ApiConfigRepository = mockk(relaxed = true)
        coEvery { apiConfigRepo.resolveConfigValues(any()) } returns mockk<ApiConfigValues>(relaxed = true)
        coEvery { characterRepo.getAll() } returns listOf(CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L))
        coEvery { characterRepo.get("c1") } returns CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)
        val sleepChecker: CharacterSleepChecker = mockk(relaxed = true)
        coEvery { sleepChecker.isSleeping(any(), any(), any(), any()) } returns false
        interactionService = MomentInteractionService(
            context = context, momentRepo = momentRepo, characterRepo = characterRepo,
            apiConfigRepo = apiConfigRepo, settingsRepo = settingsRepo, sleepChecker = sleepChecker,
            messageDao = messageDao, contextLog = mockk(relaxed = true),
            llmSlot = mockk(relaxed = true), userProfileDao = mockk(relaxed = true),
            scheduleDao = mockk(relaxed = true), conversationDao = conversationDao,
        )
        MomentPendingInteractionStore.save(context, emptyList())
    }

    @After
    fun tearDown() {
        unmockkObject(Notifier)
        unmockkObject(ProcessLifecycleOwner.Companion)
    }

    private fun setForeground(foreground: Boolean) {
        val lifecycle = mockk<Lifecycle>(relaxed = true)
        every { lifecycle.currentState } returns if (foreground) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
        val owner = mockk<ProcessLifecycleOwner>(relaxed = true)
        every { owner.lifecycle } returns lifecycle
        every { ProcessLifecycleOwner.get() } returns owner
    }

    private fun queueOne() = MomentPendingInteractionStore.add(
        context = context, postUuid = "p1", postTimestampMillis = now - 1000L,
        postAuthorUuid = "user", characterUuid = "c1", nowMillis = now,
    )

    // ── B1 待互动队列：见面中保留不消费 ──

    @Test
    fun 待互动_见面中_保留队列且零互动() = runBlocking {
        coEvery { conversationDao.latestActiveForCharacter("c1") } returns convo(inMeeting = true)
        queueOne()
        interactionService.processPendingInteractions(nowMillis = now)
        assertEquals("见面中该项须留在队列（不消费）", 1, MomentPendingInteractionStore.load(context).size)
        coVerify(exactly = 0) { momentRepo.addLike(any(), any(), any(), any()) }
        coVerify(exactly = 0) { momentRepo.getPost(any()) }
    }

    /** N1 对照：非见面 → 照常消费该项（走到取帖那一步，证明上面的静默是见面闸拦下的）。 */
    @Test
    fun 待互动_非见面_照常处理() = runBlocking {
        coEvery { conversationDao.latestActiveForCharacter("c1") } returns convo(inMeeting = false)
        coEvery { momentRepo.getPost("p1") } returns null // 帖已删 → 消费掉该项（不重排）
        queueOne()
        interactionService.processPendingInteractions(nowMillis = now)
        assertEquals("非见面须消费该项", 0, MomentPendingInteractionStore.load(context).size)
        coVerify(exactly = 1) { momentRepo.getPost("p1") }
    }

    // ── B1 自动发帖：见面中的角色本轮不发帖 ──

    private fun generationService(contextLog: com.situ.aichat.diagnostics.ContextLogService): com.situ.aichat.moments.MomentGenerationService {
        val settingsRepo: SettingsRepository = mockk(relaxed = true)
        coEvery { settingsRepo.getAppSettings() } returns AppSettings(momentAutoPostFrequency = 2, scheduleSystemEnabled = false)
        val apiConfigRepo: ApiConfigRepository = mockk(relaxed = true)
        coEvery { apiConfigRepo.resolveConfigValues(any()) } returns mockk<ApiConfigValues>(relaxed = true)
        coEvery { momentRepo.countPostsForCharacterSince(any(), any()) } returns 0
        coEvery { momentRepo.lastPostTimestampForCharacter(any()) } returns null
        val sleepChecker: CharacterSleepChecker = mockk(relaxed = true)
        coEvery { sleepChecker.isSleeping(any(), any(), any(), any()) } returns false
        return com.situ.aichat.moments.MomentGenerationService(
            context = context, contextLog = contextLog, apiConfigRepo = apiConfigRepo,
            characterRepo = characterRepo, momentRepo = momentRepo, scheduleDao = mockk(relaxed = true),
            sleepChecker = sleepChecker, settingsRepo = settingsRepo, interactionService = mockk(relaxed = true),
            backgroundScheduler = mockk(relaxed = true), giftQueue = mockk(relaxed = true),
            petShopQueue = mockk(relaxed = true), petRepository = mockk(relaxed = true),
            newPostNotifier = mockk(relaxed = true), userProfileDao = mockk(relaxed = true),
            conversationDao = conversationDao,
        )
    }

    @Test
    fun 自动发帖_见面中_不发帖不调LLM() = runBlocking {
        val contextLog: com.situ.aichat.diagnostics.ContextLogService = mockk(relaxed = true)
        coEvery { conversationDao.latestActiveForCharacter("c1") } returns convo(inMeeting = true)
        generationService(contextLog).checkAndGeneratePosts(nowMillis = now)
        coVerify(exactly = 0) {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    /** N1 对照：非见面 → 照常走到 LLM 生成（证明零调用是见面闸拦下的）。 */
    @Test
    fun 自动发帖_非见面_照常调LLM() = runBlocking {
        val contextLog: com.situ.aichat.diagnostics.ContextLogService = mockk(relaxed = true)
        coEvery { conversationDao.latestActiveForCharacter("c1") } returns convo(inMeeting = false)
        generationService(contextLog).checkAndGeneratePosts(nowMillis = now)
        coVerify(atLeast = 1) {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    // ── B1 帖子互动候选：见面中的角色不进候选 ──

    @Test
    fun 互动候选_全部见面中_零互动() = runBlocking {
        coEvery { conversationDao.latestActiveForCharacter("c1") } returns convo(inMeeting = true)
        coEvery { momentRepo.getPost("p1") } returns post().copy(characterUuid = null, authorTypeRaw = "user")
        coEvery { momentRepo.commentsForPost("p1") } returns emptyList()
        interactionService.autoInteractWithPost("p1", nowMillis = now)
        coVerify(exactly = 0) { momentRepo.addLike(any(), any(), any(), any()) }
        // 候选被闸掉 → 整条互动链早退：连相关性打分的活跃度查询都没发生。
        coVerify(exactly = 0) { messageDao.countRecentNonSystemForCharacter(any(), any()) }
    }

    /** N1 对照：非见面 → 候选照常进入打分链（点赞本身带概率，故以确定性的打分查询为证据）。 */
    @Test
    fun 互动候选_非见面_照常进入打分链() = runBlocking {
        coEvery { conversationDao.latestActiveForCharacter("c1") } returns convo(inMeeting = false)
        coEvery { momentRepo.getPost("p1") } returns post().copy(characterUuid = null, authorTypeRaw = "user")
        coEvery { momentRepo.commentsForPost("p1") } returns emptyList()
        interactionService.autoInteractWithPost("p1", nowMillis = now)
        coVerify(exactly = 1) { messageDao.countRecentNonSystemForCharacter("c1", any()) }
    }

    // ── C1 新动态通知前台判定 ──

    private fun post() = MomentPostEntity(
        uuid = "p1", characterUuid = "c1", authorTypeRaw = "character", content = "今天风很好",
        timestamp = now,
    )

    @Test
    fun 新动态通知_前台_不弹() = runBlocking {
        setForeground(true)
        MomentNewPostNotifier(context, characterRepo).notifyNewPosts(listOf(post()), nowMillis = now)
        io.mockk.verify(exactly = 0) { Notifier.postNewMomentPost(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun 新动态通知_后台_照常弹() = runBlocking {
        setForeground(false)
        MomentNewPostNotifier(context, characterRepo).notifyNewPosts(listOf(post()), nowMillis = now)
        io.mockk.verify(exactly = 1) { Notifier.postNewMomentPost(any(), any(), any(), any(), any(), any()) }
    }
}
