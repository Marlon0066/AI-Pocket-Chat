package com.situ.aichat.ui.liuli.character

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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.ui.character.MemColor
import com.situ.aichat.ui.character.fmt
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.Palette
import com.situ.aichat.ui.liuli.page.LiuliGroup
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.LiuliRowBase
import java.time.format.DateTimeFormatter
import java.util.Locale

// 几何与格式 1:1 暖陶（那边全是 private·改一侧要同步另一侧·§11 D-13）。
// 节点日期固定中文「M月d日」：1:1 iOS 写死 DateFormat 不随区域变。
private val MILESTONE_MD: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
private val TITLE_H = 20.dp
private val PHASE_SPACING = 2.dp
private val PHASE_H = 14.dp
private val ROW_SPACING = 6.dp
private val DOT_ROW_H = 14.dp
private val DATE_H = 16.dp
private val NODE_W = 76.dp
private val CONNECTOR_W = 36.dp
private val LINE_H = 3.dp
private val DOT = 12.dp
private const val LINE_ALPHA = 0.15f
private const val ARROW_ALPHA = 0.12f
private const val DOT_RING_ALPHA = 0.8f
private const val FUTURE_DASHES = 6

/**
 * 关系历程卡（琉璃·搬暖陶 `RelationshipTimelineCard`）：横向里程碑时间轴——
 * 节点 = 关系名 + phase + 着色圆点 + 日期，节点间连线、右端虚线延伸；空态两行提示。
 *
 * 圆点色：AI 自动判断走紫（`MemColor.Purple`·同暖陶那枚 `#AF52DE` 字面量），用户推进走 `accent.primary`。
 * 默认滚到最右（最新），a11y 每个节点合并成一停。
 */
@Composable
internal fun LiuliProfileTimelineCard(milestones: List<MilestoneEntity>, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    LiuliGroup(modifier = modifier, header = stringResource(R.string.profile_relationship_title)) {
        LiuliRowBase(
            divider = false,
            minHeight = 0.dp,
            verticalPadding = LiuliPageGeometry.groupPadH,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.fillMaxWidth()) {
                if (milestones.isEmpty()) {
                    Text(
                        stringResource(R.string.profile_relationship_empty_1),
                        style = AppTypography.listPreview,
                        color = colors.text.secondary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.profile_relationship_empty_2),
                        style = AppTypography.secondary,
                        color = colors.text.tertiary,
                    )
                } else {
                    val hasAnyPhase = milestones.any { !it.phase.isNullOrEmpty() }
                    // 圆点中心距节点顶部的偏移（标题块 + 行距 + 半个圆点行），连线 / 未来延伸据此对齐。
                    val titleBlock = TITLE_H + if (hasAnyPhase) PHASE_SPACING + PHASE_H else 0.dp
                    val dotCenterY = titleBlock + ROW_SPACING + DOT_ROW_H / 2
                    val scroll = rememberScrollState()
                    // 默认滚到最右（最新）。item 重进组合会重发 = iOS .onAppear 重滚行为，勿加只跑一次的门。
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
        }
    }
}

@Composable
private fun TimelineNode(milestone: MilestoneEntity, hasAnyPhase: Boolean) {
    val colors = AppTheme.colors
    val isAi = milestone.triggerTypeRaw == "aiAutomatic"
    val dotColor = if (isAi) MemColor.Purple else colors.accent.primary
    // a11y：节点合并成一停「关系名，相位，日期，来源」；「来源」视觉上只是圆点颜色。节点不可点。
    val sourceText = stringResource(
        if (isAi) R.string.a11y_milestone_source_ai else R.string.a11y_milestone_source_user,
    )
    val nodeDesc = listOfNotNull(
        milestone.relationshipName,
        milestone.phase?.takeIf { it.isNotBlank() },
        fmt(milestone.establishedDate, MILESTONE_MD),
        sourceText,
    ).joinToString("，")
    Column(
        modifier = Modifier.width(NODE_W).clearAndSetSemantics { contentDescription = nodeDesc },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ROW_SPACING),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PHASE_SPACING),
        ) {
            Box(Modifier.height(TITLE_H), contentAlignment = Alignment.Center) {
                Text(
                    milestone.relationshipName,
                    style = AppTypography.listPreview.copy(fontWeight = FontWeight.W600),
                    color = colors.text.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (hasAnyPhase) {
                Box(Modifier.height(PHASE_H), contentAlignment = Alignment.Center) {
                    Text(
                        milestone.phase?.ifEmpty { " " } ?: " ",
                        style = AppTypography.caption,
                        color = colors.text.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box(Modifier.height(DOT_ROW_H), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(DOT)
                    .clip(CircleShape)
                    .background(dotColor)
                    .border(2.dp, Palette.White.copy(alpha = DOT_RING_ALPHA), CircleShape),
            )
        }
        Box(Modifier.height(DATE_H), contentAlignment = Alignment.Center) {
            Text(
                fmt(milestone.establishedDate, MILESTONE_MD),
                style = AppTypography.caption,
                color = colors.text.secondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TimelineConnector(dotCenterY: Dp) {
    val line = AppTheme.colors.text.primary.copy(alpha = LINE_ALPHA)
    Column(Modifier.width(CONNECTOR_W)) {
        Spacer(Modifier.height(dotCenterY - LINE_H / 2))
        Box(Modifier.fillMaxWidth().height(LINE_H).background(line))
    }
}

@Composable
private fun TimelineFutureExtension(dotCenterY: Dp) {
    val ink = AppTheme.colors.text.primary
    Column {
        Spacer(Modifier.height(dotCenterY - DOT_ROW_H / 2))
        Row(
            Modifier.height(DOT_ROW_H).padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(FUTURE_DASHES) { i ->
                Box(
                    Modifier
                        .width(6.dp)
                        .height(LINE_H)
                        .clip(CircleShape)
                        .background(ink.copy(alpha = LINE_ALPHA * (1f - i / FUTURE_DASHES.toFloat()))),
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = ink.copy(alpha = ARROW_ALPHA),
                modifier = Modifier.size(10.dp),
            )
        }
    }
}
