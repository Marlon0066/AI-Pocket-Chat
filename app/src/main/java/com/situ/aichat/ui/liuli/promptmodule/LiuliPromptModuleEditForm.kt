package com.situ.aichat.ui.liuli.promptmodule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.semantics.Role
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
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.MacroHighlightTransformation
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliChip
import com.situ.aichat.ui.liuli.designsystem.LiuliField
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliInputRow
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.LiuliSaveBar
import com.situ.aichat.ui.liuli.page.LiuliSegmentRow
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.liuli.page.liuliSaveBarInset
import com.situ.aichat.ui.liuli.page.rememberLargeTitleCollapsed
import com.situ.aichat.ui.promptmodule.sceneName
import kotlinx.coroutines.delay

/** 防抖自动保存的停手时长（逐字照暖陶 400ms——改它就改了「停手多久算写完」）。 */
private const val AUTOSAVE_DEBOUNCE_MS = 400L
/** 内容文本域最小高（暖陶 `AppTextArea` 的默认 120）。 */
private val CONTENT_MIN_HEIGHT = 120.dp
/** 组内块间缝与宏片排的两轴缝。 */
private val BLOCK_GAP = 12.dp
private val MACRO_GAP = 8.dp
private val MACRO_GROUP_GAP = 6.dp

/** 底部宏区分组（标识冻结契约见 [PromptMacros]·**逐字照暖陶 `MACRO_GROUPS`**：顺序与成员一个不动）。 */
private class LiuliMacroGroup(val labelRes: Int, val macros: List<String>)

private val MACRO_GROUPS = listOf(
    LiuliMacroGroup(R.string.pm_macrogrp_name, listOf(PromptMacros.CHAR, PromptMacros.USER, PromptMacros.NOW)),
    LiuliMacroGroup(
        R.string.pm_macrogrp_profile,
        listOf(PromptMacros.CHAR_PROFILE, PromptMacros.CHAR_GROWTH, PromptMacros.USER_PERSONA),
    ),
    LiuliMacroGroup(R.string.pm_macrogrp_memory, listOf(PromptMacros.CHAR_MEMORY, PromptMacros.MEETING_MEMORY)),
    LiuliMacroGroup(
        R.string.pm_macrogrp_schedule,
        listOf(PromptMacros.SCHEDULE_TODAY, PromptMacros.CURRENT_MOMENT, PromptMacros.USER_CALENDAR, PromptMacros.TIME_CONTEXT),
    ),
    LiuliMacroGroup(
        R.string.pm_macrogrp_social,
        listOf(
            PromptMacros.MOMENTS_CONTEXT, PromptMacros.PET_STATUS, PromptMacros.GIFT_HISTORY,
            PromptMacros.ECONOMIC_STATE, PromptMacros.STICKER_LIBRARY,
        ),
    ),
    LiuliMacroGroup(R.string.pm_macrogrp_format, listOf(PromptMacros.MOOD_FORMAT, PromptMacros.REPLY_SEGMENTS)),
)

