package com.situ.aichat.ui.diary

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.DiaryEntryWithComments
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDialogTone
import com.situ.aichat.ui.designsystem.AppElevation
import com.situ.aichat.ui.designsystem.AppMenu
import com.situ.aichat.ui.designsystem.AppMenuItem
import com.situ.aichat.ui.designsystem.AppSegmentedControl
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * 日记本主界面（日记重设计 R1·契约 §1.1 S1）：手账×杂志时间线（月分节大字 + 票据虚线 + 票据卡）+
 * 三视图切换 + 胶囊「写一笔」+ API 缺失横幅 + 空状态 + 长按删除。响应式观察全部日记（VM），
 * 异步落地的自动日记/AI 评论自动刷新。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DiaryListScreen(
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
    // U4：作者筛选（全部/我的/TA 的信·F4=记到退出·随 viewMode 同脾气）。
    var entryFilter by rememberSaveable { mutableStateOf(DiaryEntryFilter.ALL) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var shownReview by remember { mutableStateOf<com.situ.aichat.data.local.entity.MonthlyReviewEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.refreshApiMissing() }
    // diary-1：进/出日记列表都标记已读，清枢纽日记卡未读角标。
    DisposableEffect(Unit) {
        viewModel.markDiaryAsRead()
        onDispose { viewModel.markDiaryAsRead() }
    }

    // 门楣升起态的滚动源：三视图各有各的列表，按当前视图取（日历视图不吃升起态）。
    val timelineState = rememberLazyListState()
    val compactState = rememberLazyListState()
    val lifted = when (viewMode) {
        DiaryViewMode.TIMELINE -> timelineState.canScrollBackward
        DiaryViewMode.LIST -> compactState.canScrollBackward
        else -> false
    }
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.diary_nav_title),
                onBack = onBack,
                lifted = lifted,
                actions = {
                    // R5 连续记录印章（发布才算·O4）：>0 才现身。
                    if (insights.streakDays > 0) {
                        val streakCd = stringResource(R.string.a11y_diary_streak, insights.streakDays)
                        Text(
                            stringResource(R.string.diary_streak_days, insights.streakDays),
                            style = AppTheme.typography.captionNumeric,
                            color = AppTheme.colors.accent.text,
                            modifier = Modifier
                                .clip(AppTheme.shapes.full)
                                .border(1.dp, AppTheme.colors.accent.container, AppTheme.shapes.full)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                .semantics { contentDescription = streakCd },
                        )
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.DateRange, contentDescription = stringResource(R.string.diary_view_mode))
                    }
                    // R1 已裁（D-2 核准·2026-08-06）：单选清单项保留 DropdownMenuItem——AppMenuItem 无
                    // trailingIcon/selected 槽，勾选标记属站点内部结构；容器已是 AppMenu 玻璃小笺。勿「统一」改掉。
                    AppMenu(expanded = menuExpanded, onDismiss = { menuExpanded = false }) {
                        DiaryViewMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(stringResource(mode.labelRes)) },
                                onClick = { viewMode = mode; menuExpanded = false },
                                modifier = Modifier.semantics { selected = mode == viewMode },
                                trailingIcon = {
                                    if (mode == viewMode) {
                                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = AppTheme.colors.accent.text)
                                    }
                                },
                            )
                        }
                        // R5 回顾与统计入口。
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.diary_menu_insights)) },
                            onClick = { menuExpanded = false; showStats = true },
                        )
                    }
                },
            )
        },
        floatingActionButton = { ComposePill(onCompose) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (apiMissing) ApiMissingBanner()
            if (entries.isEmpty()) {
                // 一篇都没有：不出筛选条，直接引导写第一篇。
                DiaryEmptyState(onCompose)
            } else {
                // U4：筛选条常驻（F1·三视图共用·F2）；筛在底层条目，切换视图跟着筛。
                DiaryEntryFilterRow(selected = entryFilter, onSelect = { entryFilter = it })
                val filtered = remember(entries, entryFilter) { entries.filter { entryFilter.matches(it.entry) } }
                // F3：非「全部」时藏顶部情境卡（交换信封 + 那年今天）→ 纯净档案。
                val showContextCards = entryFilter == DiaryEntryFilter.ALL
                when {
                    filtered.isEmpty() -> DiaryFilterEmptyState(entryFilter)
                    viewMode == DiaryViewMode.TIMELINE ->
                        DiaryTimeline(
                            listState = timelineState,
                            entries = filtered,
                            charactersByUuid = charactersByUuid,
                            exchangeUi = exchangeUi,
                            justUnlockedUuid = justUnlockedUuid,
                            onThisDay = onThisDay,
                            monthlyReviews = monthlyReviews,
                            reviewGeneratingMonth = reviewGeneratingMonth,
                            reviewFailedMonth = reviewFailedMonth,
                            showContextCards = showContextCards,
                            onOpenEntry = onOpenEntry,
                            onPublish = viewModel::publishDraft,
                            onCompose = onCompose,
                            onUnlock = viewModel::unlockExchange,
                            onOpenReview = { shownReview = it },
                            onGenerateReview = viewModel::generateMonthlyReview,
                        ) { deleteTarget = it }
                    viewMode == DiaryViewMode.LIST -> DiaryCompactList(compactState, filtered, onOpenEntry) { deleteTarget = it }
                    else -> DiaryCalendarSection(filtered, charactersByUuid, onOpenEntry, viewModel::publishDraft) { deleteTarget = it }
                }
            }
        }
    }

    if (showStats) {
        DiaryStatsSheet(stats = insights, onDismiss = { showStats = false })
    }
    shownReview?.let { review ->
        DiaryReviewSheet(review = review, onDismiss = { shownReview = null })
    }

    deleteTarget?.let { uuid ->
        AppDialog(
            onDismissRequest = { deleteTarget = null },
            title = stringResource(R.string.diary_delete_title),
            body = stringResource(R.string.diary_delete_message),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = { viewModel.delete(uuid); deleteTarget = null },
            confirmTone = AppDialogTone.Danger,
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { deleteTarget = null },
        )
    }
}

