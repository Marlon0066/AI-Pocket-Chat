package com.situ.aichat.ui.liuli.diary

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.DiaryEntryWithComments
import com.situ.aichat.data.local.entity.MonthlyReviewEntity
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.diary.DiaryEmptyState
import com.situ.aichat.ui.diary.DiaryEntryFilter
import com.situ.aichat.ui.diary.DiaryExchangeUiState
import com.situ.aichat.ui.diary.DiaryFilterEmptyState
import com.situ.aichat.ui.diary.DiaryListLifecycleEffects
import com.situ.aichat.ui.diary.DiaryViewMode
import com.situ.aichat.ui.diary.DiaryViewModel
import com.situ.aichat.ui.diary.filterDiaryEntries
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliDialog
import com.situ.aichat.ui.liuli.designsystem.LiuliFabPill
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed

/**
 * 琉璃日记本（琉璃 2.0 卷六·一 §4.1·设计稿 D1）：与暖陶 [com.situ.aichat.ui.diary.DiaryListScreen] 共用同一个 VM 与全部内容件
 * （票根卡 / 月分节头 / 交换信封 / 那年今天 / 紧凑行 / 月历），只换外壳与材质：琉璃页壳（大标题 + 收起胶囊 + 玻璃筛选条）、
 * 半透明卡、玻璃「写一笔」胶囊 + 屏底渐进带、琉璃弹层 / 对话框 / 菜单。
 */
