package com.situ.aichat.ui.liuli.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.DiaryEntryWithComments
import com.situ.aichat.data.model.imagePaths
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBarIcons
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.diary.DetailMetaRow
import com.situ.aichat.ui.diary.DiaryDetailBody
import com.situ.aichat.ui.diary.DiaryDetailImageGrid
import com.situ.aichat.ui.diary.DiaryDetailSignature
import com.situ.aichat.ui.diary.DiaryDetailViewModel
import com.situ.aichat.ui.diary.DiaryDottedDivider
import com.situ.aichat.ui.diary.DiaryReactionRow
import com.situ.aichat.ui.diary.diaryAuthorDisplayOf
import com.situ.aichat.ui.diary.diaryDetailBodyStyle
import com.situ.aichat.ui.diary.diaryExchangeNoteAuthor
import com.situ.aichat.ui.liuli.designsystem.LiuliDialog
import com.situ.aichat.ui.liuli.designsystem.LiuliMenuEntry
import com.situ.aichat.ui.liuli.designsystem.LiuliPopupMenu
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.liuliPaperMaterial
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageCircleAction
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/** 详情正文左右 20 · 项间 18（方向 C 与 LiuliPageGeometry.gutter 统一）。 */
private val DETAIL_PAD_H = LiuliPageGeometry.gutter
private val DETAIL_ITEM_GAP = 18.dp

/**
 * 琉璃日记详情（琉璃 2.0 卷六·一 §4.3·设计稿 D2 / D4）：与暖陶 [com.situ.aichat.ui.diary.DiaryDetailScreen] 共用同一个 VM 与
 * 正文 / 元信息 / 图库 / 落款 / 点赞行；页壳常驻收起态（顶部就是「← 日记 ⋮」玻璃胶囊），心情日期头换玻璃感卡、
 * TA 的信垫稿纸、评论楼层装进半透明组卡、菜单 / 对话框换琉璃。
 */
@Composable
internal fun LiuliDiaryDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: DiaryDetailViewModel = hiltViewModel(),
) {
    val entryWithComments by viewModel.entry.collectAsStateWithLifecycle()
    val charactersByUuid by viewModel.charactersByUuid.collectAsStateWithLifecycle()
    var showDelete by remember { mutableStateOf(false) }

    LiuliDiaryDetailPage(
        entryWithComments = entryWithComments,
        charactersByUuid = charactersByUuid,
        onBack = onBack,
        onEdit = onEdit,
        onRequestDelete = { showDelete = true },
        onDeleteComment = viewModel::deleteComment,
        onReply = viewModel::replyToComment,
        onSendNote = viewModel::commentOnEntry,
    )

    if (showDelete) {
        LiuliDialog(
            onDismissRequest = { showDelete = false },
            title = stringResource(R.string.diary_delete_title),
            body = stringResource(R.string.diary_delete_message),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = { showDelete = false; viewModel.delete(onDone = onBack) },
            confirmDanger = true,
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showDelete = false },
        )
    }
}

/** 无 VM 的详情页（测试直接驱动它）。[entryWithComments] 为 null = 已删 / 未加载 → 空白页、无「更多」。 */
@Composable
internal fun LiuliDiaryDetailPage(
    entryWithComments: DiaryEntryWithComments?,
    charactersByUuid: Map<String, CharacterEntity>,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onRequestDelete: () -> Unit,
    onDeleteComment: (String) -> Unit,
    onReply: (rootCommentId: String, text: String) -> Unit,
    onSendNote: (String) -> Unit,
) {
    val ewc = entryWithComments
    LiuliPage(
        title = stringResource(R.string.diary_nav_title),
        onBack = onBack,
        collapsed = true,
        actions = if (ewc != null) {
            { LiuliDiaryDetailMenu(onEdit = { onEdit(ewc.entry.uuid) }, onDelete = onRequestDelete) }
        } else {
            null
        },
    ) {
        if (ewc == null) return@LiuliPage
        val entry = ewc.entry
        val colors = AppTheme.colors
        val dark = LocalIsDarkTheme.current
        val authorDisplay = diaryAuthorDisplayOf(entry, charactersByUuid)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .contentMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = DETAIL_PAD_H,
                    end = DETAIL_PAD_H,
                    top = LiuliPageGeometry.navRow + LiuliPageGeometry.titleGap,
                    bottom = LiuliPageGeometry.pageBottom + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
            verticalArrangement = Arrangement.spacedBy(DETAIL_ITEM_GAP),
        ) {
            LiuliDiaryDateHead(entry)
            if (authorDisplay?.isOrphan == true) {
                Text(stringResource(R.string.diary_exchange_orphan_label), style = AppTypography.caption, color = colors.text.secondary)
            }
            DetailMetaRow(entry, ewc.comments.size)
            DiaryDetailBody(
                content = entry.content.ifEmpty { stringResource(R.string.diary_no_content) },
                style = diaryDetailBodyStyle(entry),
                isLetter = entry.authorCharacterUuid != null,
                letterSurface = Modifier.liuliPaperMaterial(LiuliShapes.medium, dark),
            )
            val images = entry.imagePaths
            if (images.isNotEmpty()) DiaryDetailImageGrid(images)
            DiaryDetailSignature(authorDisplay, entry.timestamp)
            val noteAuthor = diaryExchangeNoteAuthor(entry, ewc.comments, charactersByUuid)
            if (ewc.reactions.isNotEmpty() || ewc.comments.isNotEmpty() || noteAuthor != null) DiaryDottedDivider()
            if (ewc.reactions.isNotEmpty()) DiaryReactionRow(ewc.reactions, charactersByUuid)
            if (ewc.comments.isNotEmpty()) LiuliDiaryCommentSection(ewc.comments, charactersByUuid, onDeleteComment, onReply)
            if (noteAuthor != null) {
                LiuliDiaryInlineReply(
                    actionLabel = stringResource(R.string.diary_exchange_comment_action),
                    placeholder = stringResource(R.string.diary_exchange_comment_hint, noteAuthor.name),
                    stateKey = "note",
                    onSend = onSendNote,
                )
            }
        }
    }
}

/** 详情「更多」：圆钮 + 玻璃菜单（编辑 / 删除）。 */
@Composable
private fun LiuliDiaryDetailMenu(onEdit: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        LiuliPageCircleAction(
            onClick = { expanded = true },
            contentDescription = stringResource(R.string.action_more),
            icon = AppTopBarIcons.More,
        )
        LiuliPopupMenu(
            expanded = expanded,
            onDismiss = { expanded = false },
            items = listOf(
                LiuliMenuEntry(stringResource(R.string.action_edit), onClick = onEdit),
                LiuliMenuEntry(stringResource(R.string.action_delete), danger = true, onClick = onDelete),
            ),
            offset = DIARY_MENU_OFFSET,
        )
    }
}
