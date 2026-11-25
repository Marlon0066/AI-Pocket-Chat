package com.situ.aichat.ui.liuli.story

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.CustomStoryPrompts
import com.situ.aichat.story.StoryChapterLength
import com.situ.aichat.story.StoryCreationCatalog
import com.situ.aichat.story.StoryCreationLogic
import com.situ.aichat.story.StoryRoleType
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliChip
import com.situ.aichat.ui.liuli.designsystem.LiuliField
import com.situ.aichat.ui.liuli.designsystem.LiuliSegmented
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import com.situ.aichat.ui.liuli.page.LiuliRowTitleColumn
import com.situ.aichat.ui.liuli.page.LiuliToggleRow
import com.situ.aichat.ui.story.CharCounter
import com.situ.aichat.ui.story.StoryCreationForm
import com.situ.aichat.ui.story.StoryEditField
import com.situ.aichat.ui.story.UserPersonaSource
import com.situ.aichat.ui.story.acceptsPacingInput
import com.situ.aichat.ui.story.chapterLengthName
import com.situ.aichat.ui.story.chatInfluenceDetail
import com.situ.aichat.ui.story.chatInfluenceName
import com.situ.aichat.ui.story.narrativeName
import com.situ.aichat.ui.story.roleTypeLabelRes
import com.situ.aichat.ui.story.storyFormToggleCharacter
import com.situ.aichat.ui.story.storyFormWithIncludeUser
import com.situ.aichat.ui.story.storyFormWithReferenceGenre
import com.situ.aichat.ui.story.storyGenreChipSelected
import com.situ.aichat.ui.story.storyNarrativeOptions
import com.situ.aichat.ui.story.storyPersonaSourceLabelRes
import com.situ.aichat.ui.story.storyPersonaSources
import com.situ.aichat.ui.story.storyReferenceGenreOptions
import com.situ.aichat.ui.story.storyRoleTypes
import com.situ.aichat.ui.story.userRoleTypeHint

// 琉璃创建故事的四组（琉璃 2.0 卷六·三·上 §4.15）：行型子项直接放琉璃行，非行型子项放进「16 / 12 内距、12 缝」的块。

/** 块内距 16 / 12 · 缝 12（= 暖陶 `SectionCard`）。 */
private val BLOCK_PAD_H = LiuliPageGeometry.groupPadH
private val BLOCK_PAD_V = 12.dp
private val BLOCK_GAP = 12.dp
/** 角色头像 28（= 砖位）· 描述框 72 / 4 行 · 简介 2 行（= 暖陶）。 */
private val CHAR_AVATAR = LiuliPageGeometry.tile
private val CHAR_DESC_MIN = 72.dp
private const val CHAR_DESC_LINES = 4
private const val CHAR_SUB_LINES = 2

@Composable
private fun LiuliCreationBlock(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().padding(horizontal = BLOCK_PAD_H, vertical = BLOCK_PAD_V), verticalArrangement = Arrangement.spacedBy(BLOCK_GAP), content = content)

