package com.situ.aichat.ui.worldbook

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.WorldBookSummary
import com.situ.aichat.data.worldbook.WorldBookTemplate
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppFeatureIcons
import com.situ.aichat.ui.designsystem.AppMenu
import com.situ.aichat.ui.designsystem.AppMenuItem
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppSwitch
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.appCardSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设定集书架（WB7a+WB8·契约 §12.2）：常驻「导入 / 新建」双入口 + 我的书列表（开关 = 整本启停·
 * 长按 = 导出/全局/编辑/删除）+ 「从模板开始」预置区（模板卡一键复制成「我的书」后直达详情）+ 空态引导。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldBookShelfScreen(
    onBack: () -> Unit,
    onOpenBook: (bookUuid: String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: WorldBookShelfViewModel = hiltViewModel(),
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val importFeedback by viewModel.importFeedback.collectAsStateWithLifecycle()
    val exportResult by viewModel.exportResult.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showCreateDialog by remember { mutableStateOf(false) }
    var editMetaFor by remember { mutableStateOf<WorldBookSummary?>(null) }
    var deleteFor by remember { mutableStateOf<WorldBookSummary?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var pendingExportUuid by remember { mutableStateOf<String?>(null) }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val fallbackName = queryDisplayName(context, uri)
                ?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.wb_shelf_title)
            viewModel.import(fallbackName) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
                    }.getOrNull()
                }
            }
        }
    }
    val exportCreator = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val bookUuid = pendingExportUuid
        pendingExportUuid = null
        if (uri != null && bookUuid != null) {
            viewModel.exportBook(bookUuid) { json ->
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

    val listState = rememberLazyListState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.wb_shelf_title),
                onBack = onBack,
                lifted = listState.canScrollBackward,
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.wb_cd_trigger_settings))
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
            item(key = "actions") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ShelfActionTile(
                        icon = Icons.Filled.FileDownload,
                        titleRes = R.string.wb_import,
                        subtitleRes = R.string.wb_import_subtitle,
                        modifier = Modifier.weight(1f),
                        onClick = { importPicker.launch(arrayOf("*/*")) },
                    )
                    ShelfActionTile(
                        icon = Icons.Filled.Edit,
                        titleRes = R.string.wb_create,
                        subtitleRes = R.string.wb_create_subtitle,
                        modifier = Modifier.weight(1f),
                        onClick = { showCreateDialog = true },
                    )
                }
            }
            if (books.isEmpty()) {
                item(key = "empty") { EmptyShelfState() }
            } else {
                item(key = "sectionMy") {
                    Text(
                        stringResource(R.string.wb_my_books),
                        style = MaterialTheme.typography.titleSmall,
                        color = AppTheme.colors.text.secondary,
                        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                    )
                }
                items(books, key = { it.book.uuid }) { summary ->
                    BookCard(
                        summary = summary,
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
            // 「从模板开始」区（WB8·契约 §12.2）：区块空时隐藏；复制成「我的书」后直达详情。
            if (viewModel.templates.isNotEmpty()) {
                item(key = "sectionTemplates") {
                    Text(
                        stringResource(R.string.wb_templates_section),
                        style = MaterialTheme.typography.titleSmall,
                        color = AppTheme.colors.text.secondary,
                        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                    )
                }
                items(viewModel.templates, key = { "tpl_${it.id}" }) { template ->
                    TemplateCard(
                        template = template,
                        onCopy = { viewModel.copyTemplate(template) { onOpenBook(it) } },
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        BookMetaDialog(
            title = stringResource(R.string.wb_create_dialog_title),
            initialName = "",
            initialDescription = "",
            onConfirm = { name, desc ->
                showCreateDialog = false
                viewModel.createBook(name, desc) { onOpenBook(it) }
            },
            onDismiss = { showCreateDialog = false },
        )
    }
    editMetaFor?.let { s ->
        BookMetaDialog(
            title = stringResource(R.string.wb_edit_meta_dialog_title),
            initialName = s.book.name,
            initialDescription = s.book.description,
            onConfirm = { name, desc ->
                viewModel.updateBookMeta(s.book.uuid, name, desc)
                editMetaFor = null
            },
            onDismiss = { editMetaFor = null },
        )
    }
    deleteFor?.let { s ->
        DeleteBookDialog(
            bookName = s.book.name,
            entryCount = s.entryCount,
            onConfirm = {
                viewModel.deleteBook(s.book.uuid)
                deleteFor = null
            },
            onDismiss = { deleteFor = null },
        )
    }
    when (val fb = importFeedback) {
        is WorldBookImportFeedback.Success -> ImportSuccessSheet(
            result = fb.result,
            onView = {
                viewModel.dismissImportFeedback()
                onOpenBook(it)
            },
            onDone = { viewModel.dismissImportFeedback() },
        )
        is WorldBookImportFeedback.Failure -> ImportFailureDialog(fb.parseMessage) { viewModel.dismissImportFeedback() }
        null -> Unit
    }
}

/** 「导入 / 新建」半宽动作 tile：陶土小方块图标 + 标题 + 一句副文案。 */
@Composable
private fun ShelfActionTile(
    icon: ImageVector,
    titleRes: Int,
    subtitleRes: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    // tonal Surface → appCardSurface；clickableScale 移到 appCardSurface 之后（J4·裁 ripple）·内容零改。
    Column(modifier.fillMaxWidth().appCardSurface().clickableScale { onClick() }) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(colors.accent.container),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(22.dp))
            }
            Column {
                Text(stringResource(titleRes), style = MaterialTheme.typography.titleSmall, color = colors.text.primary)
                Text(
                    stringResource(subtitleRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 书卡：封面 tile + 名字（+全局徽章）+ 元信息 + 启用开关；长按出管理菜单。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    summary: WorldBookSummary,
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
    // tonal Surface → appCardSurface（alpha 保留在前·整卡随启用态调暗）；combinedClickable 在内 Row 天然受卡 clip 裁剪。
    Column(Modifier.fillMaxWidth().alpha(if (book.enabled) 1f else 0.55f).appCardSurface()) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onLongClickLabel = stringResource(R.string.wb_cd_more_actions),
                        onLongClick = onLongClick,
                        onClick = onClick,
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            book.name.ifBlank { stringResource(R.string.wb_entry_untitled) },
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.text.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (book.isGlobal) {
                            Text(
                                stringResource(R.string.wb_badge_global),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.text.secondary,
                                modifier = Modifier
                                    .clip(AppShapes.full)
                                    .background(colors.surface.sunken)
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                    val meta = when {
                        book.isGlobal -> stringResource(R.string.wb_book_meta_global, summary.entryCount)
                        summary.boundCount == 0 -> stringResource(R.string.wb_book_meta_unbound, summary.entryCount)
                        else -> stringResource(R.string.wb_book_meta, summary.entryCount, summary.boundCount)
                    }
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.text.secondary, maxLines = 1)
                }
                val enableCd = stringResource(R.string.wb_enable_book)
                AppSwitch(
                    checked = book.enabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.semantics { contentDescription = enableCd },
                )
            }
            AppMenu(expanded = menuExpanded, onDismiss = onDismissMenu) {
                AppMenuItem(
                    text = stringResource(R.string.wb_menu_export),
                    onClick = {
                        onDismissMenu()
                        onExport()
                    },
                )
                AppMenuItem(
                    text = stringResource(if (book.isGlobal) R.string.wb_menu_unset_global else R.string.wb_menu_set_global),
                    onClick = {
                        onDismissMenu()
                        onToggleGlobal()
                    },
                )
                AppMenuItem(
                    text = stringResource(R.string.wb_menu_edit_meta),
                    onClick = {
                        onDismissMenu()
                        onEditMeta()
                    },
                )
                AppMenuItem(
                    text = stringResource(R.string.action_delete),
                    danger = true,
                    onClick = {
                        onDismissMenu()
                        onDelete()
                    },
                )
            }
        }
    }
}

/** 模板卡（WB8·契约 §12.2）：封面 tile + 名字 + 一句简介 + 条目数元行 + 尾部「复制」Tonal 钮。 */
@Composable
private fun TemplateCard(template: WorldBookTemplate, onCopy: () -> Unit) {
    val colors = AppTheme.colors
    // tonal Surface → appCardSurface·内容零改。
    Column(Modifier.fillMaxWidth().appCardSurface()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(colors.surface.sunken),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppFeatureIcons.Worldbook, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(22.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    template.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    template.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.wb_template_meta, template.entries.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text.tertiary,
                    maxLines = 1,
                )
            }
            AppButton(onClick = onCopy, style = AppButtonStyle.Tonal) {
                Text(stringResource(R.string.wb_template_copy))
            }
        }
    }
}

/** 空态：暖圆底书本图标 + 引导两行（双动作 tile 常驻在上方，不重复给按钮）。 */
@Composable
private fun EmptyShelfState() {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier.size(64.dp).clip(CircleShape).background(colors.surface.sunken),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppFeatureIcons.Worldbook, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.wb_empty_title), style = MaterialTheme.typography.titleMedium, color = colors.text.primary)
        Text(
            stringResource(R.string.wb_empty_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = colors.text.secondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** SAF uri → 文件显示名（拿不到给 null，调用方兜底）。 */
internal fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()
