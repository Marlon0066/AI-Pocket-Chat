package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.situ.aichat.data.local.entity.PromiseEntity
import kotlinx.coroutines.flow.Flow

/**
 * 「我们的约定」承诺账本数据访问（记忆改造一期·部件①·图纸 §3.1）。只做 CRUD；对账 / 注入选择 / 素材收集
 * 纯逻辑在 [com.situ.aichat.promise.PromiseReconciliation] / [com.situ.aichat.promise.PromiseInjectionRenderer]，
 * 落库业务在 [com.situ.aichat.promise.PromiseLedgerService]，仓库薄包装在
 * [com.situ.aichat.data.repository.PromiseRepository]。
 */
@Dao
interface PromiseDao {

    /** PK upsert（注册新约定 / 状态流转 fulfilled·cancelled 幂等）。 */
    @Upsert
    suspend fun upsert(promise: PromiseEntity)

    @Upsert
    suspend fun upsertAll(promises: List<PromiseEntity>)

    /** 按 uuid 查（对账落库前「重读 → 仍 open 才写」防陈旧覆写用）。 */
    @Query("SELECT * FROM promises WHERE uuid = :uuid LIMIT 1")
    suspend fun byUuid(uuid: String): PromiseEntity?

    /** 某角色全部进行中约定（createdAtMillis 升序）——注入 + 对账清单 + 去重的单一数据源。 */
    @Query("SELECT * FROM promises WHERE characterUuid = :characterUuid AND statusRaw = 'open' ORDER BY createdAtMillis ASC")
    suspend fun openByCharacter(characterUuid: String): List<PromiseEntity>

    /** 某角色近期已了结约定（statusRaw != 'open' 且 resolvedAtMillis >= :sinceMillis）——注入「最近了结」组数据源。 */
    @Query("SELECT * FROM promises WHERE characterUuid = :characterUuid AND statusRaw != 'open' AND resolvedAtMillis >= :sinceMillis")
    suspend fun resolvedSince(characterUuid: String, sinceMillis: Long): List<PromiseEntity>

    /** Flow 版进行中查询（三期 UI·排序在 [com.situ.aichat.promise.PromiseInjectionRenderer.sortedOpen] 单源）。 */
    @Query("SELECT * FROM promises WHERE characterUuid = :characterUuid AND statusRaw = 'open' ORDER BY createdAtMillis ASC")
    fun observeOpenByCharacter(characterUuid: String): Flow<List<PromiseEntity>>

    /** 某角色全部已了结（全部历史·了结时间降序）——账本子页「已了结」节 + 资料页卡了结微区的数据源。 */
    @Query("SELECT * FROM promises WHERE characterUuid = :characterUuid AND statusRaw != 'open' ORDER BY resolvedAtMillis DESC")
    fun observeResolvedByCharacter(characterUuid: String): Flow<List<PromiseEntity>>

    /** 某角色进行中约定计数。 */
    @Query("SELECT COUNT(*) FROM promises WHERE characterUuid = :characterUuid AND statusRaw = 'open'")
    suspend fun countOpenByCharacter(characterUuid: String): Int

    /** 全表（备份导出用）。 */
    @Query("SELECT * FROM promises")
    suspend fun getAll(): List<PromiseEntity>

    /** 删角色连坐清理（无 FK·手动清·图纸 §3.1·E15）。 */
    @Query("DELETE FROM promises WHERE characterUuid = :characterUuid")
    suspend fun deleteByCharacter(characterUuid: String)

    /** 我们的日子·卷一·只读：该角色全部约定（createdAtMillis 升序·事实层按 created / resolved 两点切日·总图纸 §3.5）。 */
    @Query("SELECT * FROM promises WHERE characterUuid = :characterUuid ORDER BY createdAtMillis ASC")
    suspend fun allByCharacter(characterUuid: String): List<PromiseEntity>
}
