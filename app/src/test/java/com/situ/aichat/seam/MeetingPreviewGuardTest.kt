package com.situ.aichat.seam

import android.util.Log
import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.offline.OfflineMeetingService
import com.situ.aichat.quickreply.ListQuickReplyService
import com.situ.aichat.recovery.RecoveryClaimTracker
import com.situ.aichat.recovery.RecoveryReplyGenerator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 卷一 C2「预览家族」行为测试（图纸 2026-08-26 §7 T2-C2）：见面中用户侧消息**不顶会话列表预览**
 * （与 AI 侧 `assistantDeliveryPreview` 方案 A 同源），只刷新最后活动时间；非见面路径行为字节不变（N1）。
 * 另钉 §5③ 入场预览新文案「正在见面中…」。
 *
 * 手法：MockK 假掉全部协作者；`db.withTransaction` 扩展函数用 mockkStatic 桩成「同步跑 block」
 * （同 AssistantTurnControllerTest 打法）。主路径 A1 的三入口（文字/语音/表情）在
 * AssistantTurnControllerTest 内已有原生夹具，此处覆盖列表快捷回复 A2a + 入场文案。
 */
class MeetingPreviewGuardTest {

    private lateinit var messageRepo: MessageRepository
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var characterDao: CharacterDao
    private lateinit var quickReply: ListQuickReplyService

    private fun convo(inMeeting: Boolean, sessionId: String? = if (inMeeting) "sess-1" else null) =
        ConversationEntity(
            uuid = "conv-1", title = "标题", characterUuid = "c1", creationDate = 0L,
            isInOfflineMode = inMeeting, currentOfflineSessionId = sessionId,
        )

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        mockkStatic("androidx.room.RoomDatabaseKt")
        messageRepo = mockk(relaxed = true)
        conversationRepo = mockk(relaxed = true)
        characterDao = mockk(relaxed = true)
        quickReply = ListQuickReplyService(
            messageRepo = messageRepo,
            conversationRepo = conversationRepo,
            replyGenerator = mockk<RecoveryReplyGenerator>(relaxed = true),
            claimTracker = mockk<RecoveryClaimTracker>(relaxed = true),
            characterDao = characterDao,
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    // ── A2a 列表内联快捷回复（通知直接回复 / 快聊 / 分享投递共用此落库口）──

    @Test
    fun 快捷回复_见面中_只刷新时间不写预览() = runBlocking {
        coEvery { conversationRepo.get("conv-1") } returns convo(inMeeting = true)
        assertTrue(quickReply.insertUserMessage("conv-1", "在干嘛"))
        coVerify(exactly = 1) { conversationRepo.touchLastMessageDate("conv-1", any()) }
        coVerify(exactly = 0) { conversationRepo.recordLastMessage(any(), any(), any(), any()) }
        // 消息本体照常落库（闸只管预览，不吞用户输入）。
        coVerify(exactly = 1) { messageRepo.upsert(match { it.content == "在干嘛" && it.isOfflineMode }) }
    }

    @Test
    fun 快捷回复_非见面_预览原样写入() = runBlocking {
        coEvery { conversationRepo.get("conv-1") } returns convo(inMeeting = false)
        assertTrue(quickReply.insertUserMessage("conv-1", "在干嘛"))
        coVerify(exactly = 1) { conversationRepo.recordLastMessage("conv-1", "在干嘛", "user", any()) }
        coVerify(exactly = 0) { conversationRepo.touchLastMessageDate(any(), any()) }
    }

    /** E1 脏态（旗标 true 而 sessionId 空）：打标走 outgoingOfflineSessionId → 线上标，预览随打标口径照写（§3.1-A2a）。 */
    @Test
    fun 快捷回复_脏态_按打标口径走线上分支() = runBlocking {
        coEvery { conversationRepo.get("conv-1") } returns convo(inMeeting = true, sessionId = null)
        assertTrue(quickReply.insertUserMessage("conv-1", "在干嘛"))
        coVerify(exactly = 1) { messageRepo.upsert(match { !it.isOfflineMode }) }
        coVerify(exactly = 1) { conversationRepo.recordLastMessage("conv-1", "在干嘛", "user", any()) }
    }

    @Test
    fun 快捷回复_会话不存在_按线上落库不崩() = runBlocking {
        coEvery { conversationRepo.get("conv-1") } returns null
        assertTrue(quickReply.insertUserMessage("conv-1", "在干嘛"))
        coVerify(exactly = 1) { conversationRepo.recordLastMessage("conv-1", "在干嘛", "user", any()) }
    }

    // ── §5③ 入场预览文案（活预览·2026-08-26 过审选 A）──

    @Test
    fun 进入见面_写入新版活预览文案() = runBlocking {
        val db = mockk<AppDatabase>()
        coEvery { db.withTransaction<String?>(any()) } coAnswers { secondArg<suspend () -> String?>().invoke() }
        val convoRepo = mockk<ConversationRepository>(relaxed = true)
        coEvery { convoRepo.get("conv-1") } returns convo(inMeeting = false)
        val service = OfflineMeetingService(
            db = db,
            messageRepo = mockk(relaxed = true),
            conversationRepo = convoRepo,
            characterRepo = mockk<CharacterRepository>(relaxed = true),
            scheduleDao = mockk<ScheduleDao>(relaxed = true),
            userProfileDao = mockk<UserProfileDao>(relaxed = true),
        )
        service.startManualOfflineMeeting("conv-1", "咖啡馆", "喝咖啡")
        coVerify(exactly = 1) { convoRepo.recordOfflineEntered("conv-1", any(), "正在见面中…", any()) }
    }
}
