package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.situ.aichat.data.local.entity.GiftRecordEntity
import kotlinx.coroutines.flow.Flow

/**
 * 礼物记录读写（M09）。送礼/收礼事件落库 + 收礼盒/历史/印象/边际衰减查询。category 是目录派生（不在表里），凡需按
 * category 过滤的（边际衰减）都是 fetch 后在 Kotlin 侧过滤——7 天记录量有限，开销可忽略（1:1 iOS）。
 */
@Dao
interface GiftDao {

    @Insert
    suspend fun insert(record: GiftRecordEntity)

    /** 礼物店反应流第二步用：写回 reactionText/moodEmoji/affinityGain/relationshipImpactJSON（1:1 iOS generateReaction 改 record）。 */
    @Update
    suspend fun update(record: GiftRecordEntity)

    @Query("SELECT * FROM gift_records WHERE uuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): GiftRecordEntity?

    /** 全部礼物记录（按时间升序），供备份导出（13.6 全局段）。 */
    @Query("SELECT * FROM gift_records ORDER BY timestamp ASC")
    suspend fun getAllRecords(): List<GiftRecordEntity>

    /** 备份恢复用：按 uuid 覆盖式插入（再导入幂等；纯历史，不动钱包余额）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(record: GiftRecordEntity)

    /**
     * 删角色用（R6）：该角色作为送/收方的全部礼物记录 DIY 图盘路径（先查后删行，否则删行后路径无从取得→图永久孤儿）。
     * 角色 uuid 恒非空、user 侧 *CharacterUUID 恒为空串 → 直接按两侧 uuid 匹配无误命中 user 行。
     */
    @Query(
        "SELECT diyImagePath FROM gift_records WHERE isDIY = 1 AND diyImagePath IS NOT NULL " +
            "AND (senderCharacterUUID = :characterUuid OR receiverCharacterUUID = :characterUuid)",
    )
    suspend fun diyImagePathsForCharacter(characterUuid: String): List<String>

    /**
     * 删角色用（R6，1:1 iOS cleanupOrphanedRecords 对 GiftRecord 的清理）：删该角色作为送方或收方的全部礼物记录。
     * gift_records 对角色**无外键** → 不删则收礼盒永久残留「未知」对手方条目 + DB 行无界堆积。**纯历史删除，不动钱包余额**。
     * 调用方须先经 [diyImagePathsForCharacter] 删 DIY 图盘文件。
     */
    @Query("DELETE FROM gift_records WHERE senderCharacterUUID = :characterUuid OR receiverCharacterUUID = :characterUuid")
    suspend fun deleteForCharacter(characterUuid: String)

    /** 收礼盒「送出」tab：用户送出的全部礼物（跨角色），timestamp 降序（1:1 iOS GiftBoxView sentGifts 查询）。 */
    @Query("SELECT * FROM gift_records WHERE senderType = 'user' ORDER BY timestamp DESC")
    fun observeUserSentGifts(): Flow<List<GiftRecordEntity>>

    /** 收礼盒「收到」tab：用户收到的全部礼物（角色主动送），timestamp 降序（1:1 iOS GiftBoxView receivedGifts 查询）。 */
    @Query("SELECT * FROM gift_records WHERE receiverType = 'user' ORDER BY timestamp DESC")
    fun observeUserReceivedGifts(): Flow<List<GiftRecordEntity>>

    /** 用户收礼**件数**（K5·2026-07-12）：仪表盘「礼物盒」只要数字——WHERE 与 [observeUserReceivedGifts] 逐字同（平价测试钉死）。 */
    @Query("SELECT COUNT(*) FROM gift_records WHERE receiverType = 'user'")
    fun observeUserReceivedGiftCount(): Flow<Int>

    /**
     * 日记「今天的素材」芯片（J5·图纸 §4-J5）：用户在 [start, end] 收到的最近一份礼物；无 → null。
     * **纯只读·钱路零碰**（不动任何写路径 / 金额逻辑·WHERE receiverType='user' 与 [observeUserReceivedGifts] 同口径）。
     */
    @Query("SELECT * FROM gift_records WHERE receiverType = 'user' AND timestamp BETWEEN :start AND :end ORDER BY timestamp DESC LIMIT 1")
    suspend fun userReceivedGiftBetween(start: Long, end: Long): GiftRecordEntity?

    /**
     * 边际衰减用：某角色在 [since, ∞) 收到的「用户送出」礼物（排除本次刚建的 record）。
     * 调用方（[com.situ.aichat.gift.GiftMarginalDecayService]）再按 category 过滤同品类计数。
     */
    @Query(
        "SELECT * FROM gift_records WHERE receiverCharacterUUID = :characterUuid " +
            "AND senderType = 'user' AND timestamp >= :since AND uuid != :excludingUuid",
    )
    suspend fun recentUserGiftsToCharacter(
        characterUuid: String,
        since: Long,
        excludingUuid: String,
    ): List<GiftRecordEntity>

    /** 用户送给该角色的全部礼物，timestamp 降序（礼物历史【用户送我】段 + GiftMemoryService 收礼列表）。 */
    @Query(
        "SELECT * FROM gift_records WHERE receiverCharacterUUID = :characterUuid " +
            "AND senderType = 'user' ORDER BY timestamp DESC",
    )
    suspend fun userGiftsToCharacterDesc(characterUuid: String): List<GiftRecordEntity>

    /** Flow 版收礼列表，供资料页亲友账卡实时刷新（gift_records 变化即重发）。 */
    @Query(
        "SELECT * FROM gift_records WHERE receiverCharacterUUID = :characterUuid " +
            "AND senderType = 'user' ORDER BY timestamp DESC",
    )
    fun observeUserGiftsToCharacterDesc(characterUuid: String): Flow<List<GiftRecordEntity>>

    /** 该角色主动送给用户的全部礼物，timestamp 降序（礼物历史【我送用户】段）。 */
    @Query(
        "SELECT * FROM gift_records WHERE senderType = 'character' " +
            "AND senderCharacterUUID = :characterUuid AND receiverType = 'user' ORDER BY timestamp DESC",
    )
    suspend fun characterGiftsToUserDesc(characterUuid: String): List<GiftRecordEntity>

    /**
     * 主动送礼避重用（1:1 iOS `ProactiveGiftCandidateFilter.recentlySentItemIds`）：某角色在 [since, ∞) **送出**的礼物
     * itemId 列表。仅 senderType=character（不滤 receiverType，与 iOS 一致），用于近 30 天避免重复送同款。
     */
    @Query(
        "SELECT giftItemId FROM gift_records WHERE senderType = 'character' " +
            "AND senderCharacterUUID = :characterUuid AND timestamp >= :since",
    )
    suspend fun recentCharacterSentGiftItemIds(characterUuid: String, since: Long): List<String>

    /**
     * 朋友圈晒礼物候选（P9.2e，1:1 iOS `pendingGiftCandidates` 窗口查询）：某角色在 [windowStart, ∞) **收到**的
     * 礼物（用户送 + 其他角色主动送都算），timestamp 升序。调用方（[com.situ.aichat.gift.GiftMomentQueueService]）
     * 再按「晚于上次礼物帖 T」+「珍贵/手作」过滤。
     */
    @Query(
        "SELECT * FROM gift_records WHERE receiverCharacterUUID = :characterUuid " +
            "AND receiverType = 'character' AND timestamp >= :windowStart ORDER BY timestamp ASC",
    )
    suspend fun giftsReceivedByCharacterSince(characterUuid: String, windowStart: Long): List<GiftRecordEntity>

    /**
     * 日记晒礼物候选（P9.2e，1:1 iOS `pendingGiftCandidatesForDiary` 窗口查询）：用户在 [windowStart, ∞) **送给
     * （任意）角色**的礼物，timestamp 升序。调用方再按「晚于上一篇礼物日记 T」+「珍贵/手作」过滤。
     */
    @Query(
        "SELECT * FROM gift_records WHERE senderType = 'user' " +
            "AND receiverType = 'character' AND timestamp >= :windowStart ORDER BY timestamp ASC",
    )
    suspend fun userGiftsToCharactersSince(windowStart: Long): List<GiftRecordEntity>

    /** 我们的日子·卷一·只读：该角色作为送 / 收方的全部礼物记录（timestamp 升序·事实层按日切·总图纸 §3.5）。 */
    @Query("SELECT * FROM gift_records WHERE senderCharacterUUID = :characterUuid OR receiverCharacterUUID = :characterUuid ORDER BY timestamp ASC")
    suspend fun allForCharacter(characterUuid: String): List<GiftRecordEntity>
}
