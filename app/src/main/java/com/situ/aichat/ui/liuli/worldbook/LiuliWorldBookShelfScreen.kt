package com.situ.aichat.ui.liuli.worldbook

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.WorldBookSummary
import com.situ.aichat.data.worldbook.WorldBookTemplate
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppFeatureIcons
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliMenuEntry
import com.situ.aichat.ui.liuli.designsystem.LiuliPopupMenu
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.LiuliSwitch
import com.situ.aichat.ui.liuli.designsystem.LiuliTileTone
import com.situ.aichat.ui.liuli.designsystem.liuliCardMaterial
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliNavRow
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageCircleAction
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliSectionHeader
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import com.situ.aichat.ui.worldbook.BookMetaDialog
import com.situ.aichat.ui.worldbook.DeleteBookDialog
import com.situ.aichat.ui.worldbook.ImportFailureDialog
import com.situ.aichat.ui.worldbook.ImportSuccessSheet
import com.situ.aichat.ui.worldbook.WorldBookEmptyShelfState
import com.situ.aichat.ui.worldbook.WorldBookImportFeedback
import com.situ.aichat.ui.worldbook.WorldBookShelfViewModel
import com.situ.aichat.ui.worldbook.queryDisplayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 设定集书架（琉璃 2.0 脸）。 */
@Composable
fun LiuliWorldBookShelfScreen(
    onBack: () -> Unit,
    onOpenBook: (bookUuid: String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: WorldBookShelfViewModel = hiltViewModel(),
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val importFeedback by viewModel.importFeedback.collectAsStateWithLifecycle()
    val exportResult by viewModel.exportResult.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val dark = LocalIsDarkTheme.current
    val title = stringResource(R.string.wb_shelf_title)
    val exportOkMsg = stringResource(R.string.wb_export_success)
    val exportFailMsg = stringResource(R.string.wb_export_failed)

    var showCreateDialog by remember { mutableStateOf(false) }
    var editMetaFor by remember { mutableStateOf<WorldBookSummary?>(null) }
    var deleteFor by remember { mutableStateOf<WorldBookSummary?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var pendingExportUuid by remember { mutableStateOf<String?>(null) }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val fallbackName = queryDisplayName(context, uri)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: title
            viewModel.import(fallbackName) {
                withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull()
                }
            }
        }
    }
    val exportCreator = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val bookUuid = pendingExportUuid
        pendingExportUuid = null
        if (uri != null && bookUuid != null) {
            viewModel.exportBook(bookUuid) { json ->
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

    val listState = rememberLazyListState()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LiuliPage(
        title = title,
        onBack = onBack,
        collapsed = rememberLargeTitleCollapsed(listState),
        actions = {
            LiuliPageCircleAction(
                onClick = onOpenSettings,
                contentDescription = stringResource(R.string.wb_cd_trigger_settings),
                icon = Icons.Filled.Tune,
            )
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
            item(key = "actions") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiuliShelfActionTile(AppActionIcons.Download, R.string.wb_import, R.string.wb_import_subtitle, dark, Modifier.weight(1f)) {
                        importPicker.launch(arrayOf("*/*"))
                    }
                    LiuliShelfActionTile(AppActionIcons.Edit, R.string.wb_create, R.string.wb_create_subtitle, dark, Modifier.weight(1f)) {
                        showCreateDialog = true
                    }
                }
            }
            item(key = "triggerSettings") {
                LiuliGroup {
                    LiuliNavRow(
                        title = stringResource(R.string.wb_cd_trigger_settings),
                        subtitle = stringResource(R.string.settings_wb_trigger_row_sub),
                        icon = Icons.Filled.Tune,
                        tileColor = LiuliTileTone.Gold,
                        divider = false,
                        onClick = onOpenSettings,
                    )
                }
            }
            if (books.isEmpty()) {
                item(key = "empty") { WorldBookEmptyShelfState() }
            } else {
                item(key = "sectionMy") { LiuliSectionHeader(stringResource(R.string.wb_my_books)) }
                items(books, key = { it.book.uuid }) { summary ->
                    LiuliBookCard(
                        summary = summary,
                        dark = dark,
                        menuExpanded = menuFor == summary.book.uuid,
                        onClick = { onOpenBook(summary.book.uuid) },
                        onLongClick = { menuFor = summary.book.uuid },
                        onDismissMenu = { menuFor = null },
                        onToggleEnabled = { viewModel.setBookEnabled(summary.book.uuid, it) },
                        onExport = {
                            pendingExportUuid = summary.book.uuid
                            exportCreator.launch("${summary.book.name.ifBlank { "worldbook" }}.json")
                        },
                        onToggleGlobal = { viewModel.setBookGlobal(summary.book.uuid, !summary.book.isGlobal) },
                        onEditMeta = { editMetaFor = summary },
                        onDelete = { deleteFor = summary },
                    )
                }
            }
            if (viewModel.templates.isNotEmpty()) {
                item(key = "sectionTemplates") { LiuliSectionHeader(stringResource(R.string.wb_templates_section)) }
                items(viewModel.templates, key = { "tpl_${it.id}" }) { template ->
                    LiuliTemplateCard(template, dark) { viewModel.copyTemplate(template) { onOpenBook(it) } }
                }
            }
        }
    }

    if (showCreateDialog) {
        BookMetaDialog(stringResource(R.string.wb_create_dialog_title), "", "", onConfirm = { n, d ->
            showCreateDialog = false; viewModel.createBook(n, d) { onOpenBook(it) }
        }, onDismiss = { showCreateDialog = false })
    }
    editMetaFor?.let { s ->
        BookMetaDialog(stringResource(R.string.wb_edit_meta_dialog_title), s.book.name, s.book.description, onConfirm = { n, d ->
            viewModel.updateBookMeta(s.book.uuid, n, d); editMetaFor = null
        }, onDismiss = { editMetaFor = null })
    }
    deleteFor?.let { s ->
        DeleteBookDialog(s.book.name, s.entryCount, onConfirm = { viewModel.deleteBook(s.book.uuid); deleteFor = null }, onDismiss = { deleteFor = null })
    }
    when (val fb = importFeedback) {
        is WorldBookImportFeedback.Success -> ImportSuccessSheet(fb.result, onView = { viewModel.dismissImportFeedback(); onOpenBook(it) }, onDone = { viewModel.dismissImportFeedback() })
        is WorldBookImportFeedback.Failure -> ImportFailureDialog(fb.parseMessage) { viewModel.dismissImportFeedback() }
        null -> Unit
    }
}

