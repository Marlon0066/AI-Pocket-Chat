package com.situ.aichat.ui.offline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.offline.OfflineMeetingSession
import com.situ.aichat.prompt.scheduleTimeOfDayLabel
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// 资料页见面回忆「那晚的天色」窗景卡（SKY-2·契约 FABLE5_MEETING_MEMORY_SKY_PROPOSAL §1/§3）。
// 全部页 = MeetingGalleryTimeline（SKY-5 回忆长廊·旧 OfflineMeetingMemoryCard 已删，情绪主题/徽章/
// 格式器等共享件留守 OfflineMeetingCardShared.kt）；本卡 = 天色背景 + 日期行(+简版徽章) + 楷体地点 +
// 活动 + 时长 + 「第 N 场 · 情绪」药丸。摘要预览撤出卡面（D-1 拍板·数据链路不动）。

/**
 * 文字带几何 × alpha 单源（R1 🔴-1）：y 区间 = 各行在 168dp hero 卡 / 52dp 小天窗内的落带（含行高箱·
 * review-R1 实测口径），[com.situ.aichat.ui.designsystem.ColorContrastTest] 的
 * `meetingSky_textBands_compositedContrast` 逐带扫描断言 ≥4.5:1（契约 §2.1 红线）。
 * 与下方两个 Composable 的布局互指：改布局（padding / 字号 / 行距 / 元素增删）或任一 alpha，
 * 必须同步此表并重跑带扫描。alpha 上调（date/act .8→.95、时长 .78→.9、药丸字 .92→1.0）为
 * review-R1 授权杠杆（只准朝安全方向动）。
 */
internal object MeetingSkyTextBands {
    val HERO_DATE = 0.08f..0.21f
    val HERO_LOCATION = 0.44f..0.61f
    val HERO_ACTIVITY = 0.62f..0.71f
    val HERO_META = 0.79f..0.92f
    val THUMB_DATE = 0.71f..0.92f
    const val DATE_ALPHA = 0.95f
    const val LOCATION_ALPHA = 1f
    const val ACTIVITY_ALPHA = 0.95f
    const val DURATION_ALPHA = 0.9f
    const val PILL_TEXT_ALPHA = 1f
    const val PILL_FILL_LIGHT_ALPHA = 0.08f
    const val PILL_FILL_DARK_ALPHA = 0.14f
    const val THUMB_DATE_ALPHA = 0.95f
}

private val miniDateFormatter = DateTimeFormatter.ofPattern("M/d", Locale.CHINA)

private fun formatMiniDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(miniDateFormatter)

/** 最新一场·全宽窗景卡（168dp）。 */
@Composable
fun MeetingSkyHeroCard(
    session: OfflineMeetingSession,
    sequenceNumber: Int,
    onRetryFallback: (String) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isRetrying: Boolean = false,
) {
    val hour = remember(session.startMillis) {
        Instant.ofEpochMilli(session.startMillis).atZone(ZoneId.systemDefault()).hour
    }
    val spec = remember(session.startMillis, session.finalMood) {
        MeetingSky.spec(skyBucketForHour(hour), OfflineMoodKind.fromRaw(session.finalMood))
    }
    val mood = OfflineMoodTheme.forMood(session.finalMood)
    val dateLine = "${formatCardDate(session.startMillis)} · ${scheduleTimeOfDayLabel(hour)} ${formatCardTime(session.startMillis)}"
    val pillText = "${stringResource(R.string.meeting_sky_seq, sequenceNumber)} · ${mood.label}"
    val cd = listOfNotNull(
        dateLine,
        session.location,
        session.activity,
        session.durationText.takeIf { it.isNotEmpty() },
        pillText,
    ).joinToString("，")

    Box(
        modifier
            .fillMaxWidth()
            .height(168.dp)
            .clip(RoundedCornerShape(16.dp))
            // R1 🔵-3：原 combinedClickable(onLongClick={}) 会把长按吞成无操作且在 a11y 树暴露无标签动作；
            // 改普通 clickable（长按松手触发 onClick = 可接受语义变化·报告记档）。
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = cd },
    ) {
        MeetingSkyBackdrop(spec, seed = session.id.hashCode(), startMillis = session.startMillis, modifier = Modifier.matchParentSize())
        // 行序/间距 ↔ MeetingSkyTextBands 的 y 带互指：动这里必须同步带表。
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(dateLine, style = AppTheme.typography.captionNumeric, color = spec.textColor.copy(alpha = MeetingSkyTextBands.DATE_ALPHA))
                if (session.usedFallbackSummary) {
                    FallbackBadge(isRetrying = isRetrying, onRetry = { onRetryFallback(session.id) }, tint = spec.textColor)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                session.location,
                style = AppTheme.typography.kaiQuote.copy(fontSize = 21.sp, lineHeight = 26.sp),
                color = spec.textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                session.activity,
                style = AppTheme.typography.secondary,
                color = spec.textColor.copy(alpha = MeetingSkyTextBands.ACTIVITY_ALPHA),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (session.durationText.isNotEmpty()) {
                    Icon(
                        AppActionIcons.Clock,
                        contentDescription = null,
                        tint = spec.textColor.copy(alpha = MeetingSkyTextBands.DURATION_ALPHA),
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(session.durationText, style = AppTheme.typography.caption, color = spec.textColor.copy(alpha = MeetingSkyTextBands.DURATION_ALPHA))
                }
                Spacer(Modifier.weight(1f))
                Text(
                    pillText,
                    style = AppTheme.typography.caption,
                    color = spec.textColor.copy(alpha = MeetingSkyTextBands.PILL_TEXT_ALPHA),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            spec.textColor.copy(
                                alpha = if (spec.skyIsLight) MeetingSkyTextBands.PILL_FILL_LIGHT_ALPHA else MeetingSkyTextBands.PILL_FILL_DARK_ALPHA,
                            ),
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** 更早场次·小天窗（胶卷 item·64dp 宽）：纯渐变 + 底纱 + 日期字，52dp 下不画星月云（细节即噪点）。 */
@Composable
fun MeetingSkyMiniThumb(
    session: OfflineMeetingSession,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hour = remember(session.startMillis) {
        Instant.ofEpochMilli(session.startMillis).atZone(ZoneId.systemDefault()).hour
    }
    val spec = remember(session.startMillis, session.finalMood) {
        MeetingSky.spec(skyBucketForHour(hour), OfflineMoodKind.fromRaw(session.finalMood))
    }
    val cd = "${formatCardDate(session.startMillis)}，${session.location}"

    Column(
        modifier
            .width(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = stringResource(R.string.profile_meeting_all), onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = cd }
            .padding(bottom = 2.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.verticalGradient(spec.stops)),
        ) {
            if (spec.bottomHaze) {
                // R1 🔴-1：峰值 0.45→MINI_HAZE_ALPHA(0.55)；起点色 Haze@0（原 Color.Transparent 会把黑分量插进纱色）。
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                MeetingSky.MINI_HAZE_START to MeetingSky.Haze.copy(alpha = 0f),
                                1f to MeetingSky.Haze.copy(alpha = MeetingSky.MINI_HAZE_ALPHA),
                            ),
                        ),
                )
            }
            Text(
                formatMiniDate(session.startMillis),
                style = AppTheme.typography.caption.copy(fontSize = 9.sp, lineHeight = 11.sp),
                color = spec.textColor.copy(alpha = MeetingSkyTextBands.THUMB_DATE_ALPHA),
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 7.dp, bottom = 4.dp),
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            session.location,
            style = AppTheme.typography.caption.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
