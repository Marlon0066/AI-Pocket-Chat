package com.situ.aichat.ui.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.DiaryCommentEntity
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTheme

/** 一条评论线程：角色根评论 + 其下回复（用户回复 / 角色回应·按时间升序）。 */
internal data class DiaryCommentThread(
    val root: DiaryCommentEntity,
    val replies: List<DiaryCommentEntity>,
)

/**
 * 评论列表 → 一层线程（R3 评论区活化）。根 = parentCommentId 为 null；孤儿子评论（根已被删）**降级为根**
 * 展示（数据不丢不藏）；根与回复各按时间升序。纯函数·T1 看门。
 */
internal fun groupDiaryCommentThreads(comments: List<DiaryCommentEntity>): List<DiaryCommentThread> {
    val byId = comments.associateBy { it.id }
    val roots = comments.filter { it.parentCommentId == null || byId[it.parentCommentId] == null }
    val children = comments.filter { it.parentCommentId != null && byId[it.parentCommentId] != null }
        .groupBy { it.parentCommentId!! }
    return roots.sortedBy { it.timestamp }.map { root ->
        DiaryCommentThread(root, children[root.id].orEmpty().sortedBy { it.timestamp })
    }
}

/** 还能回复吗：根须是角色评论，且本根下用户尚未回复过（每根限 1 轮·O5 口径）。 */
internal fun DiaryCommentThread.canReply(): Boolean =
    root.characterUuid != null && !root.isFromUser && replies.none { it.isFromUser }

/**
 * 交换日记还能给作者留言吗（R6-1·O5 翻案）：用户尚无顶层留言即可（一封信限一条留言 → 作者回应一次，
 * 对齐 R3 每根 1 轮口径）。孤儿回复（parentCommentId 非 null）不算留言。调用方另须门控
 * 「这是交换日记且作者角色仍在」。纯函数·T1 看门。
 */
internal fun canLeaveExchangeNote(comments: List<DiaryCommentEntity>): Boolean =
    comments.none { it.isFromUser && it.parentCommentId == null }

/** 评论作者名（纯函数·T1）：用户 → [meLabel]；角色 → 活名，查不到 → [aiLabel]。 */
internal fun diaryCommentAuthorName(
    comment: DiaryCommentEntity,
    charactersByUuid: Map<String, CharacterEntity>,
    meLabel: String,
    aiLabel: String,
): String = if (comment.isFromUser) meLabel else comment.characterUuid?.let { charactersByUuid[it]?.name } ?: aiLabel

/**
 * 评论线程区（详情页·R3）：根评论行 + 回复行缩进 42dp + 「回复」入口（限 1 轮·发出后角色稍后回应，
 * 响应式 Flow 自动刷新）。删除沿用 DiaryCommentRow 长按（回复行同样可删）。
 */
@Composable
internal fun DiaryCommentThreadSection(
    comments: List<DiaryCommentEntity>,
    charactersByUuid: Map<String, CharacterEntity>,
    onDeleteComment: (String) -> Unit,
    onReply: (rootCommentId: String, text: String) -> Unit,
) {
    val threads = remember(comments) { groupDiaryCommentThreads(comments) }
    val threadGuide = AppTheme.colors.surface.stroke
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        threads.forEach { thread ->
            key(thread.root.id) {
                CommentThreadRow(thread.root, charactersByUuid, onDeleteComment)
                thread.replies.forEach { reply ->
                    key(reply.id) {
                        CommentThreadRow(
                            reply,
                            charactersByUuid,
                            onDeleteComment,
                            modifier = Modifier
                                .drawBehind {
                                    val x = 16.dp.toPx()
                                    drawLine(
                                        color = threadGuide,
                                        start = Offset(x, 0f),
                                        end = Offset(x, size.height),
                                        strokeWidth = 1.5.dp.toPx(),
                                    )
                                }
                                .padding(start = 42.dp),
                        )
                    }
                }
                if (thread.canReply()) {
                    val rootAuthor = diaryCommentAuthorName(
                        thread.root,
                        charactersByUuid,
                        stringResource(R.string.diary_role_me),
                        stringResource(R.string.diary_comment_author_ai),
                    )
                    ReplyAffordance(
                        rootAuthorName = rootAuthor,
                        rootId = thread.root.id,
                        onReply = onReply,
                    )
                }
            }
        }
    }
}

@Composable
private fun CommentThreadRow(
    comment: DiaryCommentEntity,
    charactersByUuid: Map<String, CharacterEntity>,
    onDeleteComment: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val character = comment.characterUuid?.let { charactersByUuid[it] }
    DiaryCommentRow(
        authorName = diaryCommentAuthorName(
            comment,
            charactersByUuid,
            stringResource(R.string.diary_role_me),
            stringResource(R.string.diary_comment_author_ai),
        ),
        authorAvatarPath = character?.avatarPath,
        content = comment.content,
        timestampMillis = comment.timestamp,
        onDelete = { onDeleteComment(comment.id) },
        modifier = modifier,
    )
}

/**
 * 「给 TA 留言」入口（R6-1·交换日记详情页）：折叠态 = 陶土小字；展开 = 单行输入 + 发送（发出即折叠清空）。
 * 与 [ReplyAffordance] 同交互语言，区别：顶层（无缩进）、文案对作者说话、门控由上游
 * [canLeaveExchangeNote] × 交换日记判定负责。
 */
@Composable
internal fun ExchangeNoteAffordance(
    authorName: String,
    onSend: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    Column {
        if (!expanded) {
            Text(
                stringResource(R.string.diary_exchange_comment_action),
                style = AppTheme.typography.caption,
                color = AppTheme.colors.accent.text,
                modifier = Modifier
                    .clip(AppTheme.shapes.full)
                    .background(AppTheme.colors.surface.sunken)
                    .clickable(onClickLabel = stringResource(R.string.diary_exchange_comment_action)) {
                        expanded = true
                    }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                AppTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = stringResource(R.string.diary_exchange_comment_hint, authorName),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                AppButton(
                    onClick = {
                        onSend(text)
                        text = ""
                        expanded = false
                    },
                    style = AppButtonStyle.Text,
                    enabled = text.isNotBlank(),
                ) { Text(stringResource(R.string.diary_reply_send)) }
            }
        }
    }
}

/** 「回复」入口：折叠态 = 陶土小字；展开 = 单行输入 + 发送（发出即折叠清空·限 1 轮由上游 canReply 门控）。 */
@Composable
private fun ReplyAffordance(
    rootAuthorName: String,
    rootId: String,
    onReply: (String, String) -> Unit,
) {
    var expanded by rememberSaveable(rootId) { mutableStateOf(false) }
    var text by rememberSaveable(rootId) { mutableStateOf("") }
    Column(Modifier.padding(start = 42.dp)) {
        if (!expanded) {
            Text(
                stringResource(R.string.diary_reply_action),
                style = AppTheme.typography.caption,
                color = AppTheme.colors.accent.text,
                modifier = Modifier
                    .clip(AppTheme.shapes.full)
                    .background(AppTheme.colors.surface.sunken)
                    .clickable(onClickLabel = stringResource(R.string.diary_reply_action)) { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                AppTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = stringResource(R.string.diary_reply_hint, rootAuthorName),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                AppButton(
                    onClick = {
                        onReply(rootId, text)
                        text = ""
                        expanded = false
                    },
                    style = AppButtonStyle.Text,
                    enabled = text.isNotBlank(),
                ) { Text(stringResource(R.string.diary_reply_send)) }
            }
        }
    }
}
