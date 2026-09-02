package com.situ.aichat.prompt.growth

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.model.CharacterIntent
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.IntentKind
import com.situ.aichat.data.model.IntentQueueState
import com.situ.aichat.data.model.IntentState
import com.situ.aichat.data.repository.CharacterWriteLock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 活人感内核·卷四《意图队列 + 性格复盘》T2-1（图纸 §7.2 · E19 / E36 / E42）：真 [IntentKernel] + MockK [CharacterDao]，
 * 验「每轮 tick 恰 1 次列读、只读意图列不读整行、队列不变 **0 写**、有变化恰 1 写且写出合法 JSON」的写序不变式（K-15），
 * 以及坏 JSON 覆写 / DAO 抛异常吞掉 / tick 不依赖 `CharacterWriteLock`。
 * Robolectric：内核失败路径打 `android.util.Log`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntentTickTest {

    private val now = 1_700_000_000_000L
    private val dao = mockk<CharacterDao>(relaxed = true)
    private val kernel = IntentKernel(dao)

    private fun stubColumn(json: String?) {
        coEvery { dao.getIntentQueueJson("u") } returns json
    }

    private fun capturedWrite(): IntentQueueState {
        val json = slot<String>()
        coVerify(exactly = 1) { dao.updateIntentQueue("u", capture(json)) }
        return GrowthJson.decodeIntentQueueOrNull(json.captured) ?: error("写出的不是合法 JSON：${json.captured}")
    }

    private fun intent(kind: IntentKind, state: IntentState, strength: Int = 50, bornAt: Long = now - 3_600_000L, lastChangeAt: Long = bornAt) =
        CharacterIntent(id = kind.key, kind = kind, state = state, strength = strength, bornAt = bornAt, lastChangeAt = lastChangeAt)

    // MARK: - E19 首装冷启：空列 ⇒ 默认，无变化 ⇒ 0 写；恰 1 读列、0 读整行

    @Test
    fun emptyColumn_noChange_readsColumnOnce_writesNothing() = runTest {
        stubColumn("")
        kernel.tick("u", now, "你好")
        coVerify(exactly = 1) { dao.getIntentQueueJson("u") }
        coVerify(exactly = 0) { dao.getByUuid(any()) }
        coVerify(exactly = 0) { dao.updateIntentQueue(any(), any()) }
    }

    @Test
    fun nullColumn_rowMissing_writesNothing() = runTest {
        stubColumn(null)
        kernel.tick("u", now, "")
        coVerify(exactly = 0) { dao.updateIntentQueue(any(), any()) }
    }

    // MARK: - 无变化 0 写 / 有变化恰 1 写

    @Test
    fun activeIntent_noKeyword_writesNothing() = runTest {
        stubColumn(GrowthJson.encode(IntentQueueState(intents = listOf(intent(IntentKind.WANT_APOLOGIZE, IntentState.ACTIVE)))))
        kernel.tick("u", now, "今天天气很好")
        coVerify(exactly = 0) { dao.updateIntentQueue(any(), any()) }
    }

    @Test
    fun buddingIntent_isPromoted_exactlyOneValidWrite() = runTest {
        stubColumn(GrowthJson.encode(IntentQueueState(intents = listOf(intent(IntentKind.WANT_APOLOGIZE, IntentState.BUDDING)), reviewRoundsAccrued = 7)))
        kernel.tick("u", now, "")
        val q = capturedWrite()
        assertEquals(IntentState.ACTIVE, q.intents.single().state)
        assertEquals("复盘计数原样带过", 7, q.reviewRoundsAccrued)
    }

    @Test
    fun userKeyword_noLongerResolves_writesNothing_E17() = runTest {
        // 修缮卷 J4：层 ① 了结表已删——「没关系」对 EXPRESSED 道歉零变化 ⇒ 0 写
        stubColumn(GrowthJson.encode(IntentQueueState(intents = listOf(intent(IntentKind.WANT_APOLOGIZE, IntentState.EXPRESSED, strength = 25)))))
        kernel.tick("u", now, "没关系啦")
        coVerify(exactly = 0) { dao.updateIntentQueue(any(), any()) }
    }

    @Test
    fun userClearsAll_writesQueueWithoutLive() = runTest {
        stubColumn(
            GrowthJson.encode(
                IntentQueueState(
                    intents = listOf(
                        intent(IntentKind.WANT_APOLOGIZE, IntentState.EXPRESSED, strength = 25),
                        intent(IntentKind.WANT_HIDE, IntentState.RESOLVED, strength = 0),
                    ),
                ),
            ),
        )
        kernel.tick("u", now, "没事了，都过去了")
        val q = capturedWrite()
        assertEquals(listOf(IntentState.RESOLVED), q.intents.map { it.state })
    }

    // MARK: - E36 坏 JSON：与空列同路；无变化 0 写、有变化时覆写成合法 JSON

    @Test
    fun brokenJson_noChange_writesNothing() = runTest {
        stubColumn("{坏 JSON")
        kernel.tick("u", now, "没事了")
        coVerify(exactly = 0) { dao.updateIntentQueue(any(), any()) }
    }

    @Test
    fun jsonWithUnknownKeysAndBudding_isOverwrittenWithValidJson() = runTest {
        stubColumn("""{"intents":[{"id":"a","kind":"wantShare","state":"budding","bornAt":${now - 1},"lastChangeAt":${now - 1},"legacy":1}],"futureTop":2}""")
        kernel.tick("u", now, "")
        val json = slot<String>()
        coVerify(exactly = 1) { dao.updateIntentQueue("u", capture(json)) }
        assertFalse(json.captured.contains("legacy"))
        assertFalse(json.captured.contains("futureTop"))
        assertEquals(IntentState.ACTIVE, GrowthJson.decodeIntentQueueOrNull(json.captured)!!.intents.single().state)
    }

    // MARK: - 失败吞掉（E42 同口径）

    @Test
    fun daoReadThrows_isSwallowed() = runTest {
        coEvery { dao.getIntentQueueJson("u") } throws IllegalStateException("db closed")
        kernel.tick("u", now, "")
        coVerify(exactly = 0) { dao.updateIntentQueue(any(), any()) }
    }

    @Test
    fun daoWriteThrows_isSwallowed() = runTest {
        stubColumn(GrowthJson.encode(IntentQueueState(intents = listOf(intent(IntentKind.WANT_APOLOGIZE, IntentState.BUDDING)))))
        coEvery { dao.updateIntentQueue(any(), any()) } throws IllegalStateException("disk full")
        kernel.tick("u", now, "")
        coVerify(exactly = 1) { dao.updateIntentQueue("u", any()) }
    }

    // MARK: - tick 不进 CharacterWriteLock：内核根本不持有那把锁（构造参数里没有它）

    @Test
    fun kernel_doesNotDependOnCharacterWriteLock() {
        val paramTypes = IntentKernel::class.java.constructors.single().parameterTypes.toList()
        assertEquals(listOf(CharacterDao::class.java), paramTypes)
        assertTrue(paramTypes.none { it == CharacterWriteLock::class.java })
    }
}
