package com.situ.aichat.ui.ourdays

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.appCardSurface

/**
 * 动态页「我们的日子」全宽条（卷三图纸 §4.9·提案 D-1·帧 1）：外衣逐字照 `CircleStrip`（appCardSurface + clickableScale + h16 v12）。
 * 头行标题 + 「和{名} · 第 N 天」+ chevron；本周七格（40dp·9dp 圆角·热度底·今天药丸·未来 50%）；尾行昨天 / 近日手记首句（楷体）或空态句。
 */
@Composable
fun OurDaysStrip(onClick: () -> Unit, viewModel: OurDaysStripViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = AppTheme.colors
    val title = stringResource(R.string.our_days_title)
    val subText = state.character?.let { stringResource(R.string.our_days_strip_sub, it.name, state.nthDay ?: 1) }
        ?: stringResource(R.string.our_days_strip_sub_empty)
    val a11y = stringResource(R.string.our_days_a11y_strip, subText)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .appCardSurface()
            .clickableScale(onClickLabel = title) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = a11y },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = AppTypography.listName, color = colors.text.primary)
            Spacer(Modifier.width(8.dp))
            Text(subText, style = AppTypography.secondary.copy(fontSize = 12.sp), color = colors.text.secondary)
            Spacer(Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.text.tertiary)
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            state.week.forEach { cell -> StripCell(cell, Modifier.weight(1f)) }
        }
        val preview = state.preview
        val mdPattern = stringResource(R.string.our_days_fmt_md)
        val tailStyle = AppTypography.secondary.copy(fontSize = 12.5.sp)
        if (preview != null) {
            val label = if (preview.isYesterday) {
                stringResource(R.string.our_days_strip_yesterday, preview.characterName)
            } else {
                stringResource(R.string.our_days_strip_dated, OurDaysFormat.date(preview.date, mdPattern), preview.characterName)
            }
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.accent.text, fontWeight = FontWeight.Medium)) { append(label) }
                    append("　")
                    withStyle(SpanStyle(fontFamily = AppTypography.kaiFontFamily)) { append(preview.firstSentence) }
                },
                style = tailStyle, color = colors.text.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 9.dp),
            )
        } else {
            Text(
                stringResource(R.string.our_days_empty_hint), style = tailStyle, color = colors.text.secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp),
            )
        }
    }
}

@Composable
private fun StripCell(cell: CellModel, modifier: Modifier) {
    val colors = AppTheme.colors
    Box(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(heatColor(cell.heatLevel))
            .then(if (cell.isFuture) Modifier.alpha(0.5f) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(width = 20.dp, height = 18.dp)
                    .then(
                        if (cell.isToday) {
                            Modifier.clip(RoundedCornerShape(9.dp)).background(Brush.linearGradient(listOf(colors.accent.deepStart, colors.accent.deepEnd)))
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${cell.date.dayOfMonth}",
                    style = AppTypography.caption.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
                    color = if (cell.isToday) colors.accent.onDeep else colors.text.primary,
                )
            }
            Text(OurDaysFormat.weekdayNarrow(cell.date.dayOfWeek), style = AppTypography.caption.copy(fontSize = 9.5.sp), color = colors.text.tertiary)
        }
    }
}
