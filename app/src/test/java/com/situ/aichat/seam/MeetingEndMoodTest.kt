package com.situ.aichat.seam

import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.offline.OfflineMeetingService
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 卷一 C11「F2 结束心情取真值」行为测试（图纸 §7 T2-C11）：见面结束写进角色日程的那条事件，
 * 心情取角色**此刻真实心情**（见面中照常回写的 lastMood*），而不是写死「😌 满足」；
 * 心情为空 → 回落原常量对。内心独白结束分支改「和<用户名>的见面结束了」（开始分支零碰）。
 */
class MeetingEndMoodTest {

    @Before fun setUp() = mockkStatic("androidx.room.RoomDatabaseKt")
    @After fun tearDown() = unmockkAll()

    private fun db(): AppDatabase {
        val database = mockk<AppDatabase>()
        coEvery { database.withTransaction<Boolean>(any()) } coAnswers { secondArg<suspend () -> Boolean>().invoke() }
        return database
    }

    private fun finalizeAndCapture(moodEmoji: String, moodText: String, nickname: String? = "小明"): ScheduleEventEntity {
        val captured = slot<List<ScheduleEventEntity>>()
        val scheduleDao = mockk<ScheduleDao>(relaxed = true)
        coEvery { scheduleDao.scheduleFor(any(), any()) } returns null
        coEvery { scheduleDao.eventsForSchedule(any()) } returns emptyList()
        coEvery { scheduleDao.insertEvents(capture(captured)) } returns Unit

        val convoRepo = mockk<ConversationRepository>(relaxed = true)
        coEvery { convoRepo.get("conv1") } returns ConversationEntity(
            uuid = "conv1", title = "t", characterUuid = "char1", creationDate = 0L,
            isInOfflineMode = true, currentOfflineSessionId = "sess-1",
        )
        val charRepo = mockk<CharacterRepository>(relaxed = true)
        coEvery { charRepo.get("char1") } returns CharacterEntity(
            uuid = "char1", name = "小雨", creationDate = 0L,
            lastMoodEmoji = moodEmoji, lastMoodText = moodText,
        )
        val userProfileDao = mockk<UserProfileDao>()
        coEvery { userProfileDao.get() } returns nickname?.let { UserProfileEntity(nickname = it) }

        val service = OfflineMeetingService(
            db(), mockk<MessageRepository>(relaxed = true), convoRepo, charRepo, scheduleDao, userProfileDao,
        )
        runBlocking { service.finalizeOfflineMode("conv1", OfflineMeetingService.ExitReason.USER_ENDED) }
        return captured.captured.single()
    }

    @Test
    fun 结束事件_取角色真实心情() {
        val event = finalizeAndCapture(moodEmoji = "😤", moodText = "有点上头")
        assertEquals("😤", event.moodEmoji)
        assertEquals("有点上头", event.moodText)
    }

    @Test
    fun 结束事件_心情为空_回落原常量对() {
        val event = finalizeAndCapture(moodEmoji = "", moodText = "")
        assertEquals("😌", event.moodEmoji)
        assertEquals("满足", event.moodText)
    }

    @Test
    fun 结束事件_内心独白用真名() {
        assertEquals("和小明的见面结束了", finalizeAndCapture("🙂", "还行").innerThought)
    }

    @Test
    fun 结束事件_空昵称回退用户() {
        assertEquals("和用户的见面结束了", finalizeAndCapture("🙂", "还行", nickname = null).innerThought)
    }
}
