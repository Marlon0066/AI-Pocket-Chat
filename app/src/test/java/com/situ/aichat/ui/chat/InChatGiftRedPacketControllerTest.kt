package com.situ.aichat.ui.chat

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.local.dao.RedPacketDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.gift.GiftItem
import com.situ.aichat.gift.GiftSendService
import com.situ.aichat.notification.NotificationLearningService
import com.situ.aichat.redpacket.RedPacketAcceptanceDecisionService
import com.situ.aichat.redpacket.RedPacketError
import com.situ.aichat.redpacket.RedPacketExpirationScanService
import com.situ.aichat.redpacket.RedPacketSendOutcome
import com.situ.aichat.redpacket.RedPacketService
import com.situ.aichat.util.ContentImageStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * InChatGiftRedPacketController 行为测试——验证刀9 礼物 / 红包钱路协作者「真的能用」（不止编译过）。
 *
 * 手法：MockK 假掉全部协作者（repo/dao/service/engine）；scope 用 [Dispatchers.Unconfined]（让 sendRedPacket 的
 * `scope.launch` 异步决策同步跑完，便于确定性断言）；enqueueTurn 用计数 lambda（C1 后回合入合并等待窗）；StreakManager 是纯逻辑放真跑。
 * 静态对象 ContentImageStore / android.util.Log 只在用到的用例 mockkObject / mockkStatic + try-finally 卸自己
 * （绝不 unmockkAll 污染同 JVM 后续测试类）。
 *
 * 覆盖：送礼成功触发 AI 回复 / 扣币失败不回复 / 会话不存在不送；DIY 有图落盘取 path、空标题以「手作礼物」收纳；
 * replyAfterGift 三态（无 API 跳过 / 忙碌收纳 / 空闲记账后 enqueueTurn 入窗）；
 * 红包发送四分支（成功写预览 + 异步决策 / 余额不足 / 其他错误 / 会话或角色缺失）；拆红包（成功撤闹钟 / 并发已解决吞错不抛）；
 * 礼物卡取记录 + 红包状态响应式观察透传 + DIY 取图经 ContentImageStore。
 */
class InChatGiftRedPacketControllerTest {

    private lateinit var conversationRepo: ConversationRepository
    private lateinit var characterRepo: CharacterRepository
    private lateinit var apiConfigRepo: ApiConfigRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var notificationLearningService: NotificationLearningService
    private lateinit var giftDao: GiftDao
    private lateinit var giftSendService: GiftSendService
    private lateinit var redPacketService: RedPacketService
    private lateinit var redPacketDecisionService: RedPacketAcceptanceDecisionService
    private lateinit var redPacketExpirationScanService: RedPacketExpirationScanService
    private lateinit var redPacketDao: RedPacketDao
    private var enqueuedTurns = 0
    private lateinit var controller: InChatGiftRedPacketController

