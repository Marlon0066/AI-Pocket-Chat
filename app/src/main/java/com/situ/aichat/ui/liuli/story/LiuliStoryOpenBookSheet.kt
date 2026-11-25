package com.situ.aichat.ui.liuli.story

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.story.StoryCreationCatalog
import com.situ.aichat.story.StoryTemplate
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliAvatarRing
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliMaterials
import com.situ.aichat.ui.liuli.designsystem.LiuliSheetShell
import com.situ.aichat.ui.liuli.designsystem.LiuliSwitch
import com.situ.aichat.ui.liuli.designsystem.LiuliTheme
import com.situ.aichat.ui.story.CastAvatar
import com.situ.aichat.ui.story.StoryCover
import com.situ.aichat.ui.story.narrativeViewLabel
import com.situ.aichat.ui.story.rememberStoryOpenBookState
import com.situ.aichat.ui.story.storyOpenBookShowAddSupport
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/**
 * 主演头像外框（§0.2-12·设计稿 S4 `.ring`）：选中 = `1 + 光环 50 + 1` 恰 52，未选 = `3 + 46 + 3` 恰 52
 * ——与暖陶「46 + 3 × 2」同版位，切换选中不跳；勾徽由 [CastAvatar] 原样画。
 */
private val liuliCastFrame: @Composable (Boolean, @Composable () -> Unit) -> Unit = { selected, avatar ->
    if (selected) {
        Box(Modifier.padding(1.dp)) { LiuliAvatarRing(50.dp) { avatar() } }
    } else {
        Box(Modifier.padding(3.dp)) { avatar() }
    }
}

/**
 * 琉璃开书弹层（琉璃 2.0 卷六·三·上 §4.7·设计稿 S4）：与暖陶 [com.situ.aichat.ui.story.StoryOpenBookSheet] 共用选角态与头像内容件；
 * 玻璃弹层（模板头就是题头·不给题头行）、主演光环、琉璃开关与主色钮。所有尺寸与缝同暖陶。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiuliStoryOpenBookSheet(
    template: StoryTemplate,
    characters: List<CharacterEntity>,
    creating: Boolean,
    onStart: (selectedRoles: Map<String, String>, includeUserRole: Boolean) -> Unit,
    onTweak: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberStoryOpenBookState(characters)
    val onGlass = LiuliTheme.onGlass
    val c = AppTheme.colors
    val dark = LocalIsDarkTheme.current
    LiuliSheetShell(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 30.dp),
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
                    Text(template.title, style = AppTypography.titleSmall, color = onGlass.primary)
                    Text(
                        stringResource(R.string.story_sheet_head_meta, template.genre, template.writingStyle, narrativeViewLabel(template.narrativePerson)),
                        style = AppTypography.secondary,
                        color = onGlass.secondary,
                    )
                }
            }

            // ── ① 谁来主演 ──
            Row(Modifier.padding(top = 18.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Bottom) {
                Text(stringResource(R.string.story_sheet_cast_label), style = AppTypography.label, color = onGlass.primary)
                Text(template.roleHint, style = AppTypography.caption, color = c.accent.text)
            }
            if (characters.isEmpty()) {
                Text(stringResource(R.string.story_sheet_no_chars), style = AppTypography.secondary, color = onGlass.secondary)
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    characters.forEach { ch ->
                        CastAvatar(ch, selected = state.leadId == ch.uuid, onClick = { state.toggleLead(ch.uuid) }, avatarFrame = liuliCastFrame)
                    }
                }
                // 次级「加配角 ›」展开配角多选。
                if (storyOpenBookShowAddSupport(characters)) {
                    Row(
                        Modifier.padding(top = 14.dp).clickable(role = Role.Button) { state.showSupporting = !state.showSupporting },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(stringResource(R.string.story_sheet_add_support), style = AppTypography.secondary, color = c.accent.text)
                        Icon(
                            AppActionIcons.ChevronDown,
                            contentDescription = null,
                            tint = c.accent.text,
                            modifier = Modifier.size(18.dp).rotate(if (state.showSupporting) 180f else 0f),
                        )
                    }
                    if (state.showSupporting) {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            state.supportingCandidates(characters).forEach { ch ->
                                CastAvatar(ch, selected = state.supportingIds.contains(ch.uuid), onClick = { state.toggleSupporting(ch.uuid) }, avatarFrame = liuliCastFrame)
                            }
                        }
                    }
                }
            }

            // ── ② 我也入场 ──
            Box(Modifier.padding(top = 20.dp).fillMaxWidth().height(0.5.dp).background(LiuliMaterials.divider(dark)))
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(R.string.story_sheet_join_title), style = AppTypography.label, color = onGlass.primary)
                    Text(stringResource(R.string.story_sheet_join_subtitle), style = AppTypography.caption, color = onGlass.secondary)
                }
                LiuliSwitch(checked = state.includeUserRole, onCheckedChange = { state.includeUserRole = it })
            }

            // ── ③ 开始连载 + 改一改再开 ──
            LiuliButton(
                onClick = { onStart(state.selectedRoles, state.includeUserRole) },
                style = LiuliButtonStyle.Prominent,
                enabled = state.canStart && !creating,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            ) { Text(stringResource(R.string.story_sheet_start)) }
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.story_sheet_tweak),
                style = AppTypography.label,
                color = c.accent.text,
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onTweak).padding(vertical = 2.dp),
            )
        }
    }
}
