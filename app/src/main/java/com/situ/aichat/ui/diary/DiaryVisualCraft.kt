package com.situ.aichat.ui.diary

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.appCardSurface
import kotlin.math.sqrt

/**
 * 日记方向 C（书信画报融合派）自绘器物组件库（全 Compose 原语 + Canvas，零第三方，暖陶 / 琉璃双脸共用）：
 * 1) 迷你心情邮票 [DiaryMiniMoodStamp]；
 * 2) 克制微斜拍立得单图 [DiaryPolaroidPreview]；
 * 3) 信笺双线抬头 [DiaryLetterTopRule] 与作者单字圆印 [DiaryAuthorChopSeal]；
 * 4) 宠物日记淡爪印水印 [DiaryPawWatermark]；
 * 5) 细密点线分隔 [DiaryDottedDivider]；
 * 6) 近 30 天心情色块热力格 [DiaryMoodHeatmap30]。
 */

private val MINI_STAMP_CORNER = 5.dp
private val MINI_STAMP_STROKE = 1.25.dp
private const val MINI_STAMP_ROTATION = 3.2f

/** 迷你心情邮票（时间线卡片 / 详情天头 / 撰写页共用）：齿孔虚线框 + 情绪浅底 + emoji + M·d（tnum·text.primary 保对比度）。 */
@Composable
internal fun DiaryMiniMoodStamp(
    emoji: String,
    timestamp: Long,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    clickLabel: String? = null,
) {
    val colors = AppTheme.colors
    val perfColor = (diaryMoodBand(emoji) ?: colors.accent.primary).copy(alpha = 0.72f)
    val fillColor = (diaryMoodTint(emoji) ?: colors.accent.container).copy(alpha = if (colors.isDark) 0.28f else 0.36f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .rotate(MINI_STAMP_ROTATION)
            .drawBehind {
                val r = CornerRadius(MINI_STAMP_CORNER.toPx())
                drawRoundRect(color = fillColor, cornerRadius = r)
                val sw = MINI_STAMP_STROKE.toPx()
                drawRoundRect(
                    color = perfColor,
                    topLeft = Offset(sw / 2f, sw / 2f),
                    size = Size(size.width - sw, size.height - sw),
                    cornerRadius = r,
                    style = Stroke(
                        width = sw,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.5.dp.toPx())),
                    ),
                )
            }
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = clickLabel, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(emoji, fontSize = 17.sp, lineHeight = 19.sp)
        Text(
            formatDiaryDate(timestamp, stringResource(R.string.diary_fmt_stamp)),
            style = AppTheme.typography.caption.copy(fontSize = 9.5.sp, fontFeatureSettings = "tnum"),
            color = colors.text.primary,
        )
    }
}

/** 方向 C 克制微斜拍立得（单张配图用·主页卡片与详情页首图共用）：-0.8° 微斜白框 + 顶边轻透条纹纸胶带。 */
@Composable
internal fun DiaryPolaroidPreview(
    path: String,
    imageHeight: Dp,
    modifier: Modifier = Modifier,
    tiltDegrees: Float = -0.8f,
) {
    val colors = AppTheme.colors
    val palette = listOf(colors.emotion.calm, colors.emotion.sad, colors.emotion.shy, colors.emotion.joy)
    val tapeBase = palette[path.hashCode().mod(4)].copy(alpha = 0.50f)
    val hatchColor = Color.White.copy(alpha = 0.28f)
    Box(
        modifier = modifier.fillMaxWidth().padding(top = 6.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .rotate(tiltDegrees)
                .appCardSurface(cornerRadius = 6.dp)
                .padding(start = 6.dp, top = 6.dp, end = 6.dp, bottom = 14.dp),
        ) {
            DiaryThumbnail(
                path = path,
                modifier = Modifier.fillMaxWidth().height(imageHeight),
                corner = 4.dp,
            )
        }
        // 顶边半透明条纹纸胶带
        Box(
            modifier = Modifier
                .offset(y = (-6).dp)
                .zIndex(1f)
                .rotate(-2.5f)
                .size(width = 48.dp, height = 13.dp)
                .clip(RectangleShape)
                .background(tapeBase)
                .drawBehind {
                    val stroke = 1.8.dp.toPx()
                    val period = (1.8.dp.toPx() + 4.2.dp.toPx()) * sqrt(2f)
                    val h = size.height
                    var c = -h
                    while (c <= size.width) {
                        drawLine(color = hatchColor, start = Offset(c, 0f), end = Offset(c + h, h), strokeWidth = stroke)
                        c += period
                    }
                },
        )
    }
}

