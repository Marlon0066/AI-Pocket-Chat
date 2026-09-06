package com.situ.aichat.ui.offline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.situ.aichat.offline.OfflineMeetingSession
import com.situ.aichat.prompt.scheduleTimeOfDayLabel
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import java.time.Instant
import java.time.ZoneId

// 全部页「回忆长廊」时间流（SKY-5a·契约 FABLE5_MEETING_MEMORY_SKY_PROPOSAL §9·C+ 已过审）：
// 统计句 → 虚珠「下一场?」→ 逐场条目（月相珠脊线 + 天色书签卡）→ 月份刻度 → 尽头落款。
// 文案与全屏既有口径一致走中文硬编码（屏题/图例先例）；交互零变化（点卡=回顾·长按=编辑·简版重试）。

// ── 纯函数（T1 覆盖）────────────────────────────────────────────────────────────────

private val CHINESE_MONTHS = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二")

internal fun chineseMonthLabel(month: Int): String = CHINESE_MONTHS[month - 1] + "月"

/** 月份刻度文案：同年 =「七月」；跨年 =「2025年 七月」。 */
internal fun galleryMonthLabel(millis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val d = Instant.ofEpochMilli(millis).atZone(zone)
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val base = chineseMonthLabel(d.monthValue)
    return if (d.year == now.year) base else "${d.year}年 $base"
}

/** 顶部统计句。 */
internal fun galleryStatLine(count: Int, firstMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val d = Instant.ofEpochMilli(firstMillis).atZone(zone)
    return "一起出去过 $count 次 · 第一次是 ${d.monthValue} 月 ${d.dayOfMonth} 日的${scheduleTimeOfDayLabel(d.hour)}"
}

/** 尽头落款（峰终·拍板保留）。 */
internal fun galleryEndLine(firstMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val d = Instant.ofEpochMilli(firstMillis).atZone(zone)
    return "长廊的尽头——${d.monthValue} 月 ${d.dayOfMonth} 日${scheduleTimeOfDayLabel(d.hour)}，你们第一次见面"
}

/** 年月分组键（月份刻度插在键变化处）。 */
internal fun galleryYearMonth(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
    val d = Instant.ofEpochMilli(millis).atZone(zone)
    return d.year * 100 + d.monthValue
}

// ── 时间流本体 ─────────────────────────────────────────────────────────────────────

private val SPINE_WIDTH = 40.dp

@Composable
internal fun MeetingGalleryTimeline(
    sessions: List<OfflineMeetingSession>,
    retryingSessionIds: Set<String>,
    onRetry: (String) -> Unit,
    onOpen: (OfflineMeetingSession) -> Unit,
    onEdit: (OfflineMeetingSession) -> Unit,
) {
    // extractor 已倒序；显式钉死 newest-first（同资料页 SKY-3 口径）。
    val sorted = remember(sessions) { sessions.sortedByDescending { it.startMillis } }
    val first = sorted.last()
    val nowMillis = remember { System.currentTimeMillis() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = AppSpacing.screenGutter, end = AppSpacing.screenGutter, top = 4.dp, bottom = 28.dp),
    ) {
        item(key = "stat") {
            Text(
                galleryStatLine(sorted.size, first.startMillis),
                style = AppTheme.typography.secondary.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp, bottom = 10.dp),
            )
        }
        item(key = "ghost") { GhostBeadRow() }
        sorted.forEachIndexed { index, session ->
            if (index > 0 && galleryYearMonth(session.startMillis) != galleryYearMonth(sorted[index - 1].startMillis)) {
                item(key = "month-${galleryYearMonth(session.startMillis)}") {
                    MonthMarkerRow(galleryMonthLabel(session.startMillis, nowMillis))
                }
            }
            item(key = session.id) {
                GalleryEntryRow(
                    session = session,
                    isRetrying = session.id in retryingSessionIds,
                    onRetry = onRetry,
                    onOpen = onOpen,
                    onEdit = onEdit,
                )
            }
        }
        item(key = "end") { EndSignatureRow(galleryEndLine(first.startMillis)) }
    }
}

/** 顶端虚线空珠「下一场?」——全页唯一指向未来的元素（拍板保留）。 */
@Composable
private fun GhostBeadRow() {
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.width(SPINE_WIDTH), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(22.dp)) {
            drawCircle(
                color = accent.copy(alpha = 0.45f),
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))),
            )
        }
        Text(
            "下一场?",
            style = AppTheme.typography.caption.copy(fontSize = 9.5.sp, lineHeight = 12.sp),
            color = accent,
            modifier = Modifier.padding(vertical = 3.dp),
        )
        SpineLine(Modifier.height(14.dp))
    }
}

@Composable
private fun MonthMarkerRow(label: String) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(SPINE_WIDTH), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = AppTheme.typography.kaiQuote.copy(fontSize = 13.sp, lineHeight = 16.sp),
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
        )
    }
}

