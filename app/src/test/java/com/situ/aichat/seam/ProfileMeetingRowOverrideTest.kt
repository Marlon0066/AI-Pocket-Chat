package com.situ.aichat.seam

import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.OfflineMeetingMemoryRepository
import com.situ.aichat.offline.OfflineMeetingSession
import com.situ.aichat.offline.OfflineMeetingSessionExtractor
import com.situ.aichat.ui.character.CharacterProfileViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 卷一 C12「F4 资料页见面回忆行 override」行为测试（图纸 §3.5-F4）：资料页的见面回忆卡摘要/「简版」徽章
 * 必须来自**行**（blob 早已冻结只读、注入宏直读行），骨架仍从 marker 组装；无行 → 保 extractSessions 原值。
 * 与回忆长廊 `OfflineMeetingMemoryViewModel.reload` 同模板。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileMeetingRowOverrideTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)

    private fun session(id: String, summary: String?) = OfflineMeetingSession(
        id = id, location = "咖啡馆", activity = "喝咖啡", startMillis = 1L, durationText = "约1小时",
        finalMood = "warm", summaryText = summary, conversationUuid = "conv-1",
        initiatedByUser = true, usedFallbackSummary = false,
    )

    private fun row(sessionId: String, summary: String, source: String) = OfflineMeetingMemoryEntity(
        uuid = "row-$sessionId", characterUuid = "c1", sessionId = sessionId, summary = summary, sourceRaw = source,
        startedAtMillis = 1L, createdAtMillis = 1L, updatedAtMillis = 1L,
    )

    private fun buildViewModel(
        sessions: List<OfflineMeetingSession>,
        rows: List<OfflineMeetingMemoryEntity>,
    ): CharacterProfileViewModel {
        val characterRepo: CharacterRepository = mockk(relaxed = true)
        every { characterRepo.observe("c1") } returns flowOf(character)
        val extractor: OfflineMeetingSessionExtractor = mockk()
        coEvery { extractor.extractSessions(any()) } returns sessions
        val memoryRepo: OfflineMeetingMemoryRepository = mockk(relaxed = true)
        coEvery { memoryRepo.byCharacter("c1") } returns rows
        val messageDao: com.situ.aichat.data.local.dao.MessageDao = mockk(relaxed = true)
        every { messageDao.observeNonSystemForCharacter("c1") } returns flowOf(0)
        return CharacterProfileViewModel(
            savedStateHandle = SavedStateHandle(mapOf(CharacterProfileViewModel.ARG_CHARACTER_UUID to "c1")),
            characterRepo = characterRepo, messageDao = messageDao, giftDao = mockk(relaxed = true),
            meetingAppointmentDao = mockk(relaxed = true), currencyDao = mockk(relaxed = true),
            scheduleDao = mockk(relaxed = true), settingsRepo = mockk(relaxed = true),
            promiseRepository = mockk(relaxed = true), scheduleCoordinator = mockk(relaxed = true),
            companionStatsService = mockk(relaxed = true), offlineExtractor = extractor,
            offlineRetryCoordinator = mockk(relaxed = true), salaryPayoutService = mockk(relaxed = true),
            economyLastViewed = mockk(relaxed = true), conversationRepo = mockk(relaxed = true),
            manualMemoryOrganize = mockk(relaxed = true), offlineMeetingMemoryRepository = memoryRepo,
        )
    }

    private fun sessionsOf(vm: CharacterProfileViewModel): List<OfflineMeetingSession> = runBlocking {
        val collector = launch { vm.offlineSessions.collect { } }
        val result = withTimeout(10_000L) { vm.offlineSessions.first { it.isNotEmpty() } }
        collector.cancel()
        result
    }

    @Test
    fun 有行_摘要与简版徽章取行值() {
        val vm = buildViewModel(
            sessions = listOf(session("s1", summary = "骨架里的旧摘要")),
            rows = listOf(row("s1", summary = "行里的新摘要", source = "fallback")),
        )
        val result = sessionsOf(vm).single()
        assertEquals("行里的新摘要", result.summaryText)
        assertTrue("sourceRaw=fallback → 显示「简版」徽章", result.usedFallbackSummary)
    }

    @Test
    fun 无行_保骨架原值() {
        val vm = buildViewModel(
            sessions = listOf(session("s1", summary = "骨架里的摘要")),
            rows = emptyList(),
        )
        val result = sessionsOf(vm).single()
        assertEquals("骨架里的摘要", result.summaryText)
        assertEquals(false, result.usedFallbackSummary)
    }

    /** 行摘要为空（pending 生成中）→ summaryText 置 null（UI 显示生成中态），不回落旧骨架文本。 */
    @Test
    fun 行摘要为空_置null() {
        val vm = buildViewModel(
            sessions = listOf(session("s1", summary = "骨架里的旧摘要")),
            rows = listOf(row("s1", summary = "", source = "llm")),
        )
        assertEquals(null, sessionsOf(vm).single().summaryText)
    }
}
