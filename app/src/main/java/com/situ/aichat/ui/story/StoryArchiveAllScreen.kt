package com.situ.aichat.ui.story

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar

/**
 * 结局档案全览（ST8·契约 §5·照 mockup 封面网格 .allcv）：全部已完结故事的封面网格；点封面 → 结局档案卡；
 * 长按 → 玻璃菜单删除（2026-08-04 卷·与书架档案横排同一套共用件与措辞）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryArchiveAllScreen(
    onBack: () -> Unit,
    onOpenArchive: (String) -> Unit,
    viewModel: StoryArchiveAllViewModel = hiltViewModel(),
) {
    val archived by viewModel.archived.collectAsStateWithLifecycle()
    var menuStoryId by remember { mutableStateOf<String?>(null) }
    // 删除确认只存 id 不存对象快照（PITFALLS 1b）：渲染时从当前流解析，书没了弹窗自然消失。
    var deleteId by remember { mutableStateOf<String?>(null) }
    val haptics = LocalAppHaptics.current
    val c = AppTheme.colors

    // 菜单开着时书被并行删除：格子连菜单一起消失、onDismiss 不再回调——兜底清态，防 scrim 卡住。
    LaunchedEffect(archived) {
        menuStoryId?.let { open -> if (archived.none { it.id == open }) menuStoryId = null }
    }

    val gridState = rememberLazyGridState()
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.story_archive_all_title),
                onBack = onBack,
                lifted = gridState.canScrollBackward,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                // 屏 gutter 恒 20（设计语言 §2.5 军规）
                contentPadding = PaddingValues(horizontal = AppSpacing.screenGutter, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(R.string.story_archive_all_count, archived.size),
                        style = AppTheme.typography.caption,
                        color = c.text.tertiary,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                items(archived, key = { it.id }) { story ->
                    // 包 Box 当菜单锚：菜单贴本格封面展开。
                    Box {
                        ArchiveGridCell(
                            story,
                            onClick = { onOpenArchive(story.id) },
                            onLongPress = { haptics.light(); menuStoryId = story.id },
                        )
                        StoryArchivedCardMenu(
                            expanded = menuStoryId == story.id,
                            onDismiss = { menuStoryId = null },
                            onDelete = { menuStoryId = null; deleteId = story.id },
                        )
                    }
                }
            }
            // 长按菜单期背景轻压暗（ST10-4 拍板②同款·共用件含 reduceMotion 直显隐）。
            StoryShelfMenuScrim(visible = menuStoryId != null)
        }
    }

    deleteId?.let { targetId ->
        // 从当前流按 id 解析（PITFALLS 1b）；archived 流本身只含已完结。
        archived.firstOrNull { it.id == targetId }?.let { target ->
            StoryArchivedDeleteDialog(
                story = target,
                onConfirm = { viewModel.deleteStory(target.id); deleteId = null },
                onDismiss = { deleteId = null },
            )
        }
    }
}

@Composable
private fun ArchiveGridCell(story: StoryEntity, onClick: () -> Unit, onLongPress: () -> Unit) {
    val c = AppTheme.colors
    Column(
        Modifier.clickableScale(onClick = onClick, onLongClick = onLongPress),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StoryCover(
            coverColorScheme = story.coverColorScheme,
            title = story.title,
            storyId = story.id,
            titleSizeSp = 13f,
            modifier = Modifier.fillMaxWidth().height(150.dp),
        )
        Spacer(Modifier.height(7.dp))
        Text(
            story.title,
            style = AppTheme.typography.caption,
            color = c.text.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "${story.genre} · ${story.writingStyle}",
            style = AppTheme.typography.caption,
            color = c.text.tertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
