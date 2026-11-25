package com.situ.aichat.ui.liuli.diary

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.DiaryEntryWithComments
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.diary.ApiMissingBanner
import com.situ.aichat.ui.diary.DiaryCalendarMonth
import com.situ.aichat.ui.diary.DiaryEntryCard
import com.situ.aichat.ui.diary.DiaryEntryFilter
import com.situ.aichat.ui.diary.DiaryEntryRowCompact
import com.situ.aichat.ui.diary.DiaryExchangeSlot
import com.situ.aichat.ui.diary.MonthHeader
import com.situ.aichat.ui.diary.OnThisDayCard
import com.situ.aichat.ui.diary.celebrateUnlockEntrance
import com.situ.aichat.ui.diary.diaryAuthorDisplayOf
import com.situ.aichat.ui.diary.diaryCalendarData
import com.situ.aichat.ui.diary.diaryCurrentMonthStartMillis
import com.situ.aichat.ui.diary.rememberDiaryCalendarState
import com.situ.aichat.ui.diary.rememberDiaryMonthSections
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import java.time.ZoneId

// 琉璃日记本的三视图列表 + 列表头 + 静态体 + 琉璃票根卡（琉璃 2.0 卷六·一 §4.1）。内容件全是暖陶那一份，材质外给。

/** 列表底留白 = 导航栏 + 写一笔距导航栏 24 + 胶囊高 48 + 页底 24（= 导航栏 + 96·末卡永远在写一笔之上 24）。 */
@Composable
internal fun diaryListBottomPadding(): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        LiuliPageGeometry.fabBottom + LiuliPageGeometry.fabPill + LiuliPageGeometry.pageBottom

/** 三视图共用的列表头：大标题 → 未配 API 横幅 → 纸面筛选条（都随内容滚走）。 */
internal fun LazyListScope.liuliDiaryListHeader(
    title: String,
    apiMissing: Boolean,
    filter: DiaryEntryFilter,
    onFilterChange: (DiaryEntryFilter) -> Unit,
) {
    item("large-title") { LiuliLargeTitle(title) }
    if (apiMissing) item("api-missing") { ApiMissingBanner() }
    item("filter") { LiuliDiaryFilterStrip(filter, onFilterChange, glass = false) }
}

/** 不滚、不收起的静态页体（一篇没有 / 筛到空）：大标题 → 横幅 →（筛选条）→ 余下铺满给空态。 */
@Composable
internal fun LiuliDiaryStaticBody(
    title: String,
    apiMissing: Boolean,
    filter: DiaryEntryFilter?,
    onFilterChange: (DiaryEntryFilter) -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().contentMaxWidth().padding(top = LiuliPageGeometry.navRow)) {
        LiuliLargeTitle(title)
        if (apiMissing) ApiMissingBanner()
        if (filter != null) LiuliDiaryFilterStrip(filter, onFilterChange, glass = false)
        Box(Modifier.weight(1f).fillMaxWidth(), content = content)
    }
}

@Composable
private fun LiuliDiaryLazyList(state: LazyListState, content: LazyListScope.() -> Unit) {
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize().contentMaxWidth(),
        contentPadding = PaddingValues(top = LiuliPageGeometry.navRow, bottom = diaryListBottomPadding()),
        content = content,
    )
}

/** 时间线：情境卡（信封 + 那年今天·只在「全部」）→ 逐月分节头 + 琉璃票根卡。 */
@Composable
internal fun LiuliDiaryTimelineList(
    state: LazyListState,
    title: String,
    data: LiuliDiaryListData,
    filtered: List<DiaryEntryWithComments>,
    filter: DiaryEntryFilter,
    onFilterChange: (DiaryEntryFilter) -> Unit,
    callbacks: LiuliDiaryListCallbacks,
) {
    val dark = LocalIsDarkTheme.current
    val sections = rememberDiaryMonthSections(filtered)
    val reduceMotion = rememberReduceMotion()
    val currentMonthStart = remember { diaryCurrentMonthStartMillis() }
    LiuliDiaryLazyList(state) {
        liuliDiaryListHeader(title, data.apiMissing, filter, onFilterChange)
        if (filter == DiaryEntryFilter.ALL) {
            item("exchange-slot") {
                DiaryExchangeSlot(data.exchangeUi, callbacks.onCompose, callbacks.onUnlock, surface = Modifier.liuliEnvelopeSurface(dark))
            }
            data.onThisDay.firstOrNull()?.let { hit ->
                item("on-this-day") { OnThisDayCard(hit, callbacks.onOpenEntry, surface = Modifier.liuliDiaryCard(dark)) }
            }
        }
        sections.forEach { section ->
            item("month-${section.key}") {
                MonthHeader(
                    section = section,
                    review = data.monthlyReviews[section.monthStartMillis],
                    isPastMonth = section.monthStartMillis < currentMonthStart,
                    isGenerating = data.reviewGeneratingMonth == section.monthStartMillis,
                    isFailed = data.reviewFailedMonth == section.monthStartMillis,
                    onOpenReview = callbacks.onOpenReview,
                    onGenerateReview = callbacks.onGenerateReview,
                )
            }
            items(section.entries, key = { it.entry.uuid }) { ewc ->
                LiuliDiaryEntryCard(
                    ewc,
                    data.charactersByUuid,
                    callbacks,
                    Modifier
                        .then(if (reduceMotion) Modifier else Modifier.animateItem(placementSpec = AppMotion.gentleSpring()))
                        .celebrateUnlockEntrance(enabled = ewc.entry.uuid == data.justUnlockedUuid)
                        .padding(horizontal = DIARY_CARD_GUTTER, vertical = DIARY_CARD_GAP_V),
                )
            }
        }
    }
}

