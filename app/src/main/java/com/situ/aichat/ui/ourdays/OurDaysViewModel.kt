package com.situ.aichat.ui.ourdays

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.local.entity.OurDayCalendarRow
import com.situ.aichat.data.local.entity.OurDayNoteStatus
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.DiaryRepository
import com.situ.aichat.data.repository.OurDayRepository
import com.situ.aichat.ourdays.OurDayApiMissingFlag
import com.situ.aichat.ourdays.OurDayCoordinator
import com.situ.aichat.ourdays.OurDayCoordinator.BackfillProgress
import com.situ.aichat.ourdays.OurDayKey
import com.situ.aichat.work.BackgroundScheduler
import com.situ.aichat.work.OurDayCatchUpWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.MonthDay
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject

/**
 * 日历页 VM（卷三图纸 §3.2 锁定）。选中 / 视图 / 锚定日全在 [SavedStateHandle]（进程死亡恢复·§3.8）；`today` 构造时钉死（W-8）；
 * 数据只读投影 [OurDayCalendarRow]（W-1）；一切写只经 [OurDayCoordinator]（「补写这个月」= 逐日 `regenerate`·W-6）。
 * 状态组装在 `Dispatchers.Default`（ICU 农历 / 节日查表 + facts 解码·W-9）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OurDaysViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val ourDayRepository: OurDayRepository,
    characterRepository: CharacterRepository,
    userProfileDao: UserProfileDao,
    private val diaryRepository: DiaryRepository,
    private val coordinator: OurDayCoordinator,
    backgroundScheduler: BackgroundScheduler,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    val today: LocalDate = LocalDate.now(zone)
    private val weekFields: WeekFields = WeekFields.of(Locale.getDefault())
    private val locale: Locale = Locale.getDefault()
    private val decorStrings = OurDaysStrings.decor(context)

    init {
        if (savedStateHandle.get<String>(KEY_SELECTION) == null) {
            savedStateHandle[KEY_SELECTION] = savedStateHandle.get<String>(ARG_CHARACTER).orEmpty()
        }
        if (savedStateHandle.get<String>(KEY_ANCHOR) == null) {
            savedStateHandle[KEY_ANCHOR] = savedStateHandle.get<String>(ARG_DATE)?.takeIf { OurDayKey.parse(it) != null } ?: OurDayKey.keyOf(today)
        }
        if (savedStateHandle.get<String>(KEY_VIEW) == null) savedStateHandle[KEY_VIEW] = OurDaysViewMode.MONTH.name
        // 总图纸 §3.8「日历页进入：ensure」（KEEP 防与冷启 / 回前台重入）。
        backgroundScheduler.scheduleOneShot(
            uniqueName = OurDayCatchUpWorker.UNIQUE_ENSURE,
            workerClass = OurDayCatchUpWorker::class.java,
            requireNetwork = true,
            existingPolicy = ExistingWorkPolicy.KEEP,
        )
    }

    /** 角色升序（= 识别色序·W-17）；null = 首帧未到（uiState 保持 Loading·防零角色空态闪现）。 */
    private val charactersOrNull: StateFlow<List<CharacterEntity>?> =
        characterRepository.observeAll().map { list -> list.sortedBy { it.creationDate } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val characters: StateFlow<List<CharacterEntity>> =
        charactersOrNull.map { it.orEmpty() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 选中态解析（§3.2）：`""` ⇒ 最近活跃 ?: 最新创建；ALL（角色 ≥2）⇒ All；uuid 不在表 ⇒ 退回解析；零角色 ⇒ None。解析结果写回 KEY（一次）。 */
    val selection: StateFlow<OurDaysSelection> = combine(
        savedStateHandle.getStateFlow(KEY_SELECTION, ""),
        charactersOrNull,
        ourDayRepository.observeLatestActiveCharacterUuid(),
    ) { key, chars, latest -> if (chars == null) null else resolveSelection(key, chars, latest) }
        .map { it ?: OurDaysSelection.None }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OurDaysSelection.None)

    init {
        viewModelScope.launch {
            selection.collect { sel ->
                if (sel is OurDaysSelection.Character && savedStateHandle.get<String>(KEY_SELECTION) != sel.uuid) {
                    savedStateHandle[KEY_SELECTION] = sel.uuid
                }
            }
        }
    }

    val viewMode: StateFlow<OurDaysViewMode> = savedStateHandle.getStateFlow(KEY_VIEW, OurDaysViewMode.MONTH.name)
        .map { parseViewMode(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, parseViewMode(savedStateHandle.get<String>(KEY_VIEW)))

    val anchor: StateFlow<LocalDate> = savedStateHandle.getStateFlow(KEY_ANCHOR, OurDayKey.keyOf(today))
        .map { OurDayKey.parse(it) ?: today }
        .stateIn(viewModelScope, SharingStarted.Eagerly, savedStateHandle.get<String>(KEY_ANCHOR)?.let(OurDayKey::parse) ?: today)

    val period: StateFlow<ClosedRange<LocalDate>> = combine(viewMode, anchor) { m, a -> OurDaysCalendarLogic.period(m, a, weekFields) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, OurDaysCalendarLogic.period(viewMode.value, anchor.value, weekFields))

    private val rows: Flow<List<OurDayCalendarRow>> = combine(selection, period) { s, p -> s to p }.flatMapLatest { (sel, p) ->
        when (sel) {
            is OurDaysSelection.Character -> ourDayRepository.observeCalendarRange(sel.uuid, OurDayKey.keyOf(p.start), OurDayKey.keyOf(p.endInclusive))
            OurDaysSelection.All -> ourDayRepository.observeCalendarRangeAll(OurDayKey.keyOf(p.start), OurDayKey.keyOf(p.endInclusive))
            OurDaysSelection.None -> flowOf(emptyList())
        }
    }

    private val firstDayKey: Flow<String?> = selection.flatMapLatest { sel ->
        if (sel is OurDaysSelection.Character) ourDayRepository.observeFirstDayKey(sel.uuid) else flowOf(null)
    }

    /** 初见日（R1 🟡-2）：全史 MIN，**不从当前期的行里推**——否则每翻到一个含见面的月份，该月首场见面都会被标成「初见」。 */
    private val firstMeetingDayKey: Flow<String?> = selection.flatMapLatest { sel ->
        if (sel is OurDaysSelection.Character) ourDayRepository.observeFirstMeetingDayKey(sel.uuid) else flowOf(null)
    }

    /** 「你的日记」（W-18）：全部模式按锚定日一次性重取（非 Flow）。 */
    private val userDiary: Flow<DiaryEntryEntity?> = combine(selection, anchor) { s, a -> s to a }.flatMapLatest { (sel, day) ->
        if (sel is OurDaysSelection.All) {
            flow {
                val bounds = OurDayKey.dayBounds(OurDayKey.keyOf(day), zone)
                emit(OurDayCardLogic.pickUserDiary(diaryRepository.entriesInRange(bounds.first, bounds.last + 1)))
            }
        } else {
            flowOf(null)
        }
    }

    private data class Inputs(val selection: OurDaysSelection, val characters: List<CharacterEntity>?, val viewMode: OurDaysViewMode, val anchor: LocalDate)
    private data class Data(
        val rows: List<OurDayCalendarRow>,
        val firstDayKey: String?,
        val firstMeetingDayKey: String?,
        val profile: UserProfileEntity?,
        val userDiary: DiaryEntryEntity?,
    )

    val uiState: StateFlow<OurDaysUiState> = combine(
        combine(selection, charactersOrNull, viewMode, anchor, ::Inputs),
        combine(rows, firstDayKey, firstMeetingDayKey, userProfileDao.observe(), userDiary, ::Data),
    ) { inputs, data -> buildState(inputs, data) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OurDaysUiState.Loading)

    val backfill: StateFlow<Map<String, BackfillProgress>> = coordinator.backfillProgress

    private val _monthBackfill = MutableStateFlow<Pair<Int, Int>?>(null)

    /** 「补写这个月」进度（done / total·完成置 null）。 */
    val monthBackfill: StateFlow<Pair<Int, Int>?> = _monthBackfill.asStateFlow()

    private val _apiMissing = MutableStateFlow(OurDayApiMissingFlag.get(context))
    val apiMissing: StateFlow<Boolean> = _apiMissing.asStateFlow()

    fun refreshApiMissing() {
        _apiMissing.value = OurDayApiMissingFlag.get(context)
    }

    // ── 动作 ──

    fun select(selection: OurDaysSelection) {
        savedStateHandle[KEY_SELECTION] = when (selection) {
            OurDaysSelection.All -> OurDaysRoutes.ALL
            is OurDaysSelection.Character -> selection.uuid
            OurDaysSelection.None -> ""
        }
    }

    /** 切视图保持锚定日（D-3 联动）。 */
    fun setViewMode(mode: OurDaysViewMode) {
        savedStateHandle[KEY_VIEW] = mode.name
    }

    fun setAnchor(date: LocalDate) {
        savedStateHandle[KEY_ANCHOR] = OurDayKey.keyOf(date)
    }

    fun shiftPeriod(delta: Int) = setAnchor(OurDaysCalendarLogic.shift(viewMode.value, anchor.value, delta))

    fun goToday() = setAnchor(today)

    /** 年 → 月：含今天 ⇒ today，否则该月 1 日。 */
    fun openMonth(yearMonth: YearMonth) {
        setAnchor(if (yearMonth == YearMonth.from(today)) today else yearMonth.atDay(1))
        setViewMode(OurDaysViewMode.MONTH)
    }

    /** W-6：月内既有行 `noteStatus != ok ∧ !deleted ∧ !noteEdited ∧ dayKey < today` 逐条 `regenerate`（顺序·失败继续）；进行中忽略再点。 */
    fun backfillMonth() {
        val sel = selection.value as? OurDaysSelection.Character ?: return
        if (_monthBackfill.value != null) return
        val ym = YearMonth.from(anchor.value)
        val todayKey = OurDayKey.keyOf(today)
        viewModelScope.launch {
            val targets = ourDayRepository.observeCalendarRange(sel.uuid, OurDayKey.keyOf(ym.atDay(1)), OurDayKey.keyOf(ym.atEndOfMonth())).first()
                .filter { it.noteStatus != OurDayNoteStatus.OK && !it.deleted && !it.noteEdited && it.dayKey < todayKey }
                .map { it.dayKey }
            _monthBackfill.value = 0 to targets.size
            try {
                targets.forEachIndexed { index, key ->
                    try {
                        coordinator.regenerate(sel.uuid, key)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // 失败继续下一天（W-6）
                    }
                    _monthBackfill.value = (index + 1) to targets.size
                }
            } finally {
                _monthBackfill.value = null
            }
        }
    }

    // ── 组装 ──

    private fun buildState(inputs: Inputs, data: Data): OurDaysUiState {
        val chars = inputs.characters ?: return OurDaysUiState.Loading
        val sel = inputs.selection
        val period = OurDaysCalendarLogic.period(inputs.viewMode, inputs.anchor, weekFields)
        if (sel is OurDaysSelection.None) {
            // None 只在零角色时成立；角色已到而选中尚未解析（两条 combine 先后到达）⇒ 仍 Loading，防无角色空态闪现。
            if (chars.isNotEmpty()) return OurDaysUiState.Loading
            return OurDaysUiState.Content(sel, null, inputs.viewMode, inputs.anchor, period, null, null, null, hasAnyRow = false)
        }
        val allMode = sel is OurDaysSelection.All
        val character = (sel as? OurDaysSelection.Character)?.let { s -> chars.firstOrNull { it.uuid == s.uuid } }
        val uuids = chars.map { it.uuid }
        val firstDay = if (allMode) null else data.firstDayKey?.let(OurDayKey::parse)
        val firstMeetingDay = if (allMode) null else data.firstMeetingDayKey?.let(OurDayKey::parse)
        val decor = OurDaysDecor.factory(
            zone = zone,
            characterName = character?.name,
            characterBirthday = if (allMode) null else character?.birthday?.let(::monthDayOf),
            userBirthday = if (allMode) null else data.profile?.birthday?.let(::monthDayOf),
            firstDay = firstDay,
            firstMeetingDay = firstMeetingDay,
            strings = decorStrings,
        )
        val card: (LocalDate, List<OurDayCalendarRow>) -> DayCardModel = { date, dayRows ->
            if (allMode) {
                OurDayCardLogic.allCard(date, today, dayRows, chars, decor(date), if (date == inputs.anchor) data.userDiary else null)
            } else {
                OurDayCardLogic.card(date, today, dayRows.firstOrNull(), decor(date))
            }
        }
        val month = if (inputs.viewMode == OurDaysViewMode.MONTH) {
            OurDaysCalendarLogic.buildMonth(inputs.anchor, data.rows, today, weekFields, locale, allMode, uuids, decor, inputs.anchor, card)
        } else {
            null
        }
        val week = if (inputs.viewMode == OurDaysViewMode.WEEK) {
            OurDaysCalendarLogic.buildWeek(inputs.anchor, data.rows, today, weekFields, allMode, uuids, decor, card)
        } else {
            null
        }
        val year = if (inputs.viewMode == OurDaysViewMode.YEAR) {
            OurDaysCalendarLogic.buildYear(inputs.anchor, data.rows, today, weekFields, firstDay, chars.size)
        } else {
            null
        }
        return OurDaysUiState.Content(
            selection = sel, characterName = character?.name, viewMode = inputs.viewMode, anchor = inputs.anchor, period = period,
            month = month, week = week, year = year, hasAnyRow = allMode || data.firstDayKey != null || data.rows.isNotEmpty(),
        )
    }

    private fun monthDayOf(millis: Long): MonthDay = MonthDay.from(Instant.ofEpochMilli(millis).atZone(zone).toLocalDate())

    companion object {
        const val ARG_CHARACTER = "character"
        const val ARG_DATE = "date"
        private const val KEY_SELECTION = "ourdays_selection"
        private const val KEY_VIEW = "ourdays_view"
        private const val KEY_ANCHOR = "ourdays_anchor"

        private fun parseViewMode(raw: String?): OurDaysViewMode =
            OurDaysViewMode.entries.firstOrNull { it.name == raw } ?: OurDaysViewMode.MONTH

        internal fun resolveSelection(key: String, chars: List<CharacterEntity>, latestActive: String?): OurDaysSelection {
            if (chars.isEmpty()) return OurDaysSelection.None
            if (key == OurDaysRoutes.ALL && chars.size >= 2) return OurDaysSelection.All
            if (key.isNotEmpty() && key != OurDaysRoutes.ALL && chars.any { it.uuid == key }) return OurDaysSelection.Character(key)
            val fallback = latestActive?.takeIf { l -> chars.any { it.uuid == l } } ?: chars.maxBy { it.creationDate }.uuid
            return OurDaysSelection.Character(fallback)
        }

        /** 回填横幅取值（§4.8）：单角色显该角色条目；全部显各条目 done / total 之和；空 ⇒ null。 */
        internal fun bannerProgress(map: Map<String, BackfillProgress>, selection: OurDaysSelection): BackfillProgress? = when (selection) {
            is OurDaysSelection.Character -> map[selection.uuid]
            OurDaysSelection.All -> map.values.takeIf { it.isNotEmpty() }?.let { v -> BackfillProgress(v.sumOf { it.done }, v.sumOf { it.total }) }
            OurDaysSelection.None -> null
        }
    }
}
