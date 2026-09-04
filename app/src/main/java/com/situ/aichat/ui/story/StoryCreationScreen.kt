package com.situ.aichat.ui.story

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.CustomStoryPrompts
import com.situ.aichat.story.StoryChapterLength
import com.situ.aichat.story.StoryChatInfluenceWeight
import com.situ.aichat.story.StoryCreationCatalog
import com.situ.aichat.story.StoryCreationLogic
import com.situ.aichat.story.StoryNarrativePerson
import com.situ.aichat.story.StoryRoleType
import com.situ.aichat.story.StoryWritingTechniques
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppChoiceChip
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDropdownField
import com.situ.aichat.ui.designsystem.AppDropdownMenuItem
import com.situ.aichat.ui.designsystem.AppSegmentedControl
import com.situ.aichat.ui.designsystem.AppSwitch
import com.situ.aichat.ui.designsystem.AppTextArea
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar
import com.situ.aichat.ui.designsystem.appCardSurface

private enum class StoryEditField(val titleRes: Int, val placeholderRes: Int, val subtitleRes: Int?, val maxLength: Int?) {
    // 世界观/剧情方向上限 4000（2026-08-04 用户拍板·由 2000 放宽）：书页后编辑路本就不限长（HubCreativeTextField
    // 传 maxLength=null），此处只是创建时闸口，放宽不产生「后编辑被截」的不一致。
    WORLD(R.string.story_field_world_title, R.string.story_field_world_placeholder, null, 4000),
    PLOT(R.string.story_field_plot_title, R.string.story_field_plot_placeholder, null, 4000),
    GENRE_TECH(R.string.story_field_genre_tech_title, R.string.story_field_genre_tech_placeholder, R.string.story_field_genre_tech_subtitle, null),
    WRITER(R.string.story_field_writer_title, R.string.story_field_writer_placeholder, R.string.story_field_writer_subtitle, null),
    RULES(R.string.story_field_rules_title, R.string.story_field_rules_placeholder, R.string.story_field_rules_subtitle, null),
    PERSONA(R.string.story_field_persona_title, R.string.story_field_persona_placeholder, null, 2000),
}

/** 节奏栏输入闸（卷四 §4.4·E6）：越界的这一笔**整笔拒收**（原值不动），绝不静默截一半；规矩同编辑页 `setText`。 */
internal fun acceptsPacingInput(typed: String): Boolean = typed.length <= CustomStoryPrompts.PACING_MAX_CHARS

private fun StoryCreationForm.valueFor(field: StoryEditField) = when (field) {
    StoryEditField.WORLD -> worldSetting
    StoryEditField.PLOT -> plotDirection
    StoryEditField.GENRE_TECH -> customGenreTechniques
    StoryEditField.WRITER -> customWriterIdentity
    StoryEditField.RULES -> customWritingRules
    StoryEditField.PERSONA -> customUserPersona
}

private fun StoryCreationForm.withField(field: StoryEditField, value: String) = when (field) {
    StoryEditField.WORLD -> copy(worldSetting = value)
    StoryEditField.PLOT -> copy(plotDirection = value)
    StoryEditField.GENRE_TECH -> copy(customGenreTechniques = value)
    StoryEditField.WRITER -> copy(customWriterIdentity = value)
    StoryEditField.RULES -> copy(customWritingRules = value)
    StoryEditField.PERSONA -> copy(customUserPersona = value)
}

