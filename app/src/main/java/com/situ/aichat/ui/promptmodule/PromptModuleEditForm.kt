package com.situ.aichat.ui.promptmodule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.prompt.PromptBuilder
import com.situ.aichat.prompt.PromptMacros
import com.situ.aichat.prompt.PromptModule
import com.situ.aichat.prompt.PromptModulePosition
import com.situ.aichat.prompt.PromptScene
import com.situ.aichat.prompt.PromptStrings
import com.situ.aichat.prompt.SystemModuleType
import com.situ.aichat.prompt.buildOfflineCoreRulesContent
import com.situ.aichat.ui.designsystem.AppActionChip
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppFormBar
import com.situ.aichat.ui.designsystem.AppSegmentedControl
import com.situ.aichat.ui.designsystem.AppSwitch
import com.situ.aichat.ui.designsystem.AppTextArea
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import kotlinx.coroutines.delay

/**
 * 提示词模块编辑表单（自 [PromptModuleSettingsScreen] 拆出 · 文件瘦身，**行为字节级不变**）：可编辑模块的
 * 内容缓冲 + 400ms 防抖自动保存 + 返回 flush + 宏点按插入 + 恢复默认 + position 分段控件 + 启用/场景开关。
 * 列表侧的 sceneName / TinyBadge 共用（同包 internal）。两语境模型 v2 的分版编辑增量随 chunk 7 在本文件加。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModuleEditForm(
    initial: PromptModule,
    isNew: Boolean,
    // 两语境模型 v2（图纸 §4-U4）：从线下 tab 点进（sceneFilter==OFFLINE_MEETING）→ 自动落在线下版。仅核心规则消费。
    initialOffline: Boolean,
    onClose: () -> Unit,
    onCreate: (PromptModule) -> Unit,
    onAutoSave: (PromptModule) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val context = LocalContext.current
    val type = initial.systemModuleType
    val isSystem = initial.isSystemGenerated
    // 仅核心规则支持在线/线下分版编辑（图纸 §4-U4）；其余模块表单零变化。
    val isCoreRules = type == SystemModuleType.CORE_RULES
    // 全 22 模块的默认模板（数据→整块宏；可编辑→字面文案；自定义→空）。供预填 + 恢复默认（提示词模块编辑重设计 P2）。
    val defaultTemplate = remember(type) {
        if (type == null) {
            ""
        } else {
            PromptBuilder.defaultModuleTemplate(type)
                ?: PromptBuilder.defaultEditableTemplate(type, PromptStrings(context)) ?: ""
        }
    }
    // 线下版默认模板 = 内置线下核心规则（宏留位·与装配端 [buildOfflineCoreRulesContent] 单源）。仅核心规则用到。
    val offlineDefaultTemplate = remember(type) {
        buildOfflineCoreRulesContent(PromptStrings(context), PromptMacros.CHAR, PromptMacros.USER)
    }

    var name by remember { mutableStateOf(initial.name) }
    // 内容缓冲（TextFieldValue 以支持宏插入到光标处）：空 content → 预填默认模板让用户直接看到并改；非空 → 已自定义。
    var textValue by remember { mutableStateOf(TextFieldValue(initial.content.ifEmpty { defaultTemplate })) }
    // 线下版编辑态 + 缓冲（分版独立·图纸 §4-U4）：editingOffline 初值随进入 tab；两缓冲在本编辑会话内各自保留未存输入（E17）。
    var editingOffline by remember { mutableStateOf(initialOffline) }
    var offlineTextValue by remember { mutableStateOf(TextFieldValue(initial.offlineContent.ifEmpty { offlineDefaultTemplate })) }
    var enabled by remember { mutableStateOf(initial.isEnabled) }
    var position by remember { mutableStateOf(initial.position) }
    var scenes by remember { mutableStateOf(initial.enabledScenes) }

    // 当前编辑版（核心规则线下 tab→线下版；否则主 content）的缓冲与默认，供内容区徽章/文本域/恢复默认统一取用。
    val currentValue = if (isCoreRules && editingOffline) offlineTextValue else textValue
    val currentDefault = if (isCoreRules && editingOffline) offlineDefaultTemplate else defaultTemplate
    // 与默认模板逐字一致 → 存空（跟随默认、不钉死，仍吃后续 App 默认更新）；改动过 → 存用户文本。按当前版判定。
    val isCustomized = currentValue.text != currentDefault && currentValue.text.isNotEmpty()
    val canSave = isSystem || name.isNotBlank()

    fun snapshot(): PromptModule = initial.copy(
        name = if (isSystem) initial.name else name.trim(),
        content = if (textValue.text == defaultTemplate) "" else textValue.text,
        // 线下版分版独立跟随默认（照主 content 语义）；非核心规则模块恒透传原值（不触碰）。
        offlineContent = if (isCoreRules) {
            if (offlineTextValue.text == offlineDefaultTemplate) "" else offlineTextValue.text
        } else {
            initial.offlineContent
        },
        isEnabled = enabled,
        position = position,
        enabledScenes = scenes,
    )

    // 既有模块：改完即生效——防抖自动保存（停手 ~400ms 落盘），无保存键；新建模块走顶栏显式添加（autosave 例外）。
    var autosaveArmed by remember { mutableStateOf(false) }
    if (!isNew) {
        LaunchedEffect(name, textValue.text, offlineTextValue.text, enabled, position, scenes) {
            if (!autosaveArmed) {
                autosaveArmed = true // 跳过首帧（初始值无需重存）
                return@LaunchedEffect
            }
            delay(400)
            onAutoSave(snapshot())
        }
    }

    // 返回（含系统返回键/手势）：既有模块先 flush 最后一次编辑再关闭——防 debounce 未触发即离开而丢改动；
    // 同时拦截系统返回，回到列表而非直接退出整个提示词模块屏。新建模块=取消即丢弃（不存）。
    val closeAndFlush: () -> Unit = {
        if (!isNew) onAutoSave(snapshot())
        onClose()
    }
    val scrollState = rememberScrollState()

    BackHandler { closeAndFlush() }

    Scaffold(
        topBar = {
            AppFormBar(
                title = stringResource(if (isNew) R.string.pm_add_title else R.string.pm_edit_title),
                lifted = scrollState.value > 0,
                onCancel = closeAndFlush,
                cancelText = stringResource(if (isNew) R.string.action_cancel else R.string.action_back),
                trailing = {
                    if (isNew) {
                        AppButton(enabled = canSave, onClick = { onCreate(snapshot()) }) {
                            Text(stringResource(R.string.action_save))
                        }
                    } else {
                        // 状态槽（拍板⑤）：低调灰字、不可点、不做成钮——它是「已经存好了」的告知，不是行动。
                        Text(
                            stringResource(R.string.pm_autosaved),
                            style = AppTypography.settingsRowValue,
                            color = AppTheme.colors.text.secondary,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isSystem) {
                Text(initial.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            } else {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.pm_field_name),
                    placeholder = stringResource(R.string.pm_field_name_hint),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.pm_field_enabled), Modifier.weight(1f))
                AppSwitch(checked = enabled, onCheckedChange = { enabled = it })
            }

            // 核心规则分版控件（图纸 §4-U4）：在线聊天版 / 线下见面版切换 + 一行说明。仅核心规则显示。
            if (isCoreRules) {
                AppSegmentedControl(
                    options = listOf(false, true),
                    selected = editingOffline,
                    onSelect = { editingOffline = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { stringResource(if (it) R.string.pm_version_offline else R.string.pm_version_online) },
                )
                Text(
                    stringResource(if (editingOffline) R.string.pm_version_note_offline else R.string.pm_version_note_online),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.pm_section_content),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TinyBadge(
                    text = stringResource(if (isCustomized) R.string.pm_customized else R.string.pm_using_default),
                    color = if (isCustomized) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 全模块可编辑：预填默认模板（数据类=整块宏，可编辑类=字面文案）让用户直接看到并修改。核心规则按当前版缓冲绑定。
            AppTextArea(
                value = currentValue,
                onValueChange = { if (isCoreRules && editingOffline) offlineTextValue = it else textValue = it },
                supportingText = stringResource(R.string.pm_content_macro_hint),
                highlightMacros = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (isCustomized) {
                AppButton(
                    onClick = {
                        if (isCoreRules && editingOffline) offlineTextValue = TextFieldValue(offlineDefaultTemplate)
                        else textValue = TextFieldValue(defaultTemplate)
                    },
                    style = AppButtonStyle.Text,
                    modifier = Modifier.align(Alignment.End),
                ) { Text(stringResource(R.string.pm_restore_default)) }
            }

            // 底部宏区（P3）：分组宏片点按插入到光标处 + 受保护宏警告（含 [mood:] 等解析器强耦合格式）。作用于当前版缓冲。
            MacroInserterSection(
                showWarning = PromptMacros.protectedMacros.any { currentValue.text.contains(it) } ||
                    currentValue.text.contains("[mood:"),
                onInsert = { macro ->
                    if (isCoreRules && editingOffline) offlineTextValue = insertMacro(offlineTextValue, macro)
                    else textValue = insertMacro(textValue, macro)
                },
            )

            Text(stringResource(R.string.pm_section_position), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            AppSegmentedControl(
                options = listOf(PromptModulePosition.PREFIX, PromptModulePosition.SUFFIX),
                selected = position,
                onSelect = { position = it },
                modifier = Modifier.fillMaxWidth(),
                label = { stringResource(if (it == PromptModulePosition.PREFIX) R.string.pm_position_prefix else R.string.pm_position_suffix) },
            )

            Text(stringResource(R.string.pm_section_scenes), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            // 两语境模型 v2（图纸 §4-U3）：两行开关——在线聊天 / 线下见面。读写逻辑不变，只动这两位，老配置死位不触碰。
            listOf(PromptScene.ONLINE_CHAT, PromptScene.OFFLINE_MEETING).forEach { scene ->
                val checked = (scenes ?: PromptScene.entries.toSet()).contains(scene)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(sceneName(scene), Modifier.weight(1f))
                    AppSwitch(
                        checked = checked,
                        onCheckedChange = { on ->
                            val base = (scenes ?: PromptScene.entries.toSet()).toMutableSet()
                            if (on) base.add(scene) else base.remove(scene)
                            scenes = base
                        },
                    )
                }
            }
            Text(
                stringResource(R.string.pm_scenes_footer_v2),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (onDelete != null) {
                Spacer(Modifier.height(8.dp))
                AppButton(onClick = onDelete, style = AppButtonStyle.Text, danger = true, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pm_delete_module))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 把宏插入到当前光标处（替换选区），并把光标移到宏之后（P3 宏区点按插入）。 */
