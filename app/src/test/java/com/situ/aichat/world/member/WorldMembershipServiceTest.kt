package com.situ.aichat.world.member

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldBookBindingEntity
import com.situ.aichat.data.local.entity.WorldBookEntity
import com.situ.aichat.data.local.entity.WorldNativeStateEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.local.entity.WorldTravelEntity
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.world.WorldBootstrap
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.cast.WorldResidentService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * [WorldMembershipService] T2-1（W13 图纸 §7·E1/E2/E4/E5/E7/E9/E10/E11/E13·Robolectric 真 Room 单事务）。
 *
 * 断言从图纸 §3.1/§3.2 独立反推：三动作定点写三列 / 休眠翻转 / 三条世界事件 summary 逐字 / 拒绝语义 /
 * 幂等派生 uuid。城名解析走 [com.situ.aichat.world.atlas.WorldAtlas]（city_yunye=云野镇·city_taoqiu=陶丘·恒存在）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldMembershipServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var service: WorldMembershipService
    private val seed = 1L
    private val day0 = 1_000_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        service = WorldMembershipService(
            db,
            WorldBootstrap(
                db.worldDao(),
                WorldResidentService(db.worldUserResidentDao(), db.worldNativeDao(), db.worldDao(), db),
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    /** 插一个正式角色（默认不加入·家乡云野镇）。 */
    private fun character(
        uuid: String,
        name: String,
        joined: Boolean = false,
        homeCity: String = WorldIds.HOME_CITY_ID,
        joinedAt: Long? = null,
    ) = runBlocking {
        db.characterDao().upsert(
            CharacterEntity(
                uuid = uuid, name = name, creationDate = day0,
                joinedWorld = joined, worldHomeCityId = homeCity, worldJoinedAt = joinedAt,
            ),
        )
    }

    /** 预置世界单行（seed 固定·便于 move 目标城解析）。 */
    private fun seedWorld() = runBlocking {
        db.worldDao().upsertState(WorldStateEntity(seed = seed, userTimezoneId = "UTC", createdAt = 0L))
    }

    // MARK: - E7 世界未建时 join → 先建世再写 + §3.2 三列与 join 事件 summary 逐字

    @Test
    fun `E7 join 未建世时先建世_写三列_join事件summary逐字`() = runBlocking {
        character("c1", "苏晚") // 世界尚未初始化
        assertNull("前置：世界未建", db.worldDao().getState())

        val r = service.join("c1", day0)
        assertEquals(WorldMembershipService.Result.Ok, r)
        assertNotNull("join 应先 ensureCreated 建世", db.worldDao().getState())

        val c = db.characterDao().getByUuid("c1")!!
        assertTrue(c.joinedWorld)
        assertEquals(day0, c.worldJoinedAt)
        assertEquals(WorldIds.HOME_CITY_ID, c.worldHomeCityId) // 住址不动·保留出厂
        assertEquals(listOf("c1"), db.characterDao().getInWorld().map { it.uuid })

        val ev = db.worldDao().getAllEvents().single()
        assertEquals(WorldMembershipService.JOIN_KIND, ev.kindRaw)
        assertEquals("苏晚 来到了云野镇", ev.summary)
        assertEquals(WorldIds.HOME_CITY_ID, ev.cityId)
        assertNull(ev.seenAt); assertNull(ev.notifiedAt)
        // 幂等派生 uuid（§3.2·含 nowMs）。
        val expected = UUID.nameUUIDFromBytes("world:member:join:c1:$day0".toByteArray()).toString()
        assertEquals(expected, ev.uuid)
    }

    // MARK: - E1 join → leave → rejoin：边 dormant 翻转 + 三事件 + worldJoinedAt 更新

    @Test
    fun `E1 join_leave_rejoin_边dormant翻转_三事件_worldJoinedAt刷新`() = runBlocking {
        seedWorld()
        character("a", "阿甲")
        // 预置一条 a→b 边（默认 dormant=false）。
        db.worldSocialDao().upsertEdge(WorldRelationshipEntity(fromId = "a", toId = "b", updatedAt = day0))

        assertEquals(WorldMembershipService.Result.Ok, service.join("a", day0))
        assertFalse("join 恢复休眠边", db.worldSocialDao().getEdge("a", "b")!!.dormant)

        assertEquals(WorldMembershipService.Result.Ok, service.leave("a", day0 + 1))
        assertTrue("leave 休眠边", db.worldSocialDao().getEdge("a", "b")!!.dormant)
        assertNull("leave 清 worldJoinedAt", db.characterDao().getByUuid("a")!!.worldJoinedAt)

        assertEquals(WorldMembershipService.Result.Ok, service.join("a", day0 + 2))
        assertFalse("rejoin 再恢复", db.worldSocialDao().getEdge("a", "b")!!.dormant)
        assertEquals(day0 + 2, db.characterDao().getByUuid("a")!!.worldJoinedAt) // 更新为最新

        // 三条事件各自落库（uuid 含各自 nowMs → 互不覆盖）。
        val kinds = db.worldDao().getAllEvents().map { it.kindRaw }.sorted()
        assertEquals(listOf("join", "join", "leave"), kinds)
        assertEquals(3, db.worldDao().getAllEvents().size)
    }

    // MARK: - E2 挂世界书 join → WorldbookBound·库零变化（防御纵深）

    @Test
    fun `E2 挂世界书角色join_返回WorldbookBound_库零变化`() = runBlocking {
        seedWorld()
        character("wb", "书角")
        db.worldBookDao().upsertBook(WorldBookEntity(uuid = "book1", name = "青云录"))
        db.worldBookDao().bind(WorldBookBindingEntity(characterUuid = "wb", bookUuid = "book1"))

        val r = service.join("wb", day0)
        assertEquals(WorldMembershipService.Result.WorldbookBound, r)
        assertFalse(db.characterDao().getByUuid("wb")!!.joinedWorld)
        assertTrue("零事件", db.worldDao().getAllEvents().isEmpty())
        assertTrue("零成员", db.characterDao().getInWorld().isEmpty())
    }

    // MARK: - E4 原住民出身 leave/move → NativeOrigin·库零变化

    @Test
    fun `E4 原住民出身leave与move_返回NativeOrigin_库零变化`() = runBlocking {
        seedWorld()
        character("nat", "苏晚", joined = true, joinedAt = day0)
        // 原住民出身 = world_native_state 有一行 recruitedCharacterUuid 指向该角色。
        db.worldNativeDao().upsert(
            WorldNativeStateEntity(nativeId = WorldIds.nativeId("su_wan"), recruitedCharacterUuid = "nat"),
        )

        assertEquals(WorldMembershipService.Result.NativeOrigin, service.leave("nat", day0))
        assertEquals(WorldMembershipService.Result.NativeOrigin, service.move("nat", "city_taoqiu", day0))
        assertTrue("仍加入", db.characterDao().getByUuid("nat")!!.joinedWorld)
        assertEquals(WorldIds.HOME_CITY_ID, db.characterDao().getByUuid("nat")!!.worldHomeCityId)
        assertTrue("零事件", db.worldDao().getAllEvents().isEmpty())
    }

    // MARK: - E5 删已加入角色 → getInWorld 收缩（既有清理链覆盖·不新增）

    @Test
    fun `E5 删已加入角色_getInWorld收缩_边与事件清`() = runBlocking {
        seedWorld()
        character("d", "待删")
        service.join("d", day0)
        db.worldSocialDao().upsertEdge(WorldRelationshipEntity(fromId = "d", toId = "x", updatedAt = day0))
        assertEquals(1, db.characterDao().getInWorld().size)

        CharacterRepository(
            db.characterDao(), db.milestoneDao(), db,
            WorldResidentService(db.worldUserResidentDao(), db.worldNativeDao(), db.worldDao(), db),
            io.mockk.mockk(relaxed = true),
        ).delete("d")
        assertNull(db.characterDao().getByUuid("d"))
        assertTrue("getInWorld 收缩", db.characterDao().getInWorld().isEmpty())
        assertNull("边随删角清", db.worldSocialDao().getEdge("d", "x"))
        assertTrue("提及该 id 的世界事件清", db.worldDao().getAllEvents().none { it.involvedIdsJson.contains("d") })
    }

    // MARK: - E9 move 不存在城 / 同城 → NoOp；正常搬家 → 搬去陶丘

    @Test
    fun `E9 move同城与不存在城_NoOp_库零变化`() = runBlocking {
        seedWorld()
        character("m", "小明", joined = true, joinedAt = day0)

        assertEquals("同城", WorldMembershipService.Result.NoOp, service.move("m", WorldIds.HOME_CITY_ID, day0))
        assertEquals("查无", WorldMembershipService.Result.NoOp, service.move("m", "city_nope", day0))
        assertEquals(WorldIds.HOME_CITY_ID, db.characterDao().getByUuid("m")!!.worldHomeCityId)
        assertTrue(db.worldDao().getAllEvents().isEmpty())
    }

    @Test
    fun `move 到陶丘_写住址_move事件summary逐字`() = runBlocking {
        seedWorld()
        character("m", "苏晚", joined = true, joinedAt = day0)

        assertEquals(WorldMembershipService.Result.Ok, service.move("m", "city_taoqiu", day0))
        assertEquals("city_taoqiu", db.characterDao().getByUuid("m")!!.worldHomeCityId)
        val ev = db.worldDao().getAllEvents().single()
        assertEquals(WorldMembershipService.MOVE_KIND, ev.kindRaw)
        assertEquals("苏晚 搬去了陶丘", ev.summary)
        assertEquals("city_taoqiu", ev.cityId) // 住址城 = 新城
    }

    // MARK: - E10 双击开关/连点 → 已在目标态 NoOp

    @Test
    fun `E10 已加入再join与已离开再leave_NoOp`() = runBlocking {
        seedWorld()
        character("r", "阿乙")
        service.join("r", day0)
        assertEquals("已加入再 join", WorldMembershipService.Result.NoOp, service.join("r", day0 + 1))
        service.leave("r", day0 + 2)
        assertEquals("已离开再 leave", WorldMembershipService.Result.NoOp, service.leave("r", day0 + 3))
        // 只应有一条 join + 一条 leave（NoOp 不落事件）。
        assertEquals(2, db.worldDao().getAllEvents().size)
    }

    // MARK: - E11 leave 时 TA 在旅行途中 → deleteTravel 撤场

    @Test
    fun `E11 leave时在途_删在途行`() = runBlocking {
        seedWorld()
        character("t", "旅人", joined = true, joinedAt = day0)
        db.worldDao().upsertTravel(
            WorldTravelEntity(
                ownerId = "t", fromCityId = WorldIds.HOME_CITY_ID, toCityId = "city_taoqiu",
                departAt = day0, arriveAt = day0 + 1000, modeRaw = WorldIds.TravelModes.TRAIN,
            ),
        )
        assertNotNull(db.worldDao().getTravel("t"))

        assertEquals(WorldMembershipService.Result.Ok, service.leave("t", day0 + 10))
        assertNull("leave 删在途·无孤儿", db.worldDao().getTravel("t"))
    }

    // MARK: - E13 A、B 都离开后 A 重新加入 → A 的边（含 A–B）全恢复 dormant=false

    @Test
    fun `E13 AB都离开后A重加入_A两向边恢复dormant_false`() = runBlocking {
        seedWorld()
        character("A", "甲", joined = true, joinedAt = day0)
        character("B", "乙", joined = true, joinedAt = day0)
        db.worldSocialDao().upsertEdge(WorldRelationshipEntity(fromId = "A", toId = "B", updatedAt = day0))
        db.worldSocialDao().upsertEdge(WorldRelationshipEntity(fromId = "B", toId = "A", updatedAt = day0))

        service.leave("A", day0 + 1)
        service.leave("B", day0 + 2)
        assertTrue("A→B 休眠", db.worldSocialDao().getEdge("A", "B")!!.dormant)
        assertTrue("B→A 休眠", db.worldSocialDao().getEdge("B", "A")!!.dormant)

        service.join("A", day0 + 3)
        // A 重新加入恢复所有含 A 的边（两向）；B 未回来但引擎/注入按 getInWorld 过滤，无脏显示（架构裁定·E13）。
        assertFalse("A→B 恢复", db.worldSocialDao().getEdge("A", "B")!!.dormant)
        assertFalse("B→A 恢复", db.worldSocialDao().getEdge("B", "A")!!.dormant)
        assertEquals(listOf("A"), db.characterDao().getInWorld().map { it.uuid }) // 只有 A 在世
    }

    // MARK: - 世界 × 世界书单事务自由切换（Q5 方案 B + Q6 原住民锁死）

    @Test
    fun `joinClearingWorldBooks_单事务摘除全部世界书并加入世界_原住民拒绝`() = runBlocking {
        seedWorld()
        character("c-wb", "陆沉", joined = false)
        db.worldBookDao().upsertBook(WorldBookEntity(uuid = "b1", name = "光夜暗幕录"))
        db.worldBookDao().upsertBook(WorldBookEntity(uuid = "b2", name = "副设定"))
        db.worldBookDao().bind(WorldBookBindingEntity(characterUuid = "c-wb", bookUuid = "b1"))
        db.worldBookDao().bind(WorldBookBindingEntity(characterUuid = "c-wb", bookUuid = "b2"))
        db.worldSocialDao().upsertEdge(WorldRelationshipEntity(fromId = "c-wb", toId = "other", dormant = true, updatedAt = day0))

        // 未确认普通 join 仍防误触拦截
        assertEquals(WorldMembershipService.Result.WorldbookBound, service.join("c-wb", day0))

        // 确认切换后单事务清空绑定 + 入世 + 唤醒休眠边 + 写 join 事件
        assertEquals(WorldMembershipService.Result.Ok, service.joinClearingWorldBooks("c-wb", day0 + 5))
        assertTrue(db.worldBookDao().boundBookUuids("c-wb").isEmpty())
        assertTrue(db.characterDao().getByUuid("c-wb")!!.joinedWorld)
        assertFalse(db.worldSocialDao().getEdge("c-wb", "other")!!.dormant)
        assertEquals(WorldMembershipService.JOIN_KIND, db.worldDao().getAllEvents().single().kindRaw)

        // 原住民出身拒绝
        character("c-native", "温知夏", joined = true, joinedAt = day0)
        db.worldNativeDao().upsert(WorldNativeStateEntity(nativeId = "native:wen", recruitedCharacterUuid = "c-native"))
        assertEquals(WorldMembershipService.Result.NativeOrigin, service.joinClearingWorldBooks("c-native", day0 + 10))
    }

    @Test
    fun `leaveAndBindWorldBook_单事务离世休眠清旅行并绑书_原住民拒绝`() = runBlocking {
        seedWorld()
        character("c-in", "沈星回", joined = true, joinedAt = day0)
        db.worldBookDao().upsertBook(WorldBookEntity(uuid = "b-star", name = "深空猎人录"))
        db.worldBookDao().upsertBook(WorldBookEntity(uuid = "b-extra", name = "番外设定"))
        db.worldSocialDao().upsertEdge(WorldRelationshipEntity(fromId = "c-in", toId = "other", dormant = false, updatedAt = day0))
        db.worldDao().upsertTravel(
            WorldTravelEntity(
                ownerId = "c-in", fromCityId = WorldIds.HOME_CITY_ID, toCityId = "city_taoqiu",
                departAt = day0, arriveAt = day0 + 1000, modeRaw = WorldIds.TravelModes.TRAIN,
            ),
        )

        // sole = true：离开世界 + 休眠关系 + 清在途旅行 + 写 leave 事件 + 唯一绑定 b-star
        assertEquals(
            WorldMembershipService.Result.Ok,
            service.leaveAndBindWorldBook("c-in", bookUuid = "b-star", sole = true, nowMs = day0 + 20),
        )
        assertFalse(db.characterDao().getByUuid("c-in")!!.joinedWorld)
        assertTrue(db.worldSocialDao().getEdge("c-in", "other")!!.dormant)
        assertNull(db.worldDao().getTravel("c-in"))
        assertEquals(listOf("b-star"), db.worldBookDao().boundBookUuids("c-in"))
        assertEquals(WorldMembershipService.LEAVE_KIND, db.worldDao().getAllEvents().single().kindRaw)

        // sole = false（书详情页增量勾选）：保留已有绑定并追加新书
        assertEquals(
            WorldMembershipService.Result.Ok,
            service.leaveAndBindWorldBook("c-in", bookUuid = "b-extra", sole = false, nowMs = day0 + 30),
        )
        assertEquals(setOf("b-star", "b-extra"), db.worldBookDao().boundBookUuids("c-in").toSet())

        // 原住民出身不可离世绑书
        character("c-native", "温知夏", joined = true, joinedAt = day0)
        db.worldNativeDao().upsert(WorldNativeStateEntity(nativeId = "native:wen", recruitedCharacterUuid = "c-native"))
        assertEquals(
            WorldMembershipService.Result.NativeOrigin,
            service.leaveAndBindWorldBook("c-native", bookUuid = "b-star", sole = true, nowMs = day0 + 40),
        )
        assertTrue(db.characterDao().getByUuid("c-native")!!.joinedWorld)
        assertTrue(db.worldBookDao().boundBookUuids("c-native").isEmpty())
    }
}