/**
 * 提示词模块编辑表单（琉璃·图纸 2026-09-06 卷五 §4.1 屏 21 编辑态）。**机制逐字照暖陶
 * `ModuleEditForm`**：内容缓冲 + 400ms 防抖自动保存 + 返回 flush + 宏点按插入到光标 + 恢复默认 +
 * position 分段 + 启用 / 场景两开关；核心规则的在线 / 线下**分版编辑**（两缓冲各自保留未存输入）。
 *
 * **零碰**：「与默认模板逐字一致 → 存空」这条语义是「跟随默认、不钉死，仍吃后续 App 默认更新」的开关，
 * 改它等于把所有跟随默认的模块钉死在今天的模板上。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LiuliPromptModuleEditForm(
    initial: PromptModule,
    isNew: Boolean,
    initialOffline: Boolean,
    onClose: () -> Unit,
    onCreate: (PromptModule) -> Unit,
    onAutoSave: (PromptModule) -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val context = LocalContext.current
    val colors = AppTheme.colors
    val type = initial.systemModuleType
    val isSystem = initial.isSystemGenerated
    // 仅核心规则支持在线 / 线下分版编辑（图纸 §4-U4）；其余模块表单零变化。
    val isCoreRules = type == SystemModuleType.CORE_RULES
    val defaultTemplate = remember(type) {
        if (type == null) {
            ""
        } else {
            PromptBuilder.defaultModuleTemplate(type)
                ?: PromptBuilder.defaultEditableTemplate(type, PromptStrings(context)) ?: ""
        }
    }
    val offlineDefaultTemplate = remember(type) {
        buildOfflineCoreRulesContent(PromptStrings(context), PromptMacros.CHAR, PromptMacros.USER)
    }

    var name by remember { mutableStateOf(initial.name) }
    var textValue by remember { mutableStateOf(TextFieldValue(initial.content.ifEmpty { defaultTemplate })) }
    var editingOffline by remember { mutableStateOf(initialOffline) }
    var offlineTextValue by remember {
        mutableStateOf(TextFieldValue(initial.offlineContent.ifEmpty { offlineDefaultTemplate }))
    }
    var enabled by remember { mutableStateOf(initial.isEnabled) }
    var position by remember { mutableStateOf(initial.position) }
    var scenes by remember { mutableStateOf(initial.enabledScenes) }

    val currentValue = if (isCoreRules && editingOffline) offlineTextValue else textValue
    val currentDefault = if (isCoreRules && editingOffline) offlineDefaultTemplate else defaultTemplate
    // 与默认模板逐字一致 → 存空（跟随默认）；改动过 → 存用户文本。按当前版判定。
    val isCustomized = currentValue.text != currentDefault && currentValue.text.isNotEmpty()
    val canSave = isSystem || name.isNotBlank()

    fun snapshot(): PromptModule = initial.copy(
        name = if (isSystem) initial.name else name.trim(),
        content = if (textValue.text == defaultTemplate) "" else textValue.text,
        offlineContent = if (isCoreRules) {
            if (offlineTextValue.text == offlineDefaultTemplate) "" else offlineTextValue.text
        } else {
            initial.offlineContent
        },
        isEnabled = enabled,
        position = position,
        enabledScenes = scenes,
    )

    // 既有模块：改完即生效——防抖自动保存（停手 ~400ms 落盘），无保存键；新建走底栏显式添加。
    var autosaveArmed by remember { mutableStateOf(false) }
    if (!isNew) {
        LaunchedEffect(name, textValue.text, offlineTextValue.text, enabled, position, scenes) {
            if (!autosaveArmed) {
                autosaveArmed = true // 跳过首帧（初始值无需重存）
                return@LaunchedEffect
            }
            delay(AUTOSAVE_DEBOUNCE_MS)
            onAutoSave(snapshot())
        }
    }

    // 返回（含系统返回键 / 手势）：既有模块先 flush 最后一次编辑再关闭——防 debounce 未触发即离开而丢改动。
    val closeAndFlush: () -> Unit = {
        if (!isNew) onAutoSave(snapshot())
        onClose()
    }
    BackHandler { closeAndFlush() }

    val title = stringResource(if (isNew) R.string.pm_add_title else R.string.pm_edit_title)
    val bottomInset = LiuliPageGeometry.pageBottom +
        (if (isNew) liuliSaveBarInset else 0.dp) +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LiuliPage(
        title = title,
        onBack = closeAndFlush,
        collapsed = rememberLargeTitleCollapsed(listState),
        modifier = modifier,
        // 新建走底部保存栏；既有模块自动保存 —— 那一栏换成「已自动保存」的低调告知（不是行动·拍板⑤）。
        bottomBar = if (isNew) {
            { LiuliSaveBar(text = stringResource(R.string.action_save), enabled = canSave, onClick = { onCreate(snapshot()) }) }
        } else {
            null
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding().contentMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(top = LiuliPageGeometry.navRow, bottom = bottomInset),
        ) {
            item(key = "large-title") { LiuliLargeTitle(title) }
            item(key = "groups") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = LiuliPageGeometry.gutter, vertical = LiuliPageGeometry.titleGap),
                ) {
                    LiuliGroup(footer = if (isNew) null else stringResource(R.string.pm_autosaved)) {
                        if (isSystem) {
                            LiuliRowBase(divider = false) {
                                Text(initial.name, style = AppTypography.bodyEmphasis, color = colors.text.primary)
                            }
                        } else {
                            LiuliInputRow(
                                label = stringResource(R.string.pm_field_name),
                                value = name,
                                onValueChange = { name = it },
                                placeholder = stringResource(R.string.pm_field_name_hint),
                                divider = false,
                            )
                        }
                        LiuliToggleRow(
                            title = stringResource(R.string.pm_field_enabled),
                            checked = enabled,
                            onCheckedChange = { enabled = it },
                        )
                    }

                    // 核心规则分版控件（图纸 §4-U4）：在线聊天版 / 线下见面版切换 + 一行说明。仅核心规则显示。
                    if (isCoreRules) {
                        LiuliGroup(
                            footer = stringResource(
                                if (editingOffline) R.string.pm_version_note_offline else R.string.pm_version_note_online,
                            ),
                        ) {
                            LiuliSegmentRow(
                                title = null,
                                options = listOf(false, true),
                                selected = editingOffline,
                                label = { stringResource(if (it) R.string.pm_version_offline else R.string.pm_version_online) },
                                onSelect = { editingOffline = it },
                                divider = false,
                            )
                        }
                    }

                    LiuliGroup(
                        header = stringResource(R.string.pm_section_content),
                        footer = stringResource(R.string.pm_content_macro_hint),
                    ) {
                        LiuliRowBase(
                            divider = false,
                            verticalPadding = LiuliPageGeometry.groupPadH,
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BLOCK_GAP)) {
                                LiuliTinyBadge(
                                    text = stringResource(if (isCustomized) R.string.pm_customized else R.string.pm_using_default),
                                    color = if (isCustomized) colors.accent.primary else colors.text.secondary,
                                )
                                LiuliField(
                                    value = currentValue,
                                    onValueChange = {
                                        if (isCoreRules && editingOffline) offlineTextValue = it else textValue = it
                                    },
                                    minHeight = CONTENT_MIN_HEIGHT,
                                    visualTransformation = MacroHighlightTransformation(colors.accent.text),
                                )
                                if (isCustomized) {
                                    LiuliButton(
                                        onClick = {
                                            if (isCoreRules && editingOffline) {
                                                offlineTextValue = TextFieldValue(offlineDefaultTemplate)
                                            } else {
                                                textValue = TextFieldValue(defaultTemplate)
                                            }
                                        },
                                        style = LiuliButtonStyle.Text,
                                        modifier = Modifier.align(Alignment.End),
                                    ) { Text(stringResource(R.string.pm_restore_default)) }
                                }
                            }
                        }
                    }

                    // 底部宏区（P3）：分组宏片点按插入到光标处 + 受保护宏警告（含 `[mood:` 等解析器强耦合格式）。
                    LiuliGroup(
                        header = stringResource(R.string.pm_macros_title),
                        footer = stringResource(R.string.pm_macros_note),
                    ) {
                        LiuliRowBase(
                            divider = false,
                            verticalPadding = LiuliPageGeometry.groupPadH,
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MACRO_GROUP_GAP)) {
                                MACRO_GROUPS.forEach { group ->
                                    Text(
                                        stringResource(group.labelRes),
                                        style = AppTypography.caption,
                                        color = colors.text.secondary,
                                    )
                                    FlowRow(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(MACRO_GAP),
                                    ) {
                                        group.macros.forEach { macro ->
                                            LiuliChip(
                                                selected = false,
                                                onClick = {
                                                    if (isCoreRules && editingOffline) {
                                                        offlineTextValue = liuliInsertMacro(offlineTextValue, macro)
                                                    } else {
                                                        textValue = liuliInsertMacro(textValue, macro)
                                                    }
                                                },
                                                label = macro,
                                                role = Role.Button,
                                            )
                                        }
                                    }
                                }
                                val showWarning = PromptMacros.protectedMacros.any { currentValue.text.contains(it) } ||
                                    currentValue.text.contains(MOOD_TAG_PREFIX)
                                if (showWarning) {
                                    Text(
                                        stringResource(R.string.pm_macros_warn),
                                        style = AppTypography.secondary,
                                        color = colors.status.onError,
                                    )
                                }
                            }
                        }
                    }

                    LiuliGroup(header = stringResource(R.string.pm_section_position)) {
                        LiuliSegmentRow(
                            title = null,
                            options = listOf(PromptModulePosition.PREFIX, PromptModulePosition.SUFFIX),
                            selected = position,
                            label = {
                                stringResource(
                                    if (it == PromptModulePosition.PREFIX) {
                                        R.string.pm_position_prefix
                                    } else {
                                        R.string.pm_position_suffix
                                    },
                                )
                            },
                            onSelect = { position = it },
                            divider = false,
                        )
                    }

                    LiuliGroup(
                        header = stringResource(R.string.pm_section_scenes),
                        footer = stringResource(R.string.pm_scenes_footer_v2),
                    ) {
                        // 两语境模型 v2（图纸 §4-U3）：两行开关——在线聊天 / 线下见面。老配置死位不触碰。
                        listOf(PromptScene.ONLINE_CHAT, PromptScene.OFFLINE_MEETING).forEachIndexed { index, scene ->
                            val checked = (scenes ?: PromptScene.entries.toSet()).contains(scene)
                            LiuliToggleRow(
                                title = sceneName(scene),
                                checked = checked,
                                onCheckedChange = { on ->
                                    val base = (scenes ?: PromptScene.entries.toSet()).toMutableSet()
                                    if (on) base.add(scene) else base.remove(scene)
                                    scenes = base
                                },
                                divider = index > 0,
                            )
                        }
                    }

                    if (onDelete != null) {
                        Spacer(Modifier.width(BLOCK_GAP))
                        LiuliGroup {
                            LiuliRowBase(divider = false, verticalPadding = LiuliPageGeometry.rowTwoLinePad) {
                                LiuliButton(
                                    onClick = onDelete,
                                    style = LiuliButtonStyle.Text,
                                    danger = true,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.pm_delete_module)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 把宏插入到当前光标处（替换选区），并把光标移到宏之后（逐字照暖陶 `insertMacro`）。 */
internal fun liuliInsertMacro(tv: TextFieldValue, macro: String): TextFieldValue {
    val start = tv.selection.start.coerceIn(0, tv.text.length)
    val end = tv.selection.end.coerceIn(0, tv.text.length)
    val newText = tv.text.replaceRange(start, end, macro)
    return TextFieldValue(text = newText, selection = TextRange(start + macro.length))
}

/** 情绪标记前缀（解析器强耦合格式·出现在正文里就要给受保护宏警告·逐字照暖陶）。 */
private const val MOOD_TAG_PREFIX = "[mood:"