private fun insertMacro(tv: TextFieldValue, macro: String): TextFieldValue {
    val start = tv.selection.start.coerceIn(0, tv.text.length)
    val end = tv.selection.end.coerceIn(0, tv.text.length)
    val newText = tv.text.replaceRange(start, end, macro)
    return TextFieldValue(text = newText, selection = TextRange(start + macro.length))
}

private class MacroGroup(val labelRes: Int, val macros: List<String>)

/** 底部宏区分组（标识冻结契约见 [PromptMacros]；细粒度宏二期再加）。 */
private val MACRO_GROUPS = listOf(
    MacroGroup(R.string.pm_macrogrp_name, listOf(PromptMacros.CHAR, PromptMacros.USER, PromptMacros.NOW)),
    MacroGroup(R.string.pm_macrogrp_profile, listOf(PromptMacros.CHAR_PROFILE, PromptMacros.CHAR_GROWTH, PromptMacros.USER_PERSONA)),
    MacroGroup(R.string.pm_macrogrp_memory, listOf(PromptMacros.CHAR_MEMORY, PromptMacros.MEETING_MEMORY)),
    MacroGroup(R.string.pm_macrogrp_schedule, listOf(PromptMacros.SCHEDULE_TODAY, PromptMacros.CURRENT_MOMENT, PromptMacros.USER_CALENDAR, PromptMacros.TIME_CONTEXT)),
    MacroGroup(R.string.pm_macrogrp_social, listOf(PromptMacros.MOMENTS_CONTEXT, PromptMacros.PET_STATUS, PromptMacros.GIFT_HISTORY, PromptMacros.ECONOMIC_STATE, PromptMacros.STICKER_LIBRARY)),
    MacroGroup(R.string.pm_macrogrp_format, listOf(PromptMacros.MOOD_FORMAT, PromptMacros.REPLY_SEGMENTS)),
)

/** 底部「可用宏 · 点按插入」区：分组宏片 + 说明 + 受保护宏警告（P3 · 契约 §5.3）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MacroInserterSection(showWarning: Boolean, onInsert: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.pm_macros_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        MACRO_GROUPS.forEach { group ->
            Text(
                stringResource(group.labelRes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                group.macros.forEach { macro ->
                    AppActionChip(onClick = { onInsert(macro) }, label = macro)
                }
            }
        }
        Text(
            stringResource(R.string.pm_macros_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showWarning) {
            Text(
                stringResource(R.string.pm_macros_warn),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
