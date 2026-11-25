package com.situ.aichat.ui.liuli.worldbook

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldBookEntryEntity
import com.situ.aichat.data.worldbook.decodeStringList
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBarIcons
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliCheckbox
import com.situ.aichat.ui.liuli.designsystem.LiuliDialog
import com.situ.aichat.ui.liuli.designsystem.LiuliMenuEntry
import com.situ.aichat.ui.liuli.designsystem.LiuliPopupMenu
import com.situ.aichat.ui.liuli.designsystem.LiuliSearchSlot
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.LiuliSheetShell
import com.situ.aichat.ui.liuli.designsystem.LiuliSwitch
import com.situ.aichat.ui.liuli.designsystem.liuliCardMaterial
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageCircleAction
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import com.situ.aichat.ui.worldbook.BookMetaDialog
import com.situ.aichat.ui.worldbook.DeleteBookDialog
import com.situ.aichat.ui.worldbook.WorldBookDetailViewModel
import com.situ.aichat.ui.worldbook.WorldBookGuideCard
import com.situ.aichat.ui.worldbook.WorldBookTriggerBadge
import com.situ.aichat.ui.worldbook.formatCharCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 设定集书详情（琉璃 2.0 脸）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiuliWorldBookDetailScreen(
    onBack: () -> Unit,
    onOpenEntry: (entryUuid: String) -> Unit,
    onCreateEntry: (guideKey: String?) -> Unit,
    viewModel: WorldBookDetailViewModel = hiltViewModel(),
) {
    val book by viewModel.book.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val stats by viewModel.entryStats.collectAsStateWithLifecycle()
    val boundCharacters by viewModel.boundCharacters.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val exportResult by viewModel.exportResult.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = AppTheme.colors
    val dark = LocalIsDarkTheme.current
    val exportOkMsg = stringResource(R.string.wb_export_success)
    val exportFailMsg = stringResource(R.string.wb_export_failed)

    var showMenu by remember { mutableStateOf(false) }
    var showEditMeta by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showCharacters by remember { mutableStateOf(false) }

    val exportCreator = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            viewModel.exportBook { json ->
                withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) } != null }.getOrDefault(false)
                }
            }
        }
    }
    LaunchedEffect(exportResult) {
        val ok = exportResult ?: return@LaunchedEffect
        Toast.makeText(context, if (ok) exportOkMsg else exportFailMsg, Toast.LENGTH_SHORT).show()
        viewModel.consumeExportResult()
    }

    val b = book ?: return
    val listState = rememberLazyListState()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val title = b.name.ifBlank { stringResource(R.string.wb_shelf_title) }

    LiuliPage(
        title = title,
        onBack = onBack,
        collapsed = rememberLargeTitleCollapsed(listState),
        actions = {
            Box {
                LiuliPageCircleAction(
                    onClick = { showMenu = true },
                    contentDescription = stringResource(R.string.wb_cd_more_actions),
                    icon = AppTopBarIcons.More,
                )
                LiuliPopupMenu(
                    expanded = showMenu,
                    onDismiss = { showMenu = false },
                    items = listOf(
                        LiuliMenuEntry(stringResource(R.string.wb_menu_export)) { exportCreator.launch("${b.name.ifBlank { "worldbook" }}.json") },
                        LiuliMenuEntry(stringResource(R.string.wb_menu_edit_meta)) { showEditMeta = true },
                        LiuliMenuEntry(stringResource(R.string.action_delete), danger = true) { showDelete = true },
                    ),
                )
            }
        },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().contentMaxWidth(),
            contentPadding = PaddingValues(
                start = LiuliPageGeometry.gutter,
                end = LiuliPageGeometry.gutter,
                top = LiuliPageGeometry.navRow + 4.dp,
                bottom = navBottom + LiuliPageGeometry.pageBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "title") { LiuliLargeTitle(title) }
            item(key = "header") {
                Column(modifier = Modifier.fillMaxWidth().liuliCardMaterial(LiuliShapes.group, dark).padding(vertical = 6.dp)) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        if (b.description.isNotBlank()) Text(b.description, style = AppTypography.caption, color = colors.text.secondary)
                        Text(stringResource(R.string.wb_detail_meta, stats.first, formatCharCount(stats.second)), style = AppTypography.caption, color = colors.text.secondary)
                    }
                    AppListDivider(startInset = 16.dp)
                    LiuliToggleRow(stringResource(R.string.wb_enable_book), checked = b.enabled, onCheckedChange = { viewModel.setBookEnabled(it) })
                    AppListDivider(startInset = 16.dp)
                    LiuliToggleRow(stringResource(R.string.wb_global_switch), checked = b.isGlobal, onCheckedChange = { viewModel.setBookGlobal(it) }, subtitle = stringResource(R.string.wb_global_switch_sub))
                    if (!b.isGlobal) {
                        AppListDivider(startInset = 16.dp)
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { showCharacters = true }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                                boundCharacters.take(5).forEach { c ->
                                    CharacterAvatar(c.name, c.avatarPath, size = 24.dp, modifier = Modifier.border(1.5.dp, colors.surface.raised, CircleShape))
                                }
                            }
                            Text(
                                if (boundCharacters.isEmpty()) stringResource(R.string.wb_bound_row_empty) else stringResource(R.string.wb_bound_row, boundCharacters.size),
                                style = AppTypography.caption,
                                color = colors.text.secondary,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(AppActionIcons.ChevronRight, contentDescription = null, tint = colors.text.secondary)
                        }
                    }
                }
            }
            item(key = "guide") {
                WorldBookGuideCard(stats.first > 0, { onCreateEntry(it.name) }, cardModifier = { Modifier.liuliCardMaterial(LiuliShapes.group, dark) })
            }
            item(key = "search") {
                LiuliSearchSlot(searchQuery, viewModel::setSearch, stringResource(R.string.wb_search_entries), stringResource(R.string.chat_list_search_clear))
            }
            if (entries.isEmpty()) {
                item(key = "emptyEntries") {
                    Text(stringResource(R.string.wb_detail_no_entries), style = AppTypography.caption, color = colors.text.secondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp))
                }
            } else {
                items(entries, key = { it.uuid }) { entry ->
                    LiuliWorldBookEntryRow(entry, dark, { onOpenEntry(entry.uuid) }, { viewModel.setEntryEnabled(entry.uuid, it) })
                }
            }
            item(key = "addEntry") {
                Row(
                    modifier = Modifier.fillMaxWidth().liuliCardMaterial(LiuliShapes.group, dark).clickableScale { onCreateEntry(null) }.padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(AppTopBarIcons.Add, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.wb_add_entry), style = AppTypography.titleSmall, color = colors.accent.text, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }

    if (showEditMeta) {
        BookMetaDialog(stringResource(R.string.wb_edit_meta_dialog_title), b.name, b.description, onConfirm = { n, d -> viewModel.updateMeta(n, d); showEditMeta = false }, onDismiss = { showEditMeta = false })
    }
    if (showDelete) {
        DeleteBookDialog(b.name, stats.first, onConfirm = { showDelete = false; viewModel.deleteBook(onDeleted = onBack) }, onDismiss = { showDelete = false })
    }
    if (showCharacters) {
        val allCharacters by viewModel.allCharacters.collectAsStateWithLifecycle()
        val nativeUuids by viewModel.nativeCharacterUuids.collectAsStateWithLifecycle()
        val boundUuids = boundCharacters.map { it.uuid }.toSet()
        var pendingSwitchChar by remember { mutableStateOf<CharacterEntity?>(null) }

        LiuliSheetShell(onDismissRequest = { showCharacters = false }, title = stringResource(R.string.wb_characters_sheet_title)) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                allCharacters.forEach { c ->
                    val bound = c.uuid in boundUuids
                    val isNative = c.uuid in nativeUuids
                    val inWorld = !isNative && c.joinedWorld
                    val onRowClick: () -> Unit = {
                        when {
                            isNative -> Unit
                            !bound && inWorld -> pendingSwitchChar = c
                            else -> viewModel.toggleCharacter(c.uuid, !bound)
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (isNative) 0.48f else 1f)
                            .then(if (isNative) Modifier else Modifier.clickable(onClick = onRowClick))
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CharacterAvatar(c.name, c.avatarPath, size = 36.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(c.name, style = AppTypography.body, color = colors.text.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (isNative || inWorld) {
                                    Text(
                                        stringResource(if (isNative) R.string.wb_characters_tag_native else R.string.wb_characters_tag_in_world),
                                        style = AppTypography.label,
                                        color = if (isNative) colors.text.secondary else colors.accent.text,
                                        modifier = Modifier
                                            .clip(AppShapes.full)
                                            .background(if (isNative) colors.surface.sunken else colors.accent.container)
                                            .padding(horizontal = 7.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            if (isNative || inWorld) {
                                Text(
                                    stringResource(if (isNative) R.string.wb_characters_sub_native else R.string.wb_characters_sub_in_world),
                                    style = AppTypography.caption,
                                    color = colors.text.secondary,
                                )
                            }
                        }
                        LiuliCheckbox(checked = bound, enabled = !isNative, onCheckedChange = { onRowClick() })
                    }
                }
            }
        }
        pendingSwitchChar?.let { target ->
            LiuliDialog(
                onDismissRequest = { pendingSwitchChar = null },
                title = stringResource(R.string.wb_characters_switch_title),
                body = stringResource(R.string.wb_characters_switch_body, target.name, b.name),
                confirmText = stringResource(R.string.wb_characters_switch_confirm),
                onConfirm = {
                    viewModel.switchLeaveAndBindCharacter(target.uuid)
                    pendingSwitchChar = null
                },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { pendingSwitchChar = null },
            )
        }
    }
}

@Composable
private fun LiuliWorldBookEntryRow(entry: WorldBookEntryEntity, dark: Boolean, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val colors = AppTheme.colors
    val keys = remember(entry.keysJson) { decodeStringList(entry.keysJson) }
    val title = entry.comment.ifBlank { entry.content.replace('\n', ' ').take(20).ifBlank { stringResource(R.string.wb_entry_untitled) } }
    val subtitle = if (!entry.constant && !entry.vectorized && keys.isNotEmpty()) keys.joinToString("、") else entry.content.replace('\n', ' ').take(40)
    Row(
        modifier = Modifier.fillMaxWidth().alpha(if (entry.enabled) 1f else 0.55f).liuliCardMaterial(LiuliShapes.group, dark).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        WorldBookTriggerBadge(entry)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = AppTypography.titleSmall, color = colors.text.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = AppTypography.caption, color = colors.text.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LiuliSwitch(checked = entry.enabled, onCheckedChange = onToggle, modifier = Modifier.semantics { contentDescription = title })
    }
}
