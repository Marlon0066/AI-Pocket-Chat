package com.situ.aichat.ui.liuli.sticker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.sticker.BuiltInStickerCatalog
import com.situ.aichat.sticker.StickerService
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.AppSettingsRow
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBarIcons
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.liuliCardMaterial
import com.situ.aichat.ui.liuli.designsystem.liuliCardSegment
import com.situ.aichat.ui.liuli.designsystem.liuliSegmentPosition
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageCircleAction
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliSectionHeader
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.sticker.BuiltInStickerRow
import com.situ.aichat.ui.sticker.CustomStickerRow
import com.situ.aichat.ui.sticker.StickerEmptyHint
import com.situ.aichat.ui.sticker.StickerManagementViewModel
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/** 表情包管理（琉璃 2.0 脸）。 */
@Composable
fun LiuliStickerManagementScreen(
    onBack: () -> Unit,
    onImport: () -> Unit,
    viewModel: StickerManagementViewModel = hiltViewModel(),
) {
    val customStickers by viewModel.customStickers.collectAsStateWithLifecycle()
    val disabled by viewModel.disabledIds.collectAsStateWithLifecycle()
    val enabled by viewModel.stickersEnabled.collectAsStateWithLifecycle()
    val enabledBuiltIns = remember(disabled) { BuiltInStickerCatalog.enabled(disabled) }
    val hiddenStickers = remember(disabled) { BuiltInStickerCatalog.all.filter { it.id in disabled } }
    val atLimit = customStickers.size >= StickerService.CUSTOM_STICKER_LIMIT
    var showHidden by remember { mutableStateOf(false) }

    val colors = AppTheme.colors
    val dark = LocalIsDarkTheme.current
    val listState = rememberLazyListState()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val title = "表情包管理"

    LiuliPage(
        title = title,
        onBack = onBack,
        collapsed = rememberLargeTitleCollapsed(listState),
        actions = {
            if (!atLimit) {
                LiuliPageCircleAction(
                    icon = AppTopBarIcons.Add,
                    contentDescription = stringResource(R.string.a11y_add_sticker_pack),
                    onClick = onImport,
                )
            }
        },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().contentMaxWidth(),
            contentPadding = PaddingValues(
                top = LiuliPageGeometry.navRow + 4.dp,
                bottom = navBottom + LiuliPageGeometry.pageBottom,
            ),
        ) {
            item(key = "title") {
                LiuliLargeTitle(title, modifier = Modifier.padding(horizontal = LiuliPageGeometry.gutter, vertical = 4.dp))
            }

            item(key = "master") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LiuliPageGeometry.gutter, vertical = 8.dp)
                        .liuliCardMaterial(LiuliShapes.group, dark),
                ) {
                    LiuliToggleRow(
                        title = "角色发送表情包",
                        subtitle = "关闭后角色不再发表情包，历史表情仍正常显示",
                        checked = enabled,
                        onCheckedChange = { viewModel.setStickersEnabled(it) },
                    )
                }
            }

            item(key = "customHeader") {
                LiuliSectionHeader("我的表情包 · ${customStickers.size}/${StickerService.CUSTOM_STICKER_LIMIT}")
            }
            if (customStickers.isEmpty()) {
                item(key = "customEmpty") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = LiuliPageGeometry.gutter)
                            .liuliCardMaterial(LiuliShapes.group, dark),
                    ) {
                        StickerEmptyHint("还没有自定义表情包")
                    }
                }
            } else {
                itemsIndexed(customStickers, key = { _, it -> it.stickerUuid }) { index, sticker ->
                    val pos = liuliSegmentPosition(index, customStickers.size)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = LiuliPageGeometry.gutter)
                            .liuliCardSegment(pos, dark),
                    ) {
                        if (index > 0) AppListDivider(startInset = 16.dp)
                        CustomStickerRow(sticker, customStickers, onDelete = { viewModel.deleteCustom(sticker) })
                    }
                }
            }

            val presetHasHidden = hiddenStickers.isNotEmpty()
            item(key = "builtInHeader") {
                LiuliSectionHeader("预置表情包 · ${enabledBuiltIns.size}/${BuiltInStickerCatalog.all.size}")
            }
            val builtInVisibleCount = (if (enabledBuiltIns.isEmpty()) 1 else enabledBuiltIns.size) +
                (if (presetHasHidden) 1 else 0) +
                (if (presetHasHidden && showHidden) 1 + hiddenStickers.size else 0)

            if (enabledBuiltIns.isEmpty()) {
                item(key = "builtInEmpty") {
                    val pos = liuliSegmentPosition(0, builtInVisibleCount)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = LiuliPageGeometry.gutter)
                            .liuliCardSegment(pos, dark),
                    ) {
                        StickerEmptyHint("已全部隐藏")
                    }
                }
            } else {
                itemsIndexed(enabledBuiltIns, key = { _, it -> "on_${it.id}" }) { index, sticker ->
                    val pos = liuliSegmentPosition(index, builtInVisibleCount)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = LiuliPageGeometry.gutter)
                            .liuliCardSegment(pos, dark),
                    ) {
                        if (index > 0) AppListDivider(startInset = 16.dp)
                        BuiltInStickerRow(sticker, trailingLabel = "隐藏", onAction = { viewModel.hideBuiltIn(sticker.id) })
                    }
                }
            }

            if (presetHasHidden) {
                val toggleIdx = if (enabledBuiltIns.isEmpty()) 1 else enabledBuiltIns.size
                item(key = "hiddenToggle") {
                    val pos = liuliSegmentPosition(toggleIdx, builtInVisibleCount)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = LiuliPageGeometry.gutter)
                            .liuliCardSegment(pos, dark),
                    ) {
                        AppListDivider(startInset = 16.dp)
                        AppSettingsRow(
                            title = "已隐藏的内置表情",
                            value = "${hiddenStickers.size}",
                            trailing = {
                                Icon(
                                    if (showHidden) AppActionIcons.ChevronUp else AppActionIcons.ChevronDown,
                                    contentDescription = null,
                                    tint = colors.text.tertiary,
                                )
                            },
                            modifier = Modifier.fillMaxWidth().clickable { showHidden = !showHidden },
                        )
                    }
                }
                if (showHidden) {
                    item(key = "restoreAll") {
                        val pos = liuliSegmentPosition(toggleIdx + 1, builtInVisibleCount)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = LiuliPageGeometry.gutter)
                                .liuliCardSegment(pos, dark),
                        ) {
                            AppListDivider(startInset = 16.dp)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                LiuliButton(onClick = { viewModel.restoreAllBuiltIn() }, style = LiuliButtonStyle.Text) { Text("全部恢复") }
                            }
                        }
                    }
                    itemsIndexed(hiddenStickers, key = { _, it -> "off_${it.id}" }) { index, sticker ->
                        val pos = liuliSegmentPosition(toggleIdx + 2 + index, builtInVisibleCount)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = LiuliPageGeometry.gutter)
                                .liuliCardSegment(pos, dark),
                        ) {
                            AppListDivider(startInset = 16.dp)
                            BuiltInStickerRow(sticker, trailingLabel = "取消隐藏", onAction = { viewModel.enableBuiltIn(sticker.id) })
                        }
                    }
                }
            }

            item(key = "footnote") {
                Text(
                    "隐藏不想要的预置表情后，它不再出现在选择器和 AI 提示词中，但历史消息仍能正常显示，随时可以恢复。",
                    style = AppTypography.caption,
                    color = colors.text.secondary,
                    modifier = Modifier.padding(horizontal = LiuliPageGeometry.gutter + 8.dp, vertical = 14.dp),
                )
            }
        }
    }
}
