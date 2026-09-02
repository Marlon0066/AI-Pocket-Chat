package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.situ.aichat.data.local.entity.OurDayCalendarRow
import com.situ.aichat.data.local.entity.OurDayEntity
import kotlinx.coroutines.flow.Flow

/**
 * 「我们的日子」数据访问（总图纸 docs/handoff/2026-09-02-我们的日子-总图纸.md §3.6 · 卷一建 · 卷二三只准用这些）。
 * 只做 CRUD；候选判定 / 事实聚合 / 手记生成全部在 `com.situ.aichat.ourdays` 包。
 *
 * 写序（总图纸 §3.10）：三类写各自**列级 UPDATE**互不覆盖——事实列 [updateFacts] / 手记列 [updateGeneratedNote]
 * [updateUserNote] / 标记列 [updateAttempt] [updateHidden] [markDeleted]；[upsert] 只用于新行 / 备份恢复 /
 * `regenerate` 的 `deleted` 复位（卷一图纸 §9.4）。
 */
@Dao
interface OurDayDao {

    @Upsert
    suspend fun upsert(row: OurDayEntity)

    @Query("SELECT * FROM our_days WHERE uuid = :uuid LIMIT 1")
    suspend fun byUuid(uuid: String): OurDayEntity?

    @Query("SELECT * FROM our_days WHERE characterUuid = :characterUuid AND dayKey = :dayKey LIMIT 1")
    suspend fun byDay(characterUuid: String, dayKey: String): OurDayEntity?

    @Query("SELECT dayKey FROM our_days WHERE characterUuid = :characterUuid")
    suspend fun dayKeysForCharacter(characterUuid: String): List<String>

    @Query("SELECT * FROM our_days WHERE characterUuid = :characterUuid AND dayKey BETWEEN :fromKey AND :toKey ORDER BY dayKey ASC")
    suspend fun daysInRange(characterUuid: String, fromKey: String, toKey: String): List<OurDayEntity>

    @Query("SELECT * FROM our_days WHERE characterUuid = :characterUuid AND dayKey BETWEEN :fromKey AND :toKey ORDER BY dayKey ASC")
    fun observeDaysInRange(characterUuid: String, fromKey: String, toKey: String): Flow<List<OurDayEntity>>

    @Query("SELECT * FROM our_days WHERE dayKey BETWEEN :fromKey AND :toKey ORDER BY dayKey ASC, characterUuid ASC")
    fun observeAllInRange(fromKey: String, toKey: String): Flow<List<OurDayEntity>>

    @Query("SELECT COUNT(*) FROM our_days WHERE characterUuid = :characterUuid AND deleted = 0")
    suspend fun countForCharacter(characterUuid: String): Int

    /** 事实列（可重算·手记列零碰）。 */
    @Query(
        "UPDATE our_days SET factsJson = :factsJson, messageCount = :messageCount, callSeconds = :callSeconds, " +
            "hasMeeting = :hasMeeting, hasRelation = :hasRelation, hasLife = :hasLife, updatedAtMillis = :now WHERE uuid = :uuid",
    )
    suspend fun updateFacts(
        uuid: String,
        factsJson: String,
        messageCount: Int,
        callSeconds: Int,
        hasMeeting: Boolean,
        hasRelation: Boolean,
        hasLife: Boolean,
        now: Long,
    )

    /** 自动生成的手记落库（noteEdited 清 0·向量作废等卷二重嵌）。 */
    @Query(
        "UPDATE our_days SET note = :note, factLine = :factLine, noteStatus = :status, noteAttempts = :attempts, " +
            "noteEdited = 0, generatedAt = :now, updatedAtMillis = :now, embedding = NULL WHERE uuid = :uuid",
    )
    suspend fun updateGeneratedNote(uuid: String, note: String, factLine: String, status: String, attempts: Int, now: Long)