/** 题材组：题材 chips（+ 自定义）+ 自定义题材名。 */
@Composable
internal fun LiuliCreationGenreGroup(form: StoryCreationForm, update: ((StoryCreationForm) -> StoryCreationForm) -> Unit) {
    LiuliGroup(header = stringResource(R.string.story_create_genre_section)) {
        LiuliCreationBlock {
            Text(stringResource(R.string.story_create_select_genre), style = AppTypography.label, color = AppTheme.colors.text.primary)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StoryCreationCatalog.genres.forEach { g ->
                    LiuliChip(selected = storyGenreChipSelected(form, g), onClick = { update { it.copy(selectedGenre = g, isCustomGenre = false) } }, label = g)
                }
                LiuliChip(selected = form.isCustomGenre, onClick = { update { it.copy(isCustomGenre = true) } }, label = stringResource(R.string.story_create_custom))
            }
            if (form.isCustomGenre) {
                LiuliField(
                    form.customGenreName, { v -> update { it.copy(customGenreName = v) } },
                    placeholder = stringResource(R.string.story_create_custom_genre_hint), modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 自定义提示词组（仅自定义题材出）：参考模板下拉 + 类型技法 + 身份预设 + 写作身份 + 写作规则。 */
@Composable
internal fun LiuliCreationCustomPromptGroup(form: StoryCreationForm, update: ((StoryCreationForm) -> StoryCreationForm) -> Unit, onEdit: (StoryEditField) -> Unit) {
    LiuliGroup(header = stringResource(R.string.story_create_custom_prompt_section), footer = stringResource(R.string.story_create_custom_prompt_footer)) {
        LiuliStoryMenuRow(
            title = stringResource(R.string.story_create_reference_template), options = storyReferenceGenreOptions(), selected = form.referenceGenre,
            display = { it ?: stringResource(R.string.story_create_reference_none) }, onSelect = { g -> update { storyFormWithReferenceGenre(it, g) } }, divider = false,
        )
        LiuliStoryTextEditRow(stringResource(R.string.story_field_genre_tech_title), form.customGenreTechniques, onClick = { onEdit(StoryEditField.GENRE_TECH) }, divider = true)
        // 写作身份三档预设 chips（代填动作不是单选态，故已填也不高亮·与统一编辑页共用同一份文案单源）。
        LiuliCreationBlock { LiuliStoryPresetChips(R.string.story_create_preset_hint) { text -> update { it.copy(customWriterIdentity = text) } } }
        LiuliStoryTextEditRow(stringResource(R.string.story_field_writer_title), form.customWriterIdentity, onClick = { onEdit(StoryEditField.WRITER) }, divider = true)
        LiuliStoryTextEditRow(stringResource(R.string.story_field_rules_title), form.customWritingRules, onClick = { onEdit(StoryEditField.RULES) }, divider = true)
    }
}

/** 参演角色组：聊天角色开关行（选中出定位 + 描述）+ 我也参演（打开出角色名 / 定位 / 人设来源）；组脚注 = 暖陶那句。 */
@Composable
internal fun LiuliCreationCharacterGroup(
    form: StoryCreationForm,
    characters: List<CharacterEntity>,
    nickname: String,
    bio: String,
    update: ((StoryCreationForm) -> StoryCreationForm) -> Unit,
    onEdit: (StoryEditField) -> Unit,
) {
    val c = AppTheme.colors
    val typeLabel: @Composable (String) -> String = { stringResource(roleTypeLabelRes(it)) }
    LiuliGroup(header = stringResource(R.string.story_create_char_section), footer = stringResource(R.string.story_create_char_footer)) {
        if (characters.isEmpty()) {
            LiuliCreationBlock { Text(stringResource(R.string.story_create_no_chars), style = AppTypography.secondary, color = c.text.secondary) }
        } else {
            characters.forEachIndexed { i, ch ->
                val selected = form.selectedRoles.containsKey(ch.uuid)
                LiuliToggleRow(
                    title = ch.name, checked = selected, onCheckedChange = { on -> update { storyFormToggleCharacter(it, ch.uuid, on) } },
                    subtitle = ch.personalityDescription.takeIf { it.isNotEmpty() },
                    leading = { CharacterAvatar(name = ch.name, avatarPath = ch.avatarPath, size = CHAR_AVATAR) },
                    divider = i > 0, subtitleMaxLines = CHAR_SUB_LINES,
                )
                if (selected) {
                    LiuliCreationBlock {
                        LiuliSegmented(
                            storyRoleTypes, form.selectedRoles[ch.uuid] ?: StoryRoleType.SUPPORTING, typeLabel,
                            { v -> update { it.copy(selectedRoles = it.selectedRoles + (ch.uuid to v)) } }, Modifier.fillMaxWidth(),
                        )
                        LiuliField(
                            form.roleDescriptions[ch.uuid].orEmpty(), { v -> update { it.copy(roleDescriptions = it.roleDescriptions + (ch.uuid to v)) } },
                            placeholder = stringResource(R.string.story_create_char_desc_hint), singleLine = false,
                            minHeight = CHAR_DESC_MIN, maxLines = CHAR_DESC_LINES, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        LiuliToggleRow(
            title = stringResource(R.string.story_create_include_user), checked = form.includeUserRole,
            onCheckedChange = { on -> update { storyFormWithIncludeUser(it, on, nickname) } }, divider = characters.isNotEmpty(),
        )
        if (form.includeUserRole) {
            LiuliCreationBlock {
                LiuliField(form.userRoleName, { v -> update { it.copy(userRoleName = v) } }, label = stringResource(R.string.story_create_user_role_name), modifier = Modifier.fillMaxWidth())
                LiuliSegmented(storyRoleTypes, form.userRoleType, typeLabel, { v -> update { it.copy(userRoleType = v) } }, Modifier.fillMaxWidth())
                Text(userRoleTypeHint(form.userRoleType), style = AppTypography.secondary, color = c.text.secondary)
            }
            LiuliStoryMenuRow(
                title = stringResource(R.string.story_create_persona_source), options = storyPersonaSources, selected = form.userPersonaSource,
                display = { stringResource(storyPersonaSourceLabelRes(it)) }, onSelect = { v -> update { it.copy(userPersonaSource = v) } }, divider = true,
            )
            if (form.userPersonaSource == UserPersonaSource.PROFILE) {
                LiuliCreationBlock {
                    Text(
                        if (bio.isBlank()) stringResource(R.string.story_create_persona_empty) else stringResource(R.string.story_create_persona_use, nickname.ifBlank { "我" }),
                        style = AppTypography.secondary,
                        color = if (bio.isBlank()) c.accent.text else c.text.secondary,
                    )
                }
            } else {
                LiuliStoryTextEditRow(
                    title = stringResource(R.string.story_create_edit_persona),
                    value = stringResource(if (form.customUserPersona.isNotBlank()) R.string.story_create_filled else R.string.story_create_unfilled),
                    onClick = { onEdit(StoryEditField.PERSONA) }, divider = true, showValueAsStatus = true,
                )
            }
        }
    }
}

/** 高级设置组（折叠·不存档同暖陶）：世界观 / 剧情 / 人称 / 文风 / 章长 / 聊天影响 / 节奏。 */
@Composable
internal fun LiuliCreationAdvancedGroup(form: StoryCreationForm, update: ((StoryCreationForm) -> StoryCreationForm) -> Unit, onEdit: (StoryEditField) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val c = AppTheme.colors
    LiuliGroup {
        LiuliRowBase(onClick = { expanded = !expanded }, divider = false) {
            LiuliRowTitleColumn(stringResource(R.string.story_create_advanced), null, Modifier.weight(1f))
            Icon(AppActionIcons.ChevronDown, contentDescription = null, tint = c.text.tertiary, modifier = Modifier.size(STORY_CHEVRON))
        }
        if (expanded) {
            LiuliStoryTextEditRow(stringResource(R.string.story_field_world_title), form.worldSetting, onClick = { onEdit(StoryEditField.WORLD) }, divider = true)
            LiuliStoryTextEditRow(stringResource(R.string.story_field_plot_title), form.plotDirection, onClick = { onEdit(StoryEditField.PLOT) }, divider = true)
            LiuliStoryMenuRow(stringResource(R.string.story_create_narrative), storyNarrativeOptions, form.narrativePerson, { narrativeName(it) }, { v -> update { it.copy(narrativePerson = v) } }, divider = true)
            LiuliStoryMenuRow(stringResource(R.string.story_create_style), StoryCreationCatalog.writingStyles, form.writingStyle, { it }, { v -> update { it.copy(writingStyle = v) } }, divider = true)
            if (StoryCreationLogic.styleOverriddenByWriterIdentity(form.isCustomGenre, form.customWriterIdentity)) {
                LiuliCreationBlock { Text(stringResource(R.string.story_create_style_overridden_hint), style = AppTypography.secondary, color = c.text.secondary) }
            }
            LiuliStoryMenuRow(
                stringResource(R.string.story_create_chapter_length), StoryChapterLength.entries.toList(), form.chapterLength, { chapterLengthName(it) },
                { v -> update { it.copy(chapterLength = v) } }, divider = true,
            )
            // 卷二·单模式化（用户拍板①）：「连载模式」选择行 + 自定义章数输入整体删除——
            // 故事一律无限连载，收尾改由阅读器的「准备收尾」（终章弧）承担。
            LiuliStoryMenuRow(
                stringResource(R.string.story_create_chat_influence), StoryCreationCatalog.chatInfluenceWeights, form.chatInfluenceWeight, { chatInfluenceName(it) },
                { v -> update { it.copy(chatInfluenceWeight = v) } }, divider = true,
            )
            LiuliCreationBlock {
                Text(chatInfluenceDetail(form.chatInfluenceWeight), style = AppTypography.secondary, color = c.text.secondary)
                // 卷三 V2：节奏偏好（选填一句话·与题材无关）。留空 = 完全交给 AI，不做任何必填校验。
                // 卷四 §4.4：废静默截断——越界的这一笔整笔拒收（原值不动），计数行同步告诉用户撞到顶了。
                LiuliField(
                    form.pacingPreference, { v -> if (acceptsPacingInput(v)) update { it.copy(pacingPreference = v) } },
                    label = stringResource(R.string.story_create_pacing), placeholder = stringResource(R.string.story_create_pacing_hint),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                CharCounter(form.pacingPreference.length, CustomStoryPrompts.PACING_MAX_CHARS)
            }
        }
    }
}