/** 胶囊「写一笔」（取代圆形 FAB·陶土渐变 + onAccent 字·浅色极浅投影 / 深色 1dp 描边分层）。 */
@Composable
private fun ComposePill(onClick: () -> Unit) {
    val colors = AppTheme.colors
    val elevation = if (colors.isDark) {
        Modifier.border(1.dp, colors.surface.stroke, AppTheme.shapes.full)
    } else {
        // 浮标软影：M3 shadow → 自绘双层软影（raised 档·全引 AppElevation token·照 AppSlider 拇指写法·§4.D3）。
        // BlurMaskFilter 重对象在 cache 域一次构建；圆角 = size.height/2（全 pill 形）。
        Modifier.drawWithCache {
            val radius = size.height / 2f
            val paints = listOf(
                Triple(AppElevation.REST_SHADOW_ALPHA, AppElevation.restShadowBlur.toPx(), AppElevation.restShadowY.toPx()),
                Triple(AppElevation.RAISED_SHADOW_ALPHA, AppElevation.raisedShadowBlur.toPx(), AppElevation.raisedShadowY.toPx()),
            ).map { (alpha, blur, dy) ->
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = AppElevation.shadowInk.copy(alpha = alpha).toArgb()
                    maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
                } to dy
            }
            onDrawBehind {
                paints.forEach { (paint, dy) ->
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawRoundRect(0f, dy, size.width, size.height + dy, radius, radius, paint)
                    }
                }
            }
        }
    }
    Row(
        modifier = elevation
            .clip(AppTheme.shapes.full)
            .background(Brush.linearGradient(listOf(colors.accent.gradientStart, colors.accent.gradientEnd)))
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.text.onAccent)
        Text(stringResource(R.string.diary_fab_compose), style = AppTheme.typography.label, color = colors.text.onAccent)
    }
}

@Composable
private fun ApiMissingBanner() {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(AppTheme.shapes.small)
            .background(colors.status.errorContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.status.onError, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.diary_api_missing), style = AppTheme.typography.secondary, color = colors.status.onError)
    }
}

@Composable
private fun DiaryEmptyState(onCompose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("📖", style = AppTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics {}) // 装饰压停
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.diary_empty_title), style = AppTheme.typography.titleSmall, color = AppTheme.colors.text.primary)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.diary_empty_desc),
            style = AppTheme.typography.secondary,
            color = AppTheme.colors.text.secondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        AppButton(onClick = onCompose, style = AppButtonStyle.Primary) { Text(stringResource(R.string.diary_empty_action)) }
    }
}

/** U4 作者筛选条（复用已过审 [AppSegmentedControl]·陶土药丸滑块·三视图共用·F7 不带计数）。 */
@Composable
private fun DiaryEntryFilterRow(selected: DiaryEntryFilter, onSelect: (DiaryEntryFilter) -> Unit) {
    AppSegmentedControl(
        options = DiaryEntryFilter.entries,
        selected = selected,
        onSelect = onSelect,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        label = { stringResource(it.labelRes) },
    )
}

/** U4 F5：某筛选无内容的空态（尊重语气·不塞按钮）。「全部」空态由外层 [DiaryEmptyState] 接管，此处只 MINE/THEIRS。 */
@Composable
private fun DiaryFilterEmptyState(filter: DiaryEntryFilter) {
    val msg = if (filter == DiaryEntryFilter.THEIRS) {
        R.string.diary_filter_empty_theirs
    } else {
        R.string.diary_filter_empty_mine
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(msg),
            style = AppTheme.typography.secondary,
            color = AppTheme.colors.text.secondary,
            textAlign = TextAlign.Center,
        )
    }
}

