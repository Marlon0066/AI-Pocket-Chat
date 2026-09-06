package com.situ.aichat.data.repository

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T2-3 / T2-4（图纸 2026-09-06 见面窗口与节拍卡七件 §7·Robolectric + 真 in-memory Room）：散场硬闸的
 * **持久化语义**。断言从 §5 E13/E15 规格独立反推：递减只在见面中且 > 0 时发生（永不为负、非见面会话零行受影响），
 * 进入 / 退出 / 重置线下态一律归零。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineEndHoldPersistenceTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ConversationRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = ConversationRepository(db.conversationDao())
        runBlocking { db.characterDao().upsert(CharacterEntity(uuid = "char1", name = "小和", creationDate = 0L)) }
    }

    @After
    fun tearDown() = db.close()

    private fun seed(inOffline: Boolean, hold: Int = 0) = runBlocking {
        db.conversationDao().upsert(
            ConversationEntity(
                uuid = "conv1", title = "t", characterUuid = "char1", creationDate = 0L,
                isInOfflineMode = inOffline, currentOfflineSessionId = if (inOffline) "s1" else null,
                offlineEndHoldTurns = hold,
            ),
        )
    }

    private fun hold(): Int = runBlocking { db.conversationDao().getByUuid("conv1")!!.offlineEndHoldTurns }

    /** E13：见面中 3→2→1→0，到 0 后再消耗零行受影响、值不为负。 */
    @Test
    fun 见面中逐轮递减到零后停住() = runBlocking {
        seed(inOffline = true, hold = 3)
        assertEquals(1, repo.consumeOfflineEndHold("conv1")); assertEquals(2, hold())
        assertEquals(1, repo.consumeOfflineEndHold("conv1")); assertEquals(1, hold())
        assertEquals(1, repo.consumeOfflineEndHold("conv1")); assertEquals(0, hold())
        assertEquals("闸已放开 → 零行受影响", 0, repo.consumeOfflineEndHold("conv1"))
        assertEquals(0, hold())
    }

    /** E13 后半：非见面会话即使列上残留正值也不递减（守卫含 isInOfflineMode）。 */
    @Test
    fun 非见面会话不递减() = runBlocking {
        seed(inOffline = false, hold = 2)
        assertEquals(0, repo.consumeOfflineEndHold("conv1"))
        assertEquals(2, hold())
    }

    @Test
    fun 置闸写的是本会话的列() = runBlocking {
        seed(inOffline = true, hold = 0)
        repo.setOfflineEndHold("conv1", 3)
        assertEquals(3, hold())
    }

    /** E15：进入 / 退出 / 重置线下态三个入口都把闸归零。 */
    @Test
    fun 进入退出重置线下态一律归零() = runBlocking {
        seed(inOffline = true, hold = 3)
        repo.recordOfflineExited("conv1", pendingSummarySessionId = "s1", preview = "p", timestamp = 1L)
        assertEquals("退出见面归零", 0, hold())

        db.conversationDao().setOfflineEndHold("conv1", 3)
        repo.recordOfflineEntered("conv1", sessionId = "s2", preview = "p", timestamp = 2L)
        assertEquals("重新进入见面归零", 0, hold())

        db.conversationDao().setOfflineEndHold("conv1", 3)
        repo.resetOfflineState("conv1")
        assertEquals("脏状态重置归零", 0, hold())
    }
}
