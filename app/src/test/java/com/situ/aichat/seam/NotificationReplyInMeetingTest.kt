package com.situ.aichat.seam

import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.notification.NotificationReplyThread
import com.situ.aichat.notification.Notifier
import com.situ.aichat.quickreply.ListQuickReplyService
import com.situ.aichat.work.NotificationReplyWorker
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 卷一 C3「无头家族」A2b 行为测试（图纸 §7 T2-C3 / J4）：通知直接回复撞见面时的终态 =
 * **合成线程（只含用户那句）+「已送达 · 你们正在见面中」状态行**，且**绝不**读会话可见消息
 * （见面中刚落的两条会被 SQL 滤光，读出来的是过期旧对话）。ok=false 走现状 deferred 分支（E6）。
 *
 * 手法：Robolectric 真 Context；worker 直接构造（[WorkerParameters] MockK 假），
 * [Notifier] mockkObject 捕获回推参数。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationReplyInMeetingTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var quickReply: ListQuickReplyService
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var messageRepository: MessageRepository

    private fun convo(inMeeting: Boolean) = ConversationEntity(
        uuid = "conv-1", title = "标题", characterUuid = "c1", creationDate = 0L,
        isInOfflineMode = inMeeting, currentOfflineSessionId = if (inMeeting) "sess-1" else null,
    )

    private fun worker(): NotificationReplyWorker {
        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.inputData } returns workDataOf(
            "conversationUuid" to "conv-1",
            "characterId" to "c1",
            "title" to "小雨",
            "avatarPath" to "",
            "notificationId" to 7,
            "text" to "我到门口了",
        )
        return NotificationReplyWorker(context, params, quickReply, conversationRepository, messageRepository)
    }

    @Before
    fun setUp() {
        mockkObject(Notifier)
        every { Notifier.postChatReply(any(), any(), any(), any(), any(), any(), any(), any()) } returns Unit
        quickReply = mockk(relaxed = true)
        conversationRepository = mockk(relaxed = true)
        messageRepository = mockk(relaxed = true)
        coEvery { messageRepository.recentVisibleChronological(any(), any()) } returns listOf(
            MessageEntity(messageUUID = "old", conversationUuid = "conv-1", roleRaw = "assistant", content = "上周的旧对话", timestamp = 1L),
        )
    }

    @After
    fun tearDown() {
        unmockkObject(Notifier)
    }

    /** 回推的最后一次调用（① 是「正在回复…」，最后一次才是终态）。 */
    private fun finalPost(): Pair<List<NotificationReplyThread.ReplyThreadMessage>, String?> {
        val messages = mutableListOf<List<NotificationReplyThread.ReplyThreadMessage>>()
        val hints = mutableListOf<String?>()
        verify { Notifier.postChatReply(any(), any(), any(), any(), any(), any(), capture(messages), captureNullable(hints)) }
        return messages.last() to hints.last()
    }

    @Test
    fun 见面中回合成功_合成线程加已送达状态行_不读可见消息() = runBlocking {
        coEvery { conversationRepository.get("conv-1") } returns convo(inMeeting = true)
        coEvery { quickReply.sendAndAwait("conv-1", "我到门口了") } returns true
        worker().doWork()
        val (messages, hint) = finalPost()
        assertEquals(1, messages.size)
        assertEquals("我到门口了", messages.single().text)
        assertTrue(messages.single().isUser)
        // Robolectric 默认英文 locale → 取 values/ 串；zh 串在下面的 qualifiers 用例逐字锁。
        assertEquals("Delivered · you two are meeting right now — open the app to continue", hint)
        // 见面中刚落的两条会被「见面细节不进日常聊天」的 SQL 滤掉 → 绝不读，读了就是把过期内容当新回复弹给用户。
        io.mockk.coVerify(exactly = 0) { messageRepository.recentVisibleChronological(any(), any()) }
    }

    /** E6：见面中但回合失败 → 走现状 deferred 分支（文案不变，仍读可见消息建线程）。 */
    @Test
    fun 见面中回合失败_走现状deferred分支() = runBlocking {
        coEvery { conversationRepository.get("conv-1") } returns convo(inMeeting = true)
        coEvery { quickReply.sendAndAwait("conv-1", "我到门口了") } returns false
        worker().doWork()
        val (_, hint) = finalPost()
        assertEquals("Will reply soon", hint)
        io.mockk.coVerify(exactly = 1) { messageRepository.recentVisibleChronological(any(), any()) }
    }

    /** E10 双语资源逐字锁（zh-rCN·§3.1-A2b）：中文 locale 下取到的状态行必须一字不差。 */
    @Test
    @Config(sdk = [34], qualifiers = "zh-rCN")
    fun 见面中状态行_中文串逐字锁() = runBlocking {
        coEvery { conversationRepository.get("conv-1") } returns convo(inMeeting = true)
        coEvery { quickReply.sendAndAwait("conv-1", "我到门口了") } returns true
        worker().doWork()
        val (_, hint) = finalPost()
        assertEquals("已送达 · 你们正在见面中，打开应用继续", hint)
    }

    /** N1：非见面路径行为字节不变——成功即读可见消息建线程、无状态行。 */
    @Test
    fun 非见面_成功终态与现状一致() = runBlocking {
        coEvery { conversationRepository.get("conv-1") } returns convo(inMeeting = false)
        coEvery { quickReply.sendAndAwait("conv-1", "我到门口了") } returns true
        worker().doWork()
        val (messages, hint) = finalPost()
        assertEquals(null, hint)
        assertEquals("上周的旧对话", messages.single().text)
        io.mockk.coVerify(exactly = 1) { messageRepository.recentVisibleChronological(any(), any()) }
    }
}
