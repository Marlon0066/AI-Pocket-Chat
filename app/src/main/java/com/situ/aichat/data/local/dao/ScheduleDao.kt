package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import kotlinx.coroutines.flow.Flow

/** 日程读写（P5.1）。日程按「角色 + 当天 0 点毫秒」唯一定位；事件按 sortOrder→startTime 排序。 */
@Dao
interface ScheduleDao {

    @Query("SELECT * FROM character_daily_schedules WHERE characterUuid = :characterUuid AND date = :date LIMIT 1")
    suspend fun scheduleFor(characterUuid: String, date: Long): CharacterDailyScheduleEntity?

    /**
     * 该角色最新一条日程的「当天 0 点」毫秒（无日程返回 null）。供 14.7a 历史缺失日补算锚定
     * （1:1 iOS `BackgroundTaskRunner.latestScheduleDate`：取 MAX(date)，不按 generatedAt 过滤——
     * 空壳行也算锚点，补算范围 = 锚点次日…昨天，落到范围内的空壳/缺日由 generateSchedule 幂等填上）。
     */
    @Query("SELECT MAX(date) FROM character_daily_schedules WHERE characterUuid = :characterUuid")
    suspend fun latestScheduleDate(characterUuid: String): Long?

    /**
     * 观察「角色 + 某日 0 点」的日程（P14.2 资料页日程卡实时刷新）。生成完成 / 重试成功后 Flow 自动推数据。
     * 事件另经 [observeEventsForSchedule] 观察。
     */
    @Query("SELECT * FROM character_daily_schedules WHERE characterUuid = :characterUuid AND date = :date LIMIT 1")
    fun observeScheduleFor(characterUuid: String, date: Long): Flow<CharacterDailyScheduleEntity?>

    /**
     * 观察某日程的事件（P14.2）。**不在 SQL 排序**——UI 层用 `ScheduleTimelineLogic.sortedEvents`
     * 按 startTime 主键重排（与入库的 sortOrder 序不同，见该纯函数注释）。
     */
    @Query("SELECT * FROM schedule_events WHERE scheduleUuid = :scheduleUuid")
    fun observeEventsForSchedule(scheduleUuid: String): Flow<List<ScheduleEventEntity>>

    @Query(
        "SELECT * FROM schedule_events WHERE scheduleUuid = :scheduleUuid " +
            "ORDER BY sortOrder ASC, startTime ASC",
    )
    suspend fun eventsForSchedule(scheduleUuid: String): List<ScheduleEventEntity>

    /**
     * 该角色近一周（schedule.date >= [sinceMillis]）所有日程的事件，供成长分析的「最近一周日常活动模式」
     * 补充材料（1:1 iOS GrowthAnalysisService.buildScheduleAnalysis 的 schedules.flatMap { events }）。
     *
     * **消费者二**（时间感知三期）：最近几天日程注入——两个发起点（`AssistantTurnEngine` / `RecoveryReplyGenerator`）
     * 传「今天往前 3 天 0 点」取事件，交 `buildRecentDaysSection` 渲染【你最近几天的日子】。
     * 本查询**只 SELECT e.\***（不返回 s.date），按日期分组一律用事件自带的绝对 `startTime`。
     */
    @Query(
        "SELECT e.* FROM schedule_events e " +
            "INNER JOIN character_daily_schedules s ON e.scheduleUuid = s.uuid " +
            "WHERE s.characterUuid = :characterUuid AND s.date >= :sinceMillis",
    )
    suspend fun eventsForCharacterSince(characterUuid: String, sinceMillis: Long): List<ScheduleEventEntity>

    /** 该角色所有日程（按日期升序），供备份导出（13.6）。事件另经 [eventsForSchedule] 取。 */
    @Query("SELECT * FROM character_daily_schedules WHERE characterUuid = :characterUuid ORDER BY date ASC")
    suspend fun schedulesForCharacter(characterUuid: String): List<CharacterDailyScheduleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: CharacterDailyScheduleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<ScheduleEventEntity>)

    /** 删某日程下的全部事件（重新生成空壳时先清旧事件）。 */
    @Query("DELETE FROM schedule_events WHERE scheduleUuid = :scheduleUuid")
    suspend fun deleteEventsForSchedule(scheduleUuid: String)

    /** 原子写入：先建/覆盖日程，再灌入其事件。 */
    @Transaction
    suspend fun insertScheduleWithEvents(
        schedule: CharacterDailyScheduleEntity,
        events: List<ScheduleEventEntity>,
    ) {
        insertSchedule(schedule)
        insertEvents(events)
    }
}
