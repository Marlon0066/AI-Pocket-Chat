package com.situ.aichat.ui.story

import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.outlined.Archive
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDialogTone
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.AppTopBarAction
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

    StoryBookshelfEffects(viewModel, onOpenChapter, onOpenStory)

    val split = storyShelfSplit(stories)

    // 菜单开着时书被并行删除/换区：条目连菜单一起从树上消失，onDismiss 不会再回调——兜底清态，
    // 否则 scrim 压暗层卡住不走（在读卡与归档卡两族菜单同护）。
    LaunchedEffect(stories) { if (storyMenuStale(menuStoryId, stories)) menuStoryId = null }

    val listState = rememberLazyListState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.story_nav_title),
                onBack = onBack,
                lifted = listState.canScrollBackward,
                actions = {
                    AppTopBarAction(
                        icon = AppTopBarIcons.Add,
                        contentDescription = stringResource(R.string.story_create_new),
                        onClick = onCreateStory,
                    )
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            if (stories.isEmpty()) {
                StoryEmptyState(Modifier.padding(padding)) {
                    AppButton(onClick = onCreateStory, style = AppButtonStyle.Primary) { Text(stringResource(R.string.story_new_story_title)) }
                }
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
                    items(split.active, key = { it.id }) { story ->
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
                                        action.dispatch(
                                            onTogglePause = { viewModel.togglePause(story) },
                                            onArchive = { archiveTargetId = story.id },
                                            onOpenSettings = { onOpenSettings(story.id) },
                                            onDelete = { deleteTarget = story },
                                        )
                                    },
                                )
                            }
                        }
                    }
                    item(key = "_newstory") { NewStoryCard(onClick = onCreateStory) }
                    if (split.archived.isNotEmpty()) {
                        item(key = "_archive") {
                            StoryArchiveSection(
                                split.archived,
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
        storyActiveById(stories, targetId)?.let { target ->
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
        storyArchivedById(stories, targetId)?.let { target ->
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

internal fun menuActionLabel(action: StoryCardMenuAction): Int = when (action) {
    StoryCardMenuAction.PAUSE -> R.string.story_menu_pause
    StoryCardMenuAction.RESUME -> R.string.story_menu_resume
    StoryCardMenuAction.ARCHIVE -> R.string.story_menu_archive
    StoryCardMenuAction.SETTINGS -> R.string.story_menu_settings
    StoryCardMenuAction.DELETE -> R.string.story_menu_delete
}

internal fun menuActionIcon(action: StoryCardMenuAction): ImageVector = when (action) {
    StoryCardMenuAction.PAUSE -> AppActionIcons.Pause
    StoryCardMenuAction.RESUME -> AppActionIcons.Play
    StoryCardMenuAction.ARCHIVE -> Icons.Outlined.Archive
    StoryCardMenuAction.SETTINGS -> Icons.AutoMirrored.Outlined.MenuBook
    StoryCardMenuAction.DELETE -> AppActionIcons.Delete
}

@Composable
internal fun StoryEmptyState(modifier: Modifier = Modifier, action: @Composable () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StoryEmptyBookIllustration()
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.story_empty_title), style = AppTheme.typography.titleSmall, color = AppTheme.colors.text.primary)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.story_empty_subtitle),
            style = AppTheme.typography.secondary,
            color = AppTheme.colors.text.secondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        action()
    }
}

/** 书架空态自绘线描摊开书本插画（取代系统 📚 emoji，双脸主题色自动适配）。 */
@Composable
private fun StoryEmptyBookIllustration() {
    val colors = AppTheme.colors
    val strokeColor = colors.text.secondary.copy(alpha = 0.72f)
    val accentColor = colors.accent.primary
    val softWash = colors.accent.primary.copy(alpha = 0.12f)
    Canvas(Modifier.size(72.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        // 温润光晕底
        drawCircle(color = softWash, radius = w * 0.42f, center = Offset(w * 0.5f, h * 0.52f))
        // 左页
        val leftPage = Path().apply {
            moveTo(w * 0.50f, h * 0.34f)
            quadraticTo(w * 0.36f, h * 0.28f, w * 0.20f, h * 0.32f)
            lineTo(w * 0.20f, h * 0.68f)
            quadraticTo(w * 0.36f, h * 0.64f, w * 0.50f, h * 0.71f)
            close()
        }
        // 右页
        val rightPage = Path().apply {
            moveTo(w * 0.50f, h * 0.34f)
            quadraticTo(w * 0.64f, h * 0.28f, w * 0.80f, h * 0.32f)
            lineTo(w * 0.80f, h * 0.68f)
            quadraticTo(w * 0.64f, h * 0.64f, w * 0.50f, h * 0.71f)
            close()
        }
        drawPath(leftPage, color = strokeColor, style = stroke)
        drawPath(rightPage, color = strokeColor, style = stroke)
        // 右页文字线
        drawLine(strokeColor.copy(alpha = 0.45f), Offset(w * 0.58f, h * 0.44f), Offset(w * 0.72f, h * 0.42f), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
        drawLine(strokeColor.copy(alpha = 0.45f), Offset(w * 0.58f, h * 0.53f), Offset(w * 0.70f, h * 0.51f), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
        // 顶部星芒点缀
        val sx = w * 0.50f
        val sy = h * 0.19f
        val sr = w * 0.055f
        drawLine(accentColor, Offset(sx, sy - sr), Offset(sx, sy + sr), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
        drawLine(accentColor, Offset(sx - sr, sy), Offset(sx + sr, sy), strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
    }
}
