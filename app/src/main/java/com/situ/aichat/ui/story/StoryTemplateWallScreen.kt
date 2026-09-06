package com.situ.aichat.ui.story

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.UserStoryTemplateEntity
import com.situ.aichat.data.model.UserStoryTemplatePayload
import com.situ.aichat.story.StoryCreationCatalog
import com.situ.aichat.story.StoryNarrativePerson
import com.situ.aichat.story.StoryTemplate
import com.situ.aichat.story.StoryTemplates
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDialogTone
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 模板墙（ST7b·契约 §3.1 / §6.2·照 mockup 屏二）= 新默认创建入口：12 套模板卡两列瀑布（程序化封面 + 模板名 +
 * 钩子一行）+ 尾卡「自己从头写」。点模板 → [StoryOpenBookSheet] 三步开书；尾卡 / sheet「改一改再开」→ [onOpenCustom]
 * 进高级自定义（尾卡 null = 空表单·改一改带 templateId 预填）。开书成功 → [onCreated] 回书架看生成中卡片。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryTemplateWallScreen(
    onBack: () -> Unit,
    onOpenCustom: (templateId: String?) -> Unit,
    onCreated: () -> Unit,
    viewModel: StoryCreationViewModel = hiltViewModel(),
) {
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val creating by viewModel.creating.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val myTemplates by viewModel.userTemplates.collectAsStateWithLifecycle()

    var sheetTemplate by remember { mutableStateOf<StoryTemplate?>(null) }
    var menuTemplateUuid by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<UserStoryTemplateEntity?>(null) }
    var deleteTargetUuid by remember { mutableStateOf<String?>(null) }
    val templates = remember { StoryTemplates.all }
    val haptics = LocalAppHaptics.current
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.toastEvents.collect { resId -> Toast.makeText(context, resId, Toast.LENGTH_SHORT).show() }
    }

    val gridState = rememberLazyGridState()

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.story_new_story_title),
                onBack = onBack,
                // 创建中禁止退出：钮灰掉但仍在原位（图纸 §4.6）。
                backEnabled = !creating,
                lifted = gridState.canScrollBackward,
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            modifier = Modifier.fillMaxSize().padding(padding),
            // 屏 gutter 恒 20（设计语言 §2.5 军规）
            contentPadding = PaddingValues(horizontal = AppSpacing.screenGutter, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 图纸四「我的模板」区：有存货才出现（空 = 模板墙与现状零差异）。照 mockup 画面③本区领头，
            // 内置区的副标题降到两区之间——那一行同时是**换行符**，否则内置第一张卡会补进本区最后一行的
            // 空位、两区糊成一排（装机实测·图纸 §11 D-4）。
            // ⚠️ 首项 key 恒为 `_head`、恒存在（只换文案）：模板是从库里异步来的，若首项随之新增，
            // LazyGrid 会锚住原首项、把新插的两项顶到可视区**上方**——整个区肉眼看不见（同上实测）。
            item(span = { GridItemSpan(maxLineSpan) }, key = "_head") {
                SectionHeader(
                    stringResource(
                        if (myTemplates.isEmpty()) R.string.story_wall_subtitle else R.string.story_my_templates_header,
                    ),
                )
            }
            if (myTemplates.isNotEmpty()) {
                items(myTemplates, key = { it.uuid }) { row ->
                    val display = row.toDisplayTemplate(
                        tagline = stringResource(R.string.story_my_template_tagline, formatTemplateDate(row.createdAt)),
                    )
                    Box {
                        TemplateCard(
                            template = display,
                            onClick = { sheetTemplate = display },
                            onLongPress = { haptics.light(); menuTemplateUuid = row.uuid },
                        )
                        UserTemplateMenu(
                            expanded = menuTemplateUuid == row.uuid,
                            onDismiss = { menuTemplateUuid = null },
                            onRename = { menuTemplateUuid = null; renameTarget = row },
                            onDelete = { menuTemplateUuid = null; deleteTargetUuid = row.uuid },
                        )
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "_builtin_header") {
                    SectionHeader(stringResource(R.string.story_wall_subtitle))
                }
            }
            items(templates, key = { it.id }) { template ->
                TemplateCard(template, onClick = { sheetTemplate = template })
            }
            item(key = "_diy") { DiyCard(onClick = { onOpenCustom(null) }) }
        }
    }

    sheetTemplate?.let { template ->
        StoryOpenBookSheet(
            template = template,
            characters = characters,
            creating = creating,
            onStart = { roles, includeUser ->
                viewModel.createFromTemplate(template, roles, includeUser) { onCreated() }
            },
            onTweak = { sheetTemplate = null; onOpenCustom(template.id) },
            onDismiss = { sheetTemplate = null },
        )
    }

    renameTarget?.let { row ->
        TemplateNameDialog(
            titleRes = R.string.story_template_rename,
            initialName = row.name,
            onConfirm = { viewModel.renameUserTemplate(row.uuid, it); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTargetUuid?.let { uuid ->
        AppDialog(
            onDismissRequest = { deleteTargetUuid = null },
            title = stringResource(R.string.story_template_delete),
            body = stringResource(R.string.story_template_delete_confirm),
            confirmText = stringResource(R.string.story_template_delete),
            onConfirm = { viewModel.deleteUserTemplate(uuid); deleteTargetUuid = null },
            confirmTone = AppDialogTone.Danger,
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { deleteTargetUuid = null },
        )
    }

    error?.let { msg ->
        AppDialog(
            onDismissRequest = viewModel::dismissError,
            title = stringResource(R.string.story_create_failed),
            body = msg,
            confirmText = stringResource(R.string.action_confirm),
            onConfirm = viewModel::dismissError,
        )
    }
}

/** 区头（灰小字·样式与顶部副标题行同一对 token）。 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = AppTheme.typography.secondary,
        color = AppTheme.colors.text.secondary,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

/**
 * 用户模板 → 模板墙 / 开书 sheet 用的展示态 [StoryTemplate]（图纸四 §3.4）。
 *
 * id 带 `user:` 前缀：开书流据此分派到 [StoryTemplateAssembly.toCreationFormFromUserTemplate]。
 * payload 损坏（decode 得 null）时字段留空**照样出卡**——点它会收到「模板已损坏」提示、长按仍可删掉（E7）。
 * `coverMotif` 留空：卡与 sheet 的封面都由「题材配色 + id 种子」程序化生成，从不读 motif（图纸 §11 P-3）。
 */
@Composable
private fun UserStoryTemplateEntity.toDisplayTemplate(tagline: String): StoryTemplate {
    val payload = remember(payloadJson) { UserStoryTemplatePayload.decode(payloadJson) }
    return StoryTemplate(
        id = UserStoryTemplatePayload.USER_TEMPLATE_ID_PREFIX + uuid,
        title = name,
        tagline = tagline,
        genre = payload?.genre.orEmpty(),
        writingStyle = payload?.writingStyle.orEmpty(),
        narrativePerson = payload?.narrativePerson ?: StoryNarrativePerson.SECOND,
        worldSetting = payload?.worldSetting.orEmpty(),
        plotDirection = payload?.plotDirection.orEmpty(),
        roleHint = stringResource(R.string.story_my_template_role_hint),
        coverMotif = "",
    )
}

/** 卡片副行的存入日期（「8/2」·跟随系统语言与时区）。 */
private fun formatTemplateDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M/d"))

/** 「我的模板」卡长按菜单（族语言与书架卡菜单同源 = [StoryGlassMenu]）。 */
@Composable
private fun UserTemplateMenu(expanded: Boolean, onDismiss: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val colors = AppTheme.colors
    StoryGlassMenu(expanded = expanded, onDismiss = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.story_template_rename), style = AppTheme.typography.body, color = colors.text.primary) },
            leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(20.dp)) },
            onClick = onRename,
            modifier = Modifier.heightIn(min = 48.dp),
        )
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).height(0.75.dp).background(storyGlassMenuHairline()))
        DropdownMenuItem(
            text = { Text(stringResource(R.string.story_template_delete), style = AppTheme.typography.body, color = colors.status.onError) },
            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = colors.status.onError, modifier = Modifier.size(20.dp)) },
            onClick = onDelete,
            modifier = Modifier.heightIn(min = 48.dp),
        )
    }
}

