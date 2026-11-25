package com.situ.aichat.ui.liuli.diary

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.DiaryCommentEntity
import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.diary.DiaryMiniMoodStamp
import com.situ.aichat.ui.diary.canReply
import com.situ.aichat.ui.diary.diaryCommentAuthorName
import com.situ.aichat.ui.diary.diaryCommentRelativeTime
import com.situ.aichat.ui.diary.diaryMoodTint
import com.situ.aichat.ui.diary.formatDiaryDate
import com.situ.aichat.ui.diary.groupDiaryCommentThreads
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliDialog
import com.situ.aichat.ui.liuli.designsystem.LiuliField
import com.situ.aichat.ui.liuli.designsystem.LiuliMaterials
import com.situ.aichat.ui.liuli.designsystem.LiuliMenuEntry
import com.situ.aichat.ui.liuli.designsystem.LiuliPopupMenu
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.liuliCardMaterial
import com.situ.aichat.ui.liuli.page.LiuliGroupHeader
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.liuliTouchHeight
import com.situ.aichat.ui.theme.LocalIsDarkTheme

// 琉璃日记详情的心情日期头 / 评论组 / 评论行 / 行内回复（琉璃 2.0 卷六·一 §4.3 · 方向 C 升级）。

private val DATE_HEAD_PAD_H = 18.dp
private val DATE_HEAD_PAD_V = 16.dp
private val COMMENT_CARD_PAD_V = 14.dp
private val COMMENT_GAP = 12.dp
private val COMMENT_REPLY_INDENT = 42.dp
private val COMMENT_AVATAR = 32.dp
private val COMMENT_AVATAR_GAP = 10.dp

/**
 * 方向 C 琉璃水彩晕染日期头：半透明玻璃感卡 + 心情水彩对角轻透晕染 + 36sp 等宽日号 + 竖发丝线 +
 * 日期时间 + 右侧迷你心情邮票。情绪色上的字一律 `text.primary`（WCAG 对比度保证）。
 */
