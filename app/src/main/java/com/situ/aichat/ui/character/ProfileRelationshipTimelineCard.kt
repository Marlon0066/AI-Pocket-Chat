package com.situ.aichat.ui.character

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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.ui.designsystem.AppActionIcons
import java.time.format.DateTimeFormatter
import java.util.Locale

// 资料页成长卡 · 关系历程卡族（从 ProfileGrowthCards.kt 按卡族纯搬拆出，未改一像素）。
// 公用件 fmt() 留在 ProfileGrowthCards.kt（同包 internal 可见）。

// 关系历程节点日期固定中文「M月d日」（1:1 iOS：写死 DateFormat 不随区域变）。
private val milestoneMd = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)

// ── 关系历程卡（横向里程碑时间轴：节点=关系名+phase+着色圆点+日期，连线+右端虚线延伸；空态提示）─────

private val mileTitleH = 20.dp
private val milePhaseSpacing = 2.dp
private val milePhaseH = 14.dp
private val mileRowSpacing = 6.dp
private val mileDotRowH = 14.dp
private val mileDateH = 16.dp
private val mileNodeW = 76.dp
private val mileConnectorW = 36.dp
private val mileLineH = 3.dp
private val mileAccentPurple = Color(0xFFAF52DE)

@Composable
internal fun RelationshipTimelineCard(
    milestones: List<MilestoneEntity>,
    modifier: Modifier = Modifier,
) {
    ProfileCard(modifier) {
        CardSectionHeader(Icons.Filled.Favorite, MaterialTheme.colorScheme.primary, stringResource(R.string.profile_relationship_title))
        Spacer(Modifier.size(8.dp))

        if (milestones.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(stringResource(R.string.profile_relationship_empty_1), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(2.dp))
                Text(stringResource(R.string.profile_relationship_empty_2), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
            return@ProfileCard
        }

        val hasAnyPhase = milestones.any { !it.phase.isNullOrEmpty() }
        // 圆点中心距节点顶部的偏移（标题块 + 行距 + 半个圆点行），连线/未来延伸据此对齐。
        val titleBlock = mileTitleH + if (hasAnyPhase) milePhaseSpacing + milePhaseH else 0.dp
        val dotCenterY = titleBlock + mileRowSpacing + mileDotRowH / 2

        val scroll = rememberScrollState()
        // 默认滚到最右（最新）。LazyColumn item 重进组合会重发 = iOS .onAppear 重滚行为，勿加只跑一次的门。
        LaunchedEffect(milestones.size) { scroll.scrollTo(scroll.maxValue) }
        Row(Modifier.fillMaxWidth().horizontalScroll(scroll), verticalAlignment = Alignment.Top) {
            milestones.forEachIndexed { i, m ->
                if (i > 0) TimelineConnector(dotCenterY)
                TimelineNode(m, hasAnyPhase)
            }
            TimelineFutureExtension(dotCenterY)
        }
    }
}

@Composable
private fun TimelineNode(milestone: MilestoneEntity, hasAnyPhase: Boolean) {
    val dotColor = if (milestone.triggerTypeRaw == "aiAutomatic") mileAccentPurple else MaterialTheme.colorScheme.primary
    // 无障碍（P1-22·iOS 零 a11y=安卓超越）：节点合并为一停「关系名，相位，日期，来源」。「来源」视觉上只是
    // 圆点颜色（紫=AI 自动/主题色=用户操作，措辞取自 iOS :200 代码注释——displayName「AI 判断/用户推进」
    // 在 iOS 全部 View 中未被引用，无 1:1 字符串约束）；phase 空占位 " " 不拼入。节点不可点。
    val sourceText = stringResource(
        if (milestone.triggerTypeRaw == "aiAutomatic") R.string.a11y_milestone_source_ai else R.string.a11y_milestone_source_user,
    )
    val nodeDesc = listOfNotNull(
        milestone.relationshipName,
        milestone.phase?.takeIf { it.isNotBlank() },
        fmt(milestone.establishedDate, milestoneMd),
        sourceText,
    ).joinToString("，")
    Column(
        modifier = Modifier
            .width(mileNodeW)
            .clearAndSetSemantics { contentDescription = nodeDesc },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(mileRowSpacing),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(milePhaseSpacing)) {
            Box(Modifier.height(mileTitleH), contentAlignment = Alignment.Center) {
                Text(
                    milestone.relationshipName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (hasAnyPhase) {
                Box(Modifier.height(milePhaseH), contentAlignment = Alignment.Center) {
                    Text(
                        milestone.phase?.ifEmpty { " " } ?: " ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box(Modifier.height(mileDotRowH), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(dotColor)
                    .border(2.dp, Color.White.copy(alpha = 0.8f), CircleShape),
            )
        }
        Box(Modifier.height(mileDateH), contentAlignment = Alignment.Center) {
            Text(fmt(milestone.establishedDate, milestoneMd), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun TimelineConnector(dotCenterY: androidx.compose.ui.unit.Dp) {
    Column(Modifier.width(mileConnectorW)) {
        Spacer(Modifier.height(dotCenterY - mileLineH / 2))
        Box(Modifier.fillMaxWidth().height(mileLineH).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)))
    }
}

@Composable
private fun TimelineFutureExtension(dotCenterY: androidx.compose.ui.unit.Dp) {
    Column {
        Spacer(Modifier.height(dotCenterY - mileDotRowH / 2))
        Row(Modifier.height(mileDotRowH).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(6) { i ->
                Box(
                    Modifier
                        .width(6.dp)
                        .height(mileLineH)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f * (1f - i / 6f))),
                )
            }
            Icon(AppActionIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), modifier = Modifier.size(10.dp))
        }
    }
}
