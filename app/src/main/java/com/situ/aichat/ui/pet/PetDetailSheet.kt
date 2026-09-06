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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppProgressBar
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme

// 宠物详情底部弹窗（从 PetDetailScreen 抽出·只搬不改）：点养护栏「详情」打开的 ModalBottomSheet——
// 需求领衔 + 信息卡（在一起/互动/成长值）+ 状态条（莫兰迪填充 + 趋势箭头）+ 成长进度 + 技能 +
// 散步纪念品图鉴（6 列可折叠）+ 成就图鉴（5 列可折叠）。配套私有小卡 DetailCard/InfoItem/StatusRow。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PetDetailSheet(pet: CharacterPetEntity, trends: PetStatusTrends, onDismiss: () -> Unit) {
    AppSheet(onDismissRequest = onDismiss) {
        Column(
            // 屏 gutter 恒 20（设计语言 §2.5 军规）
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AppSpacing.screenGutter).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${pet.name}的详情", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            // S4：需求领衔（sheet 顶·emoji + 第一人称文案·urgent → 深陶强调）
            val need = petNeedHeadline(pet)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(need.emoji, fontSize = 18.sp)
                Text(
                    need.headline,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (need.action != null) AppTheme.colors.accent.text else AppTheme.colors.text.secondary,
                )
            }
            var souvenirExpanded by remember { mutableStateOf(false) } // pet-ui-4 纪念品图鉴折叠态
            var milestonesExpanded by remember { mutableStateOf(false) } // 14.4 成就图鉴折叠态
            // 信息：在一起天数 / 互动 / 成长值
            val daysTogether = ((System.currentTimeMillis() - pet.adoptedDate) / 86_400_000L).toInt().coerceAtLeast(0) + 1
            DetailCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    InfoItem("$daysTogether", "在一起", "天")
                    InfoItem("${pet.totalInteractions}", "互动", "次")
                    InfoItem("${pet.growthPoints}", "成长值", "")
                }
            }
            // 状态条
            DetailCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val petC = AppTheme.colors.pet
                    StatusRow("🍖 饱食度", 100 - pet.hunger, petC.satiety, trends.hunger)
                    StatusRow("💧 清洁度", pet.cleanliness, petC.cleanliness, trends.cleanliness)
                    StatusRow("❤️ 心情", pet.happiness, petC.mood, trends.happiness)
                    StatusRow("➕ 健康", pet.health, petC.health, trends.health)
                }
            }
            // 成长进度
            DetailCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val threshold = PetGrowthThresholds.threshold(pet.growthStage)
                    val next = PetGrowthThresholds.nextStage(pet.growthStage)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("成长进度", fontWeight = FontWeight.Medium)
                        Text(if (next != null) "下一阶段: ${next.displayName}" else "已满级", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (threshold != null) {
                        AppProgressBar(progress = pet.growthPoints.toFloat() / threshold, modifier = Modifier.fillMaxWidth())
                        Text("${pet.growthPoints} / $threshold", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        AppProgressBar(progress = 1f, modifier = Modifier.fillMaxWidth())
                        Text("特殊形态 · 满级", fontSize = 11.sp, color = AppTheme.colors.accent.text)
                    }
                }
            }
            // 技能
            val tricks = pet.metadata.learnedTricks
            DetailCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("技能", fontWeight = FontWeight.Medium)
                    val learnedNames = PetTrickMilestones.milestones.filter { tricks.contains(it.trickId) }.map { it.name }
                    Text(if (learnedNames.isEmpty()) "还没有学会任何技能" else learnedNames.joinToString("、"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val playCount = pet.metadata.playCount
                    val nextTrick = PetTrickMilestones.milestones.firstOrNull { !tricks.contains(it.trickId) }
                    if (nextTrick != null) {
                        // pet-ui-4：下一个技能进度条（= iOS ProgressView(playCount/next.plays)）。
                        AppProgressBar(
                            progress = playCount.toFloat() / nextTrick.plays,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("下一个技能：${nextTrick.name}（还需玩耍 ${(nextTrick.plays - playCount).coerceAtLeast(0)} 次）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (learnedNames.isNotEmpty()) {
                        // pet-ui-4：所有技能已学会分支（= iOS "所有技能已学会！"）。
                        Text("所有技能已学会！", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            // 散步纪念品图鉴（pet-ui-4：可折叠 6 列网格 + 查看全部/收起，对齐 iOS souvenirCard）
            val collected = pet.metadata.souvenirs.map { it.name }.toSet()
            val allTypes = PetWalkService.allSouvenirTypes
            val maxCollapsed = 12 // = iOS souvenirColumns(6) * collapsedRows(2)
            val showToggle = allTypes.size > maxCollapsed
            val displayItems = if (souvenirExpanded) allTypes else allTypes.take(maxCollapsed)
            DetailCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.animateContentSize()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("散步纪念品", fontWeight = FontWeight.Medium)
                        Text("${allTypes.count { collected.contains(it.name) }}/${allTypes.size} 件", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    Text(
                                        if (isCollected) type.emoji else "❓",
                                        fontSize = 22.sp,
                                        modifier = Modifier.alpha(if (isCollected) 1f else 0.3f),
                                    )
                                    Text(
                                        if (isCollected) type.name else "???",
                                        fontSize = 9.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (isCollected) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    )
                                }
                            }
                            // 末行补空格，保持每格等宽（对齐 iOS LazyVGrid 6 列）
                            repeat(6 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (showToggle) {
                        AppButton(style = AppButtonStyle.Text, onClick = { souvenirExpanded = !souvenirExpanded }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (souvenirExpanded) "收起" else "查看全部", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            Icon(
                                if (souvenirExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
            // 成就图鉴（14.4·PetMilestones 100 个·1:1 iOS milestonesCard）：5 列网格·已达成 1.0/未达成 0.2 透明·可折叠（前 2 行=10 个）。
            val achieved = PetMilestones.achievedIDs(
                daysSinceAdoption = PetMilestones.daysSinceAdoption(pet.adoptedDate, System.currentTimeMillis()),
                totalInteractions = pet.totalInteractions,
                tricksCount = pet.metadata.learnedTricks.size,
                souvenirCount = pet.metadata.souvenirs.size,
                isSpecial = pet.growthStage == PetGrowthStage.SPECIAL,
                playCount = pet.metadata.playCount,
                growthPoints = pet.growthPoints,
            )
            val milestoneMaxCollapsed = 10 // = iOS milestoneColumns(5) * collapsedRows(2)
            val milestoneShowToggle = PetMilestones.all.size > milestoneMaxCollapsed
            val milestoneItems = if (milestonesExpanded) PetMilestones.all else PetMilestones.all.take(milestoneMaxCollapsed)
            DetailCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.animateContentSize()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("成就", fontWeight = FontWeight.Medium)
                        Text("${achieved.size}/${PetMilestones.all.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                        color = if (done) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    )
                                }
                            }
                            // 末行补空格保持等宽（对齐 iOS LazyVGrid 5 列）。
                            repeat(5 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (milestoneShowToggle) {
                        AppButton(style = AppButtonStyle.Text, onClick = { milestonesExpanded = !milestonesExpanded }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (milestonesExpanded) "收起" else "查看全部", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            Icon(
                                if (milestonesExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
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
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun InfoItem(value: String, label: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            if (unit.isNotEmpty()) Text(unit, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** S4：详情 sheet 状态行——莫兰迪填充条 + 趋势箭头（↑绿/↓陶·STABLE 留位不显）。 */
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
