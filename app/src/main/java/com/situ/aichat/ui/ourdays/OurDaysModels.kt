package com.situ.aichat.ui.ourdays

import java.time.LocalDate
import java.time.YearMonth

/**
 * 「我们的日子」卷三 UI 数据模型（图纸 docs/handoff/2026-09-02-我们的日子-卷三-界面.md §3.4 / §3.5 / §3.6）。
 * 纯值类型：全部由 [OurDaysCalendarLogic] / [OurDayCardLogic] 在 `Dispatchers.Default` 算出，UI 只读；
 * 文案由 UI 按资源格式化（纯核只给数与结构·§4.3 D「纯核只给数」）。
 */

/** 视图三态（提案 D-3·`AppSegmentedControl` 三段）。 */
enum class OurDaysViewMode { YEAR, MONTH, WEEK }

/** 角色行选中态（图纸 §3.2）：无角色 / 全部 / 单角色。 */
sealed interface OurDaysSelection {
    data object None : OurDaysSelection
    data object All : OurDaysSelection
    data class Character(val uuid: String) : OurDaysSelection
}

/** 路由（图纸 §3.6 锁定·照 `AIChatApp` 裸字符串先例）。 */
object OurDaysRoutes {
    const val CALENDAR = "ourDays"
    const val DAY = "ourDays/day"

    /** 「全部」模式哨兵（日页 `characterUuid` 位）。 */
    const val ALL = "all"

    /** `ourDays?character=…&date=…`（null 段省略）。 */
    fun calendar(characterUuid: String? = null, dayKey: String? = null): String {
        val query = listOfNotNull(characterUuid?.let { "character=$it" }, dayKey?.let { "date=$it" })
        return if (query.isEmpty()) CALENDAR else "$CALENDAR?" + query.joinToString("&")
    }

    fun day(characterUuid: String, dayKey: String): String = "$DAY/$characterUuid/$dayKey"
}

/** 休 / 班 角标（`ChineseHolidays` 硬表·表外 null）。 */
enum class DayBadge { REST, WORK }

/** 格子副行（图纸 §3.3）：[subtitle] = 农历或替换标签；[emphasized] = 纪念 / 生日 / 节日 / 假名（陶土深档 500 字重）。 */
data class DayDecor(val subtitle: String, val emphasized: Boolean, val badge: DayBadge?)

/** 三色点家族（提案 D-4·固定序 MEETING → RELATION → LIFE）。 */
enum class DotFamily { MEETING, RELATION, LIFE }

/**
 * 月格 / 周条 / 入口条 / 资料卡的一格（图纸 §3.4）。[identity] = 全部模式识别色序号（≤3）；[moreIdentity] = 第 4 位起「+」圈。
 * [isFuture] ⇒ 不出热度 / 点 / 识别色；[inPeriod] = false（邻月格）⇒ 只显灰数字、不可点。
 */
data class CellModel(
    val date: LocalDate,
    val key: String,
    val inPeriod: Boolean,
    val isToday: Boolean,
    val isFuture: Boolean,
    val heatLevel: Int,
    val dots: List<DotFamily> = emptyList(),
    val identity: List<Int> = emptyList(),
    val moreIdentity: Boolean = false,
    val decor: DayDecor? = null,
    val selected: Boolean = false,
)

/** 月下汇总（图纸 §3.4·文案由 UI 拼·月份标签由 UI 按 `our_days_fmt_month` 格式化 [yearMonth]）。 */
data class MonthSummary(
    val yearMonth: YearMonth,
    val chatDays: Int,
    val meetings: Int,
    val promisesFulfilled: Int,
    val milestones: Int,
    val justStarted: Boolean,
    val allMode: Boolean,
    val characterCount: Int,
    val recordedDays: Int,
)

data class MonthModel(
    val yearMonth: YearMonth,
    val weekdayLabels: List<String>,
    val cells: List<CellModel>,
    val summary: MonthSummary,
    val selectedCard: DayCardModel?,
)

/** 周视图（图纸 §3.4）：周条 7 格 + 非未来日的日卡。 */
data class WeekModel(val start: LocalDate, val end: LocalDate, val strip: List<CellModel>, val cards: List<DayCardModel>)

/** 年视图微格（图纸 §3.4 / §4.5）：见面日暖金 > 热度档 > 未来 sunken。 */
data class MiniCell(val inMonth: Boolean, val heatLevel: Int, val meeting: Boolean, val isToday: Boolean, val isFuture: Boolean)

data class MiniMonth(val yearMonth: YearMonth, val cells: List<MiniCell>, val dimmed: Boolean, val isCurrent: Boolean)