/** 尽头 ✦ 落款（峰终·拍板保留）。 */
@Composable
private fun EndSignatureRow(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.width(SPINE_WIDTH), contentAlignment = Alignment.Center) {
            Text("✦", style = AppTheme.typography.label, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = AppTheme.typography.kaiQuote.copy(fontSize = 11.5.sp, lineHeight = 17.sp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryEntryRow(
    session: OfflineMeetingSession,
    isRetrying: Boolean,
    onRetry: (String) -> Unit,
    onOpen: (OfflineMeetingSession) -> Unit,
    onEdit: (OfflineMeetingSession) -> Unit,
) {
    val hour = remember(session.startMillis) {
        Instant.ofEpochMilli(session.startMillis).atZone(ZoneId.systemDefault()).hour
    }
    val bucket = remember(session.startMillis) { skyBucketForHour(hour) }
    val spec = remember(session.startMillis, session.finalMood) {
        MeetingSky.spec(bucket, OfflineMoodKind.fromRaw(session.finalMood))
    }
    val mood = OfflineMoodTheme.forMood(session.finalMood)
    val dayText = remember(session.startMillis) {
        val d = Instant.ofEpochMilli(session.startMillis).atZone(ZoneId.systemDefault())
        "${d.monthValue}/${d.dayOfMonth}"
    }
    val periodWord = scheduleTimeOfDayLabel(hour)
    val summary = session.summaryText?.takeIf { it.isNotBlank() }
    val footer = listOfNotNull(
        session.durationText.takeIf { it.isNotEmpty() },
        "${mood.emoji} ${mood.label}",
        if (session.initiatedByUser) "你约 TA" else "TA 约你",
    ).joinToString(" · ")
    val cd = listOfNotNull(
        "${formatCardDate(session.startMillis)} $periodWord ${formatCardTime(session.startMillis)}",
        session.location,
        session.activity,
        summary,
        footer,
    ).joinToString("，")

    Row(Modifier.height(IntrinsicSize.Min)) {
        // 脊线列：月相珠 + 日期/时段 + 续线（续线穿过条目下方 12dp 间距，长廊不断流）。
        Column(Modifier.width(SPINE_WIDTH).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            SkyBead(bucket, spec, session.startMillis)
            Text(
                "$dayText\n$periodWord",
                style = AppTheme.typography.caption.copy(fontSize = 9.5.sp, lineHeight = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 3.dp),
            )
            SpineLine(Modifier.weight(1f))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 76.dp)
                    .height(IntrinsicSize.Min)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .combinedClickable(
                        onClickLabel = "查看回顾",
                        onLongClickLabel = "编辑这次见面",
                        onLongClick = { onEdit(session) },
                        onClick = { onOpen(session) },
                    )
                    .semantics(mergeDescendants = true) { contentDescription = cd },
            ) {
                Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            session.location,
                            style = AppTheme.typography.kaiQuote.copy(fontSize = 15.5.sp, lineHeight = 20.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (session.usedFallbackSummary) {
                            FallbackBadge(isRetrying = isRetrying, onRetry = { onRetry(session.id) }, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        session.activity,
                        style = AppTheme.typography.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (summary != null) {
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "「$summary」",
                            style = AppTheme.typography.kaiQuote.copy(fontSize = 11.5.sp, lineHeight = 18.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(
                        footer,
                        style = AppTheme.typography.caption.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                }
                // 天色书签：52dp 整高，一期绘制原样（同 seed = 同一片星空）。
                MeetingSkyBackdrop(
                    spec = spec,
                    seed = session.id.hashCode(),
                    startMillis = session.startMillis,
                    modifier = Modifier.width(52.dp).fillMaxHeight(),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SpineLine(modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(1.5.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
    )
}

/**
 * 月相珠（26dp）：迷你天色渐变底；夜桶画真实月相（一期盖影法），白天嵌暖阳点，清晨嵌一粒星，黄昏纯渐变。
 */
@Composable
private fun SkyBead(bucket: SkyBucket, spec: SkySpec, startMillis: Long) {
    val moon = remember(spec, startMillis) { moonRenderFor(spec, startMillis) }
    Canvas(Modifier.size(26.dp)) {
        val r = size.minDimension / 2f
        val center = Offset(r, r)
        val disc = Path().apply { addOval(Rect(center = center, radius = r)) }
        clipPath(disc) {
            drawRect(Brush.verticalGradient(listOf(spec.stops[0], spec.stops[2])))
        }
        when {
            moon != null -> {
                val mr = 7.5.dp.toPx()
                drawCircle(MeetingSky.Moon.copy(alpha = spec.moonAlpha), radius = mr, center = center)
                val shadowOffset = 2f * mr * moon.illumination
                val shadowCx = if (moon.waxing) center.x - shadowOffset else center.x + shadowOffset
                val moonDisc = Path().apply { addOval(Rect(center = center, radius = mr)) }
                clipPath(moonDisc) {
                    drawCircle(spec.stops[0], radius = mr * 1.02f, center = Offset(shadowCx, center.y))
                }
            }
            bucket == SkyBucket.DAY -> {
                drawCircle(androidx.compose.ui.graphics.Color(0xFFF2C978), radius = 4.5.dp.toPx(), center = center + Offset(5.dp.toPx(), (-5).dp.toPx()))
            }
            bucket == SkyBucket.DAWN -> {
                drawCircle(MeetingSky.WarmWhite.copy(alpha = 0.8f), radius = 1.2.dp.toPx(), center = center + Offset(4.dp.toPx(), (-6).dp.toPx()))
            }
            else -> Unit
        }
    }
}
