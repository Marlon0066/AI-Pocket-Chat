package com.situ.aichat.ui.liuli.ourdays

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ourdays.OurDayKey
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppMenu
import com.situ.aichat.ui.designsystem.AppMenuItem
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBarIcons
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliDialogShell
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.liuliCardMaterial
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageCircleAction
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberScrollCollapsed
import com.situ.aichat.ui.ourdays.CardStatus
import com.situ.aichat.ui.ourdays.DayCardModel
import com.situ.aichat.ui.ourdays.OurDayEditSheet
import com.situ.aichat.ui.ourdays.OurDayFactsSection
import com.situ.aichat.ui.ourdays.OurDayFooterRow
import com.situ.aichat.ui.ourdays.OurDayPageUiState
import com.situ.aichat.ui.ourdays.OurDayPageViewModel
import com.situ.aichat.ui.ourdays.OurDaysEmptyDayCard
import com.situ.aichat.ui.ourdays.OurDaysFormat
import com.situ.aichat.ui.ourdays.UserDiaryLine
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import java.time.ZoneId

/** 一天的页（琉璃 2.0 脸）。 */
@Composable
fun LiuliOurDayPageScreen(
    onBack: () -> Unit,
    onOpenDay: (characterUuid: String, dayKey: String) -> Unit,
    onOpenMeetings: (String) -> Unit,
    onOpenPromises: (String) -> Unit,
    onOpenMoments: (String, String) -> Unit,
    onOpenDiary: (String) -> Unit,
    onOpenSchedule: (characterUuid: String, dayKey: String) -> Unit,
    viewModel: OurDayPageViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetOpen by viewModel.sheetOpen.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val draftHidden by viewModel.draftHidden.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalAppHaptics.current
    val dark = LocalIsDarkTheme.current
    var menuOpen by remember { mutableStateOf(false) }
    var rewriteOpen by rememberSaveable { mutableStateOf(false) }
    var deleteOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.toast.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
    LaunchedEffect(Unit) { viewModel.saved.collect { haptics.success() } }

    val canEdit = !state.isAll && !state.isToday && !state.isFuture && state.row != null
    val name = state.characterName.orEmpty()
    val scrollState = rememberScrollState()
    val collapsed = rememberScrollCollapsed(scrollState)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val title = OurDaysFormat.date(state.date, stringResource(R.string.our_days_fmt_md))

    LiuliPage(
        title = title,
        onBack = onBack,
        collapsed = collapsed,
        actions = {
            if (canEdit) {
                Box {
                    LiuliPageCircleAction(
                        onClick = { menuOpen = true },
                        contentDescription = stringResource(R.string.our_days_a11y_more),
                        icon = AppTopBarIcons.More,
                    )
                    AppMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                        AppMenuItem(stringResource(R.string.our_days_action_edit), onClick = { menuOpen = false; viewModel.openSheet() })
                        AppMenuItem(stringResource(R.string.our_days_action_rewrite), onClick = { menuOpen = false; rewriteOpen = true })
                        AppMenuItem(stringResource(R.string.our_days_edit_delete), onClick = { menuOpen = false; deleteOpen = true }, danger = true)
                    }
                }
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = LiuliPageGeometry.gutter)
                .padding(top = LiuliPageGeometry.navRow + 4.dp, bottom = navBottom + LiuliPageGeometry.pageBottom),
        ) {
            LiuliDayHead(state, name)
            if (!state.loaded) return@Column
            val card = state.card
            if (state.isAll && card != null) {
                card.segments.forEach { seg ->
                    LiuliNotePaper(
                        card = seg.card, name = seg.name, avatarPath = seg.avatarPath, busy = false, showActions = false,
                        dark = dark, onEdit = {}, onRewrite = {}, onRetry = {}, onWriteAgain = {},
                        openTheirDay = { onOpenDay(seg.characterUuid, card.key) },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                if (card.status == CardStatus.EMPTY) OurDaysEmptyDayCard(null, "", Modifier.padding(top = 12.dp))
                card.userDiary?.let { LiuliUserDiaryLine(it) }
            } else if (card != null) {
                if (state.row == null && !state.isToday) {
                    OurDaysEmptyDayCard(state.characterName, card.scheduleLine, Modifier.padding(top = 12.dp))
                } else {
                    LiuliNotePaper(
                        card = card, name = name, avatarPath = state.avatarPath, busy = state.busy, showActions = true,
                        hidden = state.row?.hiddenFromMemory == true, dark = dark,
                        onEdit = viewModel::openSheet, onRewrite = { rewriteOpen = true }, onRetry = viewModel::retry, onWriteAgain = viewModel::rewrite,
                        openTheirDay = null, modifier = Modifier.padding(top = 12.dp),
                    )
                    if (state.facts.isNotEmpty()) {
                        OurDayFactsSection(state.facts, state.characterUuid.orEmpty(), OurDayKey.keyOf(state.date), onOpenMeetings, onOpenPromises, onOpenMoments, onOpenDiary, onOpenSchedule)
                    }
                    OurDayFooterRow(state.footer, card.hasMeeting)
                }
            }
        }
    }

    if (sheetOpen) {
        OurDayEditSheet(
            date = state.date, draft = draft, draftHidden = draftHidden, isDirty = viewModel::isDirty,
            onDraftChange = viewModel::updateDraft, onHiddenChange = viewModel::updateDraftHidden,
            onSave = viewModel::save, onDelete = { deleteOpen = true }, onClose = viewModel::closeSheet,
        )
    }
    if (rewriteOpen) {
        LiuliDayConfirmDialog(
            title = stringResource(R.string.our_days_rewrite_title),
            body = stringResource(R.string.our_days_rewrite_body, name),
            confirmText = stringResource(R.string.our_days_action_rewrite),
            danger = false,
            onConfirm = { rewriteOpen = false; viewModel.rewrite() },
            onDismiss = { rewriteOpen = false },
        )
    }
    if (deleteOpen) {
        LiuliDayConfirmDialog(
            title = stringResource(R.string.our_days_delete_title),
            body = stringResource(R.string.our_days_delete_body),
            confirmText = stringResource(R.string.action_delete),
            danger = true,
            onConfirm = { deleteOpen = false; viewModel.delete() },
            onDismiss = { deleteOpen = false },
        )
    }
}

@Composable
private fun LiuliDayHead(state: OurDayPageUiState, name: String) {
    val colors = AppTheme.colors
    val yearMonth = OurDaysFormat.date(state.date, stringResource(R.string.our_days_fmt_year_month))
    val weekday = OurDaysFormat.date(state.date, stringResource(R.string.our_days_fmt_weekday))
    val nth = state.nthDay?.takeIf { !state.isAll }?.let { stringResource(R.string.our_days_nth_day, name, it) }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
        Text(
            "${state.date.dayOfMonth}",
            style = AppTypography.titleLarge.copy(fontSize = 44.sp, lineHeight = 44.sp, letterSpacing = (-1).sp, fontFeatureSettings = "tnum"),
            color = colors.text.primary,
        )
        Column(Modifier.padding(bottom = 4.dp)) {
            Text("$yearMonth · $weekday", style = AppTypography.label, color = colors.text.primary)
            val decor = state.decor
            if (decor != null || nth != null) {
                Text(
                    buildAnnotatedString {
                        if (decor != null) {
                            if (decor.emphasized) withStyle(SpanStyle(color = colors.accent.text, fontWeight = FontWeight.Medium)) { append(decor.subtitle) } else append(decor.subtitle)
                        }
                        if (nth != null) append(if (decor != null) " · $nth" else nth)
                    },
                    style = AppTypography.secondary, color = colors.text.secondary,
                )
            }
        }
    }
}

