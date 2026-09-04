package com.situ.aichat.ui.story

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.story.StoryCreationCatalog
import com.situ.aichat.story.StoryCreationLogic
import com.situ.aichat.story.StoryNarrativePerson
import com.situ.aichat.story.StoryRoleType
import com.situ.aichat.story.StoryTemplate
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppSwitch
import com.situ.aichat.ui.designsystem.AppTheme

/**
 * 开书 sheet（ST7b·契约 §3.1 / §6.2·照 mockup 屏三）：点模板 → 底部弹层「三步内开书」。
 * ① 谁来主演（主演单选头像排 + roleHint 提示语 + 「加配角 ›」展开配角多选）② 「我也入场」开关（默认开·关则旁观
 * 第三人称）③「开始连载」→ [onStart] 交 [StoryCreationViewModel.createFromTemplate]（生成管线零改动）。
 * 底部「改一改再开 ›」= [onTweak] 带模板预填值进高级自定义（J3：权重/连载/章长/人称一律不问·吃模板值）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryOpenBookSheet(
    template: StoryTemplate,
    characters: List<CharacterEntity>,
    creating: Boolean,
    onStart: (selectedRoles: Map<String, String>, includeUserRole: Boolean) -> Unit,
    onTweak: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AppTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var leadId by remember { mutableStateOf<String?>(characters.firstOrNull()?.uuid) }
    var supportingIds by remember { mutableStateOf(emptySet<String>()) }
    var includeUserRole by remember { mutableStateOf(true) }
    var showSupporting by remember { mutableStateOf(false) }

    val selectedRoles: Map<String, String> = remember(leadId, supportingIds) {
        buildMap {
            leadId?.let { put(it, StoryRoleType.PROTAGONIST) }
            supportingIds.forEach { put(it, StoryRoleType.SUPPORTING) }
        }
    }
    val canStart = StoryCreationLogic.canCreateStory(
        isCustomGenre = false,
        customGenreName = "",
        includeUserRole = includeUserRole,
        selectedCharacterCount = selectedRoles.size,
    )

    AppSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, end = 22.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ── 模板头（小封面 + 剧名 + 题材·文风·视角回显） ──
            Row(horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.CenterVertically) {
                StoryCover(
                    coverColorScheme = StoryCreationCatalog.coverColorScheme(template.genre),
                    title = template.title,
                    storyId = template.id,
                    titleSizeSp = 8.5f,
                    modifier = Modifier.size(width = 54.dp, height = 72.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(template.title, style = AppTheme.typography.titleSmall, color = c.text.primary)
                    Text(
                        stringResource(R.string.story_sheet_head_meta, template.genre, template.writingStyle, narrativeViewLabel(template.narrativePerson)),
                        style = AppTheme.typography.secondary,
                        color = c.text.secondary,
                    )
                }
            }

            // ── ① 谁来主演 ──
            Row(
                Modifier.padding(top = 18.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(stringResource(R.string.story_sheet_cast_label), style = AppTheme.typography.label, color = c.text.primary)
                Text(template.roleHint, style = AppTheme.typography.caption, color = c.accent.text)
            }
            if (characters.isEmpty()) {
                Text(stringResource(R.string.story_sheet_no_chars), style = AppTheme.typography.secondary, color = c.text.secondary)
            } else {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    characters.forEach { ch ->
                        CastAvatar(
                            character = ch,
                            selected = leadId == ch.uuid,
                            onClick = {
                                leadId = if (leadId == ch.uuid) null else ch.uuid
                                supportingIds = supportingIds - ch.uuid
                            },
                        )
                    }
                }
                // 次级「加配角 ›」展开配角多选（复用 roleType SUPPORTING·契约 §3.1）
                if (characters.size >= 2) {
                    Row(
                        Modifier
                            .padding(top = 14.dp)
                            .clickableScale { showSupporting = !showSupporting },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(stringResource(R.string.story_sheet_add_support), style = AppTheme.typography.secondary, color = c.accent.text)
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = c.accent.text,
                            modifier = Modifier.size(18.dp).rotate(if (showSupporting) 180f else 0f),
                        )
                    }
                    if (showSupporting) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            characters.filter { it.uuid != leadId }.forEach { ch ->
                                CastAvatar(
                                    character = ch,
                                    selected = supportingIds.contains(ch.uuid),
                                    onClick = {
                                        supportingIds = if (supportingIds.contains(ch.uuid)) supportingIds - ch.uuid else supportingIds + ch.uuid
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // ── ② 我也入场 ──
            AppListDivider(modifier = Modifier.padding(top = 20.dp), startInset = 0.dp)
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(R.string.story_sheet_join_title), style = AppTheme.typography.label, color = c.text.primary)
                    Text(stringResource(R.string.story_sheet_join_subtitle), style = AppTheme.typography.caption, color = c.text.secondary)
                }
                AppSwitch(checked = includeUserRole, onCheckedChange = { includeUserRole = it })
            }

            // ── ③ 开始连载 + 改一改再开 ──
            AppButton(
                onClick = { onStart(selectedRoles, includeUserRole) },
                style = AppButtonStyle.Primary,
                enabled = canStart && !creating,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            ) { Text(stringResource(R.string.story_sheet_start)) }
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.story_sheet_tweak),
                style = AppTheme.typography.label,
                color = c.accent.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableScale(onClick = onTweak)
                    .padding(vertical = 2.dp),
            )
        }
    }
}

/** 选角头像：46dp 圆头 + 选中态陶土环 + 右下勾徽（照 mockup .role.sel）。 */
@Composable
private fun CastAvatar(character: CharacterEntity, selected: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    Column(
        Modifier.width(60.dp).clickableScale(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            val ring = if (selected) {
                Modifier.border(2.5.dp, c.accent.primary, CircleShape).padding(3.dp)
            } else {
                Modifier.padding(3.dp)
            }
            Box(ring) { CharacterAvatar(name = character.name, avatarPath = character.avatarPath, size = 46.dp) }
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(c.accent.gradientStart, c.accent.gradientEnd)))
                        .border(2.dp, c.surface.raised, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = c.text.onAccent, modifier = Modifier.size(11.dp))
                }
            }
        }
        Text(
            character.name,
            style = AppTheme.typography.caption,
            color = if (selected) c.accent.text else c.text.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 模板头视角回显：第二人称「以「你」的视角」/ 第一「以「我」」/ 第三「旁观」（描述模板固有人称·非当前 sheet 状态）。 */
@Composable
private fun narrativeViewLabel(person: String): String = stringResource(
    when (person) {
        StoryNarrativePerson.FIRST -> R.string.story_sheet_view_first
        StoryNarrativePerson.THIRD -> R.string.story_sheet_view_third
        else -> R.string.story_sheet_view_second
    },
)
