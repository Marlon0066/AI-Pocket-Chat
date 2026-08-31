package com.situ.aichat.ui.chat

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test

/**
 * [refreshConversationLastMessage] 行为测试——验证「删消息后重算会话列表『最后一条』预览快照」真的对（问题②修复）。
 *
 * 背景 bug：删一条消息此前无人重算会话反范式存的 `lastMessagePreview` → 删掉最后一条后，聊天列表仍显示那条已删
 * 消息的预览。手法：MockK 假掉两仓库（不碰真 DB·确定性·秒级·无设备），喂不同的「删后最新可见消息」，
 * coVerify 写回的预览/角色/时间正确，且结构化卡（红包）经 [MessagePreviewText] 脱敏（绝不露原始 JSON）；
 * 删空（latestVisibleMessage=null）走 clearLastMessage、不写 recordLastMessage。
 */
class ConversationPreviewRefreshTest {

    private val conv = "conv-1"
    private val messageRepo = mockk<MessageRepository>()
    private val conversationRepo = mockk<ConversationRepository>(relaxed = true)

    private fun message(
        uuid: String,
        role: String,
        content: String,
        timestamp: Long,
        kindRaw: String = "plain_text",
        isVoiceMessage: Boolean = false,
    ) = MessageEntity(
        messageUUID = uuid,
        conversationUuid = conv,
        roleRaw = role,
        content = content,
        timestamp = timestamp,
        messageKindRaw = kindRaw,
        isVoiceMessage = isVoiceMessage,
    )

    @Test
    fun `删后_快照重算为新的最新文本消息`() = runBlocking {
        coEvery { messageRepo.latestVisibleMessages(conv, any()) } returns listOf(message("m-user", "user", "那早点睡呀", 5_000L))

        refreshConversationLastMessage(conv, messageRepo, conversationRepo)

        coVerify(exactly = 1) { conversationRepo.recordLastMessage(conv, "那早点睡呀", "user", 5_000L) }
        coVerify(exactly = 0) { conversationRepo.clearLastMessage(any()) }
    }

    @Test
    fun `新最新是红包卡_预览脱敏为人话而非原始JSON`() = runBlocking {
        coEvery { messageRepo.latestVisibleMessages(conv, any()) } returns
            listOf(message("m-rp", "assistant", """{"amount":888,"blessing":"恭喜发财"}""", 6_000L, kindRaw = "red_packet"))

        refreshConversationLastMessage(conv, messageRepo, conversationRepo)

        // 关键：写回的是 MessagePreviewText 脱敏后的「🧧 红包」，绝不是含金额的原始 JSON。
        coVerify(exactly = 1) { conversationRepo.recordLastMessage(conv, "🧧 红包", "assistant", 6_000L) }
    }

    @Test
    fun `删空整会话_清空快照而非写预览`() = runBlocking {
        coEvery { messageRepo.latestVisibleMessages(conv, any()) } returns emptyList()

        refreshConversationLastMessage(conv, messageRepo, conversationRepo)

        coVerify(exactly = 1) { conversationRepo.clearLastMessage(conv) }
        coVerify(exactly = 0) { conversationRepo.recordLastMessage(any(), any(), any(), any()) }
    }

    // ────────────── 图纸 2026-09-01 件①：脏行不进列表预览（T2-2·E7） ──────────────

    /** 模型复读记忆段标题 = 典型脏输出（此处重新打字为字面量，不引检测器常量）。 */
    private val dirtyEcho = "【长期事实】\n- 喜欢猫\n【近期经历】\n- [2026-06-10] 去了公园"

    @Test
    fun `最新一条是库内脏行_预览取下一条非脏消息`() = runBlocking {
        coEvery { messageRepo.latestVisibleMessages(conv, any()) } returns listOf(
            message("m-dirty", "assistant", dirtyEcho, 9_000L),
            message("m-clean", "assistant", "那我七点到楼下等你", 8_500L),
            message("m-older", "user", "好呀", 8_000L),
        )

        refreshConversationLastMessage(conv, messageRepo, conversationRepo)

        // 快照取第一条非脏消息（含它自己的角色与时间戳），脏行绝不上列表。
        coVerify(exactly = 1) { conversationRepo.recordLastMessage(conv, "那我七点到楼下等你", "assistant", 8_500L) }
        coVerify(exactly = 0) { conversationRepo.clearLastMessage(any()) }
    }

