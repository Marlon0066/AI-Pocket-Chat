package com.situ.aichat.ui.contextlog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.diagnostics.LogTokenFormat
import com.situ.aichat.prompt.ContextSegment
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBar

/** 上下文结构（分段占比）屏（批 D·D-3）：汇总卡 + 各模块行（名/位置徽标/token+占比/占比条·按 position 着色）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextLogSegmentsScreen(
    onBack: () -> Unit,
    viewModel: ContextLogDetailViewModel = hiltViewModel(),
) {
    val entry by viewModel.entry.collectAsStateWithLifecycle()
    val segments = entry?.let { viewModel.decodeSegments(it.contextSegmentsJson) } ?: emptyList()
    val totalToken = segments.sumOf { it.estimatedTokens }
    val totalChar = segments.sumOf { it.charCount }

    val scrollState = rememberScrollState()
    Scaffold(
        containerColor = AppTheme.colors.surface.base,
        topBar = {
            AppTopBar(
                title = "上下文结构",
                onBack = onBack,
                lifted = scrollState.value > 0,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (segments.isEmpty()) {
                Spacer(Modifier.height(40.dp))
                Text("该记录无结构化分段（后台生成类调用不经模块系统）", style = AppTheme.typography.body, color = AppTheme.colors.text.tertiary)
                return@Column
            }

            SectionCard(null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryCell("总输入 token", LogTokenFormat.compact(totalToken), Modifier.weight(1f))
                    SummaryCell("模块", segments.size.toString(), Modifier.weight(1f))
                    SummaryCell("总字符", LogTokenFormat.compact(totalChar), Modifier.weight(1f))
                }
            }
            SectionCard(null) {
                segments.forEachIndexed { i, seg ->
                    SegmentRow(seg, totalToken)
                    if (i != segments.lastIndex) Spacer(Modifier.height(11.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun SummaryCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.background(AppTheme.colors.surface.sunken, AppTheme.shapes.small).padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = AppTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"), color = AppTheme.colors.text.primary)
        Spacer(Modifier.height(2.dp))
        Text(label, style = AppTheme.typography.caption, color = AppTheme.colors.text.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SegmentRow(seg: ContextSegment, total: Int) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                seg.name,
                style = AppTheme.typography.secondary,
                color = AppTheme.colors.text.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(7.dp))
            PositionBadge(seg.position)
            Spacer(Modifier.weight(1f))
            Text(LogTokenFormat.percent(seg.estimatedTokens, total), style = AppTheme.typography.captionNumeric, color = AppTheme.colors.text.secondary)
        }
        Spacer(Modifier.height(5.dp))
        ProportionBar(part = seg.estimatedTokens, total = total, fill = positionFill(seg.position))
    }
}

@Composable
private fun PositionBadge(position: String) {
    Box(Modifier.background(positionContainer(position), AppTheme.shapes.full).padding(horizontal = 7.dp, vertical = 1.5.dp)) {
        Text(positionLabel(position), style = AppTheme.typography.caption, color = positionOn(position))
    }
}

@Composable
private fun ProportionBar(part: Int, total: Int, fill: androidx.compose.ui.graphics.Color) {
    val frac = if (total > 0) (part.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    Box(
        Modifier
            .fillMaxWidth()
            .height(7.dp)
            .background(AppTheme.colors.surface.sunken, AppTheme.shapes.full),
    ) {
        Box(
            Modifier
                .fillMaxWidth(frac)
                .height(7.dp)
                .background(fill, AppTheme.shapes.full),
        )
    }
}
