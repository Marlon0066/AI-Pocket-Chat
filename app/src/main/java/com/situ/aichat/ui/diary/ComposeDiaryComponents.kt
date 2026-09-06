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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.data.model.DiaryVisibility
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppTheme

/**
 * 日记编写页（U1·契约 [FABLE5_DIARY_COMPOSE_REDESIGN_PROPOSAL.md]）的自绘子组件——从 [ComposeDiaryScreen]
 * 抽出（文件瘦身·只搬不改行为）。含票据日期头（M1）/ 心情行（M4）/ 纸面书写区（M2/M3）/ AI 起头胶囊（M5）/
 * 图片缩略 / 底部行动条（M6）；动效（心情 lively 弹 / 生成 breathing）走 [AppMotion] + [reduceMotion] 门控。
 */

/**
 * M1 票据日期头 + 心情色回声：大数字 dd（28sp tnum）+ M月·周几 / 年 + 选中心情 J4 邮票盖章；[wash] 洇染动画色、
 * [scale] 发布 celebrate 落定。[moodSelectTick] 仅随用户切换心情前进（R1 🔵-1·transparently 透传给 [MoodStamp]
 * 决定是否盖章+触觉——开页/恢复预置心情静置不震）。
 */
@Composable
internal fun ComposeDateHead(timestamp: Long, moodEmoji: String?, wash: Color, scale: Float, reduceMotion: Boolean, moodSelectTick: Int) {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(AppTheme.shapes.medium)
            .background(wash)
            .padding(16.dp)
            .semantics { heading() },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatDiaryDate(timestamp, stringResource(R.string.diary_fmt_day_number)),
                style = AppTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                color = colors.text.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    formatDiaryDate(timestamp, stringResource(R.string.diary_fmt_detail_sub)),
                    style = AppTheme.typography.label,
                    color = colors.text.primary,
                )
                Text(
                    formatDiaryDate(timestamp, stringResource(R.string.diary_fmt_month_section_year)),
                    style = AppTheme.typography.caption,
                    color = colors.text.primary,
                )
            }
            // J4 心情邮票（取代原 raised@0.8 胶囊 chip）：选中心情盖章·moodText 移出头部（MoodRow 选中态仍全量可见）。
            moodEmoji?.takeIf { it.isNotEmpty() }?.let { emoji ->
                MoodStamp(emoji = emoji, timestamp = timestamp, reduceMotion = reduceMotion, selectTick = moodSelectTick)
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

/**
 * J2 恒横线纸行距 = [com.situ.aichat.ui.designsystem.AppTypography.kaiBody] lineHeight **28sp** 真单源（AppTypography.kt:90）。
 * 用 sp（非 dp）：`DrawScope` 即 `Density`，`toPx()` 自动吃 fontScale——系统开大字号时横线随楷体正文同步放大、逐行
 * 不漂移（R1 🟡-4·fontScale=1 时输出像素与旧 dp 值完全一致）。
 */
private val PAPER_LINE_SPACING = 28.sp

/** 横线基线偏移（第 n 条线 y = n×28sp − 4sp·−4 校准值经装机双确认·sp 口径随 fontScale 缩放·真机字体细校准挂真机批·图纸 §4-J2）。 */
private val PAPER_LINE_BASELINE_OFFSET = 4.sp

/** 横线线宽。 */
private val PAPER_LINE_WIDTH = 0.5.dp

/** 横线色不透明度（两模同 alpha·text.primary 随主题翻转·纯装饰 ≤6% 无对比度要求·契约 §4）。 */
private const val PAPER_RULE_ALPHA = 0.055f

/** M2 恒横线楷体纸面书写区（J2·所写即所读）+ M3 楷体每日引导语占位 + M5 空态「让 TA 帮你起个头」软胶囊。 */
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
) {
    val colors = AppTheme.colors
    // J2 恒横线纸：书写区 drawBehind 逐行铺横线（行距 = kaiBody lineHeight 单源）；正文/引导语同走 kaiBody·同起点天然对齐。
    val ruleColor = colors.text.primary.copy(alpha = PAPER_RULE_ALPHA)
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 180.dp)
                .drawBehind {
                    val spacing = PAPER_LINE_SPACING.toPx()          // 28sp = kaiBody lineHeight（toPx 吃 fontScale）
                    val baseline = PAPER_LINE_BASELINE_OFFSET.toPx() // −4sp 校准·随 fontScale 缩放
                    val stroke = PAPER_LINE_WIDTH.toPx()             // 0.5dp
                    var n = 1
                    while (true) {
                        val y = n * spacing - baseline
                        if (y > size.height) break
                        drawLine(color = ruleColor, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = stroke)
                        n++
                    }
                },
        ) {
            if (content.isEmpty()) {
                Text(prompt, style = AppTheme.typography.kaiBody, color = colors.text.secondary)
            }
            BasicTextField(
                value = content,
                onValueChange = onContentChange,
                textStyle = AppTheme.typography.kaiBody.copy(color = colors.text.primary),
                cursorBrush = SolidColor(colors.accent.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
            .border(1.dp, colors.accent.text.copy(alpha = 0.5f), AppTheme.shapes.full)
            .clickable(enabled = !isGenerating, onClickLabel = label) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Icon(Icons.Filled.Edit, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(16.dp))
        Text(label, style = AppTheme.typography.secondary, color = colors.accent.text)
    }
}

/** 图片缩略横排（有图才现·配图入口在底部行动条）·J3 拍立得化（单张 = [PolaroidPhoto]·消费签名不变）。 */
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

/** M6 底部行动条（J6 重排）：左组四钮（加图 / 麦克风 / [hasContent] AI 重写 / 可见性·40dp 视觉 + 48 触达）+ 右组「先存着」草稿 + 陶土「记下」。 */
@Composable
internal fun ComposeActionBar(
    canSave: Boolean,
    hasContent: Boolean,
    canAddImage: Boolean,
    isGenerating: Boolean,
    /** 「AI 帮我写」是否可用（编辑「TA 的信」时为 false → 该入口整个不出现）。 */
    aiAssistAvailable: Boolean,
    visibility: DiaryVisibility,
    onAddImage: () -> Unit,
    onToggleVisibility: () -> Unit,
    onAiAssist: () -> Unit,
    onSaveDraft: () -> Unit,
    onRecord: () -> Unit,
    onStartVoice: () -> Unit,
    onVoiceDrag: (Float) -> Unit,
    onFinishVoice: () -> Unit,
) {
    val colors = AppTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.surface.raised)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.surface.stroke))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                // R1 🟡-2：360dp 最窄档满配「记下」曾折两行——minimumInteractiveComponentSize 使每钮布局占位 48dp
                // (4×48=192 非 160)、满配实占 ≈378>360。48 触达是 a11y 红线不可缩，改从间距/内边距回收 26dp
                // (横 16→12·spacedBy 6→4·先存着 10→8·记下 contentPadding 20→16)→满配 ≈352 ≤360 单行。
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 左组（spacedBy 0·宽度预算锁死）：加图 / 麦克风（新）/ AI 重写（hasContent 态保留·D-J1）/ 可见性（去文字标签·D-J2）。
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
                DiaryNavIcon(Icons.Filled.Add, stringResource(R.string.diary_compose_add_image), enabled = canAddImage, onClick = onAddImage)
                DiaryMicButton(onStart = onStartVoice, onDrag = onVoiceDrag, onFinish = onFinishVoice)
                if (hasContent && aiAssistAvailable) {
                    DiaryNavIcon(Icons.Filled.Edit, stringResource(R.string.diary_compose_ai_assist), enabled = !isGenerating, onClick = onAiAssist)
                }
                val isOpen = visibility == DiaryVisibility.OPEN_TO_AI
                DiaryNavIcon(
                    if (isOpen) Icons.Filled.Person else Icons.Filled.Lock,
                    stringResource(if (isOpen) R.string.diary_visibility_open else R.string.diary_visibility_private),
                    onClick = onToggleVisibility,
                )
            }
            Spacer(Modifier.weight(1f))
            if (canSave) {
                Text(
                    stringResource(R.string.diary_compose_save_draft_short),
                    style = AppTheme.typography.secondary,
                    color = colors.text.secondary,
                    modifier = Modifier
                        .clip(AppTheme.shapes.full)
                        .clickable(onClickLabel = stringResource(R.string.diary_compose_save_draft_short)) { onSaveDraft() }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            // R1 🟡-2：记下横向内边距 20→16（-8·contentPadding 覆写默认 Primary 20dp），字号不动。
            AppButton(
                onClick = onRecord,
                style = AppButtonStyle.Primary,
                enabled = canSave,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.diary_compose_record))
            }
        }
    }
}

/** J6 动作栏左组图标钮：40dp 视觉 + [minimumInteractiveComponentSize]（触达 48·a11y 红线）；contentDescription 兼作 onClickLabel。 */
@Composable
private fun DiaryNavIcon(icon: ImageVector, contentDescription: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) colors.accent.text else colors.accent.text.copy(alpha = 0.4f),
            modifier = Modifier.size(24.dp),
        )
    }
}