/** 模板命名 / 重命名弹窗（造型同设定页存入弹窗；名字全空白时保存键禁用·E9）。 */
@Composable
private fun TemplateNameDialog(titleRes: Int, initialName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initialName)) }
    AppDialog(
        onDismissRequest = onDismiss,
        title = stringResource(titleRes),
        confirmText = stringResource(R.string.action_save),
        onConfirm = { onConfirm(value.text) },
        confirmEnabled = value.text.trim().isNotEmpty(),
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        content = {
            AppTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = stringResource(R.string.story_save_template_name_label),
            )
        },
    )
}

/** 模板卡：程序化封面（竖排书名 + 题材种子微变）+ 模板名 + 钩子一行。[onLongPress] 非空 = 「我的模板」卡（可管理）。 */
@Composable
private fun TemplateCard(template: StoryTemplate, onClick: () -> Unit, onLongPress: (() -> Unit)? = null) {
    val c = AppTheme.colors
    Column(
        Modifier.fillMaxWidth().clickableScale(onLongClick = onLongPress, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StoryCover(
            coverColorScheme = StoryCreationCatalog.coverColorScheme(template.genre),
            title = template.title,
            storyId = template.id,
            titleSizeSp = 13f,
            modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
        )
        Text(template.title, style = AppTheme.typography.label, color = c.text.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(template.tagline, style = AppTheme.typography.caption, color = c.text.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 尾卡「自己从头写」：虚线陶土框 3:4 + 笔 + 标题/副文案 → 空白高级自定义。 */
@Composable
private fun DiyCard(onClick: () -> Unit) {
    val c = AppTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(AppShapes.small)
            .background(c.accent.gradientStart.copy(alpha = 0.07f))
            .drawBehind {
                val r = 8.dp.toPx()
                drawRoundRect(
                    color = c.accent.text.copy(alpha = 0.4f),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                    cornerRadius = CornerRadius(r, r),
                )
            }
            .clickableScale(onClick = onClick)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(38.dp).clip(AppShapes.full).background(c.surface.sunken),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Create, contentDescription = null, tint = c.accent.text, modifier = Modifier.size(18.dp))
            }
            Text(stringResource(R.string.story_wall_diy_title), style = AppTheme.typography.label, color = c.accent.text, textAlign = TextAlign.Center)
            Text(stringResource(R.string.story_wall_diy_subtitle), style = AppTheme.typography.caption, color = c.text.tertiary, textAlign = TextAlign.Center)
        }
    }
}