// MARK: - 时间线（月分节：M月 大字 + yyyy 小字 + 票据虚线）

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiaryTimeline(
    listState: LazyListState,
    entries: List<DiaryEntryWithComments>,
    charactersByUuid: Map<String, com.situ.aichat.data.local.entity.CharacterEntity>,
    exchangeUi: DiaryExchangeUiState,
    justUnlockedUuid: String?,
    onThisDay: List<DiaryEntryWithComments>,
    monthlyReviews: Map<Long, com.situ.aichat.data.local.entity.MonthlyReviewEntity>,
    reviewGeneratingMonth: Long?,
    reviewFailedMonth: Long?,
    /** U4 F3：仅「全部」筛选时显示顶部情境卡（交换信封 + 那年今天）。 */
    showContextCards: Boolean,
    onOpenEntry: (String) -> Unit,
    onPublish: (String) -> Unit,
    onCompose: () -> Unit,
    onUnlock: () -> Unit,
    onOpenReview: (com.situ.aichat.data.local.entity.MonthlyReviewEntity) -> Unit,
    onGenerateReview: (Long) -> Unit,
    onLongPress: (String) -> Unit,
) {
    val sections = rememberDiaryMonthSections(entries)
    val reduceMotion = rememberReduceMotion()
    // 当前未完月不出回顾 chip（月过完才小结）。
    val currentMonthStart = remember {
        YearMonth.now(ZoneId.systemDefault())
            .atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // 底部留胶囊「写一笔」的悬浮净空。
        contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp),
    ) {
        // U4 F3：情境卡仅「全部」筛选时露出（交换信封 + 那年今天）。
        if (showContextCards) {
            // R4 交换日记信封位（当日置顶·Hidden/Unlocked 自隐藏）。
            item(key = "exchange-slot") {
                DiaryExchangeSlot(ui = exchangeUi, onCompose = onCompose, onUnlock = onUnlock)
            }
            // R5 那年今天（纯本地·有才现身·取最近一年那篇）。
            onThisDay.firstOrNull()?.let { hit ->
                item(key = "on-this-day") {
                    OnThisDayCard(hit, onOpenEntry)
                }
            }
        }
        sections.forEach { section ->
            item(key = "month-${section.key}") {
                MonthHeader(
                    section = section,
                    review = monthlyReviews[section.monthStartMillis],
                    isPastMonth = section.monthStartMillis < currentMonthStart,
                    isGenerating = reviewGeneratingMonth == section.monthStartMillis,
                    isFailed = reviewFailedMonth == section.monthStartMillis,
                    onOpenReview = onOpenReview,
                    onGenerateReview = onGenerateReview,
                )
            }
            items(section.entries, key = { it.entry.uuid }) { ewc ->
                // U3：活角色取活名，已删取快照名 + 「故友的信」淡标（§6.3 O1/O2）。
                val authorDisplay = diaryAuthorDisplay(
                    ewc.entry.authorCharacterUuid,
                    ewc.entry.authorNameSnapshot,
                    ewc.entry.authorCharacterUuid?.let { charactersByUuid[it]?.name },
                )
                DiaryEntryCard(
                    entry = ewc.entry,
                    commentCount = ewc.comments.size,
                    preview = true,
                    reactionCount = ewc.reactions.size,
                    onPublish = { onPublish(ewc.entry.uuid) },
                    authorName = authorDisplay?.name,
                    isOrphan = authorDisplay?.isOrphan == true,
                    modifier = Modifier
                        .then(if (reduceMotion) Modifier else Modifier.animateItem(placementSpec = AppMotion.gentleSpring()))
                        // 拆信揭晓（celebrate·只对刚拆开的那封一次）。
                        .celebrateUnlockEntrance(enabled = ewc.entry.uuid == justUnlockedUuid)
                        .padding(horizontal = 16.dp, vertical = 5.dp)
                        .combinedClickable(
                            onClickLabel = stringResource(R.string.a11y_diary_open),
                            onClick = { onOpenEntry(ewc.entry.uuid) },
                            onLongClickLabel = stringResource(R.string.a11y_diary_delete),
                            onLongClick = { onLongPress(ewc.entry.uuid) },
                        ),
                )
            }
        }
    }
}

// MARK: - 列表（紧凑行）

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiaryCompactList(
    listState: LazyListState,
    entries: List<DiaryEntryWithComments>,
    onOpenEntry: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
        items(entries, key = { it.entry.uuid }) { ewc ->
            DiaryEntryRowCompact(
                entry = ewc.entry,
                commentCount = ewc.comments.size,
                modifier = Modifier.combinedClickable(
                    onClickLabel = stringResource(R.string.a11y_diary_open),
                    onClick = { onOpenEntry(ewc.entry.uuid) },
                    onLongClickLabel = stringResource(R.string.a11y_diary_delete),
                    onLongClick = { onLongPress(ewc.entry.uuid) },
                ),
            )
        }
    }
}

// 月分组与月分节头（R5 起）迁 DiaryTimelineParts.kt——本屏只留编排。