/**
 * 故事创建屏 = 高级自定义表单（ST7b·契约 §3.1 第二层 / §6.2「自己从头写」）。表单分区：类型 chips/自定义 →
 * 自定义提示词（仅自定义类型）→ 参演角色（AI 角色 + 我也参演）→ 高级设置（世界观/剧情/人称/文风/章节长度/
 * 聊天影响）→ 开始创作。ST7b-1 全量脱 M3 配色 → AppTheme token + App* 组件（AppDropdownField 换旧
 * DropdownMenu·行为不变）；Scaffold/TopAppBar/Switch 仍走 M3 结构件（照 ST7a 书架换装惯例·弹窗已换 AppDialog）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryCreationScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    viewModel: StoryCreationViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val characters by viewModel.characters.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val creating by viewModel.creating.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var editingField by remember { mutableStateOf<StoryEditField?>(null) }
    // D-7：本书专属角色（非空名的那些）同样满足「至少一个角色」——纯专属角色也能开书。
    val canCreate = StoryCreationLogic.canCreateStory(
        form.isCustomGenre,
        form.customGenreName,
        form.includeUserRole,
        form.selectedRoles.size,
        form.customRoles.count { it.name.isNotBlank() },
    )

    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.story_create_title),
                onBack = onBack,
                // 创建中禁止退出：钮灰掉但仍在原位（图纸 §4.6）。
                backEnabled = !creating,
                lifted = listState.canScrollBackward,
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item { GenreSection(form, viewModel::update) }
            if (form.isCustomGenre) {
                item { CustomPromptSection(form, viewModel::update) { editingField = it } }
            }
            item { CharacterSection(form, characters, userProfile?.nickname.orEmpty(), userProfile?.bio.orEmpty(), viewModel::update) { editingField = it } }
            item { AdvancedSection(form, viewModel::update) { editingField = it } }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppButton(
                        onClick = { viewModel.createStory(onCreated) },
                        style = AppButtonStyle.Primary,
                        enabled = canCreate && !creating,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.story_create_start), fontWeight = FontWeight.Bold) }
                    Text(
                        stringResource(R.string.story_create_footer),
                        style = AppTheme.typography.secondary,
                        color = AppTheme.colors.text.secondary,
                    )
                }
            }
        }
    }

    editingField?.let { field ->
        StoryTextEditorSheet(
            title = stringResource(field.titleRes),
            subtitle = field.subtitleRes?.let { stringResource(it) },
            placeholder = stringResource(field.placeholderRes),
            initialText = form.valueFor(field),
            maxLength = field.maxLength,
            fillDefaultLabel = if (field == StoryEditField.WRITER || field == StoryEditField.RULES) stringResource(R.string.story_editor_fill_default) else null,
            fillDefault = when (field) {
                StoryEditField.WRITER -> { { StoryWritingTechniques.writerIdentity(form.writingStyle) } }
                // 只填风格原则：忌口由「文字忌口」字段单独负责，两者正交（修双重注入·提案 §5）
                StoryEditField.RULES -> { { StoryWritingTechniques.writingPrinciples } }
                else -> null
            },
            onConfirm = { value -> viewModel.update { it.withField(field, value) } },
            onDismiss = { editingField = null },
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

// ── 类型 ──

@Composable
private fun GenreSection(form: StoryCreationForm, update: ((StoryCreationForm) -> StoryCreationForm) -> Unit) {
    SectionCard(stringResource(R.string.story_create_genre_section)) {
        Text(stringResource(R.string.story_create_select_genre), style = AppTheme.typography.label, color = AppTheme.colors.text.primary)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StoryCreationCatalog.genres.forEach { genre ->
                val selected = form.selectedGenre == genre && !form.isCustomGenre
                AppChoiceChip(
                    selected = selected,
                    onClick = { update { it.copy(selectedGenre = genre, isCustomGenre = false) } },
                    label = genre,
                )
            }
            AppChoiceChip(
                selected = form.isCustomGenre,
                onClick = { update { it.copy(isCustomGenre = true) } },
                label = stringResource(R.string.story_create_custom),
            )
        }
        if (form.isCustomGenre) {
            AppTextField(
                value = form.customGenreName,
                onValueChange = { v -> update { it.copy(customGenreName = v) } },
                placeholder = stringResource(R.string.story_create_custom_genre_hint),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ── 自定义提示词 ──

@Composable
private fun CustomPromptSection(
    form: StoryCreationForm,
    update: ((StoryCreationForm) -> StoryCreationForm) -> Unit,
    onEdit: (StoryEditField) -> Unit,
) {
    SectionCard(stringResource(R.string.story_create_custom_prompt_section)) {
        LabeledDropdown(
            label = stringResource(R.string.story_create_reference_template),
            options = listOf<String?>(null) + StoryCreationCatalog.genres,
            selected = form.referenceGenre,
            display = { it ?: stringResource(R.string.story_create_reference_none) },
            onSelect = { genre ->
                update {
                    if (genre != null) it.copy(referenceGenre = genre, customGenreTechniques = StoryWritingTechniques.genreTechniques(genre))
                    else it.copy(referenceGenre = null)
                }
            },
        )
        TextEditRow(stringResource(R.string.story_field_genre_tech_title), form.customGenreTechniques) { onEdit(StoryEditField.GENRE_TECH) }
        // 卷四 §4.4：写作身份三档预设 chips（代填动作不是单选态，故已填也不高亮·与统一编辑页共用同一件）。
        PresetChips(R.string.story_create_preset_hint) { text -> update { it.copy(customWriterIdentity = text) } }
        TextEditRow(stringResource(R.string.story_field_writer_title), form.customWriterIdentity) { onEdit(StoryEditField.WRITER) }
        TextEditRow(stringResource(R.string.story_field_rules_title), form.customWritingRules) { onEdit(StoryEditField.RULES) }
        Text(stringResource(R.string.story_create_custom_prompt_footer), style = AppTheme.typography.secondary, color = AppTheme.colors.text.secondary)
    }
}

// ── 参演角色 ──

@Composable
private fun CharacterSection(
    form: StoryCreationForm,
    characters: List<CharacterEntity>,
    nickname: String,
    bio: String,
    update: ((StoryCreationForm) -> StoryCreationForm) -> Unit,
    onEdit: (StoryEditField) -> Unit,
) {
    val c = AppTheme.colors
    SectionCard(stringResource(R.string.story_create_char_section)) {
        if (characters.isEmpty()) {
            Text(stringResource(R.string.story_create_no_chars), style = AppTheme.typography.secondary, color = c.text.secondary)
        } else {
            characters.forEach { ch -> CharacterRow(ch, form, update) }
        }

        ToggleRow(stringResource(R.string.story_create_include_user), form.includeUserRole) { on ->
            update {
                val name = if (on && nickname.isNotBlank()) nickname else it.userRoleName
                it.copy(includeUserRole = on, userRoleName = name)
            }
        }

        if (form.includeUserRole) {
            AppTextField(
                value = form.userRoleName,
                onValueChange = { v -> update { it.copy(userRoleName = v) } },
                label = stringResource(R.string.story_create_user_role_name),
                modifier = Modifier.fillMaxWidth(),
            )
            RoleTypeSelector(form.userRoleType) { v -> update { it.copy(userRoleType = v) } }
            Text(userRoleTypeHint(form.userRoleType), style = AppTheme.typography.secondary, color = c.text.secondary)

            LabeledDropdown(
                label = stringResource(R.string.story_create_persona_source),
                options = listOf(UserPersonaSource.PROFILE, UserPersonaSource.CUSTOM),
                selected = form.userPersonaSource,
                display = { stringResource(if (it == UserPersonaSource.PROFILE) R.string.story_create_persona_profile else R.string.story_create_persona_custom) },
                onSelect = { v -> update { it.copy(userPersonaSource = v) } },
            )
            if (form.userPersonaSource == UserPersonaSource.PROFILE) {
                if (bio.isBlank()) {
                    Text(stringResource(R.string.story_create_persona_empty), style = AppTheme.typography.secondary, color = c.accent.text)
                } else {
                    Text(stringResource(R.string.story_create_persona_use, nickname.ifBlank { "我" }), style = AppTheme.typography.secondary, color = c.text.secondary)
                }
            } else {
                val filled = form.customUserPersona.isNotBlank()
                TextEditRow(
                    title = stringResource(R.string.story_create_edit_persona),
                    value = if (filled) stringResource(R.string.story_create_filled) else stringResource(R.string.story_create_unfilled),
                    showValueAsStatus = true,
                ) { onEdit(StoryEditField.PERSONA) }
            }
        }
        Text(stringResource(R.string.story_create_char_footer), style = AppTheme.typography.secondary, color = c.text.secondary)

        // 图纸二 D1：本书专属角色（攒在表单里，随「开始创作」一起落库）——行与弹层全在 StoryRolesSection.kt
        StoryCustomRolesBlock(
            customRoles = form.customRoles,
            onAdd = { draft -> update { it.copy(customRoles = it.customRoles + draft) } },
            onUpdate = { index, draft -> update { it.withCustomRoleAt(index, draft) } },
            onRemove = { index -> update { it.withoutCustomRoleAt(index) } },
        )
    }
}

@Composable
private fun CharacterRow(ch: CharacterEntity, form: StoryCreationForm, update: ((StoryCreationForm) -> StoryCreationForm) -> Unit) {
    val c = AppTheme.colors
    val selected = form.selectedRoles.containsKey(ch.uuid)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CharacterAvatar(name = ch.name, avatarPath = ch.avatarPath, size = 44.dp)
            Column(Modifier.weight(1f)) {
                Text(ch.name, style = AppTheme.typography.label, color = c.text.primary)
                if (ch.personalityDescription.isNotEmpty()) {
                    Text(ch.personalityDescription, style = AppTheme.typography.secondary, color = c.text.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            AppSwitch(
                checked = selected,
                onCheckedChange = { on ->
                    update {
                        val roles = it.selectedRoles.toMutableMap()
                        val descs = it.roleDescriptions.toMutableMap()
                        if (on) {
                            roles[ch.uuid] = defaultRoleType(it)
                        } else {
                            roles.remove(ch.uuid)
                            descs.remove(ch.uuid)
                        }
                        it.copy(selectedRoles = roles, roleDescriptions = descs)
                    }
                },
            )
        }
        if (selected) {
            RoleTypeSelector(form.selectedRoles[ch.uuid] ?: StoryRoleType.SUPPORTING) { v ->
                update { it.copy(selectedRoles = it.selectedRoles + (ch.uuid to v)) }
            }
            AppTextArea(
                value = form.roleDescriptions[ch.uuid].orEmpty(),
                onValueChange = { v -> update { it.copy(roleDescriptions = it.roleDescriptions + (ch.uuid to v)) } },
                placeholder = stringResource(R.string.story_create_char_desc_hint),
                minHeight = 72.dp, // ≈ 2 行起步（原 minLines=2），随内容长到 maxLines=4
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 默认角色定位：首个选中角色（无其他角色 + 用户未参演）→ 主角，否则配角（1:1 iOS defaultRoleType）。 */
private fun defaultRoleType(form: StoryCreationForm): String =
    if (form.selectedRoles.isEmpty() && !form.includeUserRole) StoryRoleType.PROTAGONIST else StoryRoleType.SUPPORTING

