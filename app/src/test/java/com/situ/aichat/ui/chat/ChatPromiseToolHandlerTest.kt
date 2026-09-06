package com.situ.aichat.ui.chat

import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.local.entity.PromiseSource
import com.situ.aichat.data.local.entity.PromiseStatus
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.promise.PromiseLedgerService
import com.situ.aichat.promise.PromiseToolAction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 约定记账闸门 / 落库 / 提示（图纸 2026-09-06 约定工具调用化 §3.5·T1-4/5 + T2-1…4）。
 * 断言从图纸 §3.5/§5 独立反推：闸门四道（证据 / 编号 / status / 上限）；落库只经
 * [PromiseLedgerService]；见面中零写；提示排队与撤销。MockK 假掉账本与会话仓库。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatPromiseToolHandlerTest {

    private val now = 1_700_000_000_000L
    private val conv = "conv-1"

    private lateinit var ledger: PromiseLedgerService
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var blocked: MutableStateFlow<Boolean>
    private lateinit var handler: ChatPromiseToolHandler

    /** 证据海：这段话去空白后即闸二的比对底本。 */
    private val material = "用户：那就周六一起去看展吧\n角色：好呀，说定啦\n用户：简历我已经改好发你了"
    private val haystack = ChatPromiseToolHandler.haystack(
        listOf(msg(material)),
        "嗯，记住咯",
    )

    private fun open(uuid: String, content: String, due: Long? = null) = PromiseEntity(
        uuid = uuid, characterUuid = "c1", content = content, statusRaw = PromiseStatus.OPEN,
        dueAtMillis = due, sourceRaw = PromiseSource.CHAT, createdAtMillis = 0L, updatedAtMillis = 0L,
    )

    private val numbered = listOf(open("p1", "一起去看画展"), open("p2", "帮忙改简历"))

    @Before fun setUp() {
        ledger = mockk(relaxed = true)
        conversationRepo = mockk(relaxed = true)
        blocked = MutableStateFlow(false)
        coEvery { conversationRepo.get(conv) } returns ConversationEntity(
            uuid = conv, title = "标题", characterUuid = "c1", creationDate = 0L,
        )
        handler = ChatPromiseToolHandler(
            scope = CoroutineScope(Dispatchers.Unconfined),
            ledger = ledger,
            conversationRepo = conversationRepo,
            blocked = blocked,
            conversationUuid = conv,
        )
    }

    // ── T1-4 screen（纯函数四道闸 + 上限） ──

    @Test fun screen_dropsShortOrForeignEvidence() {
        val short = PromiseToolAction.Record("周六一起去看展", null, "好呀") // 去空白后 <6 字
        val foreign = PromiseToolAction.Record("周六一起去看展", null, "这句话素材里根本没有出现过")
        val ok = PromiseToolAction.Record("周六一起去看展", null, "那就周六一起去看展吧")
        val r = ChatPromiseToolHandler.screen(listOf(short, foreign, ok), numbered, haystack)
        assertEquals(1, r.records.size)
        assertEquals("周六一起去看展", r.records[0].content)
    }

    @Test fun screen_dropsOverlongContent() {
        val long = PromiseToolAction.Record("看展".repeat(31), null, "那就周六一起去看展吧") // 62 码点 > 60
        assertTrue(ChatPromiseToolHandler.screen(listOf(long), numbered, haystack).records.isEmpty())
        val edge = PromiseToolAction.Record("看展".repeat(30), null, "那就周六一起去看展吧") // 恰 60 → 留
        assertEquals(1, ChatPromiseToolHandler.screen(listOf(edge), numbered, haystack).records.size)
    }

    @Test fun screen_resolve_outOfRange_duplicate_badStatus_allDropped() {
        val actions = listOf(
            PromiseToolAction.Resolve(3, "fulfilled", "简历我已经改好发你了"), // 越界（清单只有 2 条）
            PromiseToolAction.Resolve(0, "fulfilled", "简历我已经改好发你了"), // 越界
            PromiseToolAction.Resolve(2, "done", "简历我已经改好发你了"), // status 非白名单
            PromiseToolAction.Resolve(2, "fulfilled", "简历我已经改好发你了"), // 合法（同 no 首条 = 上一条已占位）
        )
        val r = ChatPromiseToolHandler.screen(actions, numbered, haystack)
        assertTrue("同 no 只取首条，而首条 status 非法被丢 → 该编号整体落空", r.resolves.isEmpty())
    }

    @Test fun screen_resolve_mapsNoToUuid_andKeepsFirstOfDuplicateNo() {
        val actions = listOf(
            PromiseToolAction.Resolve(2, "fulfilled", "简历我已经改好发你了"),
            PromiseToolAction.Resolve(2, "cancelled", "简历我已经改好发你了"), // 同 no 第二条丢
        )
        val r = ChatPromiseToolHandler.screen(actions, numbered, haystack)
        assertEquals(1, r.resolves.size)
        assertEquals("p2", r.resolves[0].second.promiseUuid) // no=2 → numbered[1]
        assertEquals(PromiseStatus.FULFILLED, r.resolves[0].second.status)
    }

    @Test fun screen_emptyNumberedList_dropsEveryResolve() {
        val r = ChatPromiseToolHandler.screen(
            listOf(PromiseToolAction.Resolve(1, "fulfilled", "简历我已经改好发你了")),
            emptyList(),
            haystack,
        )
        assertTrue(r.resolves.isEmpty())
    }

    @Test fun screen_capsRecordsAtTwo_andResolvesAtThree() {
        val records = (1..4).map { PromiseToolAction.Record("周六一起去看展$it", null, "那就周六一起去看展吧") }
        val threeOpen = numbered + listOf(open("p3", "第三条"), open("p4", "第四条"))
        val resolves = (1..4).map { PromiseToolAction.Resolve(it, "fulfilled", "简历我已经改好发你了") }
        val r = ChatPromiseToolHandler.screen(records + resolves, threeOpen, haystack)
        assertEquals(2, r.records.size)
        assertEquals(listOf("周六一起去看展1", "周六一起去看展2"), r.records.map { it.content })
        assertEquals(3, r.resolves.size)
        assertEquals(listOf("p1", "p2", "p3"), r.resolves.map { it.second.promiseUuid })
    }

    // ── T1-5 hintFor（纯函数） ──

    private fun outcome(r: Int = 0, f: Int = 0, c: Int = 0) = PromiseApplyOutcome(
        recorded = (1..r).map { open("r$it", "记下的$it") },
        fulfilled = (1..f).map { open("f$it", "兑现的$it") },
        cancelled = (1..c).map { open("c$it", "取消的$it") },
    )

    @Test fun hintFor_allShapes() {
        assertNull(ChatPromiseToolHandler.hintFor(outcome(), 1))

        val recorded = ChatPromiseToolHandler.hintFor(outcome(r = 1), 1)!!
        assertEquals(PromiseHint.Kind.RECORDED, recorded.kind)
        assertEquals("记下的1", recorded.content)
        assertEquals("r1", recorded.undoUuid) // 撤销键只在单记这条上

        val fulfilled = ChatPromiseToolHandler.hintFor(outcome(f = 1), 2)!!
        assertEquals(PromiseHint.Kind.FULFILLED, fulfilled.kind)
        assertEquals("兑现的1", fulfilled.content)
        assertNull(fulfilled.undoUuid)

        val cancelled = ChatPromiseToolHandler.hintFor(outcome(c = 1), 3)!!
        assertEquals(PromiseHint.Kind.CANCELLED, cancelled.kind)
        assertNull(cancelled.undoUuid)

        // 同轮既记又了结 → 合并条、无撤销（D-4）。
        val merged = ChatPromiseToolHandler.hintFor(outcome(r = 1, f = 1), 4)!!
        assertEquals(PromiseHint.Kind.MERGED, merged.kind)
        assertEquals(1, merged.recorded)
        assertEquals(1, merged.fulfilled)
        assertEquals(0, merged.cancelled)
        assertNull(merged.undoUuid)
        // 两条新约定也是合并条。
        assertEquals(PromiseHint.Kind.MERGED, ChatPromiseToolHandler.hintFor(outcome(r = 2), 5)!!.kind)
    }

    // ── T2-1…4 行为（MockK 假账本） ──

    @Test fun applyAndShow_recordsThroughLedger_andShowsRecordedHint() = runBlocking {
        val saved = open("new-1", "周六一起去看展")
        coEvery { ledger.register(any(), any(), any(), any(), any(), any(), any()) } returns saved

        handler.applyAndShow(
            listOf(PromiseToolAction.Record("周六一起去看展", 123L, "那就周六一起去看展吧")),
            numbered, "c1", haystack, now,
        )

        coVerify(exactly = 1) {
            ledger.register("c1", conv, "周六一起去看展", 123L, PromiseSource.CHAT, "", now)
        }
        val hint = handler.hint.value!!
        assertEquals(PromiseHint.Kind.RECORDED, hint.kind)
        assertEquals("周六一起去看展", hint.content) // 文案已落定（非仅「非空」）
        assertEquals("new-1", hint.undoUuid)
    }

    @Test fun applyAndShow_registerReturnsNull_noHint() = runBlocking {
        coEvery { ledger.register(any(), any(), any(), any(), any(), any(), any()) } returns null // 去重 / 金额守卫挡下

        handler.applyAndShow(
            listOf(PromiseToolAction.Record("周六一起去看展", null, "那就周六一起去看展吧")),
            numbered, "c1", haystack, now,
        )
        assertNull(handler.hint.value)
    }

    @Test fun applyAndShow_inOfflineMeeting_zeroWriteZeroHint() = runBlocking {
        coEvery { conversationRepo.get(conv) } returns ConversationEntity(
            uuid = conv, title = "标题", characterUuid = "c1", creationDate = 0L, isInOfflineMode = true,
        )
        handler.applyAndShow(
            listOf(
                PromiseToolAction.Record("周六一起去看展", null, "那就周六一起去看展吧"),
                PromiseToolAction.Resolve(2, "fulfilled", "简历我已经改好发你了"),
            ),
            numbered, "c1", haystack, now,
        )
        coVerify(exactly = 0) { ledger.register(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { ledger.applyChange(any(), any()) }
        assertNull(handler.hint.value)
    }

    @Test fun applyAndShow_resolveMapsNumberToUuid_andShowsFulfilledHint() = runBlocking {
        coEvery { ledger.applyChange(any(), any()) } returns true

        handler.applyAndShow(
            listOf(PromiseToolAction.Resolve(2, "fulfilled", "简历我已经改好发你了")),
            numbered, "c1", haystack, now,
        )

        coVerify(exactly = 1) {
            ledger.applyChange(
                match { it.promiseUuid == "p2" && it.status == PromiseStatus.FULFILLED && it.evidence == "简历我已经改好发你了" },
                now,
            )
        }
        val hint = handler.hint.value!!
        assertEquals(PromiseHint.Kind.FULFILLED, hint.kind)
        assertEquals("帮忙改简历", hint.content)
    }

    @Test fun applyAndShow_applyChangeFalse_noHint() = runBlocking {
        coEvery { ledger.applyChange(any(), any()) } returns false // 编号过期 / 已被对账了结

        handler.applyAndShow(
            listOf(PromiseToolAction.Resolve(2, "fulfilled", "简历我已经改好发你了")),
            numbered, "c1", haystack, now,
        )
        assertNull(handler.hint.value)
    }

    @Test fun hint_waitsWhileBlocked_thenAppears() = runBlocking {
        blocked.value = true
        coEvery { ledger.register(any(), any(), any(), any(), any(), any(), any()) } returns open("new-1", "周六一起去看展")

        handler.applyAndShow(
            listOf(PromiseToolAction.Record("周六一起去看展", null, "那就周六一起去看展吧")),
            numbered, "c1", haystack, now,
        )
        assertNull("同槽有别的横幅 → 提示先等着", handler.hint.value)

        blocked.value = false
        assertEquals(PromiseHint.Kind.RECORDED, handler.hint.value!!.kind)
    }

    @Test fun undoRecorded_successShowsUndoneHint_failureDismisses() = runBlocking {
        coEvery { ledger.resolveManually("new-1", PromiseStatus.CANCELLED, any()) } returns true
        handler.undoRecorded("new-1")
        coVerify(exactly = 1) { ledger.resolveManually("new-1", PromiseStatus.CANCELLED, any()) }
        assertEquals(PromiseHint.Kind.UNDONE, handler.hint.value!!.kind)

        coEvery { ledger.resolveManually("gone", PromiseStatus.CANCELLED, any()) } returns false
        handler.undoRecorded("gone")
        assertNull("目标已非 open → 提示直接收起，不报错", handler.hint.value)
    }

    @Test fun dismiss_clearsHint() = runBlocking {
        coEvery { ledger.register(any(), any(), any(), any(), any(), any(), any()) } returns open("new-1", "周六一起去看展")
        handler.applyAndShow(
            listOf(PromiseToolAction.Record("周六一起去看展", null, "那就周六一起去看展吧")),
            numbered, "c1", haystack, now,
        )
        handler.dismiss()
        assertNull(handler.hint.value)
    }

    // ── 复核 R1 追加（🟡-2 / 🟡-3）：排队超时不许留旧条；取消不许吞 ──

    @Test fun show_timeoutWhileBlocked_clearsStaleHint_insteadOfLeavingItStuck() = runTest {
        // 用虚拟时钟驱动 handler 的 scope：withTimeoutOrNull / delay 都按 testScheduler 走。
        val h = ChatPromiseToolHandler(CoroutineScope(StandardTestDispatcher(testScheduler)), ledger, conversationRepo, blocked, conv)
        coEvery { ledger.register(any(), any(), any(), any(), any(), any(), any()) } returnsMany listOf(open("a", "第一条"), open("b", "第二条"))

        h.applyAndShow(listOf(PromiseToolAction.Record("第一条", null, "那就周六一起去看展吧")), numbered, "c1", haystack, now)
        runCurrent()
        assertEquals("A 已在屏上", "第一条", h.hint.value?.content)

        blocked.value = true // 下一轮到来时同槽被别的横幅占住（日历 toast / 赴约钮 …）
        h.applyAndShow(listOf(PromiseToolAction.Record("第二条", null, "那就周六一起去看展吧")), numbered, "c1", haystack, now)
        runCurrent()
        assertEquals("B 在等位，A 的计时已被取消 → 屏上暂时仍是 A", "第一条", h.hint.value?.content)

        advanceTimeBy(ChatPromiseToolHandler.BLOCKED_WAIT_CAP_MILLIS + 1)
        runCurrent()
        assertNull("B 等超放弃显示时必须把挂着的 A 一并收掉，否则 A 永远停在屏上", h.hint.value)
    }

    @Test fun show_blockedThenReleased_replacesOldWithNew() = runTest {
        val h = ChatPromiseToolHandler(CoroutineScope(StandardTestDispatcher(testScheduler)), ledger, conversationRepo, blocked, conv)
        coEvery { ledger.register(any(), any(), any(), any(), any(), any(), any()) } returnsMany listOf(open("a", "第一条"), open("b", "第二条"))
        h.applyAndShow(listOf(PromiseToolAction.Record("第一条", null, "那就周六一起去看展吧")), numbered, "c1", haystack, now)
        runCurrent()
        blocked.value = true
        h.applyAndShow(listOf(PromiseToolAction.Record("第二条", null, "那就周六一起去看展吧")), numbered, "c1", haystack, now)
        runCurrent()
        advanceTimeBy(1_000); runCurrent()
        blocked.value = false // 1 秒后让位（未超 8 秒）
        runCurrent()
        assertEquals("让位后 B 顶替 A 上屏", "第二条", h.hint.value?.content)
        advanceTimeBy(ChatPromiseToolHandler.HINT_MILLIS + 1); runCurrent()
        assertNull("B 满 4 秒自消", h.hint.value)
    }

    @Test fun applyAndShow_rethrowsCancellation_butSwallowsOtherFailures() = runBlocking {
        coEvery { ledger.register(any(), any(), any(), any(), any(), any(), any()) } throws CancellationException("turn cancelled")
        var cancelled = false
        try {
            handler.applyAndShow(listOf(PromiseToolAction.Record("周六一起去看展", null, "那就周六一起去看展吧")), numbered, "c1", haystack, now)
        } catch (e: CancellationException) {
            cancelled = true
        }
        assertTrue("取消必须上抛（吞掉会让引擎在已取消的协程里继续走后段）", cancelled)
        assertNull(handler.hint.value)

        coEvery { ledger.register(any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("db down")
        handler.applyAndShow(listOf(PromiseToolAction.Record("周六一起去看展", null, "那就周六一起去看展吧")), numbered, "c1", haystack, now)
        assertNull("普通异常：吞成计数日志、不炸回合、无提示", handler.hint.value)
    }

    /** 计时常量锁（「到点真消」交装机·PITFALLS 1e：compose 测试推不动 LaunchedEffect 的 delay）。 */
    @Test fun timingConstants_areLocked() {
        assertEquals(4000L, ChatPromiseToolHandler.HINT_MILLIS)
        assertEquals(1500L, ChatPromiseToolHandler.UNDONE_MILLIS)
        assertEquals(8000L, ChatPromiseToolHandler.BLOCKED_WAIT_CAP_MILLIS)
    }
}

private fun msg(content: String) = MessageEntity(
    messageUUID = "m1", conversationUuid = "conv-1", roleRaw = "user", content = content, timestamp = 1L,
)