@Composable
private fun LiuliShelfActionTile(icon: ImageVector, titleRes: Int, subtitleRes: Int, dark: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().liuliCardMaterial(LiuliShapes.group, dark).clickableScale { onClick() }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(colors.accent.container), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(22.dp))
        }
        Column {
            Text(stringResource(titleRes), style = AppTypography.titleSmall, color = colors.text.primary)
            Text(stringResource(subtitleRes), style = AppTypography.caption, color = colors.text.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiuliBookCard(
    summary: WorldBookSummary,
    dark: Boolean,
    menuExpanded: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDismissMenu: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onExport: () -> Unit,
    onToggleGlobal: () -> Unit,
    onEditMeta: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = AppTheme.colors
    val book = summary.book
    Box(Modifier.fillMaxWidth().alpha(if (book.enabled) 1f else 0.55f).liuliCardMaterial(LiuliShapes.group, dark)) {
        Row(
            modifier = Modifier.fillMaxWidth().combinedClickable(
                onLongClickLabel = stringResource(R.string.wb_cd_more_actions),
                onLongClick = onLongClick,
                onClick = onClick,
            ).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(colors.accent.container), contentAlignment = Alignment.Center) {
                Icon(AppFeatureIcons.Worldbook, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(22.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(book.name.ifBlank { stringResource(R.string.wb_entry_untitled) }, style = AppTypography.titleMedium, color = colors.text.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (book.isGlobal) {
                        Text(stringResource(R.string.wb_badge_global), style = AppTypography.label, color = colors.text.secondary, modifier = Modifier.clip(AppShapes.full).background(colors.surface.sunken).padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
                val meta = when {
                    book.isGlobal -> stringResource(R.string.wb_book_meta_global, summary.entryCount)
                    summary.boundCount == 0 -> stringResource(R.string.wb_book_meta_unbound, summary.entryCount)
                    else -> stringResource(R.string.wb_book_meta, summary.entryCount, summary.boundCount)
                }
                Text(meta, style = AppTypography.caption, color = colors.text.secondary, maxLines = 1)
            }
            val enableCd = stringResource(R.string.wb_enable_book)
            LiuliSwitch(checked = book.enabled, onCheckedChange = onToggleEnabled, modifier = Modifier.semantics { contentDescription = enableCd })
        }
        LiuliPopupMenu(
            expanded = menuExpanded,
            onDismiss = onDismissMenu,
            items = listOf(
                LiuliMenuEntry(stringResource(R.string.wb_menu_export), onClick = onExport),
                LiuliMenuEntry(stringResource(if (book.isGlobal) R.string.wb_menu_unset_global else R.string.wb_menu_set_global), onClick = onToggleGlobal),
                LiuliMenuEntry(stringResource(R.string.wb_menu_edit_meta), onClick = onEditMeta),
                LiuliMenuEntry(stringResource(R.string.action_delete), danger = true, onClick = onDelete),
            ),
        )
    }
}

@Composable
private fun LiuliTemplateCard(template: WorldBookTemplate, dark: Boolean, onCopy: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().liuliCardMaterial(LiuliShapes.group, dark).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(colors.surface.sunken), contentAlignment = Alignment.Center) {
            Icon(AppFeatureIcons.Worldbook, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(22.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(template.name, style = AppTypography.titleMedium, color = colors.text.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(template.description, style = AppTypography.caption, color = colors.text.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.wb_template_meta, template.entries.size), style = AppTypography.caption, color = colors.text.tertiary, maxLines = 1)
        }
        LiuliButton(onClick = onCopy, style = LiuliButtonStyle.Glass) { Text(stringResource(R.string.wb_template_copy)) }
    }
}
