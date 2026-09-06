package com.situ.aichat.ui.promptmodule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppActionChip
import com.situ.aichat.ui.designsystem.AppChoiceChip
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppMenu
import com.situ.aichat.ui.designsystem.AppMenuItem
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.prompt.PromptModule
import com.situ.aichat.prompt.PromptModulePosition
import com.situ.aichat.prompt.PromptScene
import com.situ.aichat.prompt.SystemModuleType

/** Scene filter chips（两语境模型 v2·2026-07-12）：三枚——在线聊天(默认落点)、线下见面、全部(移末位)。顺序即显示序；null = 全部。 */
private val SCENE_FILTERS: List<PromptScene?> = listOf(PromptScene.ONLINE_CHAT, PromptScene.OFFLINE_MEETING, null)

// internal（原 private）：拆分后供 [PromptModuleEditForm] 的场景开关行复用（只搬不改·同包）。
@Composable
internal fun sceneName(scene: PromptScene): String = stringResource(
    when (scene) {
        PromptScene.ONLINE_CHAT -> R.string.scene_online_chat
        PromptScene.OFFLINE_MEETING -> R.string.scene_offline_meeting
        PromptScene.VOICE_CALL -> R.string.scene_voice_call
        PromptScene.BUSY_REPLY -> R.string.scene_busy_reply
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptModuleSettingsScreen(
    onBack: () -> Unit,
    onOpenImmersiveSettings: () -> Unit, // §4-U5 叙事卡→沉浸设置页
    viewModel: PromptModuleSettingsViewModel = hiltViewModel(),
) {
    val modules by viewModel.modules.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    // settings-misc-2：表情包模块行受「角色发送表情包」总开关 gating。
    val canSendStickers by viewModel.characterCanSendStickersEnabled.collectAsStateWithLifecycle()
    val narrativeDetailRaw by viewModel.offlineNarrativeDetailRaw.collectAsStateWithLifecycle() // §4-U5 叙事卡回显

    // All remember() calls stay above the early return so the edit↔list switch never skips a slot.
    var editing by remember { mutableStateOf<PromptModule?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    var sceneFilter by remember { mutableStateOf<PromptScene?>(PromptScene.ONLINE_CHAT) }
    var showPresetMenu by remember { mutableStateOf(false) }
    var showSavePreset by remember { mutableStateOf(false) }

    val current = editing
    if (current != null) {
        ModuleEditForm(
            initial = current,
            isNew = editingIsNew,
            // 两语境模型 v2（§4-U4）：从线下 tab 点进核心规则→自动落线下版。
            initialOffline = sceneFilter == PromptScene.OFFLINE_MEETING,
            onClose = { editing = null },
            onCreate = { updated -> viewModel.addModule(updated); editing = null },
            onAutoSave = { updated -> viewModel.updateModule(updated) },
            onDelete = if (!editingIsNew && !current.isSystemGenerated) {
                { viewModel.deleteModule(current.id); editing = null }
            } else {
                null
            },
        )
        return
    }

    val openEdit: (PromptModule) -> Unit = { editing = it; editingIsNew = false }

    fun visible(position: PromptModulePosition): List<PromptModule> = modules
        .filter { it.position == position }
        // 忙碌延迟回复功能已删除（2026-07-11）：其专属指令模块从列表隐藏（仅显示层过滤;
        // 持久化 JSON/枚举保留=与 iOS 备份线格式兼容,该模块场景永不触发=天然惰性）。
        .filterNot { it.systemModuleType == SystemModuleType.BUSY_REPLY_INSTRUCTION }
        .filter { m -> sceneFilter?.let { (m.enabledScenes ?: PromptScene.entries.toSet()).contains(it) } ?: true }
        .sortedBy { it.sortOrder }

    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.pm_title),
                onBack = onBack,
                lifted = listState.canScrollBackward,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editing = viewModel.newCustomModuleTemplate()
                editingIsNew = true
            }) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.pm_add_module)) }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            state = listState,
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            item {
                // 屏 gutter 恒 20（设计语言 §2.5 军规）
                Column(Modifier.padding(horizontal = AppSpacing.screenGutter, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.pm_tip_1), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.pm_tip_2), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.pm_tip_3), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = AppSpacing.screenGutter), // 屏 gutter 恒 20（设计语言 §2.5 军规）
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SCENE_FILTERS.forEach { scene ->
                        AppChoiceChip(
                            selected = sceneFilter == scene,
                            onClick = { sceneFilter = scene },
                            label = scene?.let { sceneName(it) } ?: stringResource(R.string.pm_scene_all),
                        )
                    }
                }
            }
            item {
                Row(
                    // 屏 gutter 恒 20（设计语言 §2.5 军规）
                    Modifier.fillMaxWidth().padding(horizontal = AppSpacing.screenGutter, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box {
                        AppActionChip(onClick = { showPresetMenu = true }, label = stringResource(R.string.pm_preset_load))
                        AppMenu(expanded = showPresetMenu, onDismiss = { showPresetMenu = false }) {
                            presets.forEach { preset ->
                                AppMenuItem(
                                    text = preset.name,
                                    onClick = { viewModel.applyPreset(preset); showPresetMenu = false },
                                )
                            }
                        }
                    }
                    AppActionChip(onClick = { showSavePreset = true }, label = stringResource(R.string.pm_preset_save))
                }
            }

            sectionHeader(R.string.pm_section_prefix)
            moduleSection(visible(PromptModulePosition.PREFIX), R.string.pm_empty_prefix, canSendStickers, sceneFilter, viewModel::toggle, openEdit, viewModel::move)
            sectionHeader(R.string.pm_section_suffix)
            moduleSection(visible(PromptModulePosition.SUFFIX), R.string.pm_empty_suffix, canSendStickers, sceneFilter, viewModel::toggle, openEdit, viewModel::move)
            if (sceneFilter == PromptScene.OFFLINE_MEETING) { // 线下 tab 底部叙事预设跳转卡（§4-U5·只读·不排序）
                item { NarrativePresetCard(levelRaw = narrativeDetailRaw, onClick = onOpenImmersiveSettings) }
            }
        }
    }

    if (showSavePreset) {
        var name by remember { mutableStateOf("") }
        AppDialog(
            onDismissRequest = { showSavePreset = false },
            title = stringResource(R.string.pm_preset_save_title),
            confirmText = stringResource(R.string.action_save),
            onConfirm = { viewModel.saveAsPreset(name); showSavePreset = false },
            confirmEnabled = name.isNotBlank(),
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showSavePreset = false },
            content = {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = stringResource(R.string.pm_preset_name_hint),
                )
            },
        )
    }
}