// ── 高级设置 ──

@Composable
private fun AdvancedSection(
    form: StoryCreationForm,
    update: ((StoryCreationForm) -> StoryCreationForm) -> Unit,
    onEdit: (StoryEditField) -> Unit,
) {
    val c = AppTheme.colors
    var expanded by remember { mutableStateOf(false) }
    SectionCard(null) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.story_create_advanced), style = AppTheme.typography.label, color = c.text.primary, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = c.text.secondary)
        }
        if (expanded) {
            TextEditRow(stringResource(R.string.story_field_world_title), form.worldSetting) { onEdit(StoryEditField.WORLD) }
            TextEditRow(stringResource(R.string.story_field_plot_title), form.plotDirection) { onEdit(StoryEditField.PLOT) }
            LabeledDropdown(
                label = stringResource(R.string.story_create_narrative),
                options = listOf(StoryNarrativePerson.SECOND, StoryNarrativePerson.FIRST, StoryNarrativePerson.THIRD),
                selected = form.narrativePerson,
                display = { narrativeName(it) },
                onSelect = { v -> update { it.copy(narrativePerson = v) } },
            )
            LabeledDropdown(
                label = stringResource(R.string.story_create_style),
                options = StoryCreationCatalog.writingStyles,
                selected = form.writingStyle,
                display = { it },
                onSelect = { v -> update { it.copy(writingStyle = v) } },
            )
            if (StoryCreationLogic.styleOverriddenByWriterIdentity(form.isCustomGenre, form.customWriterIdentity)) {
                Text(stringResource(R.string.story_create_style_overridden_hint), style = AppTheme.typography.secondary, color = c.text.secondary)
            }
            LabeledDropdown(
                label = stringResource(R.string.story_create_chapter_length),
                options = StoryChapterLength.entries.toList(),
                selected = form.chapterLength,
                display = { chapterLengthName(it) },
                onSelect = { v -> update { it.copy(chapterLength = v) } },
            )
            // 卷二·单模式化（用户拍板①）：「连载模式」选择行 + 自定义章数输入整体删除——
            // 故事一律无限连载，收尾改由阅读器的「准备收尾」（终章弧）承担。
            LabeledDropdown(
                label = stringResource(R.string.story_create_chat_influence),
                options = StoryCreationCatalog.chatInfluenceWeights,
                selected = form.chatInfluenceWeight,
                display = { chatInfluenceName(it) },
                onSelect = { v -> update { it.copy(chatInfluenceWeight = v) } },
            )
            Text(chatInfluenceDetail(form.chatInfluenceWeight), style = AppTheme.typography.secondary, color = c.text.secondary)
            // 卷三 V2：节奏偏好（选填一句话·与题材无关）。留空 = 完全交给 AI，不做任何必填校验。
            // 卷四 §4.4：废静默截断——越界的这一笔整笔拒收（原值不动），计数行同步告诉用户撞到顶了。
            AppTextField(
                value = form.pacingPreference,
                onValueChange = { v -> if (acceptsPacingInput(v)) update { it.copy(pacingPreference = v) } },
                label = stringResource(R.string.story_create_pacing),
                placeholder = stringResource(R.string.story_create_pacing_hint),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            CharCounter(form.pacingPreference.length, CustomStoryPrompts.PACING_MAX_CHARS)
        }
    }
}