    /** 一次失败 / 第 3 次转 failed（标记列）。 */
    @Query("UPDATE our_days SET noteStatus = :status, noteAttempts = :attempts, updatedAtMillis = :now WHERE uuid = :uuid")
    suspend fun updateAttempt(uuid: String, status: String, attempts: Int, now: Long)

    /** 卷三用：用户手改（noteEdited 置 1·自动流程此后永不覆盖）。 */
    @Query(
        "UPDATE our_days SET note = :note, factLine = :factLine, noteEdited = 1, noteStatus = 'ok', deleted = 0, " +
            "generatedAt = :now, updatedAtMillis = :now, embedding = NULL WHERE uuid = :uuid",
    )
    suspend fun updateUserNote(uuid: String, note: String, factLine: String, now: Long)

    /** 卷三用：「别让 TA 记」。 */
    @Query("UPDATE our_days SET hiddenFromMemory = :hidden, embedding = NULL, updatedAtMillis = :now WHERE uuid = :uuid")
    suspend fun updateHidden(uuid: String, hidden: Boolean, now: Long)

    /** 卷三用：墓碑（行留·手记清空·不自动重生）。 */
    @Query(
        "UPDATE our_days SET deleted = 1, note = '', factLine = '', noteStatus = 'none', embedding = NULL, " +
            "updatedAtMillis = :now WHERE uuid = :uuid",
    )
    suspend fun markDeleted(uuid: String, now: Long)

    /** 全表（备份导出用）。 */
    @Query("SELECT * FROM our_days")
    suspend fun getAll(): List<OurDayEntity>

    /**
     * 卷二·注入候选：未删 · 未隐藏 · 事实行非空（dayKey 升序）。**投影行**（R1 🔵-7）：注入只读 dayKey / factLine / hasMeeting /
     * messageCount / callSeconds，`embedding`（≈2KB/行）/ `note` / `factsJson` 以占位返回（NULL / '' / ''）不物化——本查询每回合
     * （含语音每句）全量预取，IO 随天数线性增长；别拿它的行当完整行用（照 `StoryDao` `'' AS content` 先例·Room 不许非空列缺席）。
     */
    @SuppressWarnings(androidx.room.RoomWarnings.QUERY_MISMATCH)
    @Query(
        "SELECT uuid, characterUuid, dayKey, '' AS factsJson, messageCount, callSeconds, hasMeeting, hasRelation, hasLife, " +
            "'' AS note, factLine, noteStatus, noteAttempts, noteEdited, hiddenFromMemory, deleted, generatedAt, createdAtMillis, " +
            "updatedAtMillis, NULL AS embedding FROM our_days " +
            "WHERE characterUuid = :characterUuid AND deleted = 0 AND hiddenFromMemory = 0 AND factLine != '' ORDER BY dayKey ASC",
    )
    suspend fun injectableForCharacter(characterUuid: String): List<OurDayEntity>

    /** 卷二·缺嵌入回填批（hidden / deleted / 空事实行永不建向量·E13）。 */
    @Query("SELECT * FROM our_days WHERE embedding IS NULL AND factLine != '' AND hiddenFromMemory = 0 AND deleted = 0 LIMIT :limit")
    suspend fun missingEmbedding(limit: Int): List<OurDayEntity>

    /** 卷二·列级写嵌入（照 OfflineMeetingMemoryDao.updateEmbedding）。 */
    @Query("UPDATE our_days SET embedding = :embedding WHERE uuid = :uuid")
    suspend fun updateEmbedding(uuid: String, embedding: ByteArray)

    /** 卷二·换模型三清（照 OfflineMeetingMemoryDao.clearAllEmbeddings）。 */
    @Query("UPDATE our_days SET embedding = NULL WHERE embedding IS NOT NULL")
    suspend fun clearAllEmbeddings(): Int

    /** 卷二·检索候选：已建向量 · 未删 · 未隐藏。 */
    @Query("SELECT * FROM our_days WHERE characterUuid = :characterUuid AND embedding IS NOT NULL AND deleted = 0 AND hiddenFromMemory = 0")
    suspend fun embeddedForCharacter(characterUuid: String): List<OurDayEntity>

