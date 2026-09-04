package com.situ.aichat.ui.ourdays

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.ourdays.OurDayKey
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppChoiceChip
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppMenu
import com.situ.aichat.ui.designsystem.AppMenuItem
import com.situ.aichat.ui.designsystem.AppSegmentedControl
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.AppTopBarAction
import com.situ.aichat.ui.designsystem.AppTopBarIcons
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.grainSurface

/**
 * 日历页壳（卷三图纸 §4.2·提案 D-3）：顶栏 + 菜单（回到今天 / 补写这个月 / 关于）+ 横幅区 + 角色行 + 三段视图切换 +
 * 视图区（`AnimatedContent` 翻期滑入 / 年→月缩放 / 其余淡入·reduceMotion 瞬时）+ 月 / 周横滑翻期（50dp 阈·禁 Pager）+ 关于框。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OurDaysScreen(
    onBack: () -> Unit,
    onOpenDay: (characterUuid: String, dayKey: String) -> Unit,
    viewModel: OurDaysViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val backfill by viewModel.backfill.collectAsStateWithLifecycle()
    val monthBackfill by viewModel.monthBackfill.collectAsStateWithLifecycle()
    val apiMissing by viewModel.apiMissing.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshApiMissing() }
    val colors = AppTheme.colors
    val content = uiState as? OurDaysUiState.Content
    val selection = content?.selection ?: OurDaysSelection.None
    var menuOpen by remember { mutableStateOf(false) }
    var aboutOpen by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.our_days_title),
                onBack = onBack,
                actions = {
                    Box {
                        AppTopBarAction(AppTopBarIcons.More, stringResource(R.string.our_days_a11y_more), onClick = { menuOpen = true })
                        AppMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                            AppMenuItem(stringResource(R.string.our_days_menu_today), onClick = { menuOpen = false; viewModel.goToday() })
                            if (selection is OurDaysSelection.Character) {
                                AppMenuItem(
                                    stringResource(R.string.our_days_menu_backfill_month),
                                    onClick = { menuOpen = false; viewModel.backfillMonth() },
                                    enabled = monthBackfill == null,
                                )
                            }
                            AppMenuItem(stringResource(R.string.our_days_menu_about), onClick = { menuOpen = false; aboutOpen = true })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(colors.surface.base).grainSurface()) {
            OurDaysBannerArea(
                backfill = OurDaysViewModel.bannerProgress(backfill, selection),
                monthBackfill = monthBackfill,
                apiMissing = apiMissing,
            )
            OurDaysCharacterRow(characters = characters, selection = selection, onSelect = viewModel::select)
            AppSegmentedControl(
                options = listOf(OurDaysViewMode.YEAR, OurDaysViewMode.MONTH, OurDaysViewMode.WEEK),
                selected = content?.viewMode ?: OurDaysViewMode.MONTH,
                onSelect = viewModel::setViewMode,
                modifier = Modifier.padding(horizontal = 20.dp),
                label = { stringResource(it.labelRes()) },
            )
            OurDaysViewArea(content = content, viewModel = viewModel, onOpenDay = onOpenDay, modifier = Modifier.weight(1f))
        }
    }

    if (aboutOpen) {
        AppDialog(
            onDismissRequest = { aboutOpen = false },
            title = stringResource(R.string.our_days_menu_about),
            body = stringResource(R.string.our_days_about_body),
            confirmText = stringResource(R.string.action_close),
            onConfirm = { aboutOpen = false },
        )
    }
}

private fun OurDaysViewMode.labelRes(): Int = when (this) {
    OurDaysViewMode.YEAR -> R.string.our_days_view_year
    OurDaysViewMode.MONTH -> R.string.our_days_view_month
    OurDaysViewMode.WEEK -> R.string.our_days_view_week
}

/** 角色行（§4.2-2）：「全部」chip 只在角色 ≥2 时出现（恒最左）；每角色 22dp 头像 + 名。 */
@Composable
internal fun OurDaysCharacterRow(characters: List<CharacterEntity>, selection: OurDaysSelection, onSelect: (OurDaysSelection) -> Unit) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (characters.size >= 2) {
            AppChoiceChip(
                selected = selection is OurDaysSelection.All,
                onClick = { onSelect(OurDaysSelection.All) },
                label = stringResource(R.string.our_days_all),
                leading = {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(colors.surface.sunken), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.our_days_all_glyph), style = AppTypography.caption, color = colors.text.secondary)
                    }
                },
            )
        }
        characters.forEach { c ->
            AppChoiceChip(
                selected = selection == OurDaysSelection.Character(c.uuid),
                onClick = { onSelect(OurDaysSelection.Character(c.uuid)) },
                label = c.name,
                leading = { CharacterAvatar(name = c.name, avatarPath = c.avatarPath, size = 22.dp) },
            )
        }
    }
}