data class YearStats(val chatDays: Int, val meetings: Int, val milestones: Int, val promisesFulfilled: Int, val calls: Int)

/** 年视图（图纸 §3.4）：[daysTogether] = 第 N 天（W-3·无相识日 null）；[recordedDays] / [characterCount] 供全部模式副标。 */
data class YearModel(
    val year: Int,
    val months: List<MiniMonth>,
    val stats: YearStats,
    val firstDay: LocalDate?,
    val daysTogether: Int?,
    val characterCount: Int,
    val recordedDays: Int,
)

/** 日卡 / 纸面状态（图纸 §3.5 判定序）。 */
enum class CardStatus { NORMAL, TODAY, EMPTY, FAILED, DELETED, HIDDEN_NORMAL }

/** chip 家族色点（图纸 §3.5）：聊天 / 通话 = accent.primary；见面 / 关系 / 生活 = 三色点同源。 */
enum class ChipFamily { CHAT, MEETING, RELATION, LIFE }

/** chip 种类（固定序 = 枚举序·图纸 §3.5）；文案由 UI 按种类取资源，[Chip.count] / [Chip.text] 作占位参数。 */
enum class ChipKind(val family: ChipFamily) {
    CHAT(ChipFamily.CHAT),
    CALL(ChipFamily.CHAT),
    MEETING(ChipFamily.MEETING),
    PROMISE(ChipFamily.RELATION),
    PROMISE_FULFILLED(ChipFamily.RELATION),
    PROMISE_CANCELLED(ChipFamily.RELATION),
    MILESTONE(ChipFamily.RELATION),
    GIFT(ChipFamily.LIFE),
    RED_PACKET(ChipFamily.LIFE),
    MOMENTS(ChipFamily.LIFE),
    DIARY(ChipFamily.LIFE),
}

data class Chip(val kind: ChipKind, val count: Int = 0, val text: String = "")

/** 「你的日记」行（全部模式·W-18）。 */
data class UserDiaryLine(val moodEmoji: String?, val firstLine: String)

/**
 * 选中日卡 / 周卡 / 日页纸面共用模型（图纸 §3.5）。单角色：[note] 全文（UI 两行截断或全显）；全部模式：[segments] 按角色分段。
 * [hasMeeting] 供页脚提示分支；[generatedAt] 供「M 月 d 日 HH:mm 写下」。
 */
data class DayCardModel(
    val key: String,
    val date: LocalDate,
    val isToday: Boolean,
    val isFuture: Boolean,
    val status: CardStatus,
    val note: String,
    val chips: List<Chip>,
    val scheduleLine: String,
    val decor: DayDecor?,
    val hasMeeting: Boolean,
    val generatedAt: Long?,
    val segments: List<DaySegment> = emptyList(),
    val userDiary: UserDiaryLine? = null,
)

/** 全部模式一段 = 一角色（按识别色序）。 */
data class DaySegment(
    val characterUuid: String,
    val name: String,
    val avatarPath: String?,
    val identityIndex: Int,
    val card: DayCardModel,
)

/** 事实层十类（固定序 = 枚举序·图纸 §3.5）。 */
enum class FactKind { CHAT, CALL, MEETING, GIFT, RED_PACKET, PROMISE, MILESTONE, MOMENTS, EXCHANGE_DIARY, SCHEDULE }

/** 事实条目可选跳转（图纸 §3.5）。 */
sealed interface FactLink {
    data object MEETINGS : FactLink
    data object PROMISES : FactLink
    data object MOMENTS : FactLink
    data class DIARY(val uuid: String) : FactLink
    data class SCHEDULE(val dayKey: String) : FactLink
}

data class FactItem(val kind: FactKind, val title: String, val detail: String, val link: FactLink?)

/** 日页页脚（图纸 §4.6）：今天 / 未来 / 无行 / 墓碑 ⇒ NONE；hidden ⇒ HIDDEN；否则 REMEMBERS。 */
enum class FooterKind { REMEMBERS, HIDDEN, NONE }

/** 日历页 UI 状态（图纸 §3.2）。[hasAnyRow] = false ∧ 单角色 ⇒ 角色空态（E4）。 */
sealed interface OurDaysUiState {
    data object Loading : OurDaysUiState

    data class Content(
        val selection: OurDaysSelection,
        val characterName: String?,
        val viewMode: OurDaysViewMode,
        val anchor: LocalDate,
        val period: ClosedRange<LocalDate>,
        val month: MonthModel?,
        val week: WeekModel?,
        val year: YearModel?,
        val hasAnyRow: Boolean,
    ) : OurDaysUiState
}