/** 信笺顶部极细双线抬头（TA 的来信卡片与详情信笺共用）。 */
@Composable
internal fun DiaryLetterTopRule(modifier: Modifier = Modifier) {
    val accent = AppTheme.colors.accent.primary
    Canvas(modifier.fillMaxWidth().height(4.dp).clearAndSetSemantics {}) {
        drawLine(
            color = accent.copy(alpha = 0.36f),
            start = Offset(0f, 0.75.dp.toPx()),
            end = Offset(size.width, 0.75.dp.toPx()),
            strokeWidth = 1.2.dp.toPx(),
        )
        drawLine(
            color = accent.copy(alpha = 0.18f),
            start = Offset(0f, size.height - 0.5.dp.toPx()),
            end = Offset(size.width, size.height - 0.5.dp.toPx()),
            strokeWidth = 0.6.dp.toPx(),
        )
    }
}

/** 作者单字圆印（纯装饰·TA 的来信落款/卡角克制印记）。 */
@Composable
internal fun DiaryAuthorChopSeal(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    val glyph = name.trim().lastOrNull()?.toString().orEmpty()
    if (glyph.isEmpty()) return
    val tint = AppTheme.colors.accent.text.copy(alpha = 0.68f)
    Box(
        modifier = modifier
            .size(size)
            .rotate(-6f)
            .border(1.2.dp, tint, CircleShape)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = AppTheme.typography.kaiQuote.copy(fontSize = 11.sp, lineHeight = 12.sp),
            color = tint,
        )
    }
}

/** 宠物日记角落极淡爪印水印（纯装饰 Canvas）。 */
@Composable
internal fun DiaryPawWatermark(modifier: Modifier = Modifier) {
    val tint = AppTheme.colors.accent.primary.copy(alpha = 0.16f)
    Canvas(modifier.size(26.dp).clearAndSetSemantics {}) {
        val w = size.width
        val h = size.height
        // 三枚小趾垫
        drawCircle(tint, radius = w * 0.095f, center = Offset(w * 0.28f, h * 0.34f))
        drawCircle(tint, radius = w * 0.10f, center = Offset(w * 0.50f, h * 0.24f))
        drawCircle(tint, radius = w * 0.095f, center = Offset(w * 0.72f, h * 0.34f))
        // 掌心大垫
        drawOval(
            color = tint,
            topLeft = Offset(w * 0.24f, h * 0.46f),
            size = Size(w * 0.52f, h * 0.38f),
        )
    }
}

/** 方向 C 细密点线分隔（月分节头 / 详情互动区 / 写日记天头底边共用）。 */
@Composable
internal fun DiaryDottedDivider(modifier: Modifier = Modifier) {
    val color = AppTheme.colors.text.tertiary.copy(alpha = 0.42f)
    Canvas(modifier.fillMaxWidth().height(1.dp).clearAndSetSemantics {}) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5.dp.toPx(), 4.5.dp.toPx())),
        )
    }
}

/** 近 30 天心情色块热力格（3 行 × 10 列·统计面板用，纯本地数据）。 */
@Composable
internal fun DiaryMoodHeatmap30(recentDayMoods: List<String?>, modifier: Modifier = Modifier) {
    if (recentDayMoods.isEmpty()) return
    val colors = AppTheme.colors
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.diary_stats_heatmap_title),
            style = AppTheme.typography.caption,
            color = colors.text.secondary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            recentDayMoods.chunked(10).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    row.forEach { mood ->
                        val cellColor = when {
                            mood == null -> colors.surface.sunken
                            mood.isEmpty() -> colors.accent.primary.copy(alpha = 0.45f)
                            else -> (diaryMoodBand(mood) ?: colors.accent.primary).copy(alpha = 0.85f)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(cellColor),
                        )
                    }
                    repeat((10 - row.size).coerceAtLeast(0)) {
                        Box(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