@Composable
private fun LiuliNotePaper(
    card: DayCardModel, name: String, avatarPath: String?, busy: Boolean, showActions: Boolean,
    dark: Boolean, onEdit: () -> Unit, onRewrite: () -> Unit, onRetry: () -> Unit, onWriteAgain: () -> Unit,
    openTheirDay: (() -> Unit)?, modifier: Modifier = Modifier, hidden: Boolean = false,
) {
    val colors = AppTheme.colors
    val small = AppTypography.caption.copy(fontSize = 12.5.sp)
    Column(modifier.fillMaxWidth().liuliCardMaterial(LiuliShapes.group, dark).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            CharacterAvatar(name = name, avatarPath = avatarPath, size = 22.dp)
            Text(stringResource(R.string.our_days_note_by, name), style = AppTypography.caption.copy(fontSize = 12.sp), color = colors.text.secondary)
            Spacer(Modifier.weight(1f))
            if (hidden) {
                Text(
                    stringResource(R.string.our_days_only_you), style = AppTypography.caption, color = colors.text.tertiary,
                    modifier = Modifier.clip(AppShapes.full).background(colors.surface.sunken).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        when {
            busy -> Text(stringResource(R.string.our_days_note_writing), style = AppTypography.secondary, color = colors.text.tertiary)
            card.status == CardStatus.NORMAL || card.status == CardStatus.HIDDEN_NORMAL ->
                Text(card.note, style = AppTypography.kaiQuote.copy(fontSize = 15.sp, lineHeight = 28.sp), color = colors.text.primary)
            card.status == CardStatus.TODAY -> Text(stringResource(R.string.our_days_note_today), style = AppTypography.secondary, color = colors.text.tertiary)
            card.status == CardStatus.FAILED -> {
                Text(stringResource(R.string.our_days_note_failed), style = AppTypography.secondary, color = colors.text.tertiary)
                if (showActions) LiuliButton(onClick = onRetry, style = LiuliButtonStyle.Text) { Text(stringResource(R.string.our_days_action_retry)) }
            }
            card.status == CardStatus.DELETED -> {
                Text(stringResource(R.string.our_days_note_deleted), style = AppTypography.secondary, color = colors.text.tertiary)
                if (showActions) LiuliButton(onClick = onWriteAgain, style = LiuliButtonStyle.Text) { Text(stringResource(R.string.our_days_action_write_again)) }
            }
            else -> Text(stringResource(R.string.our_days_no_record), style = AppTypography.secondary, color = colors.text.tertiary)
        }
        if (showActions && !busy && (card.status == CardStatus.NORMAL || card.status == CardStatus.HIDDEN_NORMAL)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 10.dp)) {
                val editLabel = stringResource(R.string.our_days_action_edit)
                val rewriteLabel = stringResource(R.string.our_days_action_rewrite)
                Text(editLabel, style = small, color = colors.accent.text, modifier = Modifier.clickable(onClickLabel = editLabel) { onEdit() }.padding(4.dp))
                Text(rewriteLabel, style = small, color = colors.accent.text, modifier = Modifier.clickable(onClickLabel = rewriteLabel) { onRewrite() }.padding(4.dp))
                Spacer(Modifier.weight(1f))
                card.generatedAt?.let { at ->
                    val zone = remember { ZoneId.systemDefault() }
                    Text(stringResource(R.string.our_days_note_written, OurDaysFormat.time(at, zone, stringResource(R.string.our_days_fmt_written))), style = small, color = colors.text.tertiary)
                }
            }
        }
        if (openTheirDay != null) {
            val label = stringResource(R.string.our_days_open_their_day)
            Text(label, style = AppTypography.caption.copy(fontSize = 12.sp), color = colors.accent.text, modifier = Modifier.padding(top = 8.dp).clickable(onClickLabel = label) { openTheirDay() }.padding(4.dp))
        }
    }
}

