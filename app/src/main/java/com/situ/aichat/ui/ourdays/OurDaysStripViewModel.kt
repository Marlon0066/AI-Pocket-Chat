package com.situ.aichat.ui.ourdays

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.OurDayCalendarRow
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.OurDayRepository
import com.situ.aichat.ourdays.OurDayKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject

/** 入口条尾行预览（§4.9）：昨天有手记 ⇒ [isYesterday]；否则近 7 天最近一篇（[date]）。文案由 UI 按资源拼。 */
data class StripPreview(val isYesterday: Boolean, val date: LocalDate, val characterName: String, val firstSentence: String)

/** 动态页入口条状态（§3.2）：[week] 七格（周首日起）；[nthDay] 第 N 天（无相识日 null）。 */
data class OurDaysStripState(
    val loaded: Boolean = false,
    val character: CharacterEntity? = null,
    val nthDay: Int? = null,
    val week: List<CellModel> = emptyList(),
    val preview: StripPreview? = null,
)

/**
 * 动态页「我们的日子」入口条 VM（卷三图纸 §3.2·提案 D-1）：角色 = 最近有互动 ?: 最新创建（与日历默认预选同源·W-4）；
 * 本周投影 Flow + 近 7 天手记链（today−7..today−1）+ 相识日。零角色 ⇒ 空副标 + 空态尾行。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OurDaysStripViewModel @Inject constructor(
    ourDayRepository: OurDayRepository,
    characterRepository: CharacterRepository,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()
    val today: LocalDate = LocalDate.now(zone)
    private val weekFields: WeekFields = WeekFields.of(Locale.getDefault())

    private val pick = combine(ourDayRepository.observeLatestActiveCharacterUuid(), characterRepository.observeAll()) { latest, chars ->
        val uuid = latest?.takeIf { l -> chars.any { it.uuid == l } } ?: chars.maxByOrNull { it.creationDate }?.uuid
        chars.firstOrNull { it.uuid == uuid }
    }

    val state: StateFlow<OurDaysStripState> = pick.flatMapLatest { character ->
        if (character == null) {
            flowOf(OurDaysStripState(loaded = true, week = OurDaysCalendarLogic.stripWeek(today, emptyList(), weekFields)))
        } else {
            val weekStart = OurDaysCalendarLogic.weekStart(today, weekFields)
            combine(
                ourDayRepository.observeCalendarRange(character.uuid, OurDayKey.keyOf(weekStart), OurDayKey.keyOf(weekStart.plusDays(6))),
                ourDayRepository.observeCalendarRange(character.uuid, OurDayKey.keyOf(today.minusDays(7)), OurDayKey.keyOf(today.minusDays(1))),
                ourDayRepository.observeFirstDayKey(character.uuid),
            ) { weekRows, recent, firstDayKey ->
                OurDaysStripState(
                    loaded = true,
                    character = character,
                    nthDay = OurDaysCalendarLogic.daysTogether(firstDayKey?.let(OurDayKey::parse), today),
                    week = OurDaysCalendarLogic.stripWeek(today, weekRows, weekFields),
                    preview = preview(recent, character.name),
                )
            }
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OurDaysStripState())

    /** 预览链（§4.9）：昨天有手记 ⇒ 昨天；否则近 7 天最近一篇；否则 null（墓碑 / 空手记不算）。 */
    private fun preview(recent: List<OurDayCalendarRow>, name: String): StripPreview? {
        val candidates = recent.filter { it.note.isNotBlank() && !it.deleted }
        val yesterdayKey = OurDayKey.keyOf(today.minusDays(1))
        val pick = candidates.firstOrNull { it.dayKey == yesterdayKey } ?: candidates.maxByOrNull { it.dayKey } ?: return null
        val date = OurDayKey.parse(pick.dayKey) ?: return null
        return StripPreview(pick.dayKey == yesterdayKey, date, name, OurDayCardLogic.firstSentence(pick.note))
    }
}
