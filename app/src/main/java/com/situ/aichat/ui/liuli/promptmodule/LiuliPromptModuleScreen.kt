package com.situ.aichat.ui.liuli.promptmodule

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.prompt.PromptModule
import com.situ.aichat.prompt.PromptModulePosition
import com.situ.aichat.prompt.PromptScene
import com.situ.aichat.prompt.SystemModuleType
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliChip
import com.situ.aichat.ui.liuli.designsystem.LiuliCircleButton
import com.situ.aichat.ui.liuli.designsystem.LiuliDialog
import com.situ.aichat.ui.liuli.designsystem.LiuliField
import com.situ.aichat.ui.liuli.designsystem.LiuliMenuEntry
import com.situ.aichat.ui.liuli.designsystem.LiuliPopupMenu
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.promptmodule.PromptModuleSettingsViewModel
import com.situ.aichat.ui.promptmodule.narrativeLevelName
import com.situ.aichat.ui.promptmodule.sceneName
import com.situ.aichat.prompt.PromptModulePreset
import androidx.compose.ui.Alignment

/**
 * 场景筛选芯片（两语境模型 v2·2026-07-12）：三枚——在线聊天（默认落点）、线下见面、全部（末位）。
 * **顺序即显示序**；null = 全部（逐字照暖陶 `SCENE_FILTERS`）。
 */
private val SCENE_FILTERS: List<PromptScene?> = listOf(PromptScene.ONLINE_CHAT, PromptScene.OFFLINE_MEETING, null)

/** 芯片排的缝与两块之间的缝（逐字照暖陶 8）。 */
private val CHIP_GAP = 8.dp
/** 预设菜单锚点：贴「载入预设」芯片**左缘往右**展开、落在芯片下方（芯片靠屏左·往左展开会伸出屏外·复核 R1 🔴 A-1）。 */
private val PRESET_MENU_OFFSET = DpOffset(0.dp, 40.dp)
/** 三行提示之间的缝。 */
private val TIP_GAP = 4.dp

/**
 * 提示词模块页（琉璃·图纸 2026-09-06 卷五 §4.1 屏 21·A-4 ⑦ FAB 与排序钮首用 / A-4 ⑧ 芯片排首用）。
 * 与暖陶 `PromptModuleSettingsScreen` 共用 [PromptModuleSettingsViewModel]，并沿用它的**同文件全屏编辑态**
 * （`editing != null` 整屏换 [LiuliPromptModuleEditForm] 并 `return`·F9·不进 `AIChatApp.kt`）。
 *
 * **prompt 耦合最重**（零碰）：`sortOrder` = 注入顺序 · `enabledScenes` null = 双场景 ·
 * `BUSY_REPLY_INSTRUCTION` **仅显示层过滤**（持久化 JSON 保留·与 iOS 备份线格式兼容）。
 */
