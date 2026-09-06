package com.situ.aichat.ui.story

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.story.StoryStatus
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDialogTone
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.AppTopBarIcons

/**
 * 书架主界面（ST7a 换装·1:1 iOS `StoryBookshelfView`）：在读大卡平铺 + 「开新故事」入场卡 + 「档案」已完结横排分组；
 * 长按操作菜单（暂停·查看设定·删除）+ 顶栏「+」创建 + 续读异步导航。从朋友圈枢纽 momentsStory 进入。
 *
 * [onOpenStory]→章节列表；[onOpenChapter]→阅读器；[onCreateStory]→创建（ST7b 模板墙）；[onOpenSettings]→设定；
 * [onOpenArchive]→结局档案卡（ST8·完结卡 tap 走此，不再进阅读器）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryBookshelfScreen(
    onBack: () -> Unit,
    onOpenStory: (String) -> Unit,
    onOpenChapter: (String) -> Unit,
    onCreateStory: () -> Unit,
    onOpenSettings: (String) -> Unit,
    onOpenArchive: (String) -> Unit,
    onViewAllArchive: () -> Unit,
    viewModel: StoryBookshelfViewModel = hiltViewModel(),
) {
    val stories by viewModel.stories.collectAsStateWithLifecycle()
    val activeGenerations by viewModel.activeGenerations.collectAsStateWithLifecycle()
    val lastReadNumbers by viewModel.lastReadChapterNumbers.collectAsStateWithLifecycle()
    var menuStoryId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<StoryEntity?>(null) }
    // 归档/归档卡删除确认只存 id 不存对象快照（PITFALLS 1b）：渲染时从当前流解析，书没了弹窗自然消失。
    var archiveTargetId by remember { mutableStateOf<String?>(null) }
    var archivedDeleteId by remember { mutableStateOf<String?>(null) }
    val haptics = LocalAppHaptics.current
    val context = LocalContext.current

    // 归档结果一次性提示（成功入档 / 生成中拒绝）。
    LaunchedEffect(Unit) {
        viewModel.toastEvents.collect { resId -> Toast.makeText(context, resId, Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(Unit) { viewModel.refreshReadingProgress() }
    // 续读异步结果 → 导航（有可读章进阅读器，否则退回章节列表）。
    LaunchedEffect(Unit) {
        viewModel.resumeTarget.collect { target ->
            when (target) {
                is StoryResumeTarget.Reader -> onOpenChapter(target.chapterId)
                is StoryResumeTarget.ChapterList -> onOpenStory(target.storyId)
            }
        }
    }

    val archived = stories.filter { it.status == StoryStatus.COMPLETED }
    val active = stories.filter { it.status != StoryStatus.COMPLETED }

    // 菜单开着时书被并行删除/换区：条目连菜单一起从树上消失，onDismiss 不会再回调——兜底清态，
    // 否则 scrim 压暗层卡住不走（在读卡与归档卡两族菜单同护）。
    LaunchedEffect(stories) {
        menuStoryId?.let { open -> if (stories.none { it.id == open }) menuStoryId = null }
    }

    val listState = rememberLazyListState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.story_nav_title),
                onBack = onBack,
                lifted = listState.canScrollBackward,
                actions = {
                    IconButton(onClick = onCreateStory) {
                        Icon(AppTopBarIcons.Add, contentDescription = stringResource(R.string.story_create_new))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            if (stories.isEmpty()) {
                StoryEmptyState(onCreateStory, Modifier.padding(padding))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    // 屏 gutter 恒 20（设计语言 §2.5 军规）
                    contentPadding = PaddingValues(horizontal = AppSpacing.screenGutter, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "_tagline") {
                        Text(
                            stringResource(R.string.story_shelf_subtitle),
                            style = AppTheme.typography.secondary,
                            color = AppTheme.colors.text.secondary,
                        )
                    }
                    items(active, key = { it.id }) { story ->
                        Box {
                            StoryCard(
                                story = story,
                                generation = activeGenerations[story.id],
                                lastReadChapterNumber = lastReadNumbers[story.id],
                                onOpenStory = { onOpenStory(story.id) },
                                onLongPress = { haptics.light(); menuStoryId = story.id },
                                onContinueReading = { viewModel.continueReading(story) },
                                onRetry = { viewModel.retryGeneration(story) },
                            )
                            // 菜单锚：贴卡片右缘、标题排下方展开（ST10-1 锚定修复同款——包 Box 定位，
                            // 否则 DropdownMenu 锚整个卡片 Box、弹到左缘）。
                            Box(Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 12.dp)) {
                                StoryCardGlassMenu(
                                    expanded = menuStoryId == story.id,
                                    actions = StoryCardLogic.menuActions(story.status, story.updateMode),
                                    onDismiss = { menuStoryId = null },
                                    onAction = { action ->
                                        menuStoryId = null
                                        when (action) {
                                            StoryCardMenuAction.PAUSE, StoryCardMenuAction.RESUME -> viewModel.togglePause(story)
                                            StoryCardMenuAction.ARCHIVE -> archiveTargetId = story.id
                                            StoryCardMenuAction.SETTINGS -> onOpenSettings(story.id)
                                            StoryCardMenuAction.DELETE -> deleteTarget = story
                                        }
                                    },
                                )
                            }
                        }
                    }
                    item(key = "_newstory") { NewStoryCard(onClick = onCreateStory) }
                    if (archived.isNotEmpty()) {
                        item(key = "_archive") {
                            StoryArchiveSection(
                                archived,
                                onOpen = onOpenArchive,
                                onViewAll = onViewAllArchive,
                                menuStoryId = menuStoryId,
                                onCardLongPress = { haptics.light(); menuStoryId = it },
                                onMenuDismiss = { menuStoryId = null },
                                onDeleteRequest = { menuStoryId = null; archivedDeleteId = it },
                            )
                        }
                    }
                }
            }
            // 长按菜单期背景轻压暗（拍板②·恒黑 10%）：菜单为 Popup 恒浮其上；被按卡片不单独提亮
            // （列表层级限制，微图纸 §6 登记偏差），突出感由亮玻璃菜单承担。共用件已含 reduceMotion 直显隐。
            StoryShelfMenuScrim(visible = menuStoryId != null)
        }
    }

    archiveTargetId?.let { targetId ->
        // 从当前流解析目标（只存 id）：书已不在（并行删除/已完结）→ 不渲染，弹窗自然消失。
        stories.firstOrNull { it.id == targetId && it.status != StoryStatus.COMPLETED }?.let { target ->
            AppDialog(
                onDismissRequest = { archiveTargetId = null },
                title = stringResource(R.string.story_archive_confirm_title),
                body = stringResource(R.string.story_archive_confirm_msg, target.title),
                confirmText = stringResource(R.string.story_archive_confirm_action),
                onConfirm = { viewModel.archiveStory(target.id); archiveTargetId = null },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { archiveTargetId = null },
            )
        }
    }

    deleteTarget?.let { target ->
        AppDialog(
            onDismissRequest = { deleteTarget = null },
            title = stringResource(R.string.story_menu_delete),
            body = target.title,
            confirmText = stringResource(R.string.story_action_delete),
            onConfirm = { viewModel.deleteStory(target.id); deleteTarget = null },
            confirmTone = AppDialogTone.Danger,
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { deleteTarget = null },
        )
    }

    archivedDeleteId?.let { targetId ->
        // 从当前流按 id + 已完结解析（PITFALLS 1b）：书被并行删除/状态异动 → 不渲染，弹窗自然消失。
        stories.firstOrNull { it.id == targetId && it.status == StoryStatus.COMPLETED }?.let { target ->
            StoryArchivedDeleteDialog(
                story = target,
                onConfirm = { viewModel.deleteStory(target.id); archivedDeleteId = null },
                onDismiss = { archivedDeleteId = null },
            )
        }
    }
}

/**
 * 书架卡长按玻璃菜单（ST10-4·mockup story_shelf_menu_mockup 过审）。容器造型（20dp 圆角 + 表面 94% 垫底 +
 * 0.75dp 发丝边 + 软投影 + 216dp 宽）见共用的 [StoryGlassMenu]；动作行 = 暖陶前导图标 + 文案（DropdownMenuItem
 * 自带 48dp 行高与涟漪）；危险项（删除）红色、与前组发丝分隔。菜单项按状态组装见 [StoryCardLogic.menuActions]。
 */
