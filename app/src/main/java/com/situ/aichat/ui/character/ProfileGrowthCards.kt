package com.situ.aichat.ui.character

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun fmt(millis: Long, f: DateTimeFormatter): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(f)

// ── 角色信息段（外貌/背景/说话风格/口头禅/兴趣/说话示例·静态；任一非空才渲染）──────────────────

/** 收起态导览行：按展示顺序列出非空字段的标题资源（与卡内渲染条件同口径 `isNotEmpty`；纯函数便于 T1）。 */
internal fun charInfoSummaryFieldRes(character: CharacterEntity): List<Int> = buildList {
    if (character.appearanceDescription.isNotEmpty()) add(R.string.profile_charinfo_appearance)
    if (character.backstory.isNotEmpty()) add(R.string.profile_charinfo_backstory)
    if (character.speakingStyle.isNotEmpty()) add(R.string.profile_charinfo_speaking)
    if (character.catchphrases.isNotEmpty()) add(R.string.profile_charinfo_catchphrase)
    if (character.initialInterests.isNotEmpty()) add(R.string.profile_charinfo_interests)
    if (character.exampleDialogues.isNotEmpty()) add(R.string.profile_charinfo_examples)
}

/**
 * 默认收起（标题 + 字段导览行），点整卡展开/收起——低频静态设定不再常驻整屏
 * （2026-07-10 拍板·微图纸 docs/handoff/2026-07-10-资料页卡序重排与角色信息折叠.md）。
 * rememberSaveable 只为 LazyColumn 回收后存活（滚走滚回不塌态），新进页面恒为收起。
 */
@Composable
internal fun CharacterInfoCard(character: CharacterEntity, modifier: Modifier = Modifier) {
    val summaryFields = charInfoSummaryFieldRes(character)
    if (summaryFields.isEmpty()) return

    var expanded by rememberSaveable { mutableStateOf(false) }
    val reduceMotion = rememberReduceMotion()
    val chevronTarget = if (expanded) 180f else 0f
    val chevronRotation = if (reduceMotion) {
        chevronTarget
    } else {
        animateFloatAsState(chevronTarget, label = "charInfoChevron").value
    }

    ProfileCard(
        modifier,
        onClick = { expanded = !expanded },
        onClickLabel = stringResource(
            if (expanded) R.string.a11y_charinfo_collapse else R.string.a11y_charinfo_expand,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                CardSectionHeader(AppActionIcons.Person, MaterialTheme.colorScheme.primary, stringResource(R.string.profile_charinfo_title))
            }
            Icon(
                AppActionIcons.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp).rotate(chevronRotation),
            )
        }
        if (!expanded) {
            Spacer(Modifier.size(6.dp))
            Text(
                summaryFields.map { stringResource(it) }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 展开体动画照 SettingsScreen AdvancedGatedRow 先例：系统「减少动画」时直切。
        if (reduceMotion) {
            if (expanded) CharInfoDetail(character)
        } else {
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                CharInfoDetail(character)
            }
        }
    }
}

/** 展开体：六字段渲染与收起改造前逐像素一致（只搬不改）。 */
@Composable
private fun CharInfoDetail(character: CharacterEntity) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AccentInfoRow(MemColor.Pink, Icons.Filled.Visibility, stringResource(R.string.profile_charinfo_appearance), character.appearanceDescription)
        AccentInfoRow(MemColor.Indigo, Icons.AutoMirrored.Filled.MenuBook, stringResource(R.string.profile_charinfo_backstory), character.backstory)
        AccentInfoRow(MemColor.Cyan, AppActionIcons.Quote, stringResource(R.string.profile_charinfo_speaking), character.speakingStyle)
        AccentTagRow(MemColor.Teal, Icons.AutoMirrored.Filled.Chat, stringResource(R.string.profile_charinfo_catchphrase), splitTags(character.catchphrases))
        AccentTagRow(MemColor.Orange, Icons.Filled.Star, stringResource(R.string.profile_charinfo_interests), splitTags(character.initialInterests))
        AccentInfoRow(MemColor.Purple, AppActionIcons.Quote, stringResource(R.string.profile_charinfo_examples), character.exampleDialogues)
    }
}

@Composable
private fun AccentInfoRow(accent: Color, icon: ImageVector, title: String, content: String) {
    if (content.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(3.dp).heightIn(min = 32.dp).clip(RoundedCornerShape(2.dp)).background(accent.copy(alpha = 0.6f)))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AccentLabel(accent, icon, title)
            Text(content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentTagRow(accent: Color, icon: ImageVector, title: String, items: List<String>) {
    if (items.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(3.dp).heightIn(min = 32.dp).clip(RoundedCornerShape(2.dp)).background(accent.copy(alpha = 0.6f)))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AccentLabel(accent, icon, title)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEach { tag ->
                    androidx.compose.material3.Surface(shape = RoundedCornerShape(50), color = accent.copy(alpha = 0.10f)) {
                        Text(tag, style = MaterialTheme.typography.labelMedium, color = accent, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AccentLabel(accent: Color, icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = accent.copy(alpha = 0.8f), modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = accent.copy(alpha = 0.8f))
    }
}

/** 逗号/中文逗号分隔 → 标签数组（1:1 iOS splitTags）。 */
private fun splitTags(text: String): List<String> =
    text.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }

// ── 双雷达卡（性格光谱·紫 / 关系质感·粉；Compose Canvas 蛛网图；空态哨兵 != NEUTRAL/INITIAL）─────

@Composable
internal fun PersonalityRadarCard(spectrum: PersonalitySpectrum, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    ProfileCard(modifier) {
        CardSectionHeader(Icons.Filled.Psychology, MaterialTheme.colorScheme.primary, stringResource(R.string.profile_personality_title))
        Spacer(Modifier.size(8.dp))
        if (spectrum != PersonalitySpectrum.NEUTRAL) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                RadarChart(PersonalitySpectrum.DIMENSION_NAMES, spectrum.values, fillColor = MemColor.Purple)
            }
        } else {
            RadarEmptyHint(R.string.profile_personality_empty, onEdit)
        }
    }
}

@Composable
internal fun RelationshipRadarCard(quality: RelationshipQuality, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    ProfileCard(modifier) {
        CardSectionHeader(Icons.Filled.Favorite, MemColor.Pink, stringResource(R.string.profile_rel_radar_title))
        Spacer(Modifier.size(8.dp))
        if (quality != RelationshipQuality.INITIAL) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                RadarChart(RelationshipQuality.DIMENSION_NAMES, quality.values, fillColor = MemColor.Pink)
            }
        } else {
            RadarEmptyHint(R.string.profile_rel_radar_empty, onEdit)
        }
    }
}

@Composable
private fun RadarEmptyHint(textRes: Int, onEdit: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(textRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AppButton(onClick = onEdit, style = AppButtonStyle.Text) { Text(stringResource(R.string.profile_radar_goto_settings)) }
    }
}

/** 记忆碎片与资料页莫兰迪点缀配色（低饱和温润色板，去谷歌/iOS 荧光原色）。 */
internal object MemColor {
    val Blue = Color(0xFF5A7FA3)
    val Green = Color(0xFF5D8A66)
    val Indigo = Color(0xFF6B6CA3)
    val Cyan = Color(0xFF528C9E)
    val Orange = Color(0xFFC28247)
    val Pink = Color(0xFFB86375)
    val Purple = Color(0xFF8B6BA3)
    val Yellow = Color(0xFFB8923E)
    val Teal = Color(0xFF4F8787)
    val Mint = Color(0xFF4F948A)
    val Red = Color(0xFFB85A56)
    val Brown = Color(0xFF94775E)
}
