package com.situ.aichat.ui.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.model.imagePaths
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDialogTone
import com.situ.aichat.ui.designsystem.AppMenu
import com.situ.aichat.ui.designsystem.AppMenuItem
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.AppTopBarAction
import com.situ.aichat.ui.designsystem.AppTopBarIcons

/**
 * 日记详情（方向 C · 书信画报融合）：全幅顶部心情水彩晕染天头（36sp 等宽日号 + 竖发丝线 + M月·周几 / yyyy年·HH:mm +
 * 迷你心情邮票）+ 元信息行 + 正文（可选中·AI 代写/来信走楷体 + 信笺双线抬头）+ 自适应画报图库 +
 * 落款印章（「—— 名 · 写于 HH:mm」+ 单字圆印 / 「记于 HH:mm」）+ 互动区（点线分隔）。Menu：编辑 / 删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: DiaryDetailViewModel = hiltViewModel(),
) {
    val entryWC by viewModel.entry.collectAsStateWithLifecycle()
    val chars by viewModel.charactersByUuid.collectAsStateWithLifecycle()
    var menuExpanded by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    val lifted by remember { derivedStateOf { scrollState.value > 0 } }
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.diary_nav_title),
                onBack = onBack,
                lifted = lifted,
                actions = {
                    val ewc = entryWC
                    if (ewc != null) {
                        AppTopBarAction(
                            icon = AppTopBarIcons.More,
                            contentDescription = stringResource(R.string.action_more),
                            onClick = { menuExpanded = true },
                        )
                        AppMenu(expanded = menuExpanded, onDismiss = { menuExpanded = false }) {
                            AppMenuItem(
                                text = stringResource(R.string.action_edit),
                                leadingIcon = AppActionIcons.Edit,
                                onClick = { menuExpanded = false; onEdit(ewc.entry.uuid) },
                            )
                            AppMenuItem(
                                text = stringResource(R.string.action_delete),
                                leadingIcon = AppActionIcons.Delete,
                                danger = true,
                                onClick = { menuExpanded = false; showDelete = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val ewc = entryWC
        if (ewc == null) {
            Box(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        val entry = ewc.entry
        val colors = AppTheme.colors
        val authorDisplay = diaryAuthorDisplayOf(entry, chars)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState),
        ) {
            // 全幅水彩晕染天头（自然淡入纸面，不套硬圆角灰盒）
            DetailEditorialWashHeader(entry)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenGutter)
                    .padding(top = 6.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (authorDisplay?.isOrphan == true) {
                    Text(
                        stringResource(R.string.diary_exchange_orphan_label),
                        style = AppTheme.typography.caption,
                        color = colors.text.secondary,
                    )
                }
                DetailMetaRow(entry, ewc.comments.size)

                DiaryDetailBody(
                    content = entry.content.ifEmpty { stringResource(R.string.diary_no_content) },
                    style = diaryDetailBodyStyle(entry),
                    isLetter = entry.authorCharacterUuid != null,
                )

                val images = entry.imagePaths
                if (images.isNotEmpty()) DiaryDetailImageGrid(images)

                DiaryDetailSignature(authorDisplay, entry.timestamp)

                val noteAuthor = diaryExchangeNoteAuthor(entry, ewc.comments, chars)
                if (ewc.reactions.isNotEmpty() || ewc.comments.isNotEmpty() || noteAuthor != null) {
                    DiaryDottedDivider()
                }
                if (ewc.reactions.isNotEmpty()) {
                    DiaryReactionRow(ewc.reactions, chars)
                }
                if (ewc.comments.isNotEmpty()) {
                    Text(
                        stringResource(R.string.diary_comments_header, ewc.comments.size),
                        style = AppTheme.typography.label,
                        color = colors.text.primary,
                        modifier = Modifier.semantics { heading() },
                    )
                    DiaryCommentThreadSection(
                        comments = ewc.comments,
                        charactersByUuid = chars,
                        onDeleteComment = viewModel::deleteComment,
                        onReply = viewModel::replyToComment,
                    )
                }
                if (noteAuthor != null) {
                    ExchangeNoteAffordance(
                        authorName = noteAuthor.name,
                        onSend = viewModel::commentOnEntry,
                    )
                }
            }
        }
    }

    if (showDelete) {
        AppDialog(
            onDismissRequest = { showDelete = false },
            title = stringResource(R.string.diary_delete_title),
            body = stringResource(R.string.diary_delete_message),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = { showDelete = false; viewModel.delete(onDone = onBack) },
            confirmTone = AppDialogTone.Danger,
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showDelete = false },
        )
    }
}

/** 全幅水彩晕染日期天头：顶部情绪浅色纵向自然淡入纸底，36sp 等宽日号 + 竖发丝线 + 日期时间 + 右侧迷你心情邮票。 */
@Composable
private fun DetailEditorialWashHeader(entry: DiaryEntryEntity) {
    val colors = AppTheme.colors
    val washBase = diaryMoodTint(entry.moodEmoji) ?: colors.accent.container.copy(alpha = if (colors.isDark) 0.16f else 0.24f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        washBase.copy(alpha = if (colors.isDark) 0.42f else 0.72f),
                        washBase.copy(alpha = 0f),
                    ),
                ),
            )
            .padding(horizontal = AppSpacing.screenGutter, vertical = 18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatDiaryDate(entry.timestamp, stringResource(R.string.diary_fmt_day_number)),
                style = AppTheme.typography.titleLarge.copy(
                    fontSize = 36.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum",
                ),
                color = colors.text.primary,
            )
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(34.dp)
                    .background(colors.text.tertiary.copy(alpha = 0.35f)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    formatDiaryDate(entry.timestamp, stringResource(R.string.diary_fmt_detail_sub)),
                    style = AppTheme.typography.label,
                    color = colors.text.primary,
                )
                val year = formatDiaryDate(entry.timestamp, stringResource(R.string.diary_fmt_month_section_year))
                val time = formatDiaryDate(entry.timestamp, "HH:mm")
                val moodText = entry.moodText?.takeIf { it.isNotBlank() }
                Text(
                    if (moodText != null) "$year · $time · $moodText" else "$year · $time",
                    style = AppTheme.typography.captionNumeric,
                    color = colors.text.primary,
                )
            }
            entry.moodEmoji?.takeIf { it.isNotEmpty() }?.let { emoji ->
                Spacer(Modifier.width(8.dp))
                DiaryMiniMoodStamp(emoji = emoji, timestamp = entry.timestamp)
            }
        }
    }
}
