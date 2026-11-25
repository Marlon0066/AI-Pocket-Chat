package com.situ.aichat.ui.character

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.situ.aichat.R
import com.situ.aichat.data.model.DynamicInterest
import com.situ.aichat.data.model.GrowthEventType
import com.situ.aichat.data.model.GrowthLogEntry
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import java.time.format.DateTimeFormatter
import java.util.Locale

// 资料页成长卡 · 兴趣热度卡 + 成长日志卡族（从 ProfileGrowthCards.kt 按卡族纯搬拆出，未改一像素）。
// 公用件 fmt() / MemColor 留在 ProfileGrowthCards.kt；空态件 GrowthEmptyHint 两卡共用故随之留私有。

private val growthMdHm = DateTimeFormatter.ofPattern("M/d HH:mm", Locale.getDefault())

// ── 兴趣热度卡（Top8 热度条；空态提示）─────────────────────────────────────────────────────

@Composable
internal fun InterestHeatCard(interests: List<DynamicInterest>, modifier: Modifier = Modifier) {
    val top = remember(interests) { interests.sortedByDescending { it.heat }.take(8) }
    ProfileCard(modifier) {
        CardSectionHeader(Icons.Filled.LocalFireDepartment, MemColor.Orange, stringResource(R.string.profile_interest_title))
        Spacer(Modifier.size(8.dp))
        if (top.isEmpty()) {
            GrowthEmptyHint(R.string.profile_interest_empty_1, R.string.profile_interest_empty_2)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                top.forEach { InterestRow(it) }
            }
        }
    }
}

@Composable
private fun InterestRow(interest: DynamicInterest) {
    val color = when {
        interest.heat >= 70 -> MemColor.Orange
        interest.heat >= 40 -> MemColor.Blue
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // 无障碍（P1-22·iOS 此卡零 a11y=安卓超越）：名字/热度条/裸数字三停合成一停「{名} {heat}%」+
    // 进度语义（TalkBack 可叠报百分比——真机批验双报观感，挂 DEVICE_VERIFICATION_CHECKLIST）。行不可点，无链序问题。
    // 2026-07-10 方案 A 重排（草图过审）：名字行（名字占满+数字右靠）+ 全宽热度条两行制——
    // 治旧版名字列死宽 76dp 截断长名；全条同起点保住「热度可比」本职。配色阈值/语义零改。
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
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(interest.heat.toString(), style = MaterialTheme.typography.labelMedium, color = color)
        }
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
        ) {
            if (interest.heat > 0) {
                Box(
                    Modifier
                        .fillMaxWidth((interest.heat / 100f).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(color),
                )
            }
        }
    }
}

// ── 成长日志卡（最近 5 条时间线；空态提示；>5「查看全部」dialog）──────────────────────────────

@Composable
internal fun GrowthLogCard(log: List<GrowthLogEntry>, modifier: Modifier = Modifier) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    ProfileCard(modifier) {
        CardSectionHeader(Icons.AutoMirrored.Filled.MenuBook, MaterialTheme.colorScheme.primary, stringResource(R.string.profile_growthlog_title))
        Spacer(Modifier.size(8.dp))
        if (log.isEmpty()) {
            GrowthEmptyHint(R.string.profile_growthlog_empty_1, R.string.profile_growthlog_empty_2)
        } else {
            val recent = remember(log) { log.takeLast(5).reversed() }
            Column {
                recent.forEachIndexed { index, entry -> GrowthLogRow(entry, isLast = index == recent.lastIndex) }
            }
            if (log.size > 5) {
                AppButton(onClick = { showAll = true }, style = AppButtonStyle.Text, contentPadding = PaddingValues(vertical = 4.dp)) {
                    Text(stringResource(R.string.profile_growthlog_view_all, log.size), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    if (showAll) {
        val all = remember(log) { log.reversed() }
        AppDialog(
            onDismissRequest = { showAll = false },
            title = stringResource(R.string.profile_growthlog_title),
            confirmText = stringResource(R.string.action_close),
            onConfirm = { showAll = false },
            content = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    all.forEachIndexed { index, entry -> GrowthLogRow(entry, isLast = index == all.lastIndex) }
                }
            },
        )
    }
}

@Composable
private fun GrowthLogRow(entry: GrowthLogEntry, isLast: Boolean) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.width(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(growthEventIcon(entry.type), contentDescription = null, tint = growthEventColor(entry.type), modifier = Modifier.size(16.dp))
            if (!isLast) {
                Box(Modifier.width(1.dp).height(28.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.padding(bottom = 6.dp)) {
            Text(entry.summary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.size(2.dp))
            Text(fmt(entry.timestamp, growthMdHm), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
    }
}

private fun growthEventIcon(type: GrowthEventType): ImageVector = when (type) {
    GrowthEventType.PERSONALITY_SHIFT -> AppActionIcons.Person
    GrowthEventType.RELATIONSHIP_CHANGE -> Icons.Filled.Favorite
    GrowthEventType.INTEREST_DISCOVERED -> Icons.Filled.AutoAwesome
    GrowthEventType.INTEREST_COOLED -> Icons.Filled.AcUnit
    GrowthEventType.MAJOR_EVENT -> Icons.Filled.Star
    GrowthEventType.GIFT_RECEIVED -> AppActionIcons.Gift
    GrowthEventType.GIFT_SENT -> AppActionIcons.Send
}

private val growthEventColorMap = mapOf(
    GrowthEventType.PERSONALITY_SHIFT to MemColor.Purple,
    GrowthEventType.RELATIONSHIP_CHANGE to MemColor.Pink,
    GrowthEventType.INTEREST_DISCOVERED to MemColor.Orange,
    GrowthEventType.INTEREST_COOLED to MemColor.Cyan,
    GrowthEventType.MAJOR_EVENT to MemColor.Yellow,
    GrowthEventType.GIFT_RECEIVED to MemColor.Red,
    GrowthEventType.GIFT_SENT to MemColor.Pink,
)

private fun growthEventColor(type: GrowthEventType): Color = growthEventColorMap[type] ?: MemColor.Purple

/** 成长卡通用空态提示（两行）。 */
@Composable
private fun GrowthEmptyHint(line1: Int, line2: Int) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(stringResource(line1), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(2.dp))
        Text(stringResource(line2), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
    }
}