@Composable
private fun OurDaysViewArea(content: OurDaysUiState.Content?, viewModel: OurDaysViewModel, onOpenDay: (String, String) -> Unit, modifier: Modifier) {
    if (content == null) {
        Box(modifier)
        return
    }
    val selection = content.selection
    if (selection is OurDaysSelection.None) {
        OurDaysEmptyState(stringResource(R.string.our_days_empty_no_characters_title), stringResource(R.string.our_days_empty_no_characters_body), modifier)
        return
    }
    if (!content.hasAnyRow && selection is OurDaysSelection.Character) {
        OurDaysEmptyState(stringResource(R.string.our_days_empty_character_title), stringResource(R.string.our_days_empty_hint), modifier)
        return
    }
    val reduceMotion = rememberReduceMotion()
    val haptics = LocalAppHaptics.current
    val mode = content.viewMode
    Box(
        modifier.then(
            if (mode != OurDaysViewMode.YEAR) {
                // 翻期手势（逐字照 ScheduleFullDayScreen：水平 touch-slop 越过才认领·阈 50dp）。
                Modifier.pointerInput(mode) {
                    var totalDx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDx = 0f },
                        onDragEnd = {
                            val threshold = 50.dp.toPx()
                            when {
                                totalDx <= -threshold -> { haptics.light(); viewModel.shiftPeriod(1) }
                                totalDx >= threshold -> { haptics.light(); viewModel.shiftPeriod(-1) }
                            }
                        },
                        onHorizontalDrag = { _, dragAmount -> totalDx += dragAmount },
                    )
                }
            } else {
                Modifier
            },
        ),
    ) {
        AnimatedContent(
            targetState = content,
            contentKey = { it.viewMode to OurDayKey.keyOf(it.period.start) },
            transitionSpec = { ourDaysTransition(reduceMotion) },
            label = "ourDaysView",
        ) { state ->
            val uuid = (state.selection as? OurDaysSelection.Character)?.uuid
            val allMode = state.selection is OurDaysSelection.All
            val containsToday = viewModel.today in state.period
            when (state.viewMode) {
                OurDaysViewMode.MONTH -> state.month?.let { m ->
                    OurDaysMonthView(m, containsToday, allMode, uuid, state.characterName, viewModel::setAnchor, viewModel::shiftPeriod, viewModel::goToday, onOpenDay)
                }
                OurDaysViewMode.WEEK -> state.week?.let { w ->
                    OurDaysWeekView(w, containsToday, allMode, uuid, state.characterName, viewModel::setAnchor, viewModel::shiftPeriod, viewModel::goToday, onOpenDay)
                }
                OurDaysViewMode.YEAR -> state.year?.let { y ->
                    OurDaysYearView(y, containsToday, allMode, state.characterName, viewModel::shiftPeriod, viewModel::goToday, viewModel::openMonth)
                }
            }
        }
    }
}

/** 转场（§4.2 锁定）：同视图翻期 = 1/3 位移滑入 + 淡入（gentle / effectMedium）；年→月 = 0.92 缩放；其余淡入淡出；reduceMotion = None。 */
private fun AnimatedContentTransitionScope<OurDaysUiState.Content>.ourDaysTransition(reduceMotion: Boolean): ContentTransform {
    if (reduceMotion) return EnterTransition.None togetherWith ExitTransition.None
    val from = initialState
    val to = targetState
    return when {
        from.viewMode == to.viewMode -> {
            val dir = if (to.period.start > from.period.start) 1 else -1
            (slideInHorizontally(AppMotion.gentleSpring(IntOffset.VisibilityThreshold)) { dir * it / 3 } + fadeIn(AppMotion.effectMediumSpring())) togetherWith
                (slideOutHorizontally(AppMotion.gentleSpring(IntOffset.VisibilityThreshold)) { -dir * it / 3 } + fadeOut(AppMotion.effectMediumSpring()))
        }
        from.viewMode == OurDaysViewMode.YEAR && to.viewMode == OurDaysViewMode.MONTH ->
            (scaleIn(AppMotion.gentleSpring(), initialScale = 0.92f) + fadeIn(AppMotion.effectMediumSpring())) togetherWith fadeOut(AppMotion.effectMediumSpring())
        else -> fadeIn(AppMotion.effectMediumSpring()) togetherWith fadeOut(AppMotion.effectMediumSpring())
    }
}