// ── 复用小组件 ──

@Composable
private fun SectionCard(title: String?, content: @Composable () -> Unit) {
    // 标题留卡外（start 4/bottom 6）+ 内容进 appCardSurface 卡（16×12·内容间距 12 沿既有·§4.A10·D-5）。
    Column {
        title?.let {
            Text(
                it,
                style = AppTheme.typography.titleSmall,
                color = AppTheme.colors.text.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().appCardSurface().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = AppTheme.typography.body, color = AppTheme.colors.text.primary, modifier = Modifier.weight(1f))
        AppSwitch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun TextEditRow(title: String, value: String, showValueAsStatus: Boolean = false, onClick: () -> Unit) {
    val c = AppTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = AppTheme.typography.body,
                color = if (value.isEmpty() && !showValueAsStatus) c.text.secondary else c.text.primary,
            )
            if (value.isNotEmpty()) {
                Text(value, style = AppTheme.typography.secondary, color = c.text.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = c.text.tertiary)
    }
}

/** 只读下拉（App* 换皮·框上方静态标签 + 菜单陶土 tint 勾选项）——行为等价旧 M3 DropdownMenu 版。 */
@Composable
private fun <T> LabeledDropdown(
    label: String,
    options: List<T>,
    selected: T,
    display: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    AppDropdownField(
        value = display(selected),
        expanded = expanded,
        onExpandedChange = { expanded = it },
        label = label,
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEach { opt ->
            AppDropdownMenuItem(
                text = display(opt),
                selected = opt == selected,
                onClick = { onSelect(opt); expanded = false },
            )
        }
    }
}

@Composable
private fun RoleTypeSelector(selected: String, onSelect: (String) -> Unit) {
    val types = listOf(
        StoryRoleType.PROTAGONIST to R.string.story_role_protagonist,
        StoryRoleType.SUPPORTING to R.string.story_role_supporting,
        StoryRoleType.ANTAGONIST to R.string.story_role_antagonist,
    )
    AppSegmentedControl(
        options = types.map { it.first },
        selected = selected,
        onSelect = { onSelect(it) },
        modifier = Modifier.fillMaxWidth(),
        label = { value -> stringResource(types.first { it.first == value }.second) },
    )
}

// ── 文案映射（创建/设置共用，避免 enum 持 UI 串）──

@Composable
internal fun narrativeName(person: String) = stringResource(
    when (person) {
        StoryNarrativePerson.FIRST -> R.string.story_narrative_first
        StoryNarrativePerson.THIRD -> R.string.story_narrative_third
        else -> R.string.story_narrative_second
    },
)

@Composable
internal fun chapterLengthName(length: StoryChapterLength) = stringResource(
    when (length) {
        StoryChapterLength.SHORT -> R.string.story_length_short
        StoryChapterLength.MEDIUM -> R.string.story_length_medium
        StoryChapterLength.LONG -> R.string.story_length_long
        StoryChapterLength.EXTRA_LONG -> R.string.story_length_extra_long
    },
)

@Composable
internal fun chatInfluenceName(weight: String) = stringResource(
    when (weight) {
        StoryChatInfluenceWeight.NONE -> R.string.story_influence_none
        StoryChatInfluenceWeight.LIGHT -> R.string.story_influence_light
        StoryChatInfluenceWeight.HEAVY -> R.string.story_influence_heavy
        else -> R.string.story_influence_medium
    },
)

@Composable
internal fun chatInfluenceDetail(weight: String) = stringResource(
    when (weight) {
        StoryChatInfluenceWeight.NONE -> R.string.story_influence_none_detail
        StoryChatInfluenceWeight.LIGHT -> R.string.story_influence_light_detail
        StoryChatInfluenceWeight.HEAVY -> R.string.story_influence_heavy_detail
        else -> R.string.story_influence_medium_detail
    },
)

@Composable
private fun userRoleTypeHint(roleType: String) = stringResource(
    when (roleType) {
        StoryRoleType.PROTAGONIST -> R.string.story_user_role_hint_protagonist
        StoryRoleType.ANTAGONIST -> R.string.story_user_role_hint_antagonist
        else -> R.string.story_user_role_hint_supporting
    },
)
