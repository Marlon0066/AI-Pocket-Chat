package com.situ.aichat.ui.liuli.character.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.WorldBookSummary
import com.situ.aichat.data.local.entity.WorldBookEntity
import com.situ.aichat.ui.character.WorldBookCardState
import com.situ.aichat.ui.character.worldBookCardState
import com.situ.aichat.ui.designsystem.AppFeatureIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliDialog
import com.situ.aichat.ui.liuli.designsystem.LiuliSheetShell
import com.situ.aichat.ui.liuli.designsystem.LiuliTileTone
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliNavRow
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRadioRow
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.liuli.page.LiuliValueRow
import com.situ.aichat.ui.worldbook.PendingWorldBookSwitch
import com.situ.aichat.ui.worldbook.WorldBookBindingViewModel
import kotlinx.coroutines.launch

/** 停用书行的淡显（同暖陶 0.55·仍可选）。 */
private const val DISABLED_BOOK_ALPHA = 0.55f

/**
 * 世界观组外壳（§4.11·编辑态）：自取 [WorldBookBindingViewModel]；仅原住民锁死，已入世普通角色可点开选书弹层并一键确认离世切换。
 */
@Composable
internal fun LiuliWorldBookGroup(onManageBooks: () -> Unit, viewModel: WorldBookBindingViewModel = hiltViewModel()) {
    val boundBooks by viewModel.boundBooks.collectAsStateWithLifecycle()
    val joinedWorld by viewModel.joinedWorld.collectAsStateWithLifecycle()
    val nativeOrigin by viewModel.nativeOrigin.collectAsStateWithLifecycle()
    val selectableBooks by viewModel.selectableBooks.collectAsStateWithLifecycle()
    val globalBooks by viewModel.globalBooks.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showSheet by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refreshJoinedWorld() }
    LiuliWorldBookGroupContent(
        boundBooks = boundBooks,
        joinedWorld = joinedWorld,
        nativeOrigin = nativeOrigin,
        selectableBooks = selectableBooks,
        globalBooks = globalBooks,
        sheetVisible = showSheet && !nativeOrigin,
        onOpen = { scope.launch { if (!viewModel.isNativeOriginNow()) showSheet = true } },
        onDismissSheet = { showSheet = false },
        onSelectSole = viewModel::selectSole,
        onToggle = viewModel::toggle,
        onSwitchLeaveAndBind = viewModel::switchLeaveAndBind,
        onManageBooks = {
            showSheet = false
            onManageBooks()
        },
    )
}

/** 新建角色页世界观组（4A·琉璃脸）：尚未落库，选书自动关加入世界，无需确认弹窗。 */
@Composable
internal fun LiuliWorldBookCreateGroup(
    selectedBookUuids: List<String>,
    joinedWorld: Boolean,
    onSelectSole: (String?) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onManageBooks: () -> Unit,
    viewModel: WorldBookBindingViewModel = hiltViewModel(),
) {
    val selectableBooks by viewModel.selectableBooks.collectAsStateWithLifecycle()
    val globalBooks by viewModel.globalBooks.collectAsStateWithLifecycle()
    val boundBooks = remember(selectedBookUuids, selectableBooks) {
        selectedBookUuids.mapNotNull { id -> selectableBooks.firstOrNull { it.book.uuid == id } }
    }
    var showSheet by remember { mutableStateOf(false) }
    LiuliWorldBookGroupContent(
        boundBooks = boundBooks,
        joinedWorld = joinedWorld && boundBooks.isEmpty(),
        nativeOrigin = false,
        selectableBooks = selectableBooks,
        globalBooks = globalBooks,
        sheetVisible = showSheet,
        onOpen = { showSheet = true },
        onDismissSheet = { showSheet = false },
        onSelectSole = onSelectSole,
        onToggle = onToggle,
        isCreateMode = true,
        onManageBooks = {
            showSheet = false
            onManageBooks()
        },
    )
}

