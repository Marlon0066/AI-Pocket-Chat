package com.situ.aichat.ui.worldbook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.WorldBookSummary
import com.situ.aichat.data.local.entity.WorldBookEntity
import com.situ.aichat.ui.character.WorldBookCardState
import com.situ.aichat.ui.character.worldBookCardState
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppCheckbox
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppFeatureIcons
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.AppRadio
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppTheme
import kotlinx.coroutines.launch

/** 角色编辑页「世界观」段的入口行卡（编辑模式·响应式同步 + 入世一键切换）。 */
@Composable
fun WorldBookBindingSection(
    onManageBooks: () -> Unit,
    viewModel: WorldBookBindingViewModel = hiltViewModel(),
) {
    val boundBooks by viewModel.boundBooks.collectAsStateWithLifecycle()
    val joinedWorld by viewModel.joinedWorld.collectAsStateWithLifecycle()
    val nativeOrigin by viewModel.nativeOrigin.collectAsStateWithLifecycle()
    val selectableBooks by viewModel.selectableBooks.collectAsStateWithLifecycle()
    val globalBooks by viewModel.globalBooks.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showSheet by remember { mutableStateOf(false) }
    var pendingSwitch by remember { mutableStateOf<PendingWorldBookSwitch?>(null) }

    LaunchedEffect(Unit) { viewModel.refreshJoinedWorld() }

    WorldBookBindingCard(
        boundBooks = boundBooks,
        joinedWorld = joinedWorld,
        nativeOrigin = nativeOrigin,
        isCreateMode = false,
        onClick = {
            scope.launch {
                if (!viewModel.isNativeOriginNow()) showSheet = true
            }
        },
    )

    if (showSheet && !nativeOrigin) {
        WorldBookBindingSheetContent(
            boundBooks = boundBooks,
            selectableBooks = selectableBooks,
            globalBooks = globalBooks,
            onSelectSole = { bookUuid ->
                if (bookUuid != null && joinedWorld) {
                    val bookName = selectableBooks.firstOrNull { it.book.uuid == bookUuid }?.book?.name.orEmpty()
                    pendingSwitch = PendingWorldBookSwitch(bookUuid = bookUuid, bookName = bookName, sole = true)
                } else {
                    viewModel.selectSole(bookUuid)
                    showSheet = false
                }
            },
            onToggle = { bookUuid, bound ->
                if (bound && joinedWorld) {
                    val bookName = selectableBooks.firstOrNull { it.book.uuid == bookUuid }?.book?.name.orEmpty()
                    pendingSwitch = PendingWorldBookSwitch(bookUuid = bookUuid, bookName = bookName, sole = false)
                } else {
                    viewModel.toggle(bookUuid, bound)
                }
            },
            onManageBooks = {
                showSheet = false
                onManageBooks()
            },
            onDismiss = { showSheet = false },
        )
    }

    pendingSwitch?.let { target ->
        AppDialog(
            onDismissRequest = { pendingSwitch = null },
            title = stringResource(R.string.wb_binding_switch_leave_title),
            body = stringResource(R.string.wb_binding_switch_leave_body, target.bookName),
            confirmText = stringResource(R.string.wb_binding_switch_leave_confirm),
            onConfirm = {
                viewModel.switchLeaveAndBind(target.bookUuid, sole = target.sole)
                if (target.sole) showSheet = false
                pendingSwitch = null
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { pendingSwitch = null },
        )
    }
}

/** 新建角色页「世界观」选书段（4A·尚未入世落库，选书自动关加入世界，无需弹窗打扰）。 */
@Composable
fun WorldBookCreateBindingSection(
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

    WorldBookBindingCard(
        boundBooks = boundBooks,
        joinedWorld = joinedWorld && boundBooks.isEmpty(),
        nativeOrigin = false,
        isCreateMode = true,
        onClick = { showSheet = true },
    )

    if (showSheet) {
        WorldBookBindingSheetContent(
            boundBooks = boundBooks,
            selectableBooks = selectableBooks,
            globalBooks = globalBooks,
            onSelectSole = { bookUuid ->
                onSelectSole(bookUuid)
                showSheet = false
            },
            onToggle = onToggle,
            onManageBooks = {
                showSheet = false
                onManageBooks()
            },
            onDismiss = { showSheet = false },
        )
    }
}

@Composable
private fun WorldBookBindingCard(
    boundBooks: List<WorldBookSummary>,
    joinedWorld: Boolean,
    nativeOrigin: Boolean,
    isCreateMode: Boolean,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    Surface(
        shape = AppShapes.medium,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (nativeOrigin) 0.48f else 1f)
            .then(if (nativeOrigin) Modifier else Modifier.clickableScale(onClick = onClick)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(colors.accent.container),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppFeatureIcons.Worldbook, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(22.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                when (val card = worldBookCardState(nativeOrigin = nativeOrigin, joinedWorld = joinedWorld, bound = boundBooks)) {
                    WorldBookCardState.NativeLocked -> Text(
                        stringResource(R.string.wb_binding_native_locked),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.text.secondary,
                    )
                    WorldBookCardState.WorldLocked -> Text(
                        stringResource(R.string.wb_binding_world_locked),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.text.secondary,
                    )
                    WorldBookCardState.None -> Text(
                        stringResource(R.string.wb_binding_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.text.secondary,
                    )
                    is WorldBookCardState.Single -> {
                        Text(
                            card.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.text.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val meta = stringResource(R.string.wb_book_meta_unbound, card.entryCount)
                        Text(
                            if (isCreateMode) "$meta · ${stringResource(R.string.wb_binding_create_sub_selected)}" else meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.text.secondary,
                        )
                    }
                    is WorldBookCardState.Multi -> {
                        Text(
                            stringResource(R.string.wb_binding_multi, card.firstName, card.extra),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.text.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isCreateMode) {
                            Text(
                                stringResource(R.string.wb_binding_create_sub_selected),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.text.secondary,
                            )
                        }
                    }
                }
            }
            Icon(AppActionIcons.ChevronRight, contentDescription = null, tint = colors.text.secondary)
        }
    }
}

/** 选择弹层：单选主脸（≤1 本时）/ 多选（已叠加或点开进阶入口后）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorldBookBindingSheetContent(
    boundBooks: List<WorldBookSummary>,
    selectableBooks: List<WorldBookSummary>,
    globalBooks: List<WorldBookEntity>,
    onSelectSole: (String?) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onManageBooks: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppTheme.colors
    var multiMode by remember { mutableStateOf(false) }
    val effectiveMulti = multiMode || boundBooks.size > 1
    val boundUuids = boundBooks.map { it.book.uuid }.toSet()

    AppSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Text(
                stringResource(R.string.wb_binding_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (!effectiveMulti) {
                BindingRow(
                    title = stringResource(R.string.wb_binding_not_use),
                    subtitle = null,
                    dimmed = false,
                    selected = boundUuids.isEmpty(),
                    multi = false,
                    onPick = { onSelectSole(null) },
                )
            }
            selectableBooks.forEach { summary ->
                AppListDivider(modifier = Modifier.padding(horizontal = 20.dp), startInset = 0.dp)
                val disabled = !summary.book.enabled
                BindingRow(
                    title = summary.book.name,
                    subtitle = stringResource(R.string.wb_book_meta_unbound, summary.entryCount) +
                        if (disabled) " · ${stringResource(R.string.wb_book_disabled)}" else "",
                    dimmed = disabled,
                    selected = summary.book.uuid in boundUuids,
                    multi = effectiveMulti,
                    onPick = {
                        if (effectiveMulti) {
                            onToggle(summary.book.uuid, summary.book.uuid !in boundUuids)
                        } else {
                            onSelectSole(summary.book.uuid)
                        }
                    },
                )
            }
            if (globalBooks.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .clip(AppShapes.small)
                        .background(colors.surface.sunken)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = colors.text.secondary, modifier = Modifier.size(14.dp))
                    Text(
                        stringResource(
                            R.string.wb_binding_global_note,
                            globalBooks.joinToString("、") { "「${it.name}」" },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.text.secondary,
                    )
                }
            }
            if (!effectiveMulti) {
                AppListDivider(modifier = Modifier.padding(horizontal = 20.dp), startInset = 0.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { multiMode = true }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.wb_binding_stack),
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.text.primary,
                        )
                        Text(
                            stringResource(R.string.wb_binding_stack_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.text.secondary,
                        )
                    }
                    Icon(AppActionIcons.ChevronRight, contentDescription = null, tint = colors.text.secondary)
                }
            }
            Text(
                stringResource(R.string.wb_binding_manage),
                style = MaterialTheme.typography.titleSmall,
                color = colors.accent.text,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(AppShapes.full)
                    .clickable(onClick = onManageBooks)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/** 弹层里的一行书：单选 = [AppRadio] / 多选 = [AppCheckbox]；停用书淡显但可选。 */
@Composable
private fun BindingRow(
    title: String,
    subtitle: String?,
    dimmed: Boolean,
    selected: Boolean,
    multi: Boolean,
    onPick: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .alpha(if (dimmed) 0.55f else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (multi) {
            AppCheckbox(checked = selected, onCheckedChange = { onPick() })
        } else {
            AppRadio(selected = selected, onClick = onPick)
        }
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.text.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.text.secondary)
            }
        }
    }
}
