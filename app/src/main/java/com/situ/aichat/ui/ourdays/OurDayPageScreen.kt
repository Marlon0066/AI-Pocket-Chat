package com.situ.aichat.ui.ourdays

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDialogTone
import com.situ.aichat.ui.designsystem.AppMenu
import com.situ.aichat.ui.designsystem.AppMenuItem
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.AppTopBarAction
import com.situ.aichat.ui.designsystem.AppTopBarIcons
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.grainSurface
import java.time.ZoneId

/**
 * 一天的页（卷三图纸 §4.6·提案 D-7·帧 7 / 8）：日头 + 手记纸面（单角色·底行改一改 / 重写 / 写下时刻；全部模式每段一张纸面）+
 * 「这一天」事实层 + 页脚（[OurDayFactsSection] / [OurDayFooterRow]）；空日卡；屏级 改一改 sheet + 重写 / 删除两确认框（W-12）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OurDayPageScreen(
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
    val colors = AppTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    var rewriteOpen by rememberSaveable { mutableStateOf(false) }
    var deleteOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.toast.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
    LaunchedEffect(Unit) { viewModel.saved.collect { haptics.success() } }
    val canEdit = !state.isAll && !state.isToday && !state.isFuture && state.row != null
    val name = state.characterName.orEmpty()

    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = OurDaysFormat.date(state.date, stringResource(R.string.our_days_fmt_md)),
                onBack = onBack,
                lifted = scrollState.value > 0,
                actions = {
                    if (canEdit) {
                        Box {
                            AppTopBarAction(AppTopBarIcons.More, stringResource(R.string.our_days_a11y_more), onClick = { menuOpen = true })
                            AppMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                                AppMenuItem(stringResource(R.string.our_days_action_edit), onClick = { menuOpen = false; viewModel.openSheet() })
                                AppMenuItem(stringResource(R.string.our_days_action_rewrite), onClick = { menuOpen = false; rewriteOpen = true })
                                AppMenuItem(stringResource(R.string.our_days_edit_delete), onClick = { menuOpen = false; deleteOpen = true }, danger = true)
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(colors.surface.base)
                .grainSurface()
                .verticalScroll(scrollState)
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        ) {
            DayHead(state, name)
            if (!state.loaded) return@Column
            val card = state.card
            if (state.isAll && card != null) {
                card.segments.forEach { seg ->
                    NotePaper(
                        card = seg.card, name = seg.name, avatarPath = seg.avatarPath, busy = false, showActions = false,
                        onEdit = {}, onRewrite = {}, onRetry = {}, onWriteAgain = {},
                        openTheirDay = { onOpenDay(seg.characterUuid, card.key) },
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                if (card.status == CardStatus.EMPTY) OurDaysEmptyDayCard(null, "", Modifier.padding(top = 10.dp))
                card.userDiary?.let { UserDiaryPaper(it) }
            } else if (card != null) {
                if (state.row == null && !state.isToday) {
                    OurDaysEmptyDayCard(state.characterName, card.scheduleLine, Modifier.padding(top = 10.dp))
                } else {
                    NotePaper(
                        card = card, name = name, avatarPath = state.avatarPath, busy = state.busy, showActions = true,
                        hidden = state.row?.hiddenFromMemory == true,
                        onEdit = viewModel::openSheet, onRewrite = { rewriteOpen = true }, onRetry = viewModel::retry, onWriteAgain = viewModel::rewrite,
                        openTheirDay = null, modifier = Modifier.padding(top = 10.dp),
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
        AppDialog(
            onDismissRequest = { rewriteOpen = false },
            title = stringResource(R.string.our_days_rewrite_title),
            body = stringResource(R.string.our_days_rewrite_body, name),
            confirmText = stringResource(R.string.our_days_action_rewrite),
            onConfirm = { rewriteOpen = false; viewModel.rewrite() },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { rewriteOpen = false },
        )
    }
    if (deleteOpen) {
        AppDialog(
            onDismissRequest = { deleteOpen = false },
            title = stringResource(R.string.our_days_delete_title),
            body = stringResource(R.string.our_days_delete_body),
            confirmText = stringResource(R.string.action_delete),
            confirmTone = AppDialogTone.Danger,
            onConfirm = { deleteOpen = false; viewModel.delete() },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { deleteOpen = false },
        )
    }
}

/** 日头（§4.6-1）：44sp 日数 + 「yyyy 年 M 月 · 周几」/「副行 · 和{名}的第 N 天」（强调段陶土深档 Medium）。 */
@Composable
private fun DayHead(state: OurDayPageUiState, name: String) {
    val colors = AppTheme.colors
    val yearMonth = OurDaysFormat.date(state.date, stringResource(R.string.our_days_fmt_year_month))
    val weekday = OurDaysFormat.date(state.date, stringResource(R.string.our_days_fmt_weekday))
    val nth = state.nthDay?.takeIf { !state.isAll }?.let { stringResource(R.string.our_days_nth_day, name, it) }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)) {
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

/** 手记纸面（§4.6-2）：头行头像 + 「{名}的手记」+ hidden 标；正文按状态；底行改一改 / 重写 / 写下时刻（单角色 NORMAL）。 */
@Composable
private fun NotePaper(
    card: DayCardModel, name: String, avatarPath: String?, busy: Boolean, showActions: Boolean, hidden: Boolean = false,
    onEdit: () -> Unit, onRewrite: () -> Unit, onRetry: () -> Unit, onWriteAgain: () -> Unit,
    openTheirDay: (() -> Unit)?, modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val small = AppTypography.caption.copy(fontSize = 12.5.sp)
    Column(modifier.fillMaxWidth().clip(AppShapes.medium).background(colors.surface.sunken).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            CharacterAvatar(name = name, avatarPath = avatarPath, size = 22.dp)
            Text(stringResource(R.string.our_days_note_by, name), style = AppTypography.caption.copy(fontSize = 12.sp), color = colors.text.secondary)
            Spacer(Modifier.weight(1f))
            // R1 🔵-4：标记跟「别让 TA 记」这件事本身走，不跟手记状态走——hidden 且手记空白时 status 会落 FAILED（页脚仍 HIDDEN）。
            if (hidden) {
                Text(
                    stringResource(R.string.our_days_only_you), style = AppTypography.caption, color = colors.text.tertiary,
                    modifier = Modifier.clip(AppShapes.full).background(colors.surface.raised).padding(horizontal = 8.dp, vertical = 2.dp),
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
                if (showActions) AppButton(onClick = onRetry, style = AppButtonStyle.Text) { Text(stringResource(R.string.our_days_action_retry)) }
            }
            card.status == CardStatus.DELETED -> {
                Text(stringResource(R.string.our_days_note_deleted), style = AppTypography.secondary, color = colors.text.tertiary)
                if (showActions) AppButton(onClick = onWriteAgain, style = AppButtonStyle.Text) { Text(stringResource(R.string.our_days_action_write_again)) }
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
private fun UserDiaryPaper(diary: UserDiaryLine) {
    val colors = AppTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
        Box(Modifier.size(24.dp).clip(AppShapes.full).background(colors.surface.sunken), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.our_days_me_glyph), style = AppTypography.caption, color = colors.text.secondary)
        }
        Text(stringResource(R.string.our_days_your_diary), style = AppTypography.secondary.copy(fontSize = 12.5.sp), color = colors.text.secondary)
        Text(" · " + (diary.moodEmoji ?: "") + " 「" + diary.firstLine + "」", style = AppTypography.secondary.copy(fontSize = 12.5.sp), color = colors.text.tertiary)
    }
}