@Composable
fun LiuliPromptModuleScreen(
    onBack: () -> Unit,
    onOpenImmersiveSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PromptModuleSettingsViewModel = hiltViewModel(),
    listState: LazyListState = rememberLazyListState(),
) {
    val modules by viewModel.modules.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    // settings-misc-2：表情包模块行受「角色发送表情包」总开关 gating。
    val canSendStickers by viewModel.characterCanSendStickersEnabled.collectAsStateWithLifecycle()
    val narrativeDetailRaw by viewModel.offlineNarrativeDetailRaw.collectAsStateWithLifecycle() // §4-U5 叙事卡回显

    // 所有 remember 都排在提前 return 之上，编辑 ↔ 列表切换才不会跳槽（逐字照暖陶 :92）。
    var editing by remember { mutableStateOf<PromptModule?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    var sceneFilter by remember { mutableStateOf<PromptScene?>(PromptScene.ONLINE_CHAT) }
    var showPresetMenu by remember { mutableStateOf(false) }
    var showSavePreset by remember { mutableStateOf(false) }

    val current = editing
    if (current != null) {
        LiuliPromptModuleEditForm(
            initial = current,
            isNew = editingIsNew,
            // 两语境模型 v2（§4-U4）：从线下 tab 点进核心规则 → 自动落线下版。
            initialOffline = sceneFilter == PromptScene.OFFLINE_MEETING,
            onClose = { editing = null },
            onCreate = { updated -> viewModel.addModule(updated); editing = null },
            onAutoSave = { updated -> viewModel.updateModule(updated) },
            onDelete = if (!editingIsNew && !current.isSystemGenerated) {
                { viewModel.deleteModule(current.id); editing = null }
            } else {
                null
            },
            modifier = modifier,
        )
        return
    }

    fun visible(position: PromptModulePosition): List<PromptModule> = modules
        .filter { it.position == position }
        // 忙碌延迟回复功能已删除（2026-07-11）：其专属指令模块**仅显示层**隐藏，持久化 JSON / 枚举保留。
        .filterNot { it.systemModuleType == SystemModuleType.BUSY_REPLY_INSTRUCTION }
        .filter { m -> sceneFilter?.let { (m.enabledScenes ?: PromptScene.entries.toSet()).contains(it) } ?: true }
        .sortedBy { it.sortOrder }

    val colors = AppTheme.colors
    val title = stringResource(R.string.pm_title)
    // 这四句在 `LazyListScope` 里取不到（那不是 composable 作用域），先在屏体里取好再传下去。
    val prefixHeader = stringResource(R.string.pm_section_prefix)
    val prefixEmpty = stringResource(R.string.pm_empty_prefix)
    val suffixHeader = stringResource(R.string.pm_section_suffix)
    val suffixEmpty = stringResource(R.string.pm_empty_suffix)
    val bottomInset = LiuliPageGeometry.pageBottom +
        LiuliPageGeometry.fab + LiuliPageGeometry.fabBottom +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LiuliPage(
        title = title,
        onBack = onBack,
        collapsed = rememberLargeTitleCollapsed(listState),
        modifier = modifier,
        fab = {
            LiuliCircleButton(
                onClick = {
                    editing = viewModel.newCustomModuleTemplate()
                    editingIsNew = true
                },
                contentDescription = stringResource(R.string.pm_add_module),
                size = LiuliPageGeometry.fab,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(LiuliPageGeometry.fabIcon))
            }
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().contentMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(top = LiuliPageGeometry.navRow, bottom = bottomInset),
        ) {
            item(key = "large-title") { LiuliLargeTitle(title) }
            item(key = "tips") {
                Column(
                    Modifier.padding(
                        horizontal = LiuliPageGeometry.gutter,
                        vertical = LiuliPageGeometry.titleGap,
                    ),
                    verticalArrangement = Arrangement.spacedBy(TIP_GAP),
                ) {
                    Text(stringResource(R.string.pm_tip_1), style = AppTypography.secondary, color = colors.text.secondary)
                    Text(stringResource(R.string.pm_tip_2), style = AppTypography.secondary, color = colors.text.secondary)
                    Text(stringResource(R.string.pm_tip_3), style = AppTypography.secondary, color = colors.text.secondary)
                }
            }
            item(key = "chips") {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = LiuliPageGeometry.gutter),
                        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
                    ) {
                        SCENE_FILTERS.forEach { scene ->
                            LiuliChip(
                                selected = sceneFilter == scene,
                                onClick = { sceneFilter = scene },
                                label = scene?.let { sceneName(it) } ?: stringResource(R.string.pm_scene_all),
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = LiuliPageGeometry.gutter),
                        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
                    ) {
                        Box {
                            LiuliChip(
                                selected = false,
                                onClick = { showPresetMenu = true },
                                label = stringResource(R.string.pm_preset_load),
                                role = androidx.compose.ui.semantics.Role.Button,
                            )
                            LiuliPopupMenu(
                                expanded = showPresetMenu,
                                onDismiss = { showPresetMenu = false },
                                items = presets.map { preset: PromptModulePreset ->
                                    LiuliMenuEntry(text = preset.name, onClick = { viewModel.applyPreset(preset) })
                                },
                                offset = PRESET_MENU_OFFSET,
                                alignment = Alignment.TopStart,
                            )
                        }
                        LiuliChip(
                            selected = false,
                            onClick = { showSavePreset = true },
                            label = stringResource(R.string.pm_preset_save),
                            role = androidx.compose.ui.semantics.Role.Button,
                        )
                    }
                }
            }
            liuliModuleSection(
                header = prefixHeader,
                items = visible(PromptModulePosition.PREFIX),
                emptyText = prefixEmpty,
                canSendStickers = canSendStickers,
                sceneFilter = sceneFilter,
                onToggle = viewModel::toggle,
                onEdit = { editing = it; editingIsNew = false },
                onMove = viewModel::move,
            )
            liuliModuleSection(
                header = suffixHeader,
                items = visible(PromptModulePosition.SUFFIX),
                emptyText = suffixEmpty,
                canSendStickers = canSendStickers,
                sceneFilter = sceneFilter,
                onToggle = viewModel::toggle,
                onEdit = { editing = it; editingIsNew = false },
                onMove = viewModel::move,
            )
            if (sceneFilter == PromptScene.OFFLINE_MEETING) {
                item(key = "narrative-card") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = LiuliPageGeometry.gutter)) {
                        LiuliGroup {
                            LiuliNarrativePresetRow(
                                levelName = narrativeLevelName(narrativeDetailRaw),
                                onClick = onOpenImmersiveSettings,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSavePreset) {
        var name by remember { mutableStateOf("") }
        LiuliDialog(
            onDismissRequest = { showSavePreset = false },
            title = stringResource(R.string.pm_preset_save_title),
            confirmText = stringResource(R.string.action_save),
            onConfirm = { viewModel.saveAsPreset(name); showSavePreset = false },
            confirmEnabled = name.isNotBlank(),
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showSavePreset = false },
            content = {
                LiuliField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = stringResource(R.string.pm_preset_name_hint),
                )
            },
        )
    }
}