    @Test
    fun `扫描窗内全脏_清空快照而非露脏文`() = runBlocking {
        coEvery { messageRepo.latestVisibleMessages(conv, any()) } returns
            (1..10).map { message("m-d$it", "assistant", dirtyEcho, 9_000L - it) }

        refreshConversationLastMessage(conv, messageRepo, conversationRepo)

        coVerify(exactly = 1) { conversationRepo.clearLastMessage(conv) }
        coVerify(exactly = 0) { conversationRepo.recordLastMessage(any(), any(), any(), any()) }
    }

    // ────────────── 审计 B1：口径对齐各插入点（拍板 2026-07-02） ──────────────

    @Test
    fun `新最新是用户语音_预览带语音前缀并截断40字_对齐插入口径`() = runBlocking {
        val transcript = "今天路过那家店突然想到你说想吃的那个栗子蛋糕就排队买了一个想着周末给你带过去尝尝看" // >40 字
        coEvery { messageRepo.latestVisibleMessages(conv, any()) } returns
            listOf(message("m-v", "user", transcript, 7_000L, isVoiceMessage = true))

        refreshConversationLastMessage(conv, messageRepo, conversationRepo)

        // 与 AssistantTurnController.sendVoiceMessage 插入式逐字一致："[语音] " + 前 40 字。
        coVerify(exactly = 1) {
            conversationRepo.recordLastMessage(conv, "[语音] " + transcript.take(40), "user", 7_000L)
        }
    }

    @Test
    fun `新最新是assistant超长文本_截断50字_对齐插入口径`() = runBlocking {
        val longText = "长".repeat(80)
        coEvery { messageRepo.latestVisibleMessages(conv, any()) } returns listOf(message("m-a", "assistant", longText, 8_000L))

        refreshConversationLastMessage(conv, messageRepo, conversationRepo)

        // 与 assistantDeliveryPreview 的 take(50) 逐字一致；用户文字侧不截断（插入点也不截）。
        coVerify(exactly = 1) { conversationRepo.recordLastMessage(conv, "长".repeat(50), "assistant", 8_000L) }
    }

    // ────────────── 审计 R2：per-会话锁串行化「读+写」 ──────────────

    @Test
    fun `连删竞态_迟到的旧读绝不最后落地`() = runBlocking {
        // 场景：自底向上连删两条。第一次重算读到「旧消息」后被挂起（模拟读写之间第二次删除整轮插队完成）；
        // 无锁时第二次重算（删空→clear）会先完成、迟到的 recordLastMessage(旧) 最后落地 = 已删消息重新挂上列表。
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        coEvery { messageRepo.latestVisibleMessages(conv, any()) } coAnswers {
            if (++reads == 1) {
                gate.await()
                listOf(message("m-old", "user", "旧消息", 1_000L))
            } else {
                emptyList() // 第二次删除后的重算：会话已删空
            }
        }

        val first = launch { refreshConversationLastMessage(conv, messageRepo, conversationRepo) }
        yield() // 让 first 进锁、挂在读上
        val second = launch { refreshConversationLastMessage(conv, messageRepo, conversationRepo) }
        yield() // 无锁实现下 second 此刻会直接完成（先 clear）→ 下方顺序断言失败
        gate.complete(Unit)
        first.join()
        second.join()

        // 锁保证「读+写」原子：first 的旧写回先落地、second 的 clear 终值最后落地（列表绝不停在已删消息）。
        coVerifyOrder {
            conversationRepo.recordLastMessage(conv, "旧消息", "user", 1_000L)
            conversationRepo.clearLastMessage(conv)
        }
    }
}
