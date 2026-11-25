package com.situ.aichat.ui.story

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppTheme

/**
 * 参演角色的两处呈现（图纸二 D1·2026-08-01 过审 mockup 画面①③），共用同一个 [StoryRoleEditorSheet]：
 * - [StoryRolesSection]：设定页「参演角色」组——已落库的角色行（可编辑 / 移出）+「添加角色」；改动即时落库
 * - [StoryCustomRolesBlock]：创建屏「故事专属角色」块——攒在表单里，随「开始创作」一次性落库
 *
 * 行造型：角色名 + 类型徽章（既有 pill 造型：accent container + accent 字）+ 来源小字（次级色）+ ›。
 * 零新视觉 token。两个块都放在这一个文件里，是为了守住创建屏 +30 行的硬顶（图纸 §9-⑥）。
 */
@Composable
internal fun StoryRolesSection(
    storyId: String,
    roles: List<StoryCharacterRoleEntity>,
    onSave: (StoryCharacterRoleEntity) -> Unit,
    onDelete: (String) -> Unit,
    /** 「私下反差」AI 起草（卷二）；null = 没有故事创作 API 配置 → 起草钮隐藏（提案 §6.3）。 */
    onDraftPersona: (suspend (role: StoryCharacterRoleEntity, name: String, description: String) -> String?)? = null,
) {
    var editing by remember { mutableStateOf<StoryCharacterRoleEntity?>(null) }
    var adding by remember { mutableStateOf(false) }

    SettingsGroup(
        header = stringResource(R.string.story_settings_roles),
        footer = stringResource(R.string.story_roles_footer),
    ) {
        if (roles.isEmpty()) {
            Text(
                stringResource(R.string.story_settings_no_roles),
                style = AppTheme.typography.secondary,
                color = AppTheme.colors.text.secondary,
                modifier = Modifier.padding(14.dp),
            )
        } else {
            roles.forEach { role ->
                RoleRow(name = storyRoleRowName(role), roleType = role.roleType, source = storyRoleSource(role)) { editing = role }
                RowDivider()
            }
        }
        AddRoleRow(stringResource(R.string.story_roles_add)) { adding = true }
    }

    editing?.let { role -> StoryRoleEditorSheet(storyRoleEditConfig(role, onDraftPersona, onSave, onDelete), onDismiss = { editing = null }) }

    if (adding) StoryRoleEditorSheet(storyRoleAddConfig(storyId, onDraftPersona, onSave), onDismiss = { adding = false })
}

/** 创建屏「故事专属角色」块（开书前攒在表单里，删除无需确认——还没落库）。 */
@Composable
internal fun StoryCustomRolesBlock(
    customRoles: List<CustomRoleDraft>,
    onAdd: (CustomRoleDraft) -> Unit,
    onUpdate: (Int, CustomRoleDraft) -> Unit,
    onRemove: (Int) -> Unit,
) {
    var editingIndex by remember { mutableIntStateOf(-1) }
    var adding by remember { mutableStateOf(false) }
    val c = AppTheme.colors

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.story_create_custom_roles_label), style = AppTheme.typography.label, color = c.text.primary)
        customRoles.forEachIndexed { index, draft ->
            RoleRow(
                name = draft.name,
                roleType = draft.type,
                source = stringResource(R.string.story_roles_source_custom),
            ) { editingIndex = index }
        }
        AddRoleRow(stringResource(R.string.story_create_add_custom_role)) { adding = true }
        Text(stringResource(R.string.story_create_custom_roles_footer), style = AppTheme.typography.secondary, color = c.text.secondary)
    }

    customRoles.getOrNull(editingIndex)?.let { draft ->
        StoryRoleEditorSheet(storyCustomRoleEditConfig(draft, editingIndex, onUpdate, onRemove), onDismiss = { editingIndex = -1 })
    }

    if (adding) StoryRoleEditorSheet(storyCustomRoleAddConfig(onAdd), onDismiss = { adding = false })
}

@Composable
private fun RoleRow(name: String, roleType: String, source: String?, onClick: () -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            name,
            style = AppTheme.typography.body,
            color = c.text.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        RoleTypeBadge(roleType)
        source?.let { Text(it, style = AppTheme.typography.caption, color = c.text.secondary) }
        Icon(AppActionIcons.ChevronRight, contentDescription = null, tint = c.text.tertiary)
    }
}

/** 类型徽章（主角/配角/反派）——既有故事域 pill 造型：accent 容器底 + accent 字。 */
@Composable
internal fun RoleTypeBadge(roleType: String) {
    val c = AppTheme.colors
    Text(
        stringResource(roleTypeLabelRes(roleType)),
        style = AppTheme.typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
        color = c.accent.text,
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(c.accent.container)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun AddRoleRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = AppTheme.typography.body,
        color = AppTheme.colors.accent.text,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp),
    )
}
