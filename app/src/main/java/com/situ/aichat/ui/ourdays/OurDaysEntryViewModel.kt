package com.situ.aichat.ui.ourdays

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.repository.OurDayRepository
import com.situ.aichat.ourdays.OurDayKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

/** 资料页轻卡状态（§3.2）：三数字 + 14 格（today−13..today）+ [hasAny]（一页都没有 ⇒ 副标改空态句·卡仍渲染·D-2）。 */
data class OurDaysEntryState(
    val loaded: Boolean = false,
    val daysTogether: Int = 0,
    val chatDaysThisMonth: Int = 0,
    val meetingDays: Int = 0,
    val bar: List<CellModel> = emptyList(),
    val rangeStart: LocalDate,
    val hasAny: Boolean = false,
)

/**
 * 资料页「我们的日子」轻卡 VM（卷三图纸 §3.2·提案 D-2）：卡内 `hiltViewModel()` 从父路由 `characterProfile/{characterUuid}` 的
 * [SavedStateHandle] 读角色（照 `StarfieldEntryViewModel` 范式）；本月投影 + 14 天投影 + 相识日 + 见面天数 四源轻聚合。
 */
@HiltViewModel
class OurDaysEntryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    ourDayRepository: OurDayRepository,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    val today: LocalDate = LocalDate.now(zone)
    private val characterUuid: String = savedStateHandle.get<String>(ARG_CHARACTER_UUID).orEmpty()
    private val rangeStart: LocalDate = today.minusDays((OurDaysCalendarLogic.ENTRY_BAR_DAYS - 1).toLong())

    val state: StateFlow<OurDaysEntryState> = combine(
        ourDayRepository.observeCalendarRange(characterUuid, OurDayKey.keyOf(YearMonth.from(today).atDay(1)), OurDayKey.keyOf(today)),
        ourDayRepository.observeCalendarRange(characterUuid, OurDayKey.keyOf(rangeStart), OurDayKey.keyOf(today)),
        ourDayRepository.observeFirstDayKey(characterUuid),
        ourDayRepository.observeMeetingDayCount(characterUuid),
    ) { monthRows, barRows, firstDayKey, meetingDays ->
        OurDaysEntryState(
            loaded = true,
            daysTogether = OurDaysCalendarLogic.daysTogether(firstDayKey?.let(OurDayKey::parse), today) ?: 0,
            chatDaysThisMonth = monthRows.groupBy { it.dayKey }.values.count { OurDaysCalendarLogic.heatLevelOf(it) >= 1 },
            meetingDays = meetingDays,
            bar = OurDaysCalendarLogic.entryBar(today, barRows),
            rangeStart = rangeStart,
            hasAny = firstDayKey != null || monthRows.isNotEmpty() || barRows.isNotEmpty(),
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OurDaysEntryState(rangeStart = rangeStart))

    companion object {
        /** 与 `characterProfile/{characterUuid}` 路由参同名（F15 范式）。 */
        const val ARG_CHARACTER_UUID = "characterUuid"
    }
}
