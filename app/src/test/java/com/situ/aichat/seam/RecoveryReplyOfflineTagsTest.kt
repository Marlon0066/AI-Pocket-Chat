package com.situ.aichat.seam

import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.OfflineMeetingMemoryRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.recovery.RecoveryReplyGenerator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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
 * 卷一 C3「无头家族」A2c 行为测试（图纸 §7 T2-C3）：见面期间经**无头管线**（列表快捷回复 / 通知直接回复）
 * 产生的 AI 回复必须①保留线下叙事标签供剧场渲染 ②单段投递（不拆句）——与主路径
 * `ChatReplyDeliverer` 同源；非见面路径逐字不变（N1：全剥标签 + 照常分段）。
 *
 * 手法：Robolectric 提供真 Context（PromptBuilder 需真资源装配提示词），协作者全 MockK 假掉，
 * LLM 出口 [ContextLogService.completion] 桩成固定原文；`db.withTransaction` 桩成同步跑 block。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryReplyOfflineTagsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var messageRepo: MessageRepository
    private lateinit var contextLog: ContextLogService
    private lateinit var db: AppDatabase
    private lateinit var generator: RecoveryReplyGenerator

    private val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)

    /** 带线下叙事标签的整段回复（长度 >50 → 非见面路径会被分段器拆句）。 */
    private val rawReply =
        "[叙述]她把伞收起来，靠在门边，睫毛上还挂着水汽。[/叙述]" +
            "[对话]你怎么也不带伞就出门啊，真是的。[/对话]" +
            "[动作]伸手替你把肩上的水珠掸掉，动作很轻很慢。[/动作]"

    private fun convo(inMeeting: Boolean) = ConversationEntity(
        uuid = "conv-1", title = "标题", characterUuid = "c1", creationDate = 0L,
        isInOfflineMode = inMeeting, currentOfflineSessionId = if (inMeeting) "sess-1" else null,
    )

    @Before
    fun setUp() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        db = mockk()
        coEvery { db.withTransaction<Unit>(any()) } coAnswers { secondArg<suspend () -> Unit>().invoke() }
        conversationRepo = mockk(relaxed = true)
        messageRepo = mockk(relaxed = true)
        contextLog = mockk(relaxed = true)
        coEvery { characterRepo.get("c1") } returns character
        coEvery { messageRepo.recentChronological("conv-1", any()) } returns listOf(
            MessageEntity(messageUUID = "u1", conversationUuid = "conv-1", roleRaw = "user", content = "我到啦", timestamp = 1L),
        )
        coEvery { apiConfigRepo.resolveConfigValues(any()) } returns mockk<ApiConfigValues>(relaxed = true)
        coEvery { settingsRepo.getAppSettings() } returns AppSettings()
        coEvery { offlineMemoryRepo.renderedForInjection(any()) } returns ""
        coEvery {
            contextLog.completion(
                source = any(), characterName = any(), config = any(), messages = any(),
                temperature = any(), maxTokens = any(), responseFormat = any(),
                segments = any(), onFinishReason = any(),
            )
        } returns rawReply
        generator = RecoveryReplyGenerator(
            context = context,
            conversationRepo = conversationRepo,
            characterRepo = characterRepo,
            offlineMeetingMemoryRepository = offlineMemoryRepo,
            apiConfigRepo = apiConfigRepo,
            settingsRepo = settingsRepo,
            userProfileDao = mockk(relaxed = true),
            messageRepo = messageRepo,
            vectorMemory = mockk(relaxed = true),
            memoryService = mockk(relaxed = true),
            scheduleDao = mockk(relaxed = true),
            calendarReader = mockk(relaxed = true),
            momentChatContextService = mockk(relaxed = true),
            stickerRepo = mockk(relaxed = true),
            economicStateService = mockk(relaxed = true),
            giftDao = mockk(relaxed = true),
            contextLog = contextLog,
            db = db,
            worldBookPromptService = mockk(relaxed = true),
        )
    }

    private val characterRepo = mockk<CharacterRepository>(relaxed = true)
    private val apiConfigRepo = mockk<ApiConfigRepository>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val offlineMemoryRepo = mockk<OfflineMeetingMemoryRepository>(relaxed = true)

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    private fun storedMessages(): List<MessageEntity> {
        val slot = mutableListOf<MessageEntity>()
        coVerify { messageRepo.upsert(capture(slot)) }
        return slot
    }

    @Test
    fun 见面中_保留叙事标签且单段落库() = runBlocking {
        coEvery { conversationRepo.get("conv-1") } returns convo(inMeeting = true)
        assertTrue(generator.generateAndPersist("conv-1"))
        val stored = storedMessages()
        assertEquals("见面中必须单段投递（不拆句）", 1, stored.size)
        val content = stored.single().content
        assertTrue("必须保留 [叙述] 标签供剧场渲染: $content", content.contains("[叙述]"))
        assertTrue("必须保留 [对话] 标签: $content", content.contains("[对话]"))
        assertTrue("必须保留 [动作] 标签: $content", content.contains("[动作]"))
        assertTrue("落库须打线下标", stored.single().isOfflineMode)
        // 见面期不写预览、不 +1 未读（既有行为，随本改动一并守住）。
        coVerify(exactly = 1) { conversationRepo.touchLastMessageDate("conv-1", any()) }
        coVerify(exactly = 0) { conversationRepo.applyMaterialization(any(), any(), any(), any()) }
    }

    @Test
    fun 非见面_剥光叙事标签且照常分段() = runBlocking {
        coEvery { conversationRepo.get("conv-1") } returns convo(inMeeting = false)
        assertTrue(generator.generateAndPersist("conv-1"))
        val stored = storedMessages()
        // 对照证据（防「单段断言恒真」的假绿）：同一段原文在非见面路径会被分段器拆成多条。
        assertTrue("非见面路径应分多段，实际 ${stored.size} 段", stored.size > 1)
        stored.forEach {
            assertTrue("非见面路径绝不留标签: ${it.content}", !it.content.contains("[叙述]") && !it.content.contains("[对话]"))
            assertTrue("非见面落库须为线上标", !it.isOfflineMode)
        }
        coVerify(exactly = 1) { conversationRepo.applyMaterialization(any(), any(), any(), any()) }
        coVerify(exactly = 0) { conversationRepo.touchLastMessageDate(any(), any()) }
    }
}
