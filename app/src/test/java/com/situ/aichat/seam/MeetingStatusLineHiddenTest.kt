package com.situ.aichat.seam

import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.ui.chat.ChatListViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 卷一 C12「F5 状态行隐藏」行为测试（图纸 §3.5-F5）：会话正在线下见面 → 聊天列表该行的日程状态串**缺席**
 * （不泄见面地点，也不显早已过时的线上日程）；非见面行照常出状态串（N1 对照）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeetingStatusLineHiddenTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val now = System.currentTimeMillis()

    private fun convo(uuid: String, charUuid: String, inMeeting: Boolean) = ConversationEntity(
        uuid = uuid, title = "t", characterUuid = charUuid, creationDate = 0L,
        lastMessageDate = now, isInOfflineMode = inMeeting,
        currentOfflineSessionId = if (inMeeting) "sess-1" else null,
    )

    private fun buildViewModel(conversations: List<ConversationEntity>): ChatListViewModel {
        val conversationRepo: ConversationRepository = mockk(relaxed = true)
        every { conversationRepo.observeActive() } returns flowOf(conversations)
        val characterRepo: CharacterRepository = mockk(relaxed = true)
        every { characterRepo.observeAll() } returns flowOf(
            conversations.map { CharacterEntity(uuid = it.characterUuid, name = "角色${it.characterUuid}", creationDate = 0L) },
        )
        val settingsRepository: SettingsRepository = mockk(relaxed = true)
        every { settingsRepository.appSettings } returns MutableStateFlow(AppSettings(scheduleSystemEnabled = true))
        val scheduleDao: ScheduleDao = mockk(relaxed = true)
        coEvery { scheduleDao.scheduleFor(any(), any()) } returns
            com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity(uuid = "sch-1", characterUuid = "x", date = 0L)
        coEvery { scheduleDao.eventsForSchedule(any()) } returns listOf(
            ScheduleEventEntity(
                uuid = "e1", scheduleUuid = "s1", startTime = now - 60_000L, endTime = now + 60_000L,
                periodLabel = "下午", location = "公司", activity = "在改稿", moodEmoji = "💼", moodText = "专注",
                innerThought = "", isPhoneAvailable = true, eventTypeRaw = "work", sortOrder = 0,
            ),
        )
        return ChatListViewModel(
            conversationRepo = conversationRepo, characterRepo = characterRepo,
            deletionService = mockk(relaxed = true), messageRepo = mockk(relaxed = true),
            quickReplyService = mockk(relaxed = true), scheduleDao = scheduleDao,
            settingsRepository = settingsRepository,
        )
    }

    /** N1 对照：非见面行照常出状态串（证明夹具真能产出状态，缺席才有意义）。 */
    @Test
    fun 非见面的行_照常有状态串() = runBlocking {
        val vm = buildViewModel(listOf(convo("conv-B", "B", inMeeting = false)))
        val status = withTimeout(10_000L) { vm.scheduleStatus.first { it.isNotEmpty() } }
        assertEquals(setOf("B"), status.keys)
    }

    /** E11 并发 + F5：A 在见面、B 不在 → 出状态的只有 B（A 的状态行隐藏）。 */
    @Test
    fun 混合两行_只隐见面那行() = runBlocking {
        val vm = buildViewModel(
            listOf(convo("conv-A", "A", inMeeting = true), convo("conv-B", "B", inMeeting = false)),
        )
        val status = withTimeout(10_000L) { vm.scheduleStatus.first { it.isNotEmpty() } }
        assertEquals(setOf("B"), status.keys)
    }

    /** 全员见面中 → 状态映射持续为空（配上面的正向对照才成立）。 */
    @Test
    fun 见面中的行_无状态串() = runBlocking {
        val vm = buildViewModel(listOf(convo("conv-A", "A", inMeeting = true)))
        // 必须真订阅：scheduleStatus 是 WhileSubscribed(5s)，没人收就根本不计算（否则本断言恒真=假绿）。
        val collector = launch { vm.scheduleStatus.collect { } }
        kotlinx.coroutines.delay(1_500L)
        assertEquals(emptyMap<String, String>(), vm.scheduleStatus.value)
        collector.cancel()
    }
}