/** 列表：紧凑行（行透明直接压在柔光底上·同暖陶）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LiuliDiaryCompactList(
    state: LazyListState,
    title: String,
    apiMissing: Boolean,
    filtered: List<DiaryEntryWithComments>,
    filter: DiaryEntryFilter,
    onFilterChange: (DiaryEntryFilter) -> Unit,
    callbacks: LiuliDiaryListCallbacks,
) {
    LiuliDiaryLazyList(state) {
        liuliDiaryListHeader(title, apiMissing, filter, onFilterChange)
        items(filtered, key = { it.entry.uuid }) { ewc ->
            DiaryEntryRowCompact(
                entry = ewc.entry,
                commentCount = ewc.comments.size,
                modifier = Modifier.combinedClickable(
                    onClickLabel = stringResource(R.string.a11y_diary_open),
                    onClick = { callbacks.onOpenEntry(ewc.entry.uuid) },
                    onLongClickLabel = stringResource(R.string.a11y_diary_delete),
                    onLongClick = { callbacks.onLongPress(ewc.entry.uuid) },
                ),
            )
        }
    }
}

/** 日历：月历块（两张脸共用）→ 选中日的琉璃票根卡。 */
@Composable
internal fun LiuliDiaryCalendarList(
    state: LazyListState,
    title: String,
    data: LiuliDiaryListData,
    filtered: List<DiaryEntryWithComments>,
    filter: DiaryEntryFilter,
    onFilterChange: (DiaryEntryFilter) -> Unit,
    callbacks: LiuliDiaryListCallbacks,
) {
    val dark = LocalIsDarkTheme.current
    val zone = remember { ZoneId.systemDefault() }
    val calendar = rememberDiaryCalendarState(zone)
    val calendarData = remember(filtered) { diaryCalendarData(filtered, zone) }
    LiuliDiaryLazyList(state) {
        liuliDiaryListHeader(title, data.apiMissing, filter, onFilterChange)
        item("calendar") {
            DiaryCalendarMonth(
                state = calendar,
                data = calendarData,
                zone = zone,
                modifier = Modifier.padding(horizontal = LiuliPageGeometry.gutter),
                cardSurface = Modifier.liuliDiaryCard(dark),
            )
        }
        items(calendarData.entriesByDay[calendar.selectedDate].orEmpty(), key = { it.entry.uuid }) { ewc ->
            LiuliDiaryEntryCard(
                ewc,
                data.charactersByUuid,
                callbacks,
                Modifier.padding(horizontal = LiuliPageGeometry.gutter, vertical = DIARY_CARD_GAP_V),
            )
        }
    }
}

/** 琉璃票根卡 = 暖陶票根卡 + 半透明卡面 + 排在卡面裁切之后的点击面（ripple 不漏圆角·PITFALLS §1d）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LiuliDiaryEntryCard(
    ewc: DiaryEntryWithComments,
    charactersByUuid: Map<String, CharacterEntity>,
    callbacks: LiuliDiaryListCallbacks,
    modifier: Modifier = Modifier,
) {
    val dark = LocalIsDarkTheme.current
    val uuid = ewc.entry.uuid
    val authorDisplay = diaryAuthorDisplayOf(ewc.entry, charactersByUuid)
    DiaryEntryCard(
        entry = ewc.entry,
        commentCount = ewc.comments.size,
        preview = true,
        reactionCount = ewc.reactions.size,
        onPublish = { callbacks.onPublish(uuid) },
        authorName = authorDisplay?.name,
        isOrphan = authorDisplay?.isOrphan == true,
        surface = Modifier.liuliDiaryCard(dark),
        letterSurface = Modifier.liuliLetterCard(dark),
        interaction = Modifier.combinedClickable(
            onClickLabel = stringResource(R.string.a11y_diary_open),
            onClick = { callbacks.onOpenEntry(uuid) },
            onLongClickLabel = stringResource(R.string.a11y_diary_delete),
            onLongClick = { callbacks.onLongPress(uuid) },
        ),
        modifier = modifier,
    )
}