@Composable
private fun LiuliUserDiaryLine(diary: UserDiaryLine) {
    val colors = AppTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
        Box(Modifier.size(24.dp).clip(AppShapes.full).background(colors.surface.sunken), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.our_days_me_glyph), style = AppTypography.caption, color = colors.text.secondary)
        }
        Text(stringResource(R.string.our_days_your_diary), style = AppTypography.secondary.copy(fontSize = 12.5.sp), color = colors.text.secondary)
        Text(" · " + (diary.moodEmoji ?: "") + " 「" + diary.firstLine + "」", style = AppTypography.secondary.copy(fontSize = 12.5.sp), color = colors.text.tertiary)
    }
}

@Composable
private fun LiuliDayConfirmDialog(
    title: String,
    body: String,
    confirmText: String,
    danger: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    LiuliDialogShell(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = AppTypography.titleSmall, color = colors.text.primary)
            Text(body, style = AppTypography.dialogBody, color = colors.text.secondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiuliButton(onClick = onDismiss, style = LiuliButtonStyle.Glass, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
                LiuliButton(
                    onClick = onConfirm,
                    style = if (danger) LiuliButtonStyle.Glass else LiuliButtonStyle.Prominent,
                    danger = danger,
                    modifier = Modifier.weight(1f),
                ) { Text(confirmText) }
            }
        }
    }
}
