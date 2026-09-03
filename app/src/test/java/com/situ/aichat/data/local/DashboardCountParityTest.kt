package com.situ.aichat.data.local

import androidx.room.Room
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.data.local.entity.MomentPostEntity
import kotlinx.coroutines.flow.first
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
 * K5 平价测试（2026-07-12 性能线程专项）：三条新 COUNT 查询的 WHERE 必须与对应全量查询**逐字等价**——
 * 断言 `count == 全量查询.size`（含软删/作者类型/归档翻转各边界），钉死「换 COUNT 后数字口径零漂移」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardCountParityTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun userFeedCount_matchesFullQuery_acrossSoftDeleteAndAuthorType() = runBlocking {
        val dao = db.momentDao()
        dao.insertPost(MomentPostEntity(uuid = "u1", timestamp = 1))
        dao.insertPost(MomentPostEntity(uuid = "u2", timestamp = 2))
        dao.insertPost(MomentPostEntity(uuid = "u3", timestamp = 3, isSoftDeleted = true)) // 软删不计
        dao.insertPost(MomentPostEntity(uuid = "c1", timestamp = 4, authorTypeRaw = "character", characterUuid = "ch")) // 角色帖不计
        assertEquals(2, dao.observeUserFeedCount().first())
        assertEquals(dao.observeUserFeedWithRelations(100).first().size, dao.observeUserFeedCount().first())

        dao.softDeletePost("u1") // 软删翻转实时反映
        assertEquals(1, dao.observeUserFeedCount().first())
        assertEquals(dao.observeUserFeedWithRelations(100).first().size, dao.observeUserFeedCount().first())
    }

    @Test
    fun characterFeedCount_matchesFullQuery_acrossSoftDeleteAndAuthorType() = runBlocking {
        val dao = db.momentDao()
        dao.insertPost(MomentPostEntity(uuid = "c1", timestamp = 1, authorTypeRaw = "character", characterUuid = "ch"))
        dao.insertPost(MomentPostEntity(uuid = "c2", timestamp = 2, authorTypeRaw = "character", characterUuid = "ch"))
        dao.insertPost(MomentPostEntity(uuid = "c3", timestamp = 3, authorTypeRaw = "character", characterUuid = "ch", isSoftDeleted = true)) // 软删不计
        dao.insertPost(MomentPostEntity(uuid = "o1", timestamp = 4, authorTypeRaw = "character", characterUuid = "other")) // 别的角色不计
        dao.insertPost(MomentPostEntity(uuid = "u1", timestamp = 5)) // 用户帖不计
        assertEquals(2, dao.observeCharacterFeedCount("ch").first())
        assertEquals(dao.observeCharacterFeedWithRelations("ch", 100).first().size, dao.observeCharacterFeedCount("ch").first())

        dao.softDeletePost("c1") // 软删翻转实时反映
        assertEquals(1, dao.observeCharacterFeedCount("ch").first())
        assertEquals(dao.observeCharacterFeedWithRelations("ch", 100).first().size, dao.observeCharacterFeedCount("ch").first())
    }

    @Test
    fun receivedGiftCount_matchesFullQuery() = runBlocking {
        val dao = db.giftDao()
        dao.insert(GiftRecordEntity(receiverType = "user", senderType = "character", senderCharacterUUID = "ch"))
        dao.insert(GiftRecordEntity(receiverType = "user", senderType = "character", senderCharacterUUID = "ch2"))
        dao.insert(GiftRecordEntity(receiverType = "character", receiverCharacterUUID = "ch")) // 用户送出，不计
        assertEquals(2, dao.observeUserReceivedGiftCount().first())
        assertEquals(dao.observeUserReceivedGifts().first().size, dao.observeUserReceivedGiftCount().first())
    }

    @Test
    fun archivedCount_matchesFullQuery_andFollowsArchiveFlips() = runBlocking {
        db.characterDao().upsert(CharacterEntity(uuid = "ch", name = "测", creationDate = 1L)) // conversations FK 父行
        val dao = db.conversationDao()
        dao.upsert(ConversationEntity(uuid = "a", title = "", characterUuid = "ch", creationDate = 1L, isArchived = true))
        dao.upsert(ConversationEntity(uuid = "b", title = "", characterUuid = "ch", creationDate = 2L))
        assertEquals(1, dao.observeArchivedCount().first())
        assertEquals(dao.observeArchived().first().size, dao.observeArchivedCount().first())

        dao.setArchived("b", true)
        assertEquals(2, dao.observeArchivedCount().first())
        dao.setArchived("a", false)
        assertEquals(1, dao.observeArchivedCount().first())
        assertEquals(dao.observeArchived().first().size, dao.observeArchivedCount().first())
    }
}
