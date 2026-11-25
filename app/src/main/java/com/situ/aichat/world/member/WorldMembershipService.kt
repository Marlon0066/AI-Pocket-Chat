package com.situ.aichat.world.member

import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.WorldBookBindingEntity
import com.situ.aichat.data.local.entity.WorldEventEntity
import com.situ.aichat.util.StringListJson
import com.situ.aichat.world.WorldBootstrap
import com.situ.aichat.world.atlas.WorldAtlas
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 世界成员动作（W13 图纸 §3.1/§3.2 + 世界×世界书自由切换）：把某正式角色「加入/离开/搬家」或与世界书单事务互切。
 * 每动作**单 `db.withTransaction`**（照 W6 [com.situ.aichat.world.cast.WorldRecruitService] 先例·进程死 = 全回滚）：
 * 定点列更新（**禁整行 @Update**·钱路审计教训）+ 关系边休眠翻转 + 一条世界事件落库（开机小报自然播报）。
 * join 的静默建世 [WorldBootstrap.ensureCreated] **刻意提到事务外**（锁内自持 Mutex 写库·嵌进事务 = 双挂死·复核 R1 🟡-1）。
 *
 * **成员管线零改动自动生效**：星图/结算引擎/站位演员表/聊天注入全部吃 [com.situ.aichat.data.local.dao.CharacterDao.getInWorld]，
 * `joinedWorld` 列一变即联动。休眠边由引擎既有 `!dormant` 过滤跳过（离开的人不脏显示）。
 *
 * **钱路零碰**；派生 uuid 由调用方 [nowMs] 参与幂等派生（禁掺 `System.currentTimeMillis`）。
 */