    private val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)

    private fun convo() = ConversationEntity(
        uuid = "conv-1", title = "标题", characterUuid = "c1", creationDate = 0L,
    )

    private fun giftSuccess() = GiftSendService.InChatSendOutcome.Success(
        message = mockk(relaxed = true), record = mockk(relaxed = true),
    )

    @Before
    fun setUp() {
        conversationRepo = mockk(relaxed = true)
        characterRepo = mockk(relaxed = true)
        apiConfigRepo = mockk(relaxed = true)
        settingsRepo = mockk(relaxed = true)
        notificationLearningService = mockk(relaxed = true)
        giftDao = mockk(relaxed = true)
        giftSendService = mockk(relaxed = true)
        redPacketService = mockk(relaxed = true)
        redPacketDecisionService = mockk(relaxed = true)
        redPacketExpirationScanService = mockk(relaxed = true)
        redPacketDao = mockk(relaxed = true)
        enqueuedTurns = 0
        // 默认：会话/角色存在、API 已配置、空闲（非忙碌）——个别用例覆盖。
        coEvery { conversationRepo.get("conv-1") } returns convo()
        coEvery { characterRepo.get("c1") } returns character
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.CHAT) } returns mockk<ApiConfigValues>(relaxed = true)
        controller = InChatGiftRedPacketController(
            scope = CoroutineScope(Dispatchers.Unconfined),
            appContext = mockk(relaxed = true),
            conversationUuid = "conv-1",
            conversationRepo = conversationRepo,
            characterRepo = characterRepo,
            apiConfigRepo = apiConfigRepo,
            settingsRepo = settingsRepo,
            notificationLearningService = notificationLearningService,
            giftDao = giftDao,
            giftSendService = giftSendService,
            redPacketService = redPacketService,
            redPacketDecisionService = redPacketDecisionService,
            redPacketExpirationScanService = redPacketExpirationScanService,
            redPacketDao = redPacketDao,
            enqueueTurn = { enqueuedTurns++ },
        )
    }

    // ────────────────────────── 送目录礼物 ──────────────────────────

    @Test
    fun 送礼成功_原子发送并触发AI回复() = runBlocking {
        coEvery { giftSendService.sendInChat(any(), any(), any(), any()) } returns giftSuccess()
        val result = controller.sendGiftInChat(mockk<GiftItem>(relaxed = true) { every { name } returns "玫瑰花" })
        assertTrue(result is GiftSendService.InChatSendOutcome.Success)
        coVerify { giftSendService.sendInChat(any(), "c1", "conv-1", any()) }
        assertEquals(1, enqueuedTurns) // C1：回合入合并等待窗（enqueueExternalTurn），不再直跑引擎
    }

    /** 相识天数图纸 §5 E6：送礼路的受理尾段同样落「第一次聊天时间」（字段空的角色第一次互动即是送礼）。 */
    @Test
    fun 送礼成功_字段空时落第一次聊天时间() = runBlocking {
        coEvery { giftSendService.sendInChat(any(), any(), any(), any()) } returns giftSuccess()
        controller.sendGiftInChat(mockk<GiftItem>(relaxed = true) { every { name } returns "玫瑰花" })
        coVerify(exactly = 1) { characterRepo.markFirstMessageDate("c1", any()) }
    }

    @Test
    fun 送礼扣币失败_不触发回复() = runBlocking {
        coEvery { giftSendService.sendInChat(any(), any(), any(), any()) } returns GiftSendService.InChatSendOutcome.SpendFailed
        val result = controller.sendGiftInChat(mockk(relaxed = true))
        assertEquals(GiftSendService.InChatSendOutcome.SpendFailed, result)
        assertEquals(0, enqueuedTurns)
    }

    @Test
    fun 送礼_会话不存在_返回SpendFailed且不发送() = runBlocking {
        coEvery { conversationRepo.get("conv-1") } returns null
        val result = controller.sendGiftInChat(mockk(relaxed = true))
        assertEquals(GiftSendService.InChatSendOutcome.SpendFailed, result)
        coVerify(exactly = 0) { giftSendService.sendInChat(any(), any(), any(), any()) }
    }

    // ────────────────────────── 送 DIY 手作礼物 ──────────────────────────

    @Test
    fun 送DIY有图_落盘取path后发送并回复() = runBlocking {
        mockkObject(ContentImageStore)
        try {
            coEvery { ContentImageStore.save(any(), any()) } returns "saved/diy.jpg"
            coEvery { giftSendService.sendUserDIYInChat(any(), any(), any(), any(), any(), any(), any()) } returns giftSuccess()
            controller.sendDiyGift("生日蛋糕", "祝你生日快乐", mockk<Uri>(relaxed = true), 50)
            // 落盘 path 须原样透传给发送服务（图永不进 LLM，仅本地气泡渲染）。
            coVerify { giftSendService.sendUserDIYInChat("生日蛋糕", "祝你生日快乐", "saved/diy.jpg", 50, "c1", "conv-1", any()) }
            assertEquals(1, enqueuedTurns)
        } finally {
            unmockkObject(ContentImageStore)
        }
    }

    @Test
    fun 送DIY空标题_无图不落盘_照常触发回合() = runBlocking {
        // 忙碌延迟回复已删除（2026-07-11）：送礼后一律即时触发回合。
        coEvery { giftSendService.sendUserDIYInChat(any(), any(), any(), any(), any(), any(), any()) } returns giftSuccess()
        controller.sendDiyGift("   ", "内容", imageUri = null, cost = 30)
        coVerify { giftSendService.sendUserDIYInChat("   ", "内容", null, 30, "c1", "conv-1", any()) }
        assertEquals(1, enqueuedTurns)
    }

    // ────────────────────────── replyAfterGift 三态 ──────────────────────────

    @Test
    fun 送礼后_无API配置_跳过回复() = runBlocking {
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.CHAT) } returns null
        coEvery { giftSendService.sendInChat(any(), any(), any(), any()) } returns giftSuccess()
        controller.sendGiftInChat(mockk(relaxed = true))
        assertEquals(0, enqueuedTurns)
    }

    @Test
    fun 送礼后_空闲态_火花通知记账_入合并等待窗() = runBlocking {
        // isSending 生命周期随回合移入 AssistantTurnController.launchWindowTurn（其测试覆盖）,
        // 本协作者只负责逐礼记账 + enqueueTurn（忙碌延迟回复已删除 2026-07-11）。
        coEvery { giftSendService.sendInChat(any(), any(), any(), any()) } returns giftSuccess()
        controller.sendGiftInChat(mockk(relaxed = true))
        coVerify { notificationLearningService.recordUserResponse("c1", any()) }
        assertEquals(1, enqueuedTurns)
    }

    // ────────────────────────── 发红包 ──────────────────────────

    @Test
    fun 发红包成功_写会话预览_异步触发收拒决策_返回Success() = runBlocking {
        coEvery { redPacketService.sendFromUser(any(), any(), any(), any(), any(), any(), any()) } returns
            mockk<RedPacketService.SendOutcome>(relaxed = true)
        val result = controller.sendRedPacketInChat(amount = 88, blessing = "恭喜发财")
        assertEquals(RedPacketSendOutcome.Success, result)
        coVerify { conversationRepo.recordLastMessage("conv-1", "🧧 红包", "user", any()) }
        coVerify { redPacketDecisionService.decideAndApply(any(), any()) } // scope.launch 经 Unconfined 同步跑完
    }

    @Test
    fun 发红包_余额不足_返回InsufficientBalance且不写预览不决策() = runBlocking {
        coEvery { redPacketService.sendFromUser(any(), any(), any(), any(), any(), any(), any()) } throws
            RedPacketError.InsufficientBalance(need = 200, have = 30)
        val result = controller.sendRedPacketInChat(amount = 200, blessing = "")
        assertEquals(RedPacketSendOutcome.InsufficientBalance(200, 30), result)
        coVerify(exactly = 0) { conversationRepo.recordLastMessage(any(), any(), any(), any()) }
        coVerify(exactly = 0) { redPacketDecisionService.decideAndApply(any(), any()) }
    }

    @Test
    fun 发红包_其他红包错误_返回Failed携带错误文案() = runBlocking {
        coEvery { redPacketService.sendFromUser(any(), any(), any(), any(), any(), any(), any()) } throws
            RedPacketError.ReceiverMissing
        val result = controller.sendRedPacketInChat(amount = 66, blessing = "")
        assertEquals(RedPacketSendOutcome.Failed("接收方信息缺失"), result)
        coVerify(exactly = 0) { redPacketDecisionService.decideAndApply(any(), any()) }
    }

    @Test
    fun 发红包_会话不存在_返回Failed且不发包() = runBlocking {
        coEvery { conversationRepo.get("conv-1") } returns null
        val result = controller.sendRedPacketInChat(amount = 66, blessing = "")
        assertEquals(RedPacketSendOutcome.Failed("会话不存在"), result)
        coVerify(exactly = 0) { redPacketService.sendFromUser(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun 发红包_角色不存在_返回Failed且不发包() = runBlocking {
        coEvery { characterRepo.get("c1") } returns null
        val result = controller.sendRedPacketInChat(amount = 66, blessing = "")
        assertEquals(RedPacketSendOutcome.Failed("角色不存在"), result)
        coVerify(exactly = 0) { redPacketService.sendFromUser(any(), any(), any(), any(), any(), any(), any()) }
    }

    // ────────────────────────── 拆红包 ──────────────────────────

    @Test
    fun 拆红包成功_转账并撤22h预警闹钟() = runBlocking {
        controller.openRedPacket("rp-1")
        coVerify { redPacketService.acceptRedPacket("rp-1", any()) }
        coVerify { redPacketExpirationScanService.cancelWarningAlarm("rp-1") }
    }

    @Test
    fun 拆红包_并发已解决_吞红包错误不抛且不撤闹钟() = runBlocking {
        mockkStatic(Log::class)
        try {
            every { Log.w(any<String>(), any<String>()) } returns 0
            coEvery { redPacketService.acceptRedPacket(any(), any()) } throws RedPacketError.RecordNotFound("rp-x")
            controller.openRedPacket("rp-x") // 不抛异常
            coVerify(exactly = 0) { redPacketExpirationScanService.cancelWarningAlarm(any()) } // 异常在撤闹钟前抛出
        } finally {
            unmockkStatic(Log::class)
        }
    }

    // ────────────────────────── 礼物卡 / 红包气泡读取透传 ──────────────────────────

    @Test
    fun 取礼物记录_与红包状态观察_纯透传() = runBlocking {
        val rec = mockk<GiftRecordEntity>(relaxed = true)
        coEvery { giftDao.getByUuid("g1") } returns rec
        assertSame(rec, controller.giftRecord("g1"))

        val flow = flowOf<com.situ.aichat.data.local.entity.RedPacketRecordEntity?>(null)
        every { redPacketDao.observeByUuid("rp1") } returns flow
        assertSame(flow, controller.observeRedPacketRecord("rp1"))
    }

    @Test
    fun 取DIY图_经ContentImageStore读位图() = runBlocking {
        mockkObject(ContentImageStore)
        try {
            val rec = mockk<GiftRecordEntity>(relaxed = true) { every { diyImagePath } returns "p/diy.jpg" }
            coEvery { giftDao.getByUuid("g1") } returns rec
            val bmp = mockk<Bitmap>()
            coEvery { ContentImageStore.load(any(), any()) } returns bmp
            assertNotNull(controller.loadGiftDiyImage("g1"))
            coVerify { ContentImageStore.load("p/diy.jpg", any()) }
        } finally {
            unmockkObject(ContentImageStore)
        }
    }
}
