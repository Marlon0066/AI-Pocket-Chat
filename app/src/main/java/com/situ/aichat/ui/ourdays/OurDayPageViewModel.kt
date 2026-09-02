package com.situ.aichat.ui.ourdays

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.local.entity.OurDayCalendarRow
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.DiaryRepository
import com.situ.aichat.data.repository.OurDayRepository
import com.situ.aichat.ourdays.OurDayCoordinator
import com.situ.aichat.ourdays.OurDayFactsJson
import com.situ.aichat.ourdays.OurDayKey
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId
import javax.inject.Inject

/** 日页 UI 状态（卷三图纸 §3.2）：[loaded] = 首帧已到；全部模式 [card] 带分段；单角色 [facts] 事实层 + [footer]。 */
data class OurDayPageUiState(
    val loaded: Boolean = false,
    val isAll: Boolean = false,
    val date: LocalDate,
    val isToday: Boolean = false,
    val isFuture: Boolean = false,
    val characterUuid: String? = null,
    val characterName: String? = null,
    val avatarPath: String? = null,
    val decor: DayDecor? = null,
    val nthDay: Int? = null,
    val row: OurDayCalendarRow? = null,
    val card: DayCardModel? = null,
    val facts: List<FactItem> = emptyList(),
    val footer: FooterKind = FooterKind.NONE,
    val busy: Boolean = false,
)

/**
 * 一天的页 VM（卷三图纸 §3.2 锁定）：单角色 ∧ 过去日进入时 `refreshFacts` 一次（§9.4）；行走单行投影 Flow（编辑后随 Room 刷新）；
 * sheet 开合与草稿全在 [SavedStateHandle]（E23）；写动作串行·`busy` 守卫同页只跑一个 `regenerate`（E21）；一切写只经 [OurDayCoordinator]。
 */
