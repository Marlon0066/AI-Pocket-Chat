package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.situ.aichat.data.local.entity.RedPacketRecordEntity
import kotlinx.coroutines.flow.Flow

/**
 * 红包记录读写（P9.3a）。发送落库 + 收/拒/过期状态机改写 + 拆开气泡实时查 + 24h 过期扫描候选。
 *
 * 状态机改写走纯函数 copy + [update]（不可变 Room 行）。过期/预警扫描（9.3b）取 [pendingRecords]。
 */
@Dao
interface RedPacketDao {

    /** 发送瞬间插入（status=pending）。 */
    @Insert
    suspend fun insert(record: RedPacketRecordEntity)

    /** 收/拒/过期改写状态（status/resolvedAt/rejectionReason/notifiedExpiringSoon），@Update 整行。 */
    @Update
    suspend fun update(record: RedPacketRecordEntity)

    /** 按 uuid 取（accept/reject/expire 入口 fetch + 状态校验；找不到 → recordNotFound）。 */
    @Query("SELECT * FROM red_packet_records WHERE uuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): RedPacketRecordEntity?

    /**
     * 按 recordUuid 响应式观察（拆开气泡 UI 用，9.3b）：消息卡 [com.situ.aichat.data.model.RedPacketData.recordUUID]
     * → 实时查 Record 状态自动刷新气泡（对齐 iOS RedPacketBubbleView `.task(id: recordUUID)`）。
     */
    @Query("SELECT * FROM red_packet_records WHERE uuid = :uuid LIMIT 1")
    fun observeByUuid(uuid: String): Flow<RedPacketRecordEntity?>

    /**
     * 全部托管中（pending）记录（24h 过期 + 22h 预警扫描候选，9.3b
     * `RedPacketExpirationScanService`）。createdAt 升序，先处理最早的。
     *
     * SQL 字面 `'pending'` 必须等于 [com.situ.aichat.data.model.RedPacketStatus.PENDING].raw（与 GiftDao 的
     * `'user'`/`'character'` 同约定）；该不变量由 RedPacketDataLayerTest 的 raw 断言锁定，重命名会断测试而非静默失效。
     */
    @Query("SELECT * FROM red_packet_records WHERE status = 'pending' ORDER BY createdAt ASC")
    suspend fun pendingRecords(): List<RedPacketRecordEntity>

    /**
     * 22h 预警「已通知」标记的**安全写**——仅置 `notifiedExpiringSoon=1`，且**仅当仍 pending**。单语句条件 UPDATE = 原子 CAS：
     * 绝不像整行 [update] 那样从陈旧快照回写 `status`/`resolvedAt`。修复丢更新（[com.situ.aichat.redpacket.RedPacketExpirationScanService]
     * 扫描读快照 → 并发被拆开 status=accepted → 整行回写会把 status 打回 pending → 红包被重复结算[用户重领 / 过期再退发送方]
     * **凭空造币**）。安卓不可变行 + `@Update` 整行才有此患（iOS @MainActor 改活对象只动单字段、不会回退 status）。
     * `'pending'` 字面同 [pendingRecords]·由 `RedPacketDataLayerTest` 锁定 raw（重命名断测试而非静默失效）。
     */
    @Query("UPDATE red_packet_records SET notifiedExpiringSoon = 1 WHERE uuid = :uuid AND status = 'pending'")
    suspend fun markExpiringSoonNotified(uuid: String): Int

    /**
     * P1-25（批7 复核修）：删角色撤红包预警用——这些会话的全部红包 uuid。不限 status（预警已弹后记录可能已转
     * expired，仍挂通知栏；与台账查询同款裁定）。记录无 FK 不随删角色级联，但语义上属死会话，删前枚举。
     */
    @Query("SELECT uuid FROM red_packet_records WHERE conversationUuid IN (:conversationUuids)")
    suspend fun uuidsForConversations(conversationUuids: List<String>): List<String>

    /** 全部红包记录（按创建升序），供备份导出（13.6 全局段；含全部状态，pending 恢复后重新参与过期扫描）。 */
    @Query("SELECT * FROM red_packet_records ORDER BY createdAt ASC")
    suspend fun getAllRecords(): List<RedPacketRecordEntity>

    /** 备份恢复用：按 uuid 覆盖式插入（再导入幂等；amount 是托管快照，恢复不重复扣/加币）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(record: RedPacketRecordEntity)

    /** 我们的日子·卷一·只读：该角色作为送 / 收方的全部红包记录（createdAt 升序·amount 列随行读出但绝不进 facts·总图纸 §3.5）。 */
    @Query("SELECT * FROM red_packet_records WHERE senderCharacterUUID = :characterUuid OR receiverCharacterUUID = :characterUuid ORDER BY createdAt ASC")
    suspend fun allForCharacter(characterUuid: String): List<RedPacketRecordEntity>
}