    /** 卷三·日历投影（去 embedding·W-1）：单角色范围·dayKey 升序。 */
    @Query("SELECT uuid, characterUuid, dayKey, factsJson, messageCount, callSeconds, hasMeeting, hasRelation, hasLife, note, factLine, noteStatus, noteAttempts, noteEdited, hiddenFromMemory, deleted, generatedAt, createdAtMillis, updatedAtMillis FROM our_days WHERE characterUuid = :characterUuid AND dayKey BETWEEN :fromKey AND :toKey ORDER BY dayKey ASC")
    fun observeCalendarRange(characterUuid: String, fromKey: String, toKey: String): Flow<List<OurDayCalendarRow>>

    /** 卷三·日历投影：全部角色范围（「全部」模式）·dayKey, characterUuid 升序。 */
    @Query("SELECT uuid, characterUuid, dayKey, factsJson, messageCount, callSeconds, hasMeeting, hasRelation, hasLife, note, factLine, noteStatus, noteAttempts, noteEdited, hiddenFromMemory, deleted, generatedAt, createdAtMillis, updatedAtMillis FROM our_days WHERE dayKey BETWEEN :fromKey AND :toKey ORDER BY dayKey ASC, characterUuid ASC")
    fun observeCalendarRangeAll(fromKey: String, toKey: String): Flow<List<OurDayCalendarRow>>

    /** 卷三·日页单行投影（编辑后随 Room 失效刷新）。 */
    @Query("SELECT uuid, characterUuid, dayKey, factsJson, messageCount, callSeconds, hasMeeting, hasRelation, hasLife, note, factLine, noteStatus, noteAttempts, noteEdited, hiddenFromMemory, deleted, generatedAt, createdAtMillis, updatedAtMillis FROM our_days WHERE characterUuid = :characterUuid AND dayKey = :dayKey LIMIT 1")
    fun observeCalendarRow(characterUuid: String, dayKey: String): Flow<OurDayCalendarRow?>

    /** 卷三·相识日 = 该角色非墓碑最早一页（W-3）；无行 → null。 */
    @Query("SELECT MIN(dayKey) FROM our_days WHERE characterUuid = :characterUuid AND deleted = 0")
    fun observeFirstDayKey(characterUuid: String): Flow<String?>

    /** 卷三·初见日 = 该角色非墓碑最早的见面页（R1 🟡-1 之外：原从当前期行里取 ⇒ 每期首场见面都被误标「初见」）；无 → null。 */
    @Query("SELECT MIN(dayKey) FROM our_days WHERE characterUuid = :characterUuid AND hasMeeting = 1 AND deleted = 0")
    fun observeFirstMeetingDayKey(characterUuid: String): Flow<String?>

    /** 卷三·见面天数（资料卡「见面 N 次」·按天计）。 */
    @Query("SELECT COUNT(*) FROM our_days WHERE characterUuid = :characterUuid AND hasMeeting = 1 AND deleted = 0")
    fun observeMeetingDayCount(characterUuid: String): Flow<Int>

    /** 卷三·最近有互动的角色（入口条与日历默认预选单源·W-4）：最近一个有互动日的角色；无 → null。 */
    @Query("SELECT characterUuid FROM our_days WHERE deleted = 0 AND (messageCount > 0 OR callSeconds > 0 OR hasMeeting = 1 OR hasRelation = 1 OR hasLife = 1) ORDER BY dayKey DESC, updatedAtMillis DESC LIMIT 1")
    fun observeLatestActiveCharacterUuid(): Flow<String?>

    /** 删角色连坐清理（无 FK·手动清·总图纸 §3.9）。 */
    @Query("DELETE FROM our_days WHERE characterUuid = :characterUuid")
    suspend fun deleteByCharacter(characterUuid: String)
}
