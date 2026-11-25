package com.situ.aichat.ui.liuli.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppIconButton
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.liuliCardMaterial
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberScrollCollapsed
import com.situ.aichat.ui.schedule.SCHEDULE_USER_INTERACTION_TYPE
import com.situ.aichat.ui.schedule.ScheduleDatePickerDialog
import com.situ.aichat.ui.schedule.ScheduleDayUiState
import com.situ.aichat.ui.schedule.ScheduleEmptyState
import com.situ.aichat.ui.schedule.ScheduleEventRow
import com.situ.aichat.ui.schedule.ScheduleFullDayViewModel
import com.situ.aichat.ui.schedule.ScheduleWeatherRow
import com.situ.aichat.ui.schedule.scheduleDateHeaderText
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/** 全天行程视图（琉璃 2.0 脸）。 */
@Composable
fun LiuliScheduleFullDayScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    viewModel: ScheduleFullDayViewModel = hiltViewModel(),
) {
    val characterName by viewModel.characterName.collectAsStateWithLifecycle()
    val dayState by viewModel.dayState.collectAsStateWithLifecycle()
    val daySchedule by viewModel.daySchedule.collectAsStateWithLifecycle()
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val relativeDay by viewModel.relativeDay.collectAsStateWithLifecycle()
    val canGoForward by viewModel.canGoForward.collectAsStateWithLifecycle()
    val bounds by viewModel.bounds.collectAsStateWithLifecycle()

    var showDatePicker by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.openConversation.collect { onOpenChat(it) } }

    val dark = LocalIsDarkTheme.current
    val colors = AppTheme.colors
    val scrollState = rememberScrollState()
    val collapsed = rememberScrollCollapsed(scrollState)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val title = stringResource(R.string.schedule_full_day_title, characterName)

    LiuliPage(title = title, onBack = onBack, collapsed = collapsed) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var totalDx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDx = 0f },
                        onDragEnd = {
                            val threshold = 50.dp.toPx()
                            when {
                                totalDx <= -threshold -> viewModel.shiftDay(1)
                                totalDx >= threshold -> viewModel.shiftDay(-1)
                            }
                        },
                        onHorizontalDrag = { _, dragAmount -> totalDx += dragAmount },
                    )
                },
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = LiuliPageGeometry.gutter)
                    .padding(top = LiuliPageGeometry.navRow + 4.dp, bottom = navBottom + LiuliPageGeometry.pageBottom),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LiuliLargeTitle(title)

                // 日期切换玻璃卡
                Row(
                    Modifier
                        .fillMaxWidth()
                        .liuliCardMaterial(LiuliShapes.group, dark)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIconButton(
                        icon = AppActionIcons.ChevronLeft,
                        contentDescription = stringResource(R.string.schedule_prev_day),
                        onClick = { viewModel.shiftDay(-1) },
                        tint = colors.text.secondary,
                    )
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            scheduleDateHeaderText(selectedDate, relativeDay),
                            style = AppTypography.label,
                            color = colors.text.primary,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        AppIconButton(
                            icon = AppActionIcons.Calendar,
                            contentDescription = stringResource(R.string.schedule_pick_date),
                            onClick = { showDatePicker = true },
                            tint = colors.accent.text,
                            iconSize = 18.dp,
                        )
                    }
                    AppIconButton(
                        icon = AppActionIcons.ChevronRight,
                        contentDescription = stringResource(R.string.schedule_next_day),
                        onClick = { viewModel.shiftDay(1) },
                        enabled = canGoForward,
                        tint = colors.text.secondary,
                    )
                }

                ScheduleWeatherRow(daySchedule)

                when (val state = dayState) {
                    ScheduleDayUiState.Loading -> Unit
                    ScheduleDayUiState.NoSchedule ->
                        ScheduleEmptyState(Icons.Filled.EventBusy, stringResource(R.string.schedule_full_day_empty_no_schedule))
                    ScheduleDayUiState.NotStarted ->
                        ScheduleEmptyState(AppActionIcons.Clock, stringResource(R.string.schedule_full_day_empty_not_started))
                    is ScheduleDayUiState.Events ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .liuliCardMaterial(LiuliShapes.group, dark)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        ) {
                            state.rows.forEachIndexed { index, row ->
                                val event = row.event
                                val msgUuid = event.relatedMessageUUID
                                val clickable = event.eventTypeRaw == SCHEDULE_USER_INTERACTION_TYPE && !msgUuid.isNullOrEmpty()
                                val rowModifier = if (clickable) {
                                    Modifier.clickable(onClickLabel = stringResource(R.string.a11y_schedule_open_conversation)) {
                                        viewModel.onUserInteractionClick(msgUuid)
                                    }
                                } else {
                                    Modifier
                                }
                                ScheduleEventRow(
                                    event = event,
                                    timeState = row.timeState,
                                    isLast = index == state.rows.lastIndex,
                                    modifier = rowModifier,
                                )
                            }
                        }
                }
            }
        }
    }

    if (showDatePicker) {
        ScheduleDatePickerDialog(
            selectedDate = selectedDate,
            earliest = bounds.earliest,
            latest = bounds.latest,
            onConfirm = { viewModel.setDate(it) },
            onDismiss = { showDatePicker = false },
        )
    }
}
