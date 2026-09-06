package com.situ.aichat.ui.worldbook

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
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
import com.situ.aichat.data.local.entity.WorldBookEntryEntity
import com.situ.aichat.data.worldbook.decodeStringList
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.SettingsSwitchRow
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.AppMenu
import com.situ.aichat.ui.designsystem.AppMenuItem
import com.situ.aichat.ui.designsystem.AppSearchField
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppSwitch
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.AppTopBarIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 书详情（WB7a/WB7b·契约 §12.3/§12.10）：头卡（简介 + 元信息 + 两开关 + 在用角色）+ 写作向导卡
 * （空书全展开·有条目降级一行小入口）+ 条目搜索 + 条目列表（三色触发徽章 = 常驻蓝灯 / 关键词绿灯 /
 * 语义陶土·全走既有 status/accent token）+ 添加条目行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldBookDetailScreen(
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

    var showMenu by remember { mutableStateOf(false) }
    var showEditMeta by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showCharacters by remember { mutableStateOf(false) }

    val exportCreator = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            viewModel.exportBook { json ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)
                            ?.use { it.write(json.toByteArray(Charsets.UTF_8)) } != null
                    }.getOrDefault(false)
                }
            }
        }
    }

    LaunchedEffect(exportResult) {
        val ok = exportResult ?: return@LaunchedEffect
        Toast.makeText(
            context,
            context.getString(if (ok) R.string.wb_export_success else R.string.wb_export_failed),
            Toast.LENGTH_SHORT,
        ).show()
        viewModel.consumeExportResult()
    }

    val b = book ?: return

    val listState = rememberLazyListState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = b.name.ifBlank { stringResource(R.string.wb_shelf_title) },
                onBack = onBack,
                lifted = listState.canScrollBackward,
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(AppTopBarIcons.More, contentDescription = stringResource(R.string.wb_cd_more_actions))
                        }
                        AppMenu(expanded = showMenu, onDismiss = { showMenu = false }) {
                            AppMenuItem(
                                text = stringResource(R.string.wb_menu_export),
                                onClick = {
                                    showMenu = false
                                    exportCreator.launch("${b.name.ifBlank { "worldbook" }}.json")
                                },
                            )
                            AppMenuItem(
                                text = stringResource(R.string.wb_menu_edit_meta),
                                onClick = {
                                    showMenu = false
                                    showEditMeta = true
                                },
                            )
                            AppMenuItem(
                                text = stringResource(R.string.action_delete),
                                danger = true,
                                onClick = {
                                    showMenu = false
                                    showDelete = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding).fillMaxSize().contentMaxWidth(),
            contentPadding = PaddingValues(horizontal = AppSpacing.screenGutter, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                Surface(shape = AppShapes.medium, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                            if (b.description.isNotBlank()) {
                                Text(b.description, style = MaterialTheme.typography.bodySmall, color = colors.text.secondary)
                            }
                            Text(
                                stringResource(R.string.wb_detail_meta, stats.first, formatCharCount(stats.second)),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.text.secondary,
                            )
                        }
                        AppListDivider(startInset = 0.dp)
                        SettingsSwitchRow(
                            title = stringResource(R.string.wb_enable_book),
                            checked = b.enabled,
                            onCheckedChange = { viewModel.setBookEnabled(it) },
                        )
                        SettingsSwitchRow(
                            title = stringResource(R.string.wb_global_switch),
                            subtitle = stringResource(R.string.wb_global_switch_sub),
                            checked = b.isGlobal,
                            onCheckedChange = { viewModel.setBookGlobal(it) },
                        )
                        if (!b.isGlobal) {
                            AppListDivider(startInset = 0.dp)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showCharacters = true }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                                    boundCharacters.take(5).forEach { character ->
                                        CharacterAvatar(
                                            name = character.name,
                                            avatarPath = character.avatarPath,
                                            size = 24.dp,
                                            modifier = Modifier.border(1.5.dp, colors.surface.raised, CircleShape),
                                        )
                                    }
                                }
                                Text(
                                    if (boundCharacters.isEmpty()) {
                                        stringResource(R.string.wb_bound_row_empty)
                                    } else {
                                        stringResource(R.string.wb_bound_row, boundCharacters.size)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.text.secondary,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = colors.text.secondary,
                                )
                            }
                        }
                    }
                }
            }
            item(key = "guide") {
                WorldBookGuideCard(
                    hasEntries = stats.first > 0,
                    onPickCategory = { onCreateEntry(it.name) },
                )
            }
            item(key = "search") {
                AppSearchField(
                    value = searchQuery,
                    onValueChange = viewModel::setSearch,
                    placeholder = stringResource(R.string.wb_search_entries),
                )
            }
            if (entries.isEmpty()) {
                item(key = "emptyEntries") {
                    Text(
                        stringResource(R.string.wb_detail_no_entries),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.text.secondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    )
                }
            } else {
                items(entries, key = { it.uuid }) { entry ->
                    EntryRow(
                        entry = entry,
                        onClick = { onOpenEntry(entry.uuid) },
                        onToggle = { viewModel.setEntryEnabled(entry.uuid, it) },
                    )
                }
            }
            item(key = "addEntry") {
                Surface(
                    shape = AppShapes.medium,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth().clickableScale { onCreateEntry(null) },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = colors.accent.text,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            stringResource(R.string.wb_add_entry),
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.accent.text,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        }
    }

    if (showEditMeta) {
        BookMetaDialog(
            title = stringResource(R.string.wb_edit_meta_dialog_title),
            initialName = b.name,
            initialDescription = b.description,
            onConfirm = { name, desc ->
                viewModel.updateMeta(name, desc)
                showEditMeta = false
            },
            onDismiss = { showEditMeta = false },
        )
    }
    if (showDelete) {
        DeleteBookDialog(
            bookName = b.name,
            entryCount = stats.first,
            onConfirm = {
                showDelete = false
                viewModel.deleteBook(onDeleted = onBack)
            },
            onDismiss = { showDelete = false },
        )
    }
    if (showCharacters) {
        val allCharacters by viewModel.allCharacters.collectAsStateWithLifecycle()
        val boundUuids = boundCharacters.map { it.uuid }.toSet()
        AppSheet(onDismissRequest = { showCharacters = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                Text(
                    stringResource(R.string.wb_characters_sheet_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                allCharacters.forEach { character ->
                    val bound = character.uuid in boundUuids
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.toggleCharacter(character.uuid, !bound) }
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CharacterAvatar(name = character.name, avatarPath = character.avatarPath, size = 36.dp)
                        Text(
                            character.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.text.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Checkbox(checked = bound, onCheckedChange = { viewModel.toggleCharacter(character.uuid, it) })
                    }
                }
            }
        }
    }
}