@Composable
private fun StoryCardGlassMenu(
    expanded: Boolean,
    actions: List<StoryCardMenuAction>,
    onDismiss: () -> Unit,
    onAction: (StoryCardMenuAction) -> Unit,
) {
    val colors = AppTheme.colors
    val hairline = storyGlassMenuHairline()
    // 容器造型（圆角/垫底/发丝边/投影/宽度）已抽进共用的 [StoryGlassMenu]，与模板墙「我的模板」卡菜单同源。
    StoryGlassMenu(expanded = expanded, onDismiss = onDismiss) {
        actions.forEach { action ->
            val danger = action == StoryCardMenuAction.DELETE
            if (danger) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .height(0.75.dp)
                        .background(hairline),
                )
            }
            val contentColor = if (danger) colors.status.onError else colors.text.primary
            val iconTint = if (danger) colors.status.onError else colors.accent.text
            DropdownMenuItem(
                text = { Text(stringResource(menuActionLabel(action)), style = AppTheme.typography.body, color = contentColor) },
                leadingIcon = { Icon(menuActionIcon(action), contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp)) },
                onClick = { onAction(action) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

private fun menuActionLabel(action: StoryCardMenuAction): Int = when (action) {
    StoryCardMenuAction.PAUSE -> R.string.story_menu_pause
    StoryCardMenuAction.RESUME -> R.string.story_menu_resume
    StoryCardMenuAction.ARCHIVE -> R.string.story_menu_archive
    StoryCardMenuAction.SETTINGS -> R.string.story_menu_settings
    StoryCardMenuAction.DELETE -> R.string.story_menu_delete
}

private fun menuActionIcon(action: StoryCardMenuAction): ImageVector = when (action) {
    StoryCardMenuAction.PAUSE -> Icons.Filled.Pause
    StoryCardMenuAction.RESUME -> Icons.Filled.PlayArrow
    StoryCardMenuAction.ARCHIVE -> Icons.Outlined.Archive
    StoryCardMenuAction.SETTINGS -> Icons.AutoMirrored.Outlined.MenuBook
    StoryCardMenuAction.DELETE -> Icons.Outlined.Delete
}

@Composable
private fun StoryEmptyState(onCreate: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("📚", style = AppTheme.typography.titleLarge.copy(fontSize = 52.sp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.story_empty_title), style = AppTheme.typography.titleSmall, color = AppTheme.colors.text.primary)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.story_empty_subtitle),
            style = AppTheme.typography.secondary,
            color = AppTheme.colors.text.secondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        AppButton(onClick = onCreate, style = AppButtonStyle.Primary) { Text(stringResource(R.string.story_new_story_title)) }
    }
}