private fun LazyListScope.sectionHeader(titleRes: Int) {
    item {
        Text(
            stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            // 屏 gutter 恒 20（设计语言 §2.5 军规）——本行是 LazyColumn 的裸 item，与 A8–A12 五处同为屏级兄弟，
            // 必须共线（R1 复核补：图纸三 §4.1 漏列本处，改完前段标题比它统辖的行左 4dp）。
            modifier = Modifier.padding(start = AppSpacing.screenGutter, end = AppSpacing.screenGutter, top = 16.dp, bottom = 4.dp),
        )
    }
}

private fun LazyListScope.moduleSection(
    items: List<PromptModule>,
    emptyRes: Int,
    canSendStickers: Boolean,
    sceneFilter: PromptScene?,
    onToggle: (String) -> Unit,
    onEdit: (PromptModule) -> Unit,
    onMove: (String, Boolean) -> Unit,
) {
    if (items.isEmpty()) {
        item {
            Text(
                stringResource(emptyRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // 屏 gutter 恒 20（设计语言 §2.5 军规）
                modifier = Modifier.padding(horizontal = AppSpacing.screenGutter, vertical = 8.dp),
            )
        }
        return
    }
    itemsIndexed(items, key = { _, m -> m.id }) { index, module ->
        ModuleRow(
            module = module,
            isFirst = index == 0,
            isLast = index == items.lastIndex,
            sceneFilter = sceneFilter,
            // settings-misc-2：表情包系统模块在总开关关闭时灰置不可交互（保留勾选偏好）。
            isDisabledByParentToggle = module.systemModuleType == SystemModuleType.STICKER_LIBRARY && !canSendStickers,
            onToggle = { onToggle(module.id) },
            onEdit = { onEdit(module) },
            onMoveUp = { onMove(module.id, true) },
            onMoveDown = { onMove(module.id, false) },
        )
    }
}

@Composable
private fun ModuleRow(
    module: PromptModule,
    isFirst: Boolean,
    isLast: Boolean,
    sceneFilter: PromptScene?,
    isDisabledByParentToggle: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isDisabledByParentToggle, onClick = onEdit)
            .alpha(if (isDisabledByParentToggle) 0.4f else 1f) // 对齐 iOS .opacity(0.4)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggle, enabled = !isDisabledByParentToggle) {
            if (module.isEnabled) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            } else {
                Icon(Icons.Outlined.Circle, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                module.name,
                color = if (module.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TinyBadge(
                    text = stringResource(if (module.isSystemGenerated) R.string.pm_badge_system else R.string.pm_badge_custom),
                    color = if (module.isSystemGenerated) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                )
                SceneBadge(module, sceneFilter)
            }
            if (isDisabledByParentToggle) {
                // 对齐 iOS moduleRow 的 .caption2/.secondary 灰置提示。
                Text(
                    stringResource(R.string.pm_sticker_gated_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 现在卡语义（2026-07-11）：时间感知/此刻状态默认排后置区末尾（紧贴生成点,时间把握最准）,
            // 排序完全自由——留在末尾时二者合并为「现在卡」收官,挪走则按此处顺序注入。
            if (module.systemModuleType == SystemModuleType.TIME_AWARENESS ||
                module.systemModuleType == SystemModuleType.CURRENT_MOMENT
            ) {
                Text(
                    stringResource(R.string.pm_now_card_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onMoveUp, enabled = !isFirst) {
            Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.pm_move_up))
        }
        IconButton(onClick = onMoveDown, enabled = !isLast) {
            Icon(Icons.Filled.ArrowDownward, contentDescription = stringResource(R.string.pm_move_down))
        }
    }
}

// internal（原 private）：拆分后供 [PromptModuleEditForm] 的内容徽章复用（只搬不改·同包）。
@Composable
internal fun TinyBadge(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** 徽章四态判定（两语境模型）：只看 ONLINE_CHAT/OFFLINE_MEETING 两有效位（null=双含）。internal 供 T1。 */
internal enum class SceneBadgeState { CHAT_AND_MEET, CHAT_ONLY, MEET_ONLY, NONE }

internal fun sceneBadgeState(scenes: Set<PromptScene>?): SceneBadgeState {
    val chat = scenes == null || PromptScene.ONLINE_CHAT in scenes
    val meet = scenes == null || PromptScene.OFFLINE_MEETING in scenes
    return when {
        chat && meet -> SceneBadgeState.CHAT_AND_MEET
        chat -> SceneBadgeState.CHAT_ONLY
        meet -> SceneBadgeState.MEET_ONLY
        else -> SceneBadgeState.NONE
    }
}

@Composable
private fun SceneBadge(module: PromptModule, sceneFilter: PromptScene?) {
    // 线下视角下核心规则行点明「这里是专版」——替代四态徽章（图纸 §4-U2 特例）。
    if (module.systemModuleType == SystemModuleType.CORE_RULES && sceneFilter == PromptScene.OFFLINE_MEETING) {
        TinyBadge(text = stringResource(R.string.pm_badge_offline_variant), color = MaterialTheme.colorScheme.secondary)
        return
    }
    val (textRes, color) = when (sceneBadgeState(module.enabledScenes)) {
        SceneBadgeState.CHAT_AND_MEET -> R.string.pm_scene_badge_chat_meet to MaterialTheme.colorScheme.onSurfaceVariant
        SceneBadgeState.CHAT_ONLY -> R.string.pm_scene_badge_chat_only to MaterialTheme.colorScheme.secondary
        SceneBadgeState.MEET_ONLY -> R.string.pm_scene_badge_meet_only to MaterialTheme.colorScheme.secondary
        SceneBadgeState.NONE -> R.string.pm_scene_badge_none to MaterialTheme.colorScheme.error
    }
    TinyBadge(text = stringResource(textRes), color = color)
}

/** 叙事档位 raw → 显示名（§4-U5）。未知 raw 回退平淡（照 DetailLevel.fromRaw 语义·plain/normal/detailed/custom）。琉璃卷五复用（`ui/liuli` 树借同一份实现·改这里两张脸同时变）。 */
@Composable
internal fun narrativeLevelName(raw: String): String = stringResource(
    when (raw) {
        "normal" -> R.string.pm_narrative_level_normal
        "detailed" -> R.string.pm_narrative_level_detailed
        "custom" -> R.string.pm_narrative_level_custom
        else -> R.string.pm_narrative_level_plain
    },
)

@Composable
private fun NarrativePresetCard(levelRaw: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.screenGutter) // 屏 gutter 恒 20（设计语言 §2.5 军规）
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.pm_narrative_card_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.pm_narrative_card_desc, narrativeLevelName(levelRaw)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