@Composable
internal fun LiuliDiaryDateHead(entry: DiaryEntryEntity) {
    val colors = AppTheme.colors
    val dark = LocalIsDarkTheme.current
    val tint = diaryMoodTint(entry.moodEmoji)
    Box(
        Modifier
            .fillMaxWidth()
            .liuliCardMaterial(LiuliShapes.medium, dark)
            .then(
                if (tint != null) {
                    Modifier.background(
                        Brush.linearGradient(
                            listOf(
                                tint.copy(alpha = if (dark) 0.44f else 0.72f),
                                tint.copy(alpha = 0f),
                            ),
                        ),
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = DATE_HEAD_PAD_H, vertical = DATE_HEAD_PAD_V),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val day = formatDiaryDate(entry.timestamp, stringResource(R.string.diary_fmt_day_number))
            Text(
                day,
                style = AppTypography.titleLarge.copy(
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
                val sub = formatDiaryDate(entry.timestamp, stringResource(R.string.diary_fmt_detail_sub))
                val year = formatDiaryDate(entry.timestamp, stringResource(R.string.diary_fmt_month_section_year))
                val time = formatDiaryDate(entry.timestamp, "HH:mm")
                val moodText = entry.moodText?.takeIf { it.isNotBlank() }
                Text(sub, style = AppTypography.label, color = colors.text.primary)
                Text(
                    if (moodText != null) "$year · $time · $moodText" else "$year · $time",
                    style = AppTypography.captionNumeric,
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

/** 评论组：组标题「评论（N）」+ 一张半透明组卡（一层线程·回复左侧极细引线·每根限一轮回复）。 */
@Composable
internal fun LiuliDiaryCommentSection(
    comments: List<DiaryCommentEntity>,
    charactersByUuid: Map<String, CharacterEntity>,
    onDeleteComment: (String) -> Unit,
    onReply: (rootCommentId: String, text: String) -> Unit,
) {
    val dark = LocalIsDarkTheme.current
    val threadGuide = AppTheme.colors.surface.stroke
    val threads = remember(comments) { groupDiaryCommentThreads(comments) }
    val me = stringResource(R.string.diary_role_me)
    val ai = stringResource(R.string.diary_comment_author_ai)
    Column(Modifier.fillMaxWidth()) {
        LiuliGroupHeader(stringResource(R.string.diary_comments_header, comments.size))
        Column(
            Modifier
                .fillMaxWidth()
                .liuliCardMaterial(LiuliShapes.group, dark)
                .padding(horizontal = LiuliPageGeometry.groupPadH, vertical = COMMENT_CARD_PAD_V),
            verticalArrangement = Arrangement.spacedBy(COMMENT_GAP),
        ) {
            threads.forEach { thread ->
                key(thread.root.id) {
                    LiuliDiaryCommentRow(thread.root, diaryCommentAuthorName(thread.root, charactersByUuid, me, ai), charactersByUuid, onDeleteComment)
                    thread.replies.forEach { reply ->
                        key(reply.id) {
                            val name = diaryCommentAuthorName(reply, charactersByUuid, me, ai)
                            LiuliDiaryCommentRow(
                                reply,
                                name,
                                charactersByUuid,
                                onDeleteComment,
                                Modifier
                                    .drawBehind {
                                        val x = 16.dp.toPx()
                                        drawLine(
                                            color = threadGuide,
                                            start = Offset(x, 0f),
                                            end = Offset(x, size.height),
                                            strokeWidth = 1.5.dp.toPx(),
                                        )
                                    }
                                    .padding(start = COMMENT_REPLY_INDENT),
                            )
                        }
                    }
                    if (thread.canReply()) {
                        LiuliDiaryInlineReply(
                            actionLabel = stringResource(R.string.diary_reply_action),
                            placeholder = stringResource(R.string.diary_reply_hint, diaryCommentAuthorName(thread.root, charactersByUuid, me, ai)),
                            stateKey = thread.root.id,
                            onSend = { onReply(thread.root.id, it) },
                            modifier = Modifier.padding(start = COMMENT_REPLY_INDENT),
                        )
                    }
                }
            }
        }
    }
}

/** 一条评论：头像 + 作者 / 内容 / 相对时间；长按 → 玻璃菜单「删除评论」→ 琉璃确认。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LiuliDiaryCommentRow(
    comment: DiaryCommentEntity,
    authorName: String,
    charactersByUuid: Map<String, CharacterEntity>,
    onDeleteComment: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val character = comment.characterUuid?.let { charactersByUuid[it] }
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { menuExpanded = true }), verticalAlignment = Alignment.Top) {
            CharacterAvatar(name = authorName, avatarPath = character?.avatarPath, size = COMMENT_AVATAR)
            Spacer(Modifier.width(COMMENT_AVATAR_GAP))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(authorName, style = AppTypography.label, color = colors.text.primary)
                Text(comment.content, style = AppTypography.listPreview, color = colors.text.primary)
                Text(diaryCommentRelativeTime(comment.timestamp), style = AppTypography.caption, color = colors.text.secondary)
            }
        }
        val deleteEntry = LiuliMenuEntry(stringResource(R.string.diary_comment_delete), danger = true, onClick = { showDeleteConfirm = true })
        LiuliPopupMenu(expanded = menuExpanded, onDismiss = { menuExpanded = false }, items = listOf(deleteEntry))
    }
    if (showDeleteConfirm) {
        LiuliDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = stringResource(R.string.diary_comment_delete_title),
            body = stringResource(R.string.diary_comment_delete_message),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = { showDeleteConfirm = false; onDeleteComment(comment.id) },
            confirmDanger = true,
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

/** 行内回复 / 留言：折叠 = 琉璃微胶囊（48 触达）；展开 = 琉璃单行输入 + 「发送」文字钮（发出即折叠清空）。 */
@Composable
internal fun LiuliDiaryInlineReply(
    actionLabel: String,
    placeholder: String,
    stateKey: String,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = LocalIsDarkTheme.current
    var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    var text by rememberSaveable(stateKey) { mutableStateOf("") }
    if (!expanded) {
        Box(
            modifier.liuliTouchHeight().clickable(role = Role.Button, onClickLabel = actionLabel) { expanded = true },
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                actionLabel,
                style = AppTypography.caption,
                color = AppTheme.colors.accent.text,
                modifier = Modifier
                    .clip(LiuliShapes.pill)
                    .background(LiuliMaterials.chipFill(dark))
                    .border(1.dp, LiuliMaterials.cardRim(dark), LiuliShapes.pill)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    } else {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiuliField(value = text, onValueChange = { text = it }, placeholder = placeholder, singleLine = true, modifier = Modifier.weight(1f))
            LiuliButton(onClick = { onSend(text); text = ""; expanded = false }, style = LiuliButtonStyle.Text, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.diary_reply_send))
            }
        }
    }
}