@Composable
internal fun LiuliDiaryListScreen(
    onBack: () -> Unit,
    onCompose: () -> Unit,
    onOpenEntry: (String) -> Unit,
    viewModel: DiaryViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val apiMissing by viewModel.apiMissing.collectAsStateWithLifecycle()
    val charactersByUuid by viewModel.charactersByUuid.collectAsStateWithLifecycle()
    val exchangeUi by viewModel.exchangeUi.collectAsStateWithLifecycle()
    val justUnlockedUuid by viewModel.justUnlockedUuid.collectAsStateWithLifecycle()
    val insights by viewModel.insights.collectAsStateWithLifecycle()
    val onThisDay by viewModel.onThisDay.collectAsStateWithLifecycle()
    val monthlyReviews by viewModel.monthlyReviews.collectAsStateWithLifecycle()
    val reviewGeneratingMonth by viewModel.reviewGeneratingMonth.collectAsStateWithLifecycle()
    val reviewFailedMonth by viewModel.reviewFailedMonth.collectAsStateWithLifecycle()
    var viewMode by rememberSaveable { mutableStateOf(DiaryViewMode.TIMELINE) }
    var entryFilter by rememberSaveable { mutableStateOf(DiaryEntryFilter.ALL) }
    var showStats by remember { mutableStateOf(false) }
    var shownReview by remember { mutableStateOf<MonthlyReviewEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    DiaryListLifecycleEffects(viewModel)

    LiuliDiaryListPage(
        data = LiuliDiaryListData(
            entries = entries,
            apiMissing = apiMissing,
            charactersByUuid = charactersByUuid,
            exchangeUi = exchangeUi,
            justUnlockedUuid = justUnlockedUuid,
            streakDays = insights.streakDays,
            onThisDay = onThisDay,
            monthlyReviews = monthlyReviews,
            reviewGeneratingMonth = reviewGeneratingMonth,
            reviewFailedMonth = reviewFailedMonth,
        ),
        callbacks = LiuliDiaryListCallbacks(
            onBack = onBack,
            onCompose = onCompose,
            onOpenEntry = onOpenEntry,
            onPublish = viewModel::publishDraft,
            onUnlock = viewModel::unlockExchange,
            onOpenReview = { shownReview = it },
            onGenerateReview = viewModel::generateMonthlyReview,
            onShowStats = { showStats = true },
            onLongPress = { deleteTarget = it },
        ),
        viewMode = viewMode,
        onViewModeChange = { viewMode = it },
        entryFilter = entryFilter,
        onFilterChange = { entryFilter = it },
    )

    if (showStats) LiuliDiaryStatsSheet(insights) { showStats = false }
    shownReview?.let { LiuliDiaryReviewSheet(it) { shownReview = null } }
    deleteTarget?.let { uuid ->
        LiuliDialog(
            onDismissRequest = { deleteTarget = null },
            title = stringResource(R.string.diary_delete_title),
            body = stringResource(R.string.diary_delete_message),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = { viewModel.delete(uuid); deleteTarget = null },
            confirmDanger = true,
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { deleteTarget = null },
        )
    }
}

/** 日记本页要画的全部数据（类型同 VM 流）。 */
@Immutable
internal data class LiuliDiaryListData(
    val entries: List<DiaryEntryWithComments>,
    val apiMissing: Boolean,
    val charactersByUuid: Map<String, CharacterEntity>,
    val exchangeUi: DiaryExchangeUiState,
    val justUnlockedUuid: String?,
    val streakDays: Int,
    val onThisDay: List<DiaryEntryWithComments>,
    val monthlyReviews: Map<Long, MonthlyReviewEntity>,
    val reviewGeneratingMonth: Long?,
    val reviewFailedMonth: Long?,
)

/** 日记本页的全部回调。 */
internal class LiuliDiaryListCallbacks(
    val onBack: () -> Unit,
    val onCompose: () -> Unit,
    val onOpenEntry: (String) -> Unit,
    val onPublish: (String) -> Unit,
    val onUnlock: () -> Unit,
    val onOpenReview: (MonthlyReviewEntity) -> Unit,
    val onGenerateReview: (Long) -> Unit,
    val onShowStats: () -> Unit,
    val onLongPress: (String) -> Unit,
)

/** 无 VM 的日记本页（测试直接驱动它）。 */
@Composable
internal fun LiuliDiaryListPage(
    data: LiuliDiaryListData,
    callbacks: LiuliDiaryListCallbacks,
    viewMode: DiaryViewMode,
    onViewModeChange: (DiaryViewMode) -> Unit,
    entryFilter: DiaryEntryFilter,
    onFilterChange: (DiaryEntryFilter) -> Unit,
) {
    val title = stringResource(R.string.diary_nav_title)
    val timelineState = rememberLazyListState()
    val compactState = rememberLazyListState()
    val calendarState = rememberLazyListState()
    val filtered = remember(data.entries, entryFilter) { filterDiaryEntries(data.entries, entryFilter) }
    val listed = data.entries.isNotEmpty() && filtered.isNotEmpty()
    val currentState = when (viewMode) {
        DiaryViewMode.TIMELINE -> timelineState
        DiaryViewMode.LIST -> compactState
        DiaryViewMode.CALENDAR -> calendarState
    }
    // 判据无条件先算（remember 链不随空态进出而断），再与「真有列表」相与：空态页不收起。
    val scrolledPast = rememberLargeTitleCollapsed(currentState)
    val collapsed = scrolledPast && listed
    LiuliPage(
        title = title,
        onBack = callbacks.onBack,
        collapsed = collapsed,
        actions = {
            if (data.streakDays > 0) LiuliDiaryStreakPill(data.streakDays, callbacks.onShowStats)
            LiuliDiaryViewMenuAction(viewMode, onViewModeChange, callbacks.onShowStats)
        },
        subBar = if (data.entries.isNotEmpty()) {
            { LiuliDiaryFilterStrip(entryFilter, onFilterChange, glass = true) }
        } else {
            null
        },
        fab = { LiuliFabPill(icon = AppActionIcons.Edit, label = stringResource(R.string.diary_fab_compose), onClick = callbacks.onCompose) },
        bottomEdge = DIARY_LIST_BOTTOM_EDGE,
    ) {
        when {
            data.entries.isEmpty() -> LiuliDiaryStaticBody(title, data.apiMissing, filter = null, onFilterChange) {
                DiaryEmptyState {
                    LiuliButton(onClick = callbacks.onCompose, style = LiuliButtonStyle.Prominent) {
                        Text(stringResource(R.string.diary_empty_action))
                    }
                }
            }
            filtered.isEmpty() -> LiuliDiaryStaticBody(title, data.apiMissing, filter = entryFilter, onFilterChange) {
                DiaryFilterEmptyState(entryFilter)
            }
            viewMode == DiaryViewMode.TIMELINE ->
                LiuliDiaryTimelineList(timelineState, title, data, filtered, entryFilter, onFilterChange, callbacks)
            viewMode == DiaryViewMode.LIST ->
                LiuliDiaryCompactList(compactState, title, data.apiMissing, filtered, entryFilter, onFilterChange, callbacks)
            else -> LiuliDiaryCalendarList(calendarState, title, data, filtered, entryFilter, onFilterChange, callbacks)
        }
    }
}
