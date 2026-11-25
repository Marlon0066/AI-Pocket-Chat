package com.situ.aichat.ui.pet

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.pet.PetGrowthStage
import com.situ.aichat.pet.PetGrowthThresholds
import com.situ.aichat.pet.PetMilestones
import com.situ.aichat.pet.PetTrickMilestones
import com.situ.aichat.pet.PetWalkService
import com.situ.aichat.pet.growthStage
import com.situ.aichat.pet.metadata
import com.situ.aichat.pet.petNeedHeadline
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppProgressBar
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography

// 宠物详情底部弹窗（暖陶与琉璃共用，全走 AppTheme token）。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PetDetailSheet(pet: CharacterPetEntity, trends: PetStatusTrends, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    AppSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AppSpacing.screenGutter).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${pet.name}的详情", style = AppTypography.titleSmall, color = colors.text.primary)
            val need = petNeedHeadline(pet)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(need.emoji, fontSize = 18.sp)
                Text(
                    need.headline,
                    style = AppTypography.secondary,
                    fontWeight = FontWeight.Medium,
                    color = if (need.action != null) colors.accent.text else colors.text.secondary,
                )
            }
            var souvenirExpanded by remember { mutableStateOf(false) }
            var milestonesExpanded by remember { mutableStateOf(false) }
            val daysTogether = ((System.currentTimeMillis() - pet.adoptedDate) / 86_400_000L).toInt().coerceAtLeast(0) + 1
            DetailCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    InfoItem("$daysTogether", "在一起", "天")
                    InfoItem("${pet.totalInteractions}", "互动", "次")
                    InfoItem("${pet.growthPoints}", "成长值", "")
                }
            }
            DetailCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val petC = colors.pet
                    StatusRow("🍖 饱食度", 100 - pet.hunger, petC.satiety, trends.hunger)
                    StatusRow("💧 清洁度", pet.cleanliness, petC.cleanliness, trends.cleanliness)
                    StatusRow("❤️ 心情", pet.happiness, petC.mood, trends.happiness)
                    StatusRow("➕ 健康", pet.health, petC.health, trends.health)
                }
            }
            DetailCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val threshold = PetGrowthThresholds.threshold(pet.growthStage)
                    val next = PetGrowthThresholds.nextStage(pet.growthStage)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("成长进度", style = AppTypography.label, fontWeight = FontWeight.Medium, color = colors.text.primary)
                        Text(if (next != null) "下一阶段: ${next.displayName}" else "已满级", style = AppTypography.caption, color = colors.text.secondary)
                    }
                    if (threshold != null) {
                        AppProgressBar(progress = pet.growthPoints.toFloat() / threshold, modifier = Modifier.fillMaxWidth())
                        Text("${pet.growthPoints} / $threshold", style = AppTypography.captionNumeric, color = colors.text.secondary)
                    } else {
                        AppProgressBar(progress = 1f, modifier = Modifier.fillMaxWidth())
                        Text("特殊形态 · 满级", style = AppTypography.caption, color = colors.accent.text)
                    }
                }
            }
            val tricks = pet.metadata.learnedTricks
            DetailCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("技能", style = AppTypography.label, fontWeight = FontWeight.Medium, color = colors.text.primary)
                    val learnedNames = PetTrickMilestones.milestones.filter { tricks.contains(it.trickId) }.map { it.name }
                    Text(if (learnedNames.isEmpty()) "还没有学会任何技能" else learnedNames.joinToString("、"), style = AppTypography.caption, color = colors.text.secondary)
                    val playCount = pet.metadata.playCount
                    val nextTrick = PetTrickMilestones.milestones.firstOrNull { !tricks.contains(it.trickId) }
                    if (nextTrick != null) {
                        AppProgressBar(progress = playCount.toFloat() / nextTrick.plays, modifier = Modifier.fillMaxWidth())
                        Text("下一个技能：${nextTrick.name}（还需玩耍 ${(nextTrick.plays - playCount).coerceAtLeast(0)} 次）", style = AppTypography.caption, color = colors.text.secondary)
                    } else if (learnedNames.isNotEmpty()) {
                        Text("所有技能已学会！", style = AppTypography.caption, color = colors.accent.text)
                    }
                }
            }
            val collected = pet.metadata.souvenirs.map { it.name }.toSet()
            val allTypes = PetWalkService.allSouvenirTypes
            val maxCollapsed = 12
            val showToggle = allTypes.size > maxCollapsed
            val displayItems = if (souvenirExpanded) allTypes else allTypes.take(maxCollapsed)
            DetailCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.animateContentSize()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("散步纪念品", style = AppTypography.label, fontWeight = FontWeight.Medium, color = colors.text.primary)
                        Text("${allTypes.count { collected.contains(it.name) }}/${allTypes.size} 件", style = AppTypography.caption, color = colors.text.secondary)
                    }
                    displayItems.chunked(6).forEach { rowItems ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowItems.forEach { type ->
                                val isCollected = collected.contains(type.name)
                                Column(
                                    modifier = Modifier.weight(1f),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(if (isCollected) type.emoji else "❓", fontSize = 22.sp, modifier = Modifier.alpha(if (isCollected) 1f else 0.3f))
                                    Text(
                                        if (isCollected) type.name else "???",
                                        fontSize = 9.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (isCollected) colors.text.secondary else colors.text.tertiary.copy(alpha = 0.6f),
                                    )
                                }
                            }
                            repeat(6 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (showToggle) {
                        AppButton(style = AppButtonStyle.Text, onClick = { souvenirExpanded = !souvenirExpanded }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (souvenirExpanded) "收起" else "查看全部", style = AppTypography.caption, color = colors.accent.text)
                            Icon(
                                if (souvenirExpanded) AppActionIcons.ChevronUp else AppActionIcons.ChevronDown,
                                contentDescription = null,
                                tint = colors.accent.text,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
            val achieved = PetMilestones.achievedIDs(
                daysSinceAdoption = PetMilestones.daysSinceAdoption(pet.adoptedDate, System.currentTimeMillis()),
                totalInteractions = pet.totalInteractions,
                tricksCount = pet.metadata.learnedTricks.size,
                souvenirCount = pet.metadata.souvenirs.size,
                isSpecial = pet.growthStage == PetGrowthStage.SPECIAL,
                playCount = pet.metadata.playCount,
                growthPoints = pet.growthPoints,
            )
            val milestoneMaxCollapsed = 10
            val milestoneShowToggle = PetMilestones.all.size > milestoneMaxCollapsed
            val milestoneItems = if (milestonesExpanded) PetMilestones.all else PetMilestones.all.take(milestoneMaxCollapsed)
            DetailCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.animateContentSize()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("成就", style = AppTypography.label, fontWeight = FontWeight.Medium, color = colors.text.primary)
                        Text("${achieved.size}/${PetMilestones.all.size}", style = AppTypography.caption, color = colors.text.secondary)
                    }
                    milestoneItems.chunked(5).forEach { rowItems ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowItems.forEach { m ->
                                val done = achieved.contains(m.id)
                                Column(
                                    modifier = Modifier.weight(1f),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(m.emoji, fontSize = 22.sp, modifier = Modifier.alpha(if (done) 1f else 0.2f))
                                    Text(
                                        m.name,
                                        fontSize = 9.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (done) colors.text.primary else colors.text.tertiary.copy(alpha = 0.6f),
                                    )
                                }
                            }
                            repeat(5 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (milestoneShowToggle) {
                        AppButton(style = AppButtonStyle.Text, onClick = { milestonesExpanded = !milestonesExpanded }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (milestonesExpanded) "收起" else "查看全部", style = AppTypography.caption, color = colors.accent.text)
                            Icon(
                                if (milestonesExpanded) AppActionIcons.ChevronUp else AppActionIcons.ChevronDown,
                                contentDescription = null,
                                tint = colors.accent.text,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailCard(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(AppShapes.medium)
            .background(AppTheme.colors.surface.sunken)
            .padding(16.dp),
    ) { content() }
}

@Composable
private fun InfoItem(value: String, label: String, unit: String) {
    val colors = AppTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = AppTypography.titleSmall, color = colors.text.primary)
            if (unit.isNotEmpty()) Text(unit, style = AppTypography.caption, color = colors.text.secondary)
        }
        Text(label, style = AppTypography.caption, color = colors.text.secondary)
    }
}

@Composable
private fun StatusRow(label: String, value: Int, color: Color, trend: StatusTrend) {
    val colors = AppTheme.colors
    val clamped = value.coerceIn(0, 100)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 13.sp, color = colors.text.primary, modifier = Modifier.width(72.dp))
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f))) {
            Box(Modifier.fillMaxWidth(clamped / 100f).height(8.dp).clip(RoundedCornerShape(50)).background(color))
        }
        when (trend) {
            StatusTrend.UP -> Icon(Icons.Filled.ArrowUpward, "上升", tint = colors.status.onSuccess, modifier = Modifier.size(13.dp))
            StatusTrend.DOWN -> Icon(Icons.Filled.ArrowDownward, "下降", tint = colors.accent.text, modifier = Modifier.size(13.dp))
            StatusTrend.STABLE -> Spacer(Modifier.size(13.dp))
        }
        Text("$clamped", fontSize = 11.sp, color = colors.text.secondary, modifier = Modifier.width(28.dp))
    }
}