/** 一段模块（组标题 + 组内逐行 / 空态一句）。 */
private fun androidx.compose.foundation.lazy.LazyListScope.liuliModuleSection(
    header: String,
    items: List<PromptModule>,
    emptyText: String,
    canSendStickers: Boolean,
    sceneFilter: PromptScene?,
    onToggle: (String) -> Unit,
    onEdit: (PromptModule) -> Unit,
    onMove: (String, Boolean) -> Unit,
) {
    item(key = "section-$header") {
        // 只给左右 gutter：组自带 24 底距，再加上下 12 会让前置 / 后置两组之间空到 48（复核 R1 C-2）。
        Column(Modifier.fillMaxWidth().padding(horizontal = LiuliPageGeometry.gutter)) {
            LiuliGroup(header = header) {
                if (items.isEmpty()) {
                    LiuliRowBase(divider = false, verticalPadding = LiuliPageGeometry.rowTwoLinePad) {
                        Text(
                            emptyText,
                            style = AppTypography.secondary,
                            color = AppTheme.colors.text.secondary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    items.forEachIndexed { index, module ->
                        LiuliPromptModuleRow(
                            module = module,
                            isFirst = index == 0,
                            isLast = index == items.lastIndex,
                            sceneFilter = sceneFilter,
                            // settings-misc-2：表情包系统模块在总开关关闭时灰置不可交互（保留勾选偏好）。
                            isDisabledByParentToggle = module.systemModuleType == SystemModuleType.STICKER_LIBRARY &&
                                !canSendStickers,
                            onToggle = { onToggle(module.id) },
                            onEdit = { onEdit(module) },
                            onMoveUp = { onMove(module.id, true) },
                            onMoveDown = { onMove(module.id, false) },
                            divider = index > 0,
                        )
                    }
                }
            }
        }
    }
}
