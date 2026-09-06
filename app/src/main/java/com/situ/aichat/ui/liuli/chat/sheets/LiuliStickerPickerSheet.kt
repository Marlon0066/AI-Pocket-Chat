package com.situ.aichat.ui.liuli.chat.sheets

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.local.entity.CustomStickerEntity
import com.situ.aichat.sticker.DisabledBuiltInStickerStore
import com.situ.aichat.sticker.StickerInfo
import com.situ.aichat.sticker.StickerRecentStore
import com.situ.aichat.sticker.StickerService
import com.situ.aichat.sticker.toStickerInfo
import com.situ.aichat.ui.chat.StickerImage
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliChip
import com.situ.aichat.ui.liuli.designsystem.LiuliDialogShell
import com.situ.aichat.ui.liuli.designsystem.LiuliSheetShell
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 面板几何（对版稿 A 甲）：格底 = `surface.raised` 45% 方块 18 圆角 · 图 60 · 网格 320 高 · 空态 220 高。 */
private val CELL_SHAPE = RoundedCornerShape(18.dp)
private const val CELL_ALPHA = 0.45f
private val CELL_IMAGE = 60.dp
private val GRID_HEIGHT = 320.dp
private val EMPTY_HEIGHT = 220.dp
private val PREVIEW_IMAGE = 180.dp

/** 三段的段名（照抄 F22 原字面·§9 ①）。 */
private val TABS = listOf("最近使用", "全部表情", "我的表情")

/**
 * 琉璃版表情包选择器（图纸 2026-09-05 卷二C C6b · A-16 · 照抄源 F22
 * `ui/sticker/StickerPickerSheet.kt:62-213`）。
 *
 * **只换渲染皮**：`produceState` 在 IO 线程读两份 prefs（null = 尚未就绪 → 只渲染 320dp 骨架、
 * 绝不闪错误内容）、三段的列表来源、两版空态文案、tab2 的「添加表情包」、点选即发并关、
 * 长按预览、底部「管理表情包」全部逐字照抄。段控件由暖陶的 `AppSegmentedControl` 换成三枚
 * [LiuliChip]（琉璃没有分段控件原语·§4.11 A 甲画的就是 chip 行）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun LiuliStickerPickerSheet(
    customStickers: List<CustomStickerEntity>,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val onGlass = LiuliTheme.onGlass
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val prefsSnapshot by produceState<LiuliStickerPrefs?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            LiuliStickerPrefs(
                disabled = DisabledBuiltInStickerStore.disabledIds(context),
                recentIds = StickerRecentStore.recentStickerIds(context),
            )
        }
    }
    val customAsc = remember(customStickers) { customStickers.sortedBy { it.createdAt } }
    var tab by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<StickerInfo?>(null) }

    val stickers = remember(tab, customAsc, prefsSnapshot) {
        val prefs = prefsSnapshot ?: return@remember emptyList()
        when (tab) {
            0 -> {
                val byId = StickerService.allStickers(customAsc, prefs.disabled).associateBy { it.id }
                prefs.recentIds.mapNotNull { byId[it] }
            }
            1 -> StickerService.allStickers(customAsc, prefs.disabled)
            else -> customAsc.map { it.toStickerInfo() }
        }
    }

    LiuliSheetShell(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 22.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TABS.forEachIndexed { index, name ->
                    LiuliChip(selected = tab == index, onClick = { tab = index }, label = name)
                }
            }
            if (prefsSnapshot == null) {
                // prefs 就绪前的骨架占位（高度对齐网格区，防内容到达时面板高度跳变·照抄 F22）。
                Spacer(Modifier.height(GRID_HEIGHT))
            } else if (stickers.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().height(EMPTY_HEIGHT),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        if (tab == 0) Icons.Filled.Schedule else Icons.Filled.Mood,
                        contentDescription = null,
                        tint = onGlass.secondary,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (tab == 0) "还没有使用过表情包" else "还没有自定义表情包",
                        style = AppTypography.listPreview,
                        color = onGlass.secondary,
                    )
                    if (tab == 2) {
                        Spacer(Modifier.height(12.dp))
                        LiuliButton(onClick = onManage, style = LiuliButtonStyle.Glass) { Text("添加表情包") }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth().height(GRID_HEIGHT).padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(stickers, key = { it.id }) { info ->
                        LiuliStickerCell(
                            info = info,
                            customStickers = customAsc,
                            onTap = { onSelect(info.id); onDismiss() },
                            onLongPress = { preview = info },
                        )
                    }
                }
            }
            LiuliButton(
                onClick = onManage,
                style = LiuliButtonStyle.Text,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("管理表情包")
            }
        }
    }

    preview?.let { info ->
        LiuliDialogShell(onDismissRequest = { preview = null }) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                StickerImage(info.id, customStickers, PREVIEW_IMAGE)
                Text(info.name, style = AppTypography.titleSmall, color = onGlass.primary)
                Text(
                    info.semanticDescription,
                    style = AppTypography.snackbarBody,
                    color = onGlass.secondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 打开面板时一次性读的两份 prefs 快照（照抄 F22 `StickerPickerPrefs`·null = 尚未就绪）。 */
private data class LiuliStickerPrefs(val disabled: Set<String>, val recentIds: List<String>)

/** 一格：`surface.raised` 45% 方块 18 圆角 + 60 图 + 名（对版稿 A 甲）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiuliStickerCell(
    info: StickerInfo,
    customStickers: List<CustomStickerEntity>,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(CELL_SHAPE)
            .background(AppTheme.colors.surface.raised.copy(alpha = CELL_ALPHA))
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(6.dp),
    ) {
        Box(Modifier.size(CELL_IMAGE), contentAlignment = Alignment.Center) {
            StickerImage(info.id, customStickers, CELL_IMAGE)
        }
        Text(
            info.name,
            style = AppTypography.caption,
            color = LiuliTheme.onGlass.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
