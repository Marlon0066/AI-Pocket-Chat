package com.situ.aichat.ui.liuli.character

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.DynamicInterest
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.ui.character.MemColor
import com.situ.aichat.ui.character.RadarChart
import com.situ.aichat.ui.character.charInfoSummaryFieldRes
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase

// 落值 1:1 暖陶（那边全是内联字面量）。
private const val HEAT_HOT = 70
private const val HEAT_WARM = 40
private val HEAT_BAR = 6.dp
private val HEAT_CORNER = 3.dp
private const val HEAT_TRACK_ALPHA = 0.06f
private val ACCENT_BAR_W = 3.dp
private val ACCENT_BAR_MIN_H = 32.dp
private const val ACCENT_BAR_ALPHA = 0.6f
private const val ACCENT_LABEL_ALPHA = 0.8f
private const val TAG_BG_ALPHA = 0.10f
private val CHEVRON = 20.dp
private const val CHEVRON_EXPANDED = 180f

/** 卡内一整块：一行 [LiuliRowBase]，上下内距 16、不可点。 */
@Composable
private fun AboutBlock(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) =
    LiuliRowBase(
        divider = false,
        minHeight = 0.dp,
        verticalPadding = LiuliPageGeometry.groupPadH,
        verticalAlignment = Alignment.Top,
        content = content,
    )

/** 兴趣热度卡（琉璃·搬暖陶 `InterestHeatCard`）：Top8 热度条；空态两行提示。 */
@Composable
internal fun LiuliProfileInterestCard(interests: List<DynamicInterest>, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val top = remember(interests) { interests.sortedByDescending { it.heat }.take(8) }
    LiuliGroup(modifier = modifier, header = stringResource(R.string.profile_interest_title)) {
        AboutBlock {
            Column(Modifier.fillMaxWidth()) {
                if (top.isEmpty()) {
                    Text(
                        stringResource(R.string.profile_interest_empty_1),
                        style = AppTypography.listPreview,
                        color = colors.text.secondary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.profile_interest_empty_2),
                        style = AppTypography.secondary,
                        color = colors.text.tertiary,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        top.forEach { InterestRow(it) }
                    }
                }
            }
        }
    }
}

/** 一条兴趣：名字行（名占满 + 数字右靠）+ 全宽热度条；三停合成一停 + 进度语义。 */
@Composable
private fun InterestRow(interest: DynamicInterest) {
    val colors = AppTheme.colors
    val color = when {
        interest.heat >= HEAT_HOT -> MemColor.Orange
        interest.heat >= HEAT_WARM -> MemColor.Blue
        else -> colors.text.secondary
    }
    val heatDesc = stringResource(R.string.a11y_interest_heat, interest.name, interest.heat)
    Column(
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = heatDesc
            progressBarRangeInfo = ProgressBarRangeInfo(interest.heat.coerceIn(0, 100).toFloat(), 0f..100f)
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                interest.name,
                style = AppTypography.listPreview,
                color = colors.text.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(interest.heat.toString(), style = AppTypography.secondary, color = color)
        }
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(HEAT_BAR)
                .clip(RoundedCornerShape(HEAT_CORNER))
                .background(colors.text.primary.copy(alpha = HEAT_TRACK_ALPHA)),
        ) {
            if (interest.heat > 0) {
                Box(
                    Modifier
                        .fillMaxWidth((interest.heat / 100f).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(HEAT_CORNER))
                        .background(color),
                )
            }
        }
    }
}

/** 性格光谱雷达卡（Canvas 件 [RadarChart] 借用·空态哨兵 = `NEUTRAL`）。 */
@Composable
internal fun LiuliProfilePersonalityRadarCard(
    spectrum: PersonalitySpectrum,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LiuliGroup(modifier = modifier, header = stringResource(R.string.profile_personality_title)) {
        AboutBlock {
            Column(Modifier.fillMaxWidth()) {
                if (spectrum != PersonalitySpectrum.NEUTRAL) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        RadarChart(PersonalitySpectrum.DIMENSION_NAMES, spectrum.values, fillColor = MemColor.Purple)
                    }
                } else {
                    RadarEmptyHint(R.string.profile_personality_empty, onEdit)
                }
            }
        }
    }
}

/** 关系质感雷达卡（空态哨兵 = `INITIAL`）。 */
@Composable
internal fun LiuliProfileRelationshipRadarCard(
    quality: RelationshipQuality,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LiuliGroup(modifier = modifier, header = stringResource(R.string.profile_rel_radar_title)) {
        AboutBlock {
            Column(Modifier.fillMaxWidth()) {
                if (quality != RelationshipQuality.INITIAL) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        RadarChart(RelationshipQuality.DIMENSION_NAMES, quality.values, fillColor = MemColor.Pink)
                    }
                } else {
                    RadarEmptyHint(R.string.profile_rel_radar_empty, onEdit)
                }
            }
        }
    }
}

