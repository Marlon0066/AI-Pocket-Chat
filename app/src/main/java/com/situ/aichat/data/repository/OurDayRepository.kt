package com.situ.aichat.data.repository

import com.situ.aichat.data.local.dao.OurDayDao
import com.situ.aichat.data.local.entity.OurDayCalendarRow
import com.situ.aichat.data.local.entity.OurDayEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「我们的日子」仓库（总图纸 §3.6 · 卷一建）：[OurDayDao] 薄包装——只读查询 + 备份 / 级联删 + 新行 upsert。
 * **写手记 / 事实 / 标记的口只在 [com.situ.aichat.ourdays.OurDayCoordinator]**（卷三经它），本仓库不暴露列级 UPDATE。
 * 卷二：注入候选 [injectableForCharacter]（调用方预取 → `PromptBuilder` 纯筛选渲染）；向量三件（回填 / 清空 / 检索）经
 * [com.situ.aichat.prompt.memory.OurDayVectorService] 直连 DAO，不经本仓库。
 * 卷三：日历读投影 [OurDayCalendarRow]（§3.1）。
 */
@Singleton
class OurDayRepository @Inject constructor(
    private val dao: OurDayDao,
) {
    suspend fun get(characterUuid: String, dayKey: String): OurDayEntity? = dao.byDay(characterUuid, dayKey)

    suspend fun daysInRange(characterUuid: String, fromKey: String, toKey: String): List<OurDayEntity> =
        dao.daysInRange(characterUuid, fromKey, toKey)

    fun observeDaysInRange(characterUuid: String, fromKey: String, toKey: String): Flow<List<OurDayEntity>> =
        dao.observeDaysInRange(characterUuid, fromKey, toKey)

    fun observeAllInRange(fromKey: String, toKey: String): Flow<List<OurDayEntity>> =
        dao.observeAllInRange(fromKey, toKey)

    suspend fun countForCharacter(characterUuid: String): Int = dao.countForCharacter(characterUuid)

    /** 卷二·注入候选行（`deleted = 0 ∧ hiddenFromMemory = 0 ∧ factLine != ''`·dayKey 升序）。 */
    suspend fun injectableForCharacter(characterUuid: String): List<OurDayEntity> = dao.injectableForCharacter(characterUuid)

    /** 卷三·§3.1：单角色范围投影（日历 / 周 / 年·去 embedding）。 */
    fun observeCalendarRange(characterUuid: String, fromKey: String, toKey: String): Flow<List<OurDayCalendarRow>> =
        dao.observeCalendarRange(characterUuid, fromKey, toKey)

    /** 卷三·§3.1：全部角色范围投影（「全部」模式）。 */
    fun observeCalendarRangeAll(fromKey: String, toKey: String): Flow<List<OurDayCalendarRow>> =
        dao.observeCalendarRangeAll(fromKey, toKey)

    /** 卷三·§3.1：日页单行投影。 */
    fun observeCalendarRow(characterUuid: String, dayKey: String): Flow<OurDayCalendarRow?> =
        dao.observeCalendarRow(characterUuid, dayKey)

    /** 卷三·§3.1：相识日（非墓碑最早页·无行 null）。 */
    fun observeFirstDayKey(characterUuid: String): Flow<String?> = dao.observeFirstDayKey(characterUuid)

    /** 卷三·§3.1：初见日（非墓碑最早见面页·无 null·R1 🟡-2）。 */
    fun observeFirstMeetingDayKey(characterUuid: String): Flow<String?> = dao.observeFirstMeetingDayKey(characterUuid)

    /** 卷三·§3.1：见面天数。 */
    fun observeMeetingDayCount(characterUuid: String): Flow<Int> = dao.observeMeetingDayCount(characterUuid)

    /** 卷三·§3.1：最近有互动的角色（入口条 / 日历默认预选单源）。 */
    fun observeLatestActiveCharacterUuid(): Flow<String?> = dao.observeLatestActiveCharacterUuid()

    /** 全表（备份导出用）。 */
    suspend fun getAll(): List<OurDayEntity> = dao.getAll()

    /** 角色级联删（无 FK·手动清·总图纸 §3.9）。 */
    suspend fun deleteByCharacter(characterUuid: String) = dao.deleteByCharacter(characterUuid)

    /** 新行创建 / 备份恢复（总图纸 §9.4：不用于覆盖既有行）。 */
    suspend fun upsert(row: OurDayEntity) = dao.upsert(row)
}