@HiltViewModel
class OurDayPageViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    ourDayRepository: OurDayRepository,
    characterRepository: CharacterRepository,
    userProfileDao: UserProfileDao,
    private val diaryRepository: DiaryRepository,
    private val coordinator: OurDayCoordinator,
    @ApplicationContext context: Context,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    val characterUuid: String = savedStateHandle.get<String>(ARG_CHARACTER_UUID).orEmpty()
    val dayKey: String = savedStateHandle.get<String>(ARG_DAY_KEY).orEmpty()
    val isAll: Boolean get() = characterUuid == OurDaysRoutes.ALL
    val today: LocalDate = LocalDate.now(zone)
    val date: LocalDate = OurDayKey.parse(dayKey) ?: today
    private val decorStrings = OurDaysStrings.decor(context)
    private val cardStrings = OurDaysStrings.card(context, zone)

    init {
        if (!isAll && date.isBefore(today)) {
            viewModelScope.launch {
                try {
                    coordinator.refreshFacts(characterUuid, dayKey)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 事实重算失败只影响本次刷新（E14 兜底：投影行照显）
                }
            }
        }
    }

    private val rows: Flow<List<OurDayCalendarRow>> =
        if (isAll) ourDayRepository.observeCalendarRangeAll(dayKey, dayKey) else ourDayRepository.observeCalendarRow(characterUuid, dayKey).map { listOfNotNull(it) }

    /** 单角色当前行（动作读取·Eagerly：sheet 打开期间恒有值）。 */
    private val row: StateFlow<OurDayCalendarRow?> =
        rows.map { it.firstOrNull() }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val characters: Flow<List<CharacterEntity>> = characterRepository.observeAll().map { list -> list.sortedBy { it.creationDate } }
    /** 相识日 + 初见日（R1 🟡-2：初见走全史 MIN·日页此前恒传 null ⇒ 从不显「初见」）。 */
    private val firstDays: Flow<Pair<String?, String?>> = if (isAll) {
        flowOf(null to null)
    } else {
        combine(ourDayRepository.observeFirstDayKey(characterUuid), ourDayRepository.observeFirstMeetingDayKey(characterUuid)) { a, b -> a to b }
    }

    /** 「你的日记」（W-18·全部模式一次性取）。 */
    private val userDiary: Flow<DiaryEntryEntity?> = if (isAll) {
        flow {
            val bounds = OurDayKey.dayBounds(OurDayKey.keyOf(date), zone)
            emit(OurDayCardLogic.pickUserDiary(diaryRepository.entriesInRange(bounds.first, bounds.last + 1)))
        }
    } else {
        flowOf(null)
    }

    private val _busy = MutableStateFlow(false)
    private val _toast = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 一次性事件：重写失败 toast（string res id·照 `MemoryEditViewModel` 先例）。 */
    val toast: SharedFlow<Int> = _toast.asSharedFlow()

    /** 一次性事件：保存成功（UI 触觉 success）。 */
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()

    val sheetOpen: StateFlow<Boolean> = savedStateHandle.getStateFlow(KEY_SHEET, false)
    val draft: StateFlow<String> = savedStateHandle.getStateFlow(KEY_DRAFT, "")
    val draftHidden: StateFlow<Boolean> = savedStateHandle.getStateFlow(KEY_DRAFT_HIDDEN, false)

    private data class Data(
        val rows: List<OurDayCalendarRow>,
        val characters: List<CharacterEntity>,
        val firstDays: Pair<String?, String?>,
        val userDiary: DiaryEntryEntity?,
        val profile: UserProfileEntity?,
    )

    val uiState: StateFlow<OurDayPageUiState> = combine(
        combine(rows, characters, firstDays, userDiary, userProfileDao.observe(), ::Data),
        _busy,
    ) { data, busy -> build(data, busy) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OurDayPageUiState(date = date))

    // ── sheet ──

    /** 动作读行：Eagerly 行流 ?: 已组装状态里的行（两路各自订阅 Room·到达先后不定）。 */
    private fun currentRow(): OurDayCalendarRow? = row.value ?: uiState.value.row

    fun openSheet() {
        val r = currentRow() ?: return
        savedStateHandle[KEY_DRAFT] = r.note
        savedStateHandle[KEY_DRAFT_HIDDEN] = r.hiddenFromMemory
        savedStateHandle[KEY_SHEET] = true
    }

    fun updateDraft(text: String) {
        savedStateHandle[KEY_DRAFT] = text
    }

    fun updateDraftHidden(hidden: Boolean) {
        savedStateHandle[KEY_DRAFT_HIDDEN] = hidden
    }

    fun closeSheet() {
        savedStateHandle[KEY_SHEET] = false
    }

    /** 每次点用点读（PITFALLS §1h：判据写成函数，不交给捕获）。 */
    fun isDirty(): Boolean {
        val r = currentRow() ?: return false
        return draft.value != r.note || draftHidden.value != r.hiddenFromMemory
    }

    // ── 动作（全部经协调器·串行）──

    /** W-7：note 变 ⇒ `saveUserNote`（事实行原值不动）；开关变 ⇒ `setHidden`；都没变 ⇒ 直接关；note 空白 ⇒ 不保存。 */
    fun save() {
        if (isAll) return
        val r = currentRow() ?: return
        val note = draft.value
        val hidden = draftHidden.value
        if (note.isBlank()) return
        viewModelScope.launch {
            if (note != r.note) coordinator.saveUserNote(characterUuid, dayKey, note, r.factLine)
            if (hidden != r.hiddenFromMemory) coordinator.setHidden(characterUuid, dayKey, hidden)
            closeSheet()
            _saved.tryEmit(Unit)
        }
    }

    /** 重写 / 再试一次：`busy` 守卫（E21）；失败 toast（E15）。 */
    fun rewrite() {
        if (isAll || _busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                if (!coordinator.regenerate(characterUuid, dayKey)) _toast.tryEmit(R.string.our_days_toast_rewrite_failed)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _toast.tryEmit(R.string.our_days_toast_rewrite_failed)
            } finally {
                _busy.value = false
            }
        }
    }

    fun retry() = rewrite()

    /** 删除（W-12 二次确认后）：墓碑 + 关 sheet。 */
    fun delete() {
        if (isAll) return
        viewModelScope.launch {
            coordinator.markDeleted(characterUuid, dayKey)
            closeSheet()
        }
    }

    // ── 组装 ──

    private fun build(data: Data, busy: Boolean): OurDayPageUiState {
        val isToday = date == today
        val isFuture = date.isAfter(today)
        if (isAll) {
            val decor = OurDaysDecor.factory(zone, null, null, null, null, null, decorStrings)(date)
            val card = OurDayCardLogic.allCard(date, today, data.rows, data.characters, decor, data.userDiary)
            return OurDayPageUiState(loaded = true, isAll = true, date = date, isToday = isToday, isFuture = isFuture, decor = decor, card = card, busy = busy)
        }
        val character = data.characters.firstOrNull { it.uuid == characterUuid }
        val r = data.rows.firstOrNull()
        val firstDay = data.firstDays.first?.let(OurDayKey::parse)
        val decor = OurDaysDecor.factory(
            zone = zone,
            characterName = character?.name,
            characterBirthday = character?.birthday?.let(::monthDayOf),
            userBirthday = data.profile?.birthday?.let(::monthDayOf),
            firstDay = firstDay,
            firstMeetingDay = data.firstDays.second?.let(OurDayKey::parse),
            strings = decorStrings,
        )(date)
        val facts = r?.let { OurDayFactsJson.decodeOrNull(it.factsJson) }
            ?.let { OurDayFactItems.build(it, character?.name.orEmpty(), dayKey, cardStrings) }
            .orEmpty()
        return OurDayPageUiState(
            loaded = true, isAll = false, date = date, isToday = isToday, isFuture = isFuture,
            characterUuid = characterUuid, characterName = character?.name, avatarPath = character?.avatarPath,
            decor = decor, nthDay = OurDaysCalendarLogic.daysTogether(firstDay, date), row = r,
            card = OurDayCardLogic.card(date, today, r, decor), facts = facts,
            footer = OurDayCardLogic.footer(r, isToday, isFuture), busy = busy,
        )
    }

    private fun monthDayOf(millis: Long): MonthDay = MonthDay.from(Instant.ofEpochMilli(millis).atZone(zone).toLocalDate())

    companion object {
        const val ARG_CHARACTER_UUID = "characterUuid"
        const val ARG_DAY_KEY = "dayKey"
        private const val KEY_SHEET = "ourdays_sheet_open"
        private const val KEY_DRAFT = "ourdays_note_draft"
        private const val KEY_DRAFT_HIDDEN = "ourdays_hidden_draft"
    }
}
