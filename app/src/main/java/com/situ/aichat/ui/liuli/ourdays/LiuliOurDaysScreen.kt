package com.situ.aichat.ui.liuli.ourdays

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import com.situ.aichat.ui.designsystem.AppMenu
import com.situ.aichat.ui.designsystem.AppMenuItem
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBarIcons
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliChip
import com.situ.aichat.ui.liuli.designsystem.LiuliDialogShell
import com.situ.aichat.ui.liuli.designsystem.LiuliSegmented
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageCircleAction
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.ourdays.OurDaysBannerArea
import com.situ.aichat.ui.ourdays.OurDaysEmptyState
import com.situ.aichat.ui.ourdays.OurDaysMonthView
import com.situ.aichat.ui.ourdays.OurDaysSelection
import com.situ.aichat.ui.ourdays.OurDaysUiState
import com.situ.aichat.ui.ourdays.OurDaysViewMode
import com.situ.aichat.ui.ourdays.OurDaysViewModel
import com.situ.aichat.ui.ourdays.OurDaysWeekView
import com.situ.aichat.ui.ourdays.OurDaysYearView

/** 「我们的日子」日历页（琉璃 2.0 脸）。 */
@Composable
fun LiuliOurDaysScreen(
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

    val content = uiState as? OurDaysUiState.Content
    val selection = content?.selection ?: OurDaysSelection.None
    var menuOpen by remember { mutableStateOf(false) }
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.our_days_title)

    LiuliPage(
        title = title,
        onBack = onBack,
        collapsed = true,
        actions = {
            Box {
                LiuliPageCircleAction(
                    onClick = { menuOpen = true },
                    contentDescription = stringResource(R.string.our_days_a11y_more),
                    icon = AppTopBarIcons.More,
                )
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
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = LiuliPageGeometry.navRow + 4.dp),
        ) {
            OurDaysBannerArea(
                backfill = OurDaysViewModel.bannerProgress(backfill, selection),
                monthBackfill = monthBackfill,
                apiMissing = apiMissing,
            )
            LiuliOurDaysCharacterRow(characters, selection, viewModel::select)
            LiuliSegmented(
                options = listOf(OurDaysViewMode.YEAR, OurDaysViewMode.MONTH, OurDaysViewMode.WEEK),
                selected = content?.viewMode ?: OurDaysViewMode.MONTH,
                label = { stringResource(it.liuliLabelRes()) },
                onSelect = viewModel::setViewMode,
                modifier = Modifier.fillMaxWidth().padding(horizontal = LiuliPageGeometry.gutter, vertical = 4.dp),
            )
            LiuliOurDaysViewArea(content, viewModel, onOpenDay, Modifier.weight(1f))
        }
    }

    if (aboutOpen) {
        val colors = AppTheme.colors
        LiuliDialogShell(onDismissRequest = { aboutOpen = false }) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.our_days_menu_about), style = AppTypography.titleSmall, color = colors.text.primary)
                Text(stringResource(R.string.our_days_about_body), style = AppTypography.dialogBody, color = colors.text.secondary)
                LiuliButton(onClick = { aboutOpen = false }, style = LiuliButtonStyle.Prominent, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    }
}

private fun OurDaysViewMode.liuliLabelRes(): Int = when (this) {
    OurDaysViewMode.YEAR -> R.string.our_days_view_year
    OurDaysViewMode.MONTH -> R.string.our_days_view_month
    OurDaysViewMode.WEEK -> R.string.our_days_view_week
}

@Composable
private fun LiuliOurDaysCharacterRow(
    characters: List<CharacterEntity>,
    selection: OurDaysSelection,
    onSelect: (OurDaysSelection) -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = LiuliPageGeometry.gutter, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (characters.size >= 2) {
            LiuliChip(
                selected = selection is OurDaysSelection.All,
                onClick = { onSelect(OurDaysSelection.All) },
                label = stringResource(R.string.our_days_all),
                leading = {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(colors.surface.sunken), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.our_days_all_glyph), style = AppTypography.caption, color = colors.text.secondary)
                    }
                },
            )
        }
        characters.forEach { c ->
            LiuliChip(
                selected = selection == OurDaysSelection.Character(c.uuid),
                onClick = { onSelect(OurDaysSelection.Character(c.uuid)) },
                label = c.name,
                leading = { CharacterAvatar(name = c.name, avatarPath = c.avatarPath, size = 20.dp) },
            )
        }
    }
}

@Composable
private fun LiuliOurDaysViewArea(
    content: OurDaysUiState.Content?,
    viewModel: OurDaysViewModel,
    onOpenDay: (String, String) -> Unit,
    modifier: Modifier,
) {
    if (content == null) { Box(modifier); return }
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
            } else Modifier,
        ),
    ) {
        AnimatedContent(
            targetState = content,
            contentKey = { it.viewMode to OurDayKey.keyOf(it.period.start) },
            transitionSpec = { liuliOurDaysTransition(reduceMotion) },
            label = "liuliOurDaysView",
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

private fun AnimatedContentTransitionScope<OurDaysUiState.Content>.liuliOurDaysTransition(reduceMotion: Boolean): ContentTransform {
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
