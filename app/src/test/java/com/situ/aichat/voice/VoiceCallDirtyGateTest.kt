package com.situ.aichat.voice

import android.util.Log
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.prompt.memory.VectorMemoryService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 语音通话回合转写的落库前置闸接线（图纸 2026-09-01 件①·T2-1·E4）。
 *
 * 断言从规格独立反推：脏转写 → [VoiceCallPersistence.saveAiMessage] 返 false 且**一个字都不写库**
 * （零 upsert / 零预览更新 / 零嵌入 / 零后续回合）；干净转写照旧全套落地。
 * 为什么这一站值得单钉：语音回合的落库 kind 恒 PLAIN_TEXT、且不经分条闸，纯函数测试覆盖不到它的接线。
 */
class VoiceCallDirtyGateTest {

    private val messageRepo = mockk<MessageRepository>(relaxed = true)
    private val conversationRepo = mockk<ConversationRepository>(relaxed = true)
    private val characterRepo = mockk<CharacterRepository>(relaxed = true)
    private val userProfileDao = mockk<UserProfileDao>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val vectorMemory = mockk<VectorMemoryService>(relaxed = true)
    private val postReplyRounds = mockk<VoiceCallPostReplyRounds>(relaxed = true)

    private val persistence = VoiceCallPersistence(
        messageRepo, conversationRepo, characterRepo, userProfileDao, settingsRepo, vectorMemory, postReplyRounds,
    )

    @Before fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        coEvery { characterRepo.get(any()) } returns null
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    /** 模型把系统留痕行原样复读出来（此处重新打字为字面量，不引实现常量）。 */
    private val dirtyTranscript = "[系统记录：线下见面结束（约40分钟），两人回到了线上聊天]"

    @Test
    fun dirtyTranscript_returnsFalse_andWritesNothing() = runBlocking {
        val saved = persistence.saveAiMessage("conv-1", "char-1", dirtyTranscript)
        assertFalse("脏转写必须被拒", saved)
        coVerify(exactly = 0) { messageRepo.upsert(any<MessageEntity>()) }
        coVerify(exactly = 0) { conversationRepo.recordLastMessage(any(), any(), any(), any()) }
        coVerify(exactly = 0) { vectorMemory.embedMessageIfNeeded(any()) }
        coVerify(exactly = 0) { postReplyRounds.onAssistantMessagePersisted(any(), any(), any(), any()) }
    }

    @Test
    fun cleanTranscript_persistsAsBefore() = runBlocking {
        // 正向证据：闸门没有把正常转写一起吃掉（全否定断言必须配一条正路，否则「什么都没跑」也是绿）。
        val saved = persistence.saveAiMessage("conv-1", "char-1", "在的，我刚忙完")
        assertTrue("干净转写必须照旧落库", saved)
        coVerify(exactly = 1) { messageRepo.upsert(any<MessageEntity>()) }
        coVerify(exactly = 1) { conversationRepo.recordLastMessage("conv-1", any(), "assistant", any()) }
    }
}