@Singleton
class WorldMembershipService @Inject constructor(
    private val db: AppDatabase,
    private val bootstrap: WorldBootstrap,
) {

    /** 动作结果：成功 / 挂世界书拒绝 / 原住民出身拒绝 / 无操作（已在目标态·同城·查无）。 */
    sealed interface Result {
        data object Ok : Result
        data object WorldbookBound : Result
        data object NativeOrigin : Result
        data object NoOp : Result
    }

    /**
     * 加入世界（§3.1）：先在**事务外**静默建世 [WorldBootstrap.ensureCreated]（幂等·决策 41④ 先例），再进单
     * 事务做守卫与写入——ensureCreated 自持 Mutex 且锁内写库，**绝不能**包进 `db.withTransaction`（持事务等
     * Mutex ↔ 世界屏/世界卡 init 的 ensureCreated 持 Mutex 等唯一写连接 = 双挂死·复核 R1 🟡-1）。
     * 事务内：已加入 → NoOp；挂世界书 → WorldbookBound（防御纵深·未确认摘书前拒绝）；否则定点写
     * joinedWorld=1/worldJoinedAt=nowMs（住址保留上次值·出厂 city_yunye）→ 恢复休眠边（重新加入·契约决策
     * 16）→ join 世界事件。
     */
    suspend fun join(characterUuid: String, nowMs: Long): Result {
        val state = bootstrap.ensureCreated(nowMs)
        return db.withTransaction {
            val character = db.characterDao().getByUuid(characterUuid) ?: return@withTransaction Result.NoOp
            if (character.joinedWorld) return@withTransaction Result.NoOp
            if (db.worldBookDao().boundBookUuids(characterUuid).isNotEmpty()) return@withTransaction Result.WorldbookBound
            performJoinInTx(character.uuid, character.name, character.worldHomeCityId, state.seed, nowMs)
            Result.Ok
        }
    }

    /**
     * 确认切换：单事务「摘除该角色全部世界书绑定 + 加入世界」。原住民出身 → NativeOrigin；查无角色 → NoOp。
     * 若角色本已在世界里（历史脏态自愈），仍清空残留绑定并返回 Ok，不重复写 join 事件。
     */
    suspend fun joinClearingWorldBooks(characterUuid: String, nowMs: Long): Result {
        val state = bootstrap.ensureCreated(nowMs)
        return db.withTransaction {
            val character = db.characterDao().getByUuid(characterUuid) ?: return@withTransaction Result.NoOp
            if (db.worldNativeDao().getByRecruitedUuid(characterUuid) != null) return@withTransaction Result.NativeOrigin
            db.worldBookDao().unbindAllForCharacter(characterUuid)
            if (!character.joinedWorld) {
                performJoinInTx(character.uuid, character.name, character.worldHomeCityId, state.seed, nowMs)
            }
            Result.Ok
        }
    }

    /**
     * 离开世界（§3.1）：原住民出身 → NativeOrigin（不可离开）；未加入 → NoOp。否则单事务内定点写
     * joinedWorld=0/worldJoinedAt=null（住址保留）→ 休眠两向边 → 删在途旅行（TA 退场·E11）→ leave 世界事件。
     */
    suspend fun leave(characterUuid: String, nowMs: Long): Result = db.withTransaction {
        val character = db.characterDao().getByUuid(characterUuid) ?: return@withTransaction Result.NoOp
        if (db.worldNativeDao().getByRecruitedUuid(characterUuid) != null) return@withTransaction Result.NativeOrigin
        if (!character.joinedWorld) return@withTransaction Result.NoOp
        performLeaveInTx(character.uuid, character.name, character.worldHomeCityId, nowMs)
        Result.Ok
    }

    /**
     * 确认切换：单事务「（若在世界则）离开世界 + 挂载目标世界书」。原住民出身 → NativeOrigin；查无角色 → NoOp。
     * @param sole true = 角色编辑页单选收敛（先清空旧绑定再绑 [bookUuid]）；false = 书详情页/多选增量追加。
     */
    suspend fun leaveAndBindWorldBook(
        characterUuid: String,
        bookUuid: String?,
        sole: Boolean = true,
        nowMs: Long,
    ): Result = db.withTransaction {
        val character = db.characterDao().getByUuid(characterUuid) ?: return@withTransaction Result.NoOp
        if (db.worldNativeDao().getByRecruitedUuid(characterUuid) != null) return@withTransaction Result.NativeOrigin
        if (character.joinedWorld) {
            performLeaveInTx(character.uuid, character.name, character.worldHomeCityId, nowMs)
        }
        if (sole) {
            db.worldBookDao().unbindAllForCharacter(characterUuid)
        }
        if (bookUuid != null) {
            db.worldBookDao().bind(
                WorldBookBindingEntity(characterUuid = characterUuid, bookUuid = bookUuid, createdAt = nowMs),
            )
        }
        Result.Ok
    }

    /**
     * 搬家（§3.1）：未加入 → NoOp；原住民出身 → NativeOrigin；同城 → NoOp；toCityId 图集查无 → NoOp。否则
     * 单事务内定点写 worldHomeCityId=toCityId → move 世界事件（住址城 = 新城）。
     */
    suspend fun move(characterUuid: String, toCityId: String, nowMs: Long): Result = db.withTransaction {
        val character = db.characterDao().getByUuid(characterUuid) ?: return@withTransaction Result.NoOp
        if (!character.joinedWorld) return@withTransaction Result.NoOp
        if (db.worldNativeDao().getByRecruitedUuid(characterUuid) != null) return@withTransaction Result.NativeOrigin
        if (character.worldHomeCityId == toCityId) return@withTransaction Result.NoOp
        val state = db.worldDao().getState() ?: return@withTransaction Result.NoOp
        val city = WorldAtlas.of(state.seed).cityById(toCityId) ?: return@withTransaction Result.NoOp
        db.characterDao().updateWorldHomeCity(characterUuid, toCityId)
        db.worldDao().upsertEvent(
            WorldEventEntity(
                uuid = eventUuid(MOVE_KIND, characterUuid, nowMs),
                kindRaw = MOVE_KIND,
                involvedIdsJson = StringListJson.encode(listOf(characterUuid)),
                cityId = toCityId,
                summary = "${character.name} 搬去了${city.name}",
                happenedAt = nowMs,
            ),
        )
        Result.Ok
    }

    private suspend fun performJoinInTx(
        characterUuid: String,
        characterName: String,
        homeCityId: String,
        seed: Long,
        nowMs: Long,
    ) {
        db.characterDao().updateWorldMembership(characterUuid, joined = true, joinedAt = nowMs)
        db.worldSocialDao().setDormantFor(characterUuid, dormant = false)
        val cityName = cityNameOf(seed, homeCityId)
        db.worldDao().upsertEvent(
            WorldEventEntity(
                uuid = eventUuid(JOIN_KIND, characterUuid, nowMs),
                kindRaw = JOIN_KIND,
                involvedIdsJson = StringListJson.encode(listOf(characterUuid)),
                cityId = homeCityId,
                summary = "$characterName 来到了$cityName",
                happenedAt = nowMs,
            ),
        )
    }

    private suspend fun performLeaveInTx(
        characterUuid: String,
        characterName: String,
        homeCityId: String,
        nowMs: Long,
    ) {
        db.characterDao().updateWorldMembership(characterUuid, joined = false, joinedAt = null)
        db.worldSocialDao().setDormantFor(characterUuid, dormant = true)
        db.worldDao().deleteTravel(characterUuid)
        db.worldDao().upsertEvent(
            WorldEventEntity(
                uuid = eventUuid(LEAVE_KIND, characterUuid, nowMs),
                kindRaw = LEAVE_KIND,
                involvedIdsJson = StringListJson.encode(listOf(characterUuid)),
                cityId = homeCityId,
                summary = "$characterName 离开了，大家有点想 TA",
                happenedAt = nowMs,
            ),
        )
    }

    /** 城名解析（图集查无 → 回退 id·实际 home/目标城恒经图集验真，不会命中）。 */
    private fun cityNameOf(seed: Long, cityId: String): String =
        WorldAtlas.of(seed).cityById(cityId)?.name ?: cityId

    /** 事件 uuid 幂等派生（§3.2 锁死·nowMs 参与派生·同一动作同一时刻幂等）。 */
    private fun eventUuid(kind: String, characterUuid: String, nowMs: Long): String =
        UUID.nameUUIDFromBytes("world:member:$kind:$characterUuid:$nowMs".toByteArray()).toString()

    companion object {
        /** 世界事件 kind（`world_event.kindRaw`·§9 锁死）。 */
        const val JOIN_KIND = "join"
        const val LEAVE_KIND = "leave"
        const val MOVE_KIND = "move"
    }
}
