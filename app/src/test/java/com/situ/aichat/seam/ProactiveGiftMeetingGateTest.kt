package com.situ.aichat.seam

import android.util.Log
import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.CurrencyDao
import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.CharacterWalletEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.model.ProactiveGiftTrigger
import com.situ.aichat.data.model.ProactiveGiftTriggerType
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.economy.CurrencyService
import com.situ.aichat.gift.ProactiveGiftExecutor
import com.situ.aichat.gift.ProactiveGiftLLMService
import com.situ.aichat.gift.ProactiveGiftScheduler
import com.situ.aichat.redpacket.RedPacketService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 卷一 C5「礼物红包」行为测试（图纸 §7 T2-C5·⚠️钱路邻区）：见面中两分支都在**扣款前**早退 Skipped，
 * 且 currency / gift / redPacket 三侧**零调用**（金额零动实证）、**不写幂等流水**（relatedKey 不被占用 →
 * 见面结束后维护线自然补送·E5）。非见面路径原样执行（N1 对照）。
 */
class ProactiveGiftMeetingGateTest {

    private lateinit var db: AppDatabase
    private lateinit var currencyService: CurrencyService
    private lateinit var currencyDao: CurrencyDao
    private lateinit var giftDao: GiftDao
    private lateinit var redPacketService: RedPacketService
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var messageRepo: MessageRepository
    private lateinit var scheduler: ProactiveGiftScheduler
    private lateinit var executor: ProactiveGiftExecutor

    private val character = CharacterEntity(uuid = "c1", name = "林深", creationDate = 0L)
    private val now = 1_800_000_000_000L
    private val trigger = ProactiveGiftTrigger(
        type = ProactiveGiftTriggerType.MISSING_YOU, label = "想你了", metaId = "", firedAt = now,
    )

    private val birthdayTrigger = ProactiveGiftTrigger(
        type = ProactiveGiftTriggerType.BIRTHDAY, label = "生日", metaId = "", firedAt = now,
    )

    private fun convo(inMeeting: Boolean) = ConversationEntity(
        uuid = "conv-1", title = "t", characterUuid = "c1", creationDate = 0L,
        isInOfflineMode = inMeeting, currentOfflineSessionId = if (inMeeting) "sess-1" else null,
    )

    private fun giftDecision() = ProactiveGiftLLMService.Decision(
        shouldSend = true, giftId = "gift_boba_tea", message = "路过奶茶店想到你", reason = "想你了",
        isFromFallback = false, action = ProactiveGiftLLMService.DecisionAction.GIFT,
    )

    private fun redPacketDecision() = ProactiveGiftLLMService.Decision(
        shouldSend = true, giftId = null, message = null, reason = "想你了", isFromFallback = false,
        action = ProactiveGiftLLMService.DecisionAction.RED_PACKET,
        redPacketAmount = 66, redPacketBlessing = "买点好吃的",
    )

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        mockkStatic("androidx.room.RoomDatabaseKt")
        db = mockk()
        coEvery { db.withTransaction<ProactiveGiftExecutor.ExecuteResult>(any()) } coAnswers {
            secondArg<suspend () -> ProactiveGiftExecutor.ExecuteResult>().invoke()
        }
        currencyService = mockk(relaxed = true)
        currencyDao = mockk(relaxed = true)
        giftDao = mockk(relaxed = true)
        redPacketService = mockk(relaxed = true)
        conversationRepo = mockk(relaxed = true)
        messageRepo = mockk(relaxed = true)
        scheduler = mockk(relaxed = true)
        // 前置闸门一律放行：钱包够、没到月上限、幂等未占用 → 唯一能拦住的只有见面闸。
        coEvery { currencyDao.getCharacterWallet("c1") } returns
            CharacterWalletEntity(uuid = "w1", characterUuid = "c1", coinBalance = 5_000)
        coEvery { currencyDao.transactionExists(any()) } returns false
        coEvery { scheduler.hasReachedMonthlyLimit(any(), any()) } returns false
        executor = ProactiveGiftExecutor(
            db = db, currencyService = currencyService, currencyDao = currencyDao, giftDao = giftDao,
            scheduler = scheduler, messageRepo = messageRepo, conversationRepo = conversationRepo,
            characterRepo = mockk(relaxed = true), redPacketService = redPacketService,
            redPacketExpirationScanService = mockk(relaxed = true), characterWriteLock = CharacterWriteLock(),
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    /** 金额零动实证：三条花钱通路 + 幂等流水一律零调用。 */
    private fun assertZeroMoneyTouched() {
        coVerify(exactly = 0) { currencyService.spendCoinsFromCharacter(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) {
            redPacketService.sendFromCharacter(any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { giftDao.insert(any()) }
        coVerify(exactly = 0) {
            currencyService.recordCharacterTransaction(any(), any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { currencyService.recordProactiveGiftAffinity(any(), any(), any()) }
        coVerify(exactly = 0) { messageRepo.upsert(any()) }
    }

    @Test
    fun 礼物_见面中_扣款前早退且金额零动() = runBlocking {
        coEvery { conversationRepo.recentActiveConversationFor("c1") } returns convo(inMeeting = true)
        val result = executor.execute(giftDecision(), trigger, character, now)
        assertTrue(result is ProactiveGiftExecutor.ExecuteResult.Skipped)
        assertEquals(
            "见面进行中·顺延（不写幂等流水，结束后维护线补送）",
            (result as ProactiveGiftExecutor.ExecuteResult.Skipped).reason,
        )
        assertZeroMoneyTouched()
    }

    @Test
    fun 红包_见面中_扣款前早退且金额零动() = runBlocking {
        coEvery { conversationRepo.recentActiveConversationFor("c1") } returns convo(inMeeting = true)
        // 红包白名单只放行 生日/纪念日/节日（isRedPacketEligible），故红包用例换生日触发。
        val result = executor.execute(redPacketDecision(), birthdayTrigger, character, now)
        assertTrue(result is ProactiveGiftExecutor.ExecuteResult.Skipped)
        assertEquals(
            "见面进行中·顺延（不写幂等流水，结束后维护线补送）",
            (result as ProactiveGiftExecutor.ExecuteResult.Skipped).reason,
        )
        assertZeroMoneyTouched()
    }

    /** N1 对照：非见面 → 礼物照常扣款执行（证明上面的零调用是「闸拦下」而非「桩本就跑不动」）。 */
    @Test
    fun 礼物_非见面_照常扣款执行() = runBlocking {
        coEvery { conversationRepo.recentActiveConversationFor("c1") } returns convo(inMeeting = false)
        coEvery {
            currencyService.spendCoinsFromCharacter(any(), any(), any(), any(), any(), any())
        } returns 4_985
        val result = executor.execute(giftDecision(), trigger, character, now)
        assertTrue("非见面应真执行，实际=$result", result is ProactiveGiftExecutor.ExecuteResult.Executed)
        coVerify(exactly = 1) { currencyService.spendCoinsFromCharacter("c1", 15, any(), any(), any(), any()) }
        coVerify(exactly = 1) { giftDao.insert(any()) }
    }
}
