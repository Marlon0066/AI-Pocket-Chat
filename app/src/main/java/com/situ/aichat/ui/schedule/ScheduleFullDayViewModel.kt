package com.situ.aichat.ui.schedule

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.repository.CharacterRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** 全天行程视图的一条事件（事件 + 其时态）。 */
data class ScheduleFullDayRow(val event: ScheduleEventEntity, val timeState: TimeState)

/** 日期导航边界（可达的最早/最晚一天，均为「当天 0 点」毫秒）。 */
data class ScheduleDateBounds(val earliest: Long, val latest: Long)

/**
 * 全天行程视图的某日 UI 状态（1:1 iOS `ScheduleFullDayView` 三态）：
 * - [Events]：该日有可见事件（过去日全部 / 今天已开始 / 未来日空）。
 * - [NotStarted]：有日程但无可见事件 → 「日程还未开始」。
 * - [NoSchedule]：该日无日程记录 → 「这一天没有日程记录」。
 * - [Loading]：首帧占位（本地查询，瞬时；避免空态闪烁）。
 */
sealed interface ScheduleDayUiState {
    data object Loading : ScheduleDayUiState
    data object NoSchedule : ScheduleDayUiState
    data object NotStarted : ScheduleDayUiState
    data class Events(val rows: List<ScheduleFullDayRow>) : ScheduleDayUiState
}

/**
 * 全天行程视图 ViewModel（P14.2b：日期导航 + 选择器 + userInteraction 跳会话）。数据按 [selectedDate]
 * 实时观察 Room（超越 iOS `.task` 快照，与资料页一致）。**只读不生成**——无日程的日子只显空态（1:1 iOS）。
 * 导航边界：earliest=角色创建日，latest=明天若已有日程否则今天（1:1 iOS）。
 */