/** 条目行：触发徽章 + 标题 + 关键词/内容预览 + 条目开关；点击进编辑器；停用整行淡显。 */
@Composable
private fun EntryRow(entry: WorldBookEntryEntity, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val colors = AppTheme.colors
    val keys = remember(entry.keysJson) { decodeStringList(entry.keysJson) }
    val title = entry.comment.ifBlank {
        entry.content.replace('\n', ' ').take(20).ifBlank { stringResource(R.string.wb_entry_untitled) }
    }
    val subtitle = if (!entry.constant && !entry.vectorized && keys.isNotEmpty()) {
        keys.joinToString("、")
    } else {
        entry.content.replace('\n', ' ').take(40)
    }
    Surface(
        shape = AppShapes.medium,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().alpha(if (entry.enabled) 1f else 0.55f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TriggerBadge(entry)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.text.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.text.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val toggleCd = title
            AppSwitch(
                checked = entry.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.semantics { contentDescription = toggleCd },
            )
        }
    }
}

/** 三色触发徽章：常驻 = info 家族（酒馆蓝灯）/ 关键词 = success 家族（绿灯）/ 语义 = accent 家族（契约 §12.8）。 */
@Composable
private fun TriggerBadge(entry: WorldBookEntryEntity) {
    val colors = AppTheme.colors
    val (bg, fg, labelRes) = when {
        entry.constant -> Triple(colors.status.infoContainer, colors.status.onInfo, R.string.wb_entry_badge_constant)
        entry.vectorized -> Triple(colors.accent.container, colors.accent.onContainer, R.string.wb_entry_badge_vector)
        else -> Triple(colors.status.successContainer, colors.status.onSuccess, R.string.wb_entry_badge_keyword)
    }
    Text(
        stringResource(labelRes),
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier.clip(AppShapes.full).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** 字数人话化：≥1 万显「N.N万」，否则原数。 */
internal fun formatCharCount(chars: Int): String =
    if (chars >= 10_000) String.format(Locale.ROOT, "%.1f万", chars / 10_000.0) else chars.toString()