/** 世界观组内容（无状态·可单测）：仅原住民锁死；在世界里也可开选书弹层并经确认后离世换书。 */
@Composable
internal fun LiuliWorldBookGroupContent(
    boundBooks: List<WorldBookSummary>,
    joinedWorld: Boolean,
    nativeOrigin: Boolean,
    selectableBooks: List<WorldBookSummary>,
    globalBooks: List<WorldBookEntity>,
    sheetVisible: Boolean,
    onOpen: () -> Unit,
    onDismissSheet: () -> Unit,
    onSelectSole: (String?) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onManageBooks: () -> Unit,
    onSwitchLeaveAndBind: (String, Boolean) -> Unit = { bookUuid, sole ->
        if (sole) onSelectSole(bookUuid) else onToggle(bookUuid, true)
    },
    isCreateMode: Boolean = false,
) {
    var pendingSwitch by remember { mutableStateOf<PendingWorldBookSwitch?>(null) }

    LiuliGroup(header = stringResource(R.string.wb_binding_section), footer = stringResource(R.string.wb_binding_footer)) {
        when (val card = worldBookCardState(nativeOrigin = nativeOrigin, joinedWorld = joinedWorld, bound = boundBooks)) {
            WorldBookCardState.NativeLocked -> LiuliValueRow(
                title = stringResource(R.string.wb_binding_native_locked),
                value = "",
                icon = AppFeatureIcons.Worldbook,
                tileColor = LiuliTileTone.Lilac,
                enabled = false,
                divider = false,
            )
            else -> LiuliNavRow(
                title = when (card) {
                    WorldBookCardState.WorldLocked -> stringResource(R.string.wb_binding_world_locked)
                    is WorldBookCardState.Single -> card.name
                    is WorldBookCardState.Multi -> stringResource(R.string.wb_binding_multi, card.firstName, card.extra)
                    else -> stringResource(R.string.wb_binding_none)
                },
                onClick = onOpen,
                icon = AppFeatureIcons.Worldbook,
                tileColor = LiuliTileTone.Lilac,
                subtitle = when (card) {
                    is WorldBookCardState.Single -> {
                        val meta = stringResource(R.string.wb_book_meta_unbound, card.entryCount)
                        if (isCreateMode) "$meta · ${stringResource(R.string.wb_binding_create_sub_selected)}" else meta
                    }
                    is WorldBookCardState.Multi -> if (isCreateMode) stringResource(R.string.wb_binding_create_sub_selected) else null
                    else -> null
                },
                divider = false,
            )
        }
    }

    if (sheetVisible) {
        LiuliWorldBookSheet(
            boundBooks = boundBooks,
            selectableBooks = selectableBooks,
            globalBooks = globalBooks,
            onDismiss = onDismissSheet,
            onSelectSole = { bookUuid ->
                if (!isCreateMode && bookUuid != null && joinedWorld) {
                    val bookName = selectableBooks.firstOrNull { it.book.uuid == bookUuid }?.book?.name.orEmpty()
                    pendingSwitch = PendingWorldBookSwitch(bookUuid = bookUuid, bookName = bookName, sole = true)
                } else {
                    onSelectSole(bookUuid)
                    onDismissSheet()
                }
            },
            onToggle = { bookUuid, bound ->
                if (!isCreateMode && bound && joinedWorld) {
                    val bookName = selectableBooks.firstOrNull { it.book.uuid == bookUuid }?.book?.name.orEmpty()
                    pendingSwitch = PendingWorldBookSwitch(bookUuid = bookUuid, bookName = bookName, sole = false)
                } else {
                    onToggle(bookUuid, bound)
                }
            },
            onManageBooks = onManageBooks,
        )
    }

    pendingSwitch?.let { target ->
        LiuliDialog(
            onDismissRequest = { pendingSwitch = null },
            title = stringResource(R.string.wb_binding_switch_leave_title),
            body = stringResource(R.string.wb_binding_switch_leave_body, target.bookName),
            confirmText = stringResource(R.string.wb_binding_switch_leave_confirm),
            onConfirm = {
                onSwitchLeaveAndBind(target.bookUuid, target.sole)
                if (target.sole) onDismissSheet()
                pendingSwitch = null
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { pendingSwitch = null },
        )
    }
}

/** 选择弹层（§4.11）：已叠加多本或点了「叠加」→ 多选逐本绑 / 不绑；否则单选（选一本 = 收敛为这一本）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiuliWorldBookSheet(
    boundBooks: List<WorldBookSummary>,
    selectableBooks: List<WorldBookSummary>,
    globalBooks: List<WorldBookEntity>,
    onDismiss: () -> Unit,
    onSelectSole: (String?) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onManageBooks: () -> Unit,
) {
    val colors = AppTheme.colors
    var multiMode by remember { mutableStateOf(false) }
    val effectiveMulti = multiMode || boundBooks.size > 1
    val boundUuids = boundBooks.map { it.book.uuid }.toSet()
    LiuliSheetShell(onDismissRequest = onDismiss, title = stringResource(R.string.wb_binding_sheet_title)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            LiuliGroup(Modifier.padding(horizontal = LiuliPageGeometry.gutter)) {
                if (!effectiveMulti) {
                    LiuliRadioRow(
                        title = stringResource(R.string.wb_binding_not_use),
                        selected = boundUuids.isEmpty(),
                        onSelect = { onSelectSole(null) },
                        divider = false,
                        notifyWhenSelected = true,
                    )
                }
                selectableBooks.forEachIndexed { i, summary ->
                    val disabled = !summary.book.enabled
                    val subtitle = stringResource(R.string.wb_book_meta_unbound, summary.entryCount) +
                        if (disabled) " · ${stringResource(R.string.wb_book_disabled)}" else ""
                    val rowModifier = if (disabled) Modifier.alpha(DISABLED_BOOK_ALPHA) else Modifier
                    val bound = summary.book.uuid in boundUuids
                    if (effectiveMulti) {
                        LiuliToggleRow(
                            title = summary.book.name,
                            checked = bound,
                            onCheckedChange = { onToggle(summary.book.uuid, it) },
                            modifier = rowModifier,
                            subtitle = subtitle,
                            divider = i > 0,
                        )
                    } else {
                        LiuliRadioRow(
                            title = summary.book.name,
                            selected = bound,
                            onSelect = { onSelectSole(summary.book.uuid) },
                            modifier = rowModifier,
                            subtitle = subtitle,
                            notifyWhenSelected = true,
                        )
                    }
                }
            }
            if (globalBooks.isNotEmpty()) {
                Row(
                    Modifier.padding(horizontal = LiuliPageGeometry.gutter + LiuliPageGeometry.groupPadH, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = colors.text.secondary, modifier = Modifier.size(14.dp))
                    Text(
                        stringResource(R.string.wb_binding_global_note, globalBooks.joinToString("、") { "「${it.name}」" }),
                        style = AppTypography.caption,
                        color = colors.text.secondary,
                    )
                }
            }
            if (!effectiveMulti) {
                LiuliGroup(Modifier.padding(horizontal = LiuliPageGeometry.gutter)) {
                    LiuliNavRow(
                        stringResource(R.string.wb_binding_stack),
                        onClick = { multiMode = true },
                        subtitle = stringResource(R.string.wb_binding_stack_sub),
                        divider = false,
                    )
                }
            }
            LiuliButton(onClick = onManageBooks, style = LiuliButtonStyle.Text, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.wb_binding_manage))
            }
        }
    }
}
