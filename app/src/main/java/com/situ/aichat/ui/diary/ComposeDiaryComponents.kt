package com.situ.aichat.ui.diary

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme

/**
 * 日记编写页自绘纸面子组件（方向 C 升级）：水彩晕染日期天头 + 可点击心情邮票 [ComposeDateHead]、
 * 心情胶囊行 [MoodRow]、宁静留白棉纸/磨砂书写区 [DiaryPaper]、拍立得配图横排 [ImageThumbs]。
 * 底部工具条族位于 ComposeDiaryActionBar.kt。
 */

/**
 * 方向 C 编写页水彩日期天头：顶部心情浅色纵向自然淡入纸底，36sp 等宽日号 + 竖发丝线 + M月·周几 / yyyy年·HH:mm +
 * 右侧可点击心情邮票（或「选个心情」虚线占位，点按展开/收起心情选择栏）。
 */
@Composable
internal fun ComposeDateHead(
    timestamp: Long,
    moodEmoji: String?,
    wash: Color,
    scale: Float,
    reduceMotion: Boolean,
    moodSelectTick: Int,
    onMoodClick: (() -> Unit)? = null,
    fullBleed: Boolean = false,
) {
    val colors = AppTheme.colors
    val hasTint = !moodEmoji.isNullOrEmpty()
    val topWash = if (hasTint) {
        wash.copy(alpha = if (colors.isDark) 0.42f else 0.72f)
    } else {
        colors.accent.container.copy(alpha = if (colors.isDark) 0.14f else 0.22f)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (fullBleed) Modifier else Modifier.clip(RoundedCornerShape(16.dp)))
            .background(Brush.verticalGradient(listOf(topWash, topWash.copy(alpha = 0f))))
            .padding(
                horizontal = if (fullBleed) AppSpacing.screenGutter else 14.dp,
                vertical = if (fullBleed) 16.dp else 14.dp,
            )
            .semantics { heading() },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatDiaryDate(timestamp, stringResource(R.string.diary_fmt_day_number)),
                style = AppTheme.typography.titleLarge.copy(
                    fontSize = 36.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum",
                ),
                color = colors.text.primary,
            )
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(34.dp)
                    .background(colors.text.tertiary.copy(alpha = 0.35f)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    formatDiaryDate(timestamp, stringResource(R.string.diary_fmt_detail_sub)),
                    style = AppTheme.typography.label,
                    color = colors.text.primary,
                )
                val year = formatDiaryDate(timestamp, stringResource(R.string.diary_fmt_month_section_year))
                val time = formatDiaryDate(timestamp, "HH:mm")
                Text(
                    "$year · $time",
                    style = AppTheme.typography.captionNumeric,
                    color = colors.text.primary,
                )
            }
            if (!moodEmoji.isNullOrEmpty()) {
                Box(
                    modifier = if (onMoodClick != null) {
                        Modifier.clickable(
                            onClickLabel = stringResource(R.string.diary_compose_mood_pick),
                            onClick = onMoodClick,
                        )
                    } else {
                        Modifier
                    },
                ) {
                    MoodStamp(
                        emoji = moodEmoji,
                        timestamp = timestamp,
                        reduceMotion = reduceMotion,
                        selectTick = moodSelectTick,
                    )
                }
            } else if (onMoodClick != null) {
                Text(
                    text = stringResource(R.string.diary_compose_mood_pick),
                    style = AppTheme.typography.caption,
                    color = colors.accent.text,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surface.raised.copy(alpha = 0.72f))
                        .border(1.dp, colors.accent.primary.copy(alpha = 0.42f), RoundedCornerShape(10.dp))
                        .clickable(
                            onClickLabel = stringResource(R.string.diary_compose_mood_pick),
                            onClick = onMoodClick,
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** M4 心情微提示 + 横滑 emoji 胶囊（选中染情绪浅档 + 同族描边 + lively 轻弹·选择触觉）。 */
@Composable
internal fun MoodRow(selectedEmoji: String?, reduceMotion: Boolean, onToggle: (String, String) -> Unit) {
    val colors = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.diary_compose_mood_header), style = AppTheme.typography.secondary, color = colors.text.secondary)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DIARY_MOODS.forEach { mood ->
                MoodPill(
                    emoji = mood.emoji,
                    label = stringResource(mood.labelRes),
                    isSelected = selectedEmoji == mood.emoji,
                    reduceMotion = reduceMotion,
                    onClick = onToggle,
                )
            }
        }
    }
}

