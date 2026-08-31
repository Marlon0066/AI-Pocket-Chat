package com.situ.aichat.gift

import android.util.Log
import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.CurrencyDao
import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.CharacterWalletEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.model.ProactiveGiftTrigger
import com.situ.aichat.data.model.ProactiveGiftTriggerType
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.economy.CurrencyService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 复核 R1 🟡-1 行为测试：主动送礼的陪送文案被落库前置闸判脏后，**会话列表预览也不许露出那段脏文**。
 *
 * 断言从返工指令独立反推（非照抄实现）：
 * - 脏文案 → 预览退回礼物卡口径（`[礼物]` 前缀 + 礼物名），且脏文任何片段都不得出现在预览里；
 *   消息只落 1 条（礼物卡本身），陪送文字条被丢弃。
 * - 干净文案 → 预览仍是正文前 60 字（回归钉：兜底不许波及正常路径），消息落 2 条。
 * 两例都顺带钉死「预览的 role / 时间戳参数不变」（仍是 assistant + 陪送文案时间，晚于礼物卡时间）。
 */
class ProactiveGiftDirtyPreviewTest {

    private lateinit var db: AppDatabase
    private lateinit var currencyService: CurrencyService
    private lateinit var currencyDao: CurrencyDao
    private lateinit var giftDao: GiftDao
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var messageRepo: MessageRepository
    private lateinit var scheduler: ProactiveGiftScheduler
    private lateinit var executor: ProactiveGiftExecutor

    private val character = CharacterEntity(uuid = "c1", name = "林深", creationDate = 0L)
    private val now = 1_800_000_000_000L
    private val trigger = ProactiveGiftTrigger(
        type = ProactiveGiftTriggerType.MISSING_YOU, label = "想你了", metaId = "", firedAt = now,
    )

    /** 礼物 = 珍珠奶茶 15 币（GiftCatalog 既有条目）——预览兜底应显示这个名字。 */
    private val giftId = "gift_boba_tea"
    private val giftName = "珍珠奶茶"

    /** 判脏样本：命中 DirtyMessageDetector 的「标记文本复读」（`【今日场景种子】`），规则零改动。 */
    private val dirtyMessage = "【今日场景种子】阴雨的周三傍晚，便利店门口"

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
        conversationRepo = mockk(relaxed = true)
        messageRepo = mockk(relaxed = true)
        scheduler = mockk(relaxed = true)
        // 前置闸门全放行（钱够 / 未达月上限 / 幂等未占用 / 不在见面期）→ 唯一变量只剩「陪送文案脏不脏」。
        coEvery { conversationRepo.recentActiveConversationFor("c1") } returns ConversationEntity(
            uuid = "conv-1", title = "t", characterUuid = "c1", creationDate = 0L,
            isInOfflineMode = false, currentOfflineSessionId = null,
        )
        coEvery { currencyDao.getCharacterWallet("c1") } returns
            CharacterWalletEntity(uuid = "w1", characterUuid = "c1", coinBalance = 5_000)
        coEvery { currencyDao.transactionExists(any()) } returns false
        coEvery { scheduler.hasReachedMonthlyLimit(any(), any()) } returns false
        coEvery {
            currencyService.spendCoinsFromCharacter(any(), any(), any(), any(), any(), any())
        } returns 4_985
        executor = ProactiveGiftExecutor(
            db = db, currencyService = currencyService, currencyDao = currencyDao, giftDao = giftDao,
            scheduler = scheduler, messageRepo = messageRepo, conversationRepo = conversationRepo,
            characterRepo = mockk(relaxed = true), redPacketService = mockk(relaxed = true),
            redPacketExpirationScanService = mockk(relaxed = true), characterWriteLock = CharacterWriteLock(),
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    private fun sendGift(message: String): ProactiveGiftExecutor.ExecuteResult = runBlocking {
        executor.execute(
            ProactiveGiftLLMService.Decision(
                shouldSend = true, giftId = giftId, message = message, reason = "想你了",
                isFromFallback = false, action = ProactiveGiftLLMService.DecisionAction.GIFT,
            ),
            trigger, character, now,
        )
    }

    @Test
    fun 脏陪送文案_预览退回礼物卡口径且不含脏文任何片段() {
        val previews = mutableListOf<String>()
        val roles = mutableListOf<String>()
        val stamps = mutableListOf<Long>()
        val upserted = mutableListOf<MessageEntity>()

        val result = sendGift(dirtyMessage)
        assertTrue("送礼本身应照常成功（判脏只砍陪送文字），实际=$result", result is ProactiveGiftExecutor.ExecuteResult.Executed)

        coVerify { conversationRepo.recordLastMessage("conv-1", capture(previews), capture(roles), capture(stamps)) }
        coVerify { messageRepo.upsert(capture(upserted)) }

        // ① 消息只落一条 = 礼物卡；陪送文字条被前置闸丢弃，从不落库。
        assertEquals("脏陪送文案不该落库，只剩礼物卡一条", 1, upserted.size)
        assertEquals(MessageKind.GIFT_CARD.raw, upserted.single().messageKindRaw)

        // ② 预览退回礼物卡口径，且脏文一个片段都不许露出来。
        val preview = previews.single()
        assertTrue("预览应为礼物卡口径 `[礼物]<名>`，实际=$preview", preview.startsWith("[礼物]"))
        assertTrue("预览应带礼物名，实际=$preview", preview.contains(giftName))
        assertFalse("预览含脏文整段", preview.contains(dirtyMessage))
        // 逐片段扫：任意长度 ≥2 的脏文子串都不许出现（比整段包含严格得多）。
        val dirtyFragments = (0 until dirtyMessage.length - 1).map { dirtyMessage.substring(it, it + 2) }
        dirtyFragments.forEach { frag ->
            assertFalse("预览泄漏脏文片段「$frag」：$preview", preview.contains(frag))
        }

        // ③ role / 时间戳参数不变：仍是 assistant + 陪送文案时间（晚于礼物卡的 now）。
        assertEquals("assistant", roles.single())
        assertTrue("预览时间应是陪送文案时间（晚于礼物卡 now），实际=${stamps.single()}", stamps.single() > now)
    }

    @Test
    fun 干净陪送文案_预览仍是正文前60字() {
        // 70 字正文 → 预览必须正好是前 60 字（证明兜底没波及正常路径，也没顺手改截断口径）。
        val cleanMessage = (1..70).joinToString("") { "字" }
        val previews = mutableListOf<String>()
        val roles = mutableListOf<String>()
        val stamps = mutableListOf<Long>()
        val upserted = mutableListOf<MessageEntity>()

        val result = sendGift(cleanMessage)
        assertTrue("干净文案应照常执行，实际=$result", result is ProactiveGiftExecutor.ExecuteResult.Executed)

        coVerify { conversationRepo.recordLastMessage("conv-1", capture(previews), capture(roles), capture(stamps)) }
        coVerify(exactly = 2) { messageRepo.upsert(capture(upserted)) }

        assertTrue("干净文案本体应完整落库（截断只发生在预览）", upserted.any { it.content == cleanMessage })
        assertEquals("干净文案预览 = 正文前 60 字", cleanMessage.substring(0, 60), previews.single())
        assertEquals(60, previews.single().length)
        assertEquals("assistant", roles.single())
        assertTrue("预览时间应是陪送文案时间（晚于礼物卡 now）", stamps.single() > now)
    }
}
