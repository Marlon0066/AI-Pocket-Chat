package com.situ.aichat.offline

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.AppSettings
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * 余温消息的**间隔行措辞**（相识天数 R1 §7.7·对抗轮 🟡-2）：余温是 TA **主动**发起的消息
 * （见 [OfflineAfterglowService] KDoc「见面结束几小时后，TA 主动发一条回味见面的短消息」），
 * 用户并没有「才回」——所以这条路必须走**中性**间隔行「距离你上条回复：约 X」，
 * 绝不能出现方向化的「…隔了约 X 才回你」（T5 复核🟡④ 立的规矩：系统欠的延迟不许甩锅给用户）。
 *
 * 该缺陷此前无门禁：`OfflineAfterglowServiceTest` / `AfterglowDeferTest` 都把组装器整个 mock 掉，
 * 从没有人验过它真拼出来的提示词。断言从规格独立反推（措辞取自 [com.situ.aichat.prompt.TimeAnchorFormatter]），
 * 而非照抄实现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class OfflineAfterglowGapLineTest {

    private val now: Instant = Instant.ofEpochMilli(1_750_000_000_000)
    private val threeHoursAgo = now.toEpochMilli() - 3 * 3_600_000L

    private fun assembler(nickname: String): OfflineAfterglowPromptAssembler {
        val userProfileDao = mockk<com.situ.aichat.data.local.dao.UserProfileDao>()
        coEvery { userProfileDao.get() } returns UserProfileEntity(nickname = nickname)
        val messageRepo = mockk<com.situ.aichat.data.repository.MessageRepository>()
        coEvery { messageRepo.recentChronological(any(), any()) } returns listOf(
            MessageEntity(
                messageUUID = "u1", conversationUuid = "conv1", roleRaw = "user",
                content = "今天见面很开心", timestamp = threeHoursAgo - 60_000,
            ),
            MessageEntity(
                messageUUID = "a1", conversationUuid = "conv1", roleRaw = "assistant",
                content = "我也是～", timestamp = threeHoursAgo,
            ),
        )
        return OfflineAfterglowPromptAssembler(
            context = RuntimeEnvironment.getApplication(),
            characterRepo = mockk(relaxed = true),
            offlineMeetingMemoryRepository = mockk(relaxed = true),
            userProfileDao = userProfileDao,
            messageRepo = messageRepo,
            vectorMemory = mockk(relaxed = true),
            memoryService = mockk(relaxed = true),
            scheduleDao = mockk(relaxed = true),
            calendarReader = mockk(relaxed = true),
            momentChatContextService = mockk(relaxed = true),
            stickerRepo = mockk(relaxed = true),
            economicStateService = mockk(relaxed = true),
            giftDao = mockk(relaxed = true),
            worldBookPromptService = mockk(relaxed = true),
        )
    }

    private fun assemble(nickname: String): String = runBlocking {
        assembler(nickname).assemble(
            convo = ConversationEntity(uuid = "conv1", title = "会话", characterUuid = "c1", creationDate = 0L),
            character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L),
            settings = AppSettings(vectorSearchThreshold = 0, calendarIntegrationEnabled = false),
            nowInstant = now,
        ).joinToString("\n\n") { it.content.orEmpty() }
    }

    @Test
    fun 余温消息_有昵称_不得出现方向化间隔行() {
        val prompt = assemble("小明")
        assertTrue("时间锚在", prompt.contains("<time_context>"))
        assertFalse("TA 是主动发起方，不能说用户『才回你』", prompt.contains("才回你"))
        assertFalse("更不能指名道姓地说", prompt.contains("小明隔了"))
        assertTrue("须是中性措辞", prompt.contains("距离你上条回复：约 3 小时"))
    }

    @Test
    fun 余温消息_无昵称_同样是中性措辞() {
        val prompt = assemble("")
        assertFalse(prompt.contains("才回你"))
        assertFalse(prompt.contains("对方隔了"))
        assertTrue(prompt.contains("距离你上条回复：约 3 小时"))
    }
}