@Composable
private fun RadarEmptyHint(textRes: Int, onEdit: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(textRes), style = AppTypography.listPreview, color = AppTheme.colors.text.secondary)
        LiuliButton(onClick = onEdit, style = LiuliButtonStyle.Text) {
            Text(stringResource(R.string.profile_radar_goto_settings))
        }
    }
}

/**
 * 角色信息卡（琉璃·搬暖陶 `CharacterInfoCard`）：默认收起（标题 + 字段导览行），点整块展开 / 收起。
 * 六字段任一非空才渲染整卡；展开体动画在「减少动画」时直切（同暖陶）。
 */
@Composable
internal fun LiuliProfileCharacterInfoCard(character: CharacterEntity, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val summaryFields = charInfoSummaryFieldRes(character)
    if (summaryFields.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val reduceMotion = rememberReduceMotion()
    val chevronTarget = if (expanded) CHEVRON_EXPANDED else 0f
    val chevronRotation = if (reduceMotion) {
        chevronTarget
    } else {
        animateFloatAsState(chevronTarget, label = "liuliCharInfoChevron").value
    }
    LiuliGroup(modifier = modifier, header = stringResource(R.string.profile_charinfo_title)) {
        LiuliRowBase(
            divider = false,
            minHeight = 0.dp,
            verticalPadding = LiuliPageGeometry.groupPadH,
            verticalAlignment = Alignment.Top,
            onClick = { expanded = !expanded },
        ) {
            Column(Modifier.weight(1f)) {
                if (!expanded) {
                    Text(
                        summaryFields.map { stringResource(it) }.joinToString(" · "),
                        style = AppTypography.secondary,
                        color = colors.text.secondary,
                    )
                }
                // 展开体动画照暖陶：系统「减少动画」时直切。
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
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(
                    if (expanded) R.string.a11y_charinfo_collapse else R.string.a11y_charinfo_expand,
                ),
                tint = colors.text.secondary,
                modifier = Modifier.size(CHEVRON).rotate(chevronRotation),
            )
        }
    }
}

/** 展开体：六字段（与暖陶同序同条件）。 */
@Composable
private fun CharInfoDetail(character: CharacterEntity) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AccentInfoRow(MemColor.Pink, Icons.Filled.Visibility, stringResource(R.string.profile_charinfo_appearance), character.appearanceDescription)
        AccentInfoRow(MemColor.Indigo, Icons.AutoMirrored.Filled.MenuBook, stringResource(R.string.profile_charinfo_backstory), character.backstory)
        AccentInfoRow(MemColor.Cyan, Icons.Filled.FormatQuote, stringResource(R.string.profile_charinfo_speaking), character.speakingStyle)
        AccentTagRow(MemColor.Teal, Icons.AutoMirrored.Filled.Chat, stringResource(R.string.profile_charinfo_catchphrase), splitTags(character.catchphrases))
        AccentTagRow(MemColor.Orange, Icons.Filled.Star, stringResource(R.string.profile_charinfo_interests), splitTags(character.initialInterests))
        AccentInfoRow(MemColor.Purple, Icons.Filled.FormatQuote, stringResource(R.string.profile_charinfo_examples), character.exampleDialogues)
    }
}

@Composable
private fun AccentInfoRow(accent: Color, icon: ImageVector, title: String, content: String) {
    if (content.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AccentBar(accent)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AccentLabel(accent, icon, title)
            Text(content, style = AppTypography.listPreview, color = AppTheme.colors.text.primary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentTagRow(accent: Color, icon: ImageVector, title: String, items: List<String>) {
    if (items.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AccentBar(accent)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AccentLabel(accent, icon, title)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items.forEach { tag ->
                    Box(
                        Modifier
                            .clip(LiuliShapes.pill)
                            .background(accent.copy(alpha = TAG_BG_ALPHA))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(tag, style = AppTypography.secondary, color = accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun AccentBar(accent: Color) {
    Box(
        Modifier
            .width(ACCENT_BAR_W)
            .heightIn(min = ACCENT_BAR_MIN_H)
            .clip(RoundedCornerShape(2.dp))
            .background(accent.copy(alpha = ACCENT_BAR_ALPHA)),
    )
}

@Composable
private fun AccentLabel(accent: Color, icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = accent.copy(alpha = ACCENT_LABEL_ALPHA), modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            title,
            style = AppTypography.secondary.copy(fontWeight = FontWeight.W500),
            color = accent.copy(alpha = ACCENT_LABEL_ALPHA),
        )
    }
}

/** 逗号 / 中文逗号分隔 → 标签数组（1:1 暖陶 `splitTags`）。 */
private fun splitTags(text: String): List<String> =
    text.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
