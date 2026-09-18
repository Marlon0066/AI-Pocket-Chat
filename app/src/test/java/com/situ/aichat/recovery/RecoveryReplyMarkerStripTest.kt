package com.situ.aichat.recovery

import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.OfflineMeetingMemoryRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.meeting.FutureMeetingTool
import com.situ.aichat.promise.PromiseChatTool
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 未答恢复 / 通知直接回复（共用 [RecoveryReplyGenerator] 无头管线）的暗号标记补剥（2026-09-18 提示词册核对 A 条）。
 *
 * 复现链：本路 `PromptBuilder.buildMessages` 走暗号模式、非通话 → 守卫卡照注 `[future_meeting]{…}` 与 `[promise]{…}`
 * 两条规则；旧实现只走 parseMood / sanitize（ReplyParser 不认这两种标记）→ 模型照规则附的 JSON 原样落库、进列表预览。
 * 本测先钉「规则确实注入了」（复现前提），再钉「落库正文零暗号、正常聊天内容完整」。
 *
 * 手法同 `seam.RecoveryReplyOfflineTagsTest`：Robolectric 真资源装配提示词，协作者 MockK，LLM 出口桩成固定原文。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryReplyMarkerStripTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val conversationRepo = mockk<ConversationRepository>(relaxed = true)
    private val messageRepo = mockk<MessageRepository>(relaxed = true)
    private val contextLog = mockk<ContextLogService>(relaxed = true)
    private val characterRepo = mockk<CharacterRepository>(relaxed = true)
    private val apiConfigRepo = mockk<ApiConfigRepository>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val offlineMemoryRepo = mockk<OfflineMeetingMemoryRepository>(relaxed = true)
    private lateinit var db: AppDatabase
    private lateinit var generator: RecoveryReplyGenerator
    private val sentMessages = slot<List<ChatMessageDto>>()

    /** 模型照规则在末尾附了约见面 + 约定两行暗号，外加一句宠物发言（值里带 `~` / `}` 以考验配平扫描）。 */
    private val rawReply =
        "好呀，那就周六下午一起去看展吧，我已经开始期待了！到时候我去楼下等你，别迟到哦。[PET:喵~]\n" +
            """[future_meeting]{"when_text":"周六下午","location":"美术馆","activity":"看展","invitation":"周六一起去看展吧~}","tension_hint":"","hidden_tension":""}""" + "\n" +
            """[promise]{"action":"record","content":"周六小雨和阿哲一起去看展","due":"","evidence":"那就周六下午一起去看展吧"}"""

    @Before
    fun setUp() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        db = mockk()
        coEvery { db.withTransaction<Unit>(any()) } coAnswers { secondArg<suspend () -> Unit>().invoke() }
        coEvery { conversationRepo.get("conv-1") } returns ConversationEntity(
            uuid = "conv-1", title = "标题", characterUuid = "c1", creationDate = 0L,
        )
        coEvery { characterRepo.get("c1") } returns CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)
        coEvery { messageRepo.recentChronological("conv-1", any()) } returns listOf(
            MessageEntity(messageUUID = "u1", conversationUuid = "conv-1", roleRaw = "user", content = "周六有空吗", timestamp = 1L),
        )
        coEvery { apiConfigRepo.resolveConfigValues(any()) } returns mockk<ApiConfigValues>(relaxed = true)
        coEvery { settingsRepo.getAppSettings() } returns AppSettings()
        coEvery { offlineMemoryRepo.renderedForInjection(any()) } returns ""
        coEvery {
            contextLog.completion(
                source = any(), characterName = any(), config = any(), messages = capture(sentMessages),
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

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun 复现前提_本路提示词确实注入了约见面与约定两条暗号规则() = runBlocking {
        assertTrue(generator.generateAndPersist("conv-1"))
        val system = sentMessages.captured.filter { it.role == "system" }.joinToString("\n") { it.content.orEmpty() }
        assertTrue("本路应注入约见面暗号规则", system.contains(FutureMeetingTool.FALLBACK_MARKER_RULE))
        assertTrue("本路应注入约定暗号规则", system.contains(PromiseChatTool.FALLBACK_MARKER_RULE))
    }

    @Test
    fun 落库正文零暗号_正常聊天内容完整() = runBlocking {
        assertTrue(generator.generateAndPersist("conv-1"))
        val stored = mutableListOf<MessageEntity>()
        coVerify { messageRepo.upsert(capture(stored)) }
        val all = stored.joinToString("\n") { it.content }
        for (leak in listOf("[future_meeting]", "[promise]", "when_text", "\"action\"", "[PET:", "喵~")) {
            assertFalse("落库正文不许出现「$leak」：$all", all.contains(leak))
        }
        // 正向证据：角色真正说的话一个字没丢（防「整条被判脏丢掉」的假绿）。
        assertTrue("正常内容应完整保留：$all", all.contains("那就周六下午一起去看展吧"))
        assertTrue("正常内容应完整保留：$all", all.contains("别迟到哦"))
        // 列表预览同样不许带暗号。
        val preview = slot<String>()
        coVerify { conversationRepo.applyMaterialization("conv-1", capture(preview), any(), any()) }
        assertFalse("列表预览不许带暗号：${preview.captured}", preview.captured.contains("[promise]") || preview.captured.contains("[future_meeting]"))
    }
}