@HiltViewModel
class ScheduleFullDayViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    characterRepo: CharacterRepository,
    private val scheduleDao: ScheduleDao,
    private val messageDao: MessageDao,
) : ViewModel() {

    private val characterUuid: String = savedStateHandle.get<String>(ARG_CHARACTER_UUID).orEmpty()
    private val zone: ZoneId = ZoneId.systemDefault()
    // 「今天/昨天/明天」基准（设备时区 0 点），VM 构造时定死——与 iOS `.task` 快照同语义（跨午夜停留不自动翻日，
    // 重进页面即刷新）。日级比较用设备时区，国行无夏令时。
    private val todayStart: Long = startOfDay(System.currentTimeMillis())
    private val yesterdayStart: Long = LocalDate.now(zone).minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    private val tomorrowStart: Long = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    private val _selectedDate = MutableStateFlow(initialSelected())

    /** 「我们的日子」卷三 W-10：可选 `date` 参（`yyyy-MM-dd`）⇒ 初值 = 该日零点；缺省 / 非法 ⇒ today（既有行为字节不变）。 */
    private fun initialSelected(): Long =
        savedStateHandle.get<String>(ARG_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: todayStart

    /** 当前查看的日期（当天 0 点毫秒，设备时区）。 */
    val selectedDate: StateFlow<Long> = _selectedDate.asStateFlow()

    private val characterFlow = characterRepo.observe(characterUuid)

    val characterName: StateFlow<String> =
        characterFlow.map { it?.name.orEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** 相对日标记（今天/昨天/明天/其余），驱动日期头文案。 */
    val relativeDay: StateFlow<ScheduleRelDay> =
        selectedDate.map { ScheduleNavLogic.relativeDayToken(it, todayStart, yesterdayStart, tomorrowStart) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleRelDay.TODAY)

    /**
     * 导航边界：earliest=角色创建日 0 点；latest=明天若已有日程否则今天（1:1 iOS）。「明天是否有日程」用
     * observe Flow（与当天数据一致 reactive）——若停留本页时明天日程被生成，右箭头即时放行（对齐 iOS live 计算）。
     */
    val bounds: StateFlow<ScheduleDateBounds> =
        combine(
            characterFlow,
            scheduleDao.observeScheduleFor(characterUuid, tomorrowStart),
        ) { ch, tomorrowSchedule ->
            val earliest = ch?.creationDate?.let { startOfDay(it) } ?: todayStart
            ScheduleDateBounds(
                earliest,
                ScheduleNavLogic.latestAvailableDay(todayStart, tomorrowStart, hasTomorrowSchedule = tomorrowSchedule != null),
            )
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleDateBounds(todayStart, todayStart))

    val canGoForward: StateFlow<Boolean> =
        combine(selectedDate, bounds) { sel, b -> ScheduleNavLogic.canNavigateForward(sel, b.latest) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val canGoBackward: StateFlow<Boolean> =
        combine(selectedDate, bounds) { sel, b -> ScheduleNavLogic.canNavigateBackward(sel, b.earliest) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private data class DayData(
        val schedule: CharacterDailyScheduleEntity?,
        val events: List<ScheduleEventEntity>,
        val dayStart: Long,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dayData: StateFlow<DayData?> =
        _selectedDate.flatMapLatest { day ->
            scheduleDao.observeScheduleFor(characterUuid, day)
                .flatMapLatest { sched ->
                    if (sched == null) flowOf(DayData(null, emptyList(), day))
                    else scheduleDao.observeEventsForSchedule(sched.uuid).map { DayData(sched, it, day) }
                }
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val dayState: StateFlow<ScheduleDayUiState> =
        dayData.map { dd -> if (dd == null) ScheduleDayUiState.Loading else deriveDay(dd) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleDayUiState.Loading)

    /** 当前日期的日程实体（供天气行；当前天气列恒 null[P11] → 天气行不显）。 */
    val daySchedule: StateFlow<CharacterDailyScheduleEntity?> =
        dayData.map { it?.schedule }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _openConversation = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** userInteraction 事件点击后，反查到的会话 UUID 一次性事件；Screen 收集后导航到会话。 */
    val openConversation: SharedFlow<String> = _openConversation.asSharedFlow()

    /** 上一日/下一日切换（箭头 + 左右滑共用），越界静默忽略（1:1 iOS shiftDay + canNavigateTo 守卫）。 */
    fun shiftDay(offset: Int) {
        val target = Instant.ofEpochMilli(_selectedDate.value).atZone(zone).toLocalDate()
            .plusDays(offset.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        setDate(target)
    }

    /** 跳到指定日期（日期选择器），归一化到当天 0 点并按边界守卫。 */
    fun setDate(dayStartMillis: Long) {
        val day = startOfDay(dayStartMillis)
        val b = bounds.value
        if (ScheduleNavLogic.canNavigateTo(day, b.earliest, b.latest)) {
            _selectedDate.value = day
        }
    }

    /** userInteraction 事件点击 → 反查其会话 → 发一次性导航事件（1:1 iOS navigateToConversation）。 */
    fun onUserInteractionClick(messageUuid: String) {
        if (messageUuid.isEmpty()) return
        viewModelScope.launch {
            messageDao.conversationUuidForMessage(messageUuid)?.let { _openConversation.emit(it) }
        }
    }

    private fun deriveDay(data: DayData): ScheduleDayUiState {
        if (data.schedule == null) return ScheduleDayUiState.NoSchedule
        val now = System.currentTimeMillis()
        val visible = ScheduleTimelineLogic.visibleEvents(data.events, data.dayStart, todayStart, now)
        if (visible.isEmpty()) return ScheduleDayUiState.NotStarted
        val rows = visible.map {
            ScheduleFullDayRow(it, ScheduleTimelineLogic.fullDayTimeState(it, data.dayStart, todayStart, now))
        }
        return ScheduleDayUiState.Events(rows)
    }

    private fun startOfDay(millis: Long): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    companion object {
        const val ARG_CHARACTER_UUID = "characterUuid"
        const val ARG_DATE = "date"
    }
}