/** 单个心情胶囊：选中瞬间 lively 轻弹（ζ0.78·reduceMotion 降级为瞬时）。 */
@Composable
private fun MoodPill(emoji: String, label: String, isSelected: Boolean, reduceMotion: Boolean, onClick: (String, String) -> Unit) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val scale = remember { Animatable(1f) }
    LaunchedEffect(isSelected) {
        if (isSelected && !reduceMotion) {
            scale.snapTo(1.12f)
            scale.animateTo(1f, AppMotion.livelySpring())
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .clip(AppTheme.shapes.full)
            .background(
                if (isSelected) diaryMoodTint(emoji) ?: colors.accent.container
                else colors.surface.sunken.copy(alpha = 0.55f),
            )
            .then(
                if (isSelected) Modifier.border(1.dp, diaryMoodBand(emoji) ?: colors.accent.primary, AppTheme.shapes.full)
                else Modifier,
            )
            .clickable { haptics.selection(); onClick(emoji, label) }
            .semantics { selected = isSelected }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(emoji, style = AppTheme.typography.body)
        Text(
            label,
            style = AppTheme.typography.secondary,
            color = if (isSelected) colors.text.primary else colors.text.secondary,
        )
    }
}

private val PAPER_LINE_SPACING = 28.sp
private val PAPER_LINE_BASELINE_OFFSET = 4.sp
private val PAPER_LINE_WIDTH = 0.5.dp
private const val PAPER_RULE_ALPHA = 0.055f

/**
 * 方向 C 宁静纸面书写区：默认无密杂横格（[showRules] = false，舒朗棉纸/磨砂呼吸感，所写即所读），
 * 楷体每日引导语占位 + 空态「让 TA 帮你起个头」软胶囊。
 */
@Composable
internal fun DiaryPaper(
    content: String,
    prompt: String,
    isGenerating: Boolean,
    reduceMotion: Boolean,
    /** 「让 TA 帮你起个头」空态胶囊是否可用（编辑「TA 的信」时为 false → 不出现）。 */
    aiAssistAvailable: Boolean,
    onContentChange: (String) -> Unit,
    onAiStart: () -> Unit,
    showRules: Boolean = false,
) {
    val colors = AppTheme.colors
    val ruleColor = colors.text.primary.copy(alpha = PAPER_RULE_ALPHA)
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        BasicTextField(
            value = content,
            onValueChange = onContentChange,
            textStyle = AppTheme.typography.kaiBody.copy(color = colors.text.primary),
            cursorBrush = SolidColor(colors.accent.primary),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 230.dp)
                .then(
                    if (showRules) {
                        Modifier.drawBehind {
                            val spacing = PAPER_LINE_SPACING.toPx()
                            val baseline = PAPER_LINE_BASELINE_OFFSET.toPx()
                            val stroke = PAPER_LINE_WIDTH.toPx()
                            var n = 1
                            while (true) {
                                val y = n * spacing - baseline
                                if (y > size.height) break
                                drawLine(color = ruleColor, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = stroke)
                                n++
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
            decorationBox = { innerTextField ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (content.isEmpty()) {
                        Text(prompt, style = AppTheme.typography.kaiBody, color = colors.text.tertiary)
                    }
                    innerTextField()
                }
            },
        )
        if (content.isEmpty() && aiAssistAvailable) {
            AiStartPill(isGenerating = isGenerating, reduceMotion = reduceMotion, onClick = onAiStart)
        }
    }
}

/** M5 空态：陶土描边软胶囊「让 TA 帮你起个头」；生成中改「TA 正在帮你写…」+ 纸面 breathing（reduceMotion→静态）。 */
@Composable
private fun AiStartPill(isGenerating: Boolean, reduceMotion: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val label = stringResource(if (isGenerating) R.string.diary_compose_ai_writing else R.string.diary_compose_ai_start)
    val breath = rememberInfiniteTransition(label = "aiBreath")
    val pulse by breath.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = AppMotion.EaseInOut), RepeatMode.Reverse),
        label = "aiBreathAlpha",
    )
    val alpha = if (isGenerating && !reduceMotion) pulse else 1f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .alpha(alpha)
            .clip(AppTheme.shapes.full)
            .background(colors.accent.container.copy(alpha = if (colors.isDark) 0.25f else 0.42f))
            .border(1.dp, colors.accent.text.copy(alpha = 0.42f), AppTheme.shapes.full)
            .clickable(enabled = !isGenerating, onClickLabel = label) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Icon(AppActionIcons.Edit, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(16.dp))
        Text(label, style = AppTheme.typography.secondary, color = colors.accent.text)
    }
}

/** 图片缩略横排（有图才现·配图入口在底部写作坞）·拍立得卡片样式。 */
@Composable
internal fun ImageThumbs(images: List<String>, onRemove: (String) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        images.forEach { path ->
            PolaroidPhoto(path = path, onRemove = onRemove)
        }
    }
}
