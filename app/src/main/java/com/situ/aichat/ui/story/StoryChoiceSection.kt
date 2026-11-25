package com.situ.aichat.ui.story

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.story.StoryChoiceCountdown
import com.situ.aichat.story.StoryNarrativePerson
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppTheme

/**
 * 章末选择区（ST7d·契约 §6.4 + J2）。选项卡全 token 化（照 mockup 屏六 .choice/.choice.sel）：A/B/C 键徽
 * + 未选纸感冻边卡 / 选中陶土软填充；选中即时提交（选中项弹一下 1.04·= iOS selectedChoiceScale·其余项淡到 0.45）。
 *
 * 反悔窗口的呈现改为**屏级底部非阻塞撤销条** [StoryUndoBar]（不再在本区内嵌），本区只管选项与落库后反馈。
 */
@Composable
fun StoryChoiceSection(
    chapter: StoryChapterEntity,
    isDark: Boolean,
    narrativePerson: String,
    userRoleName: String?,
    selectedChoiceText: String?,
    onSubmit: (String) -> Unit,
    onOpenCustomInput: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textColor = StoryReaderLayout.textColor(isDark)
    val secondaryColor = StoryReaderLayout.secondaryTextColor(isDark)
    val isLocked = chapter.userChoice != null
    val options = rememberStoryChoiceOptions(chapter)

    val popScale = rememberStoryChoicePopScale(selectedChoiceText, isLocked)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        StoryChoiceHeader(isDark)

        Text(
            text = storyChoicePromptText(chapter, narrativePerson, userRoleName),
            color = textColor,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Serif,
        )

        options.forEachIndexed { index, option ->
            val selected = selectedChoiceText == option
            ChoiceOptionCard(
                letter = storyChoiceLetter(index),
                option = option,
                selected = selected,
                dimmed = storyChoiceDimmed(selectedChoiceText, option),
                locked = isLocked,
                isDark = isDark,
                scale = if (selected) popScale.value else 1f,
                onClick = { if (!isLocked) onSubmit(option) },
            )
        }

        FreeInputCard(locked = isLocked, isDark = isDark, onClick = { if (!isLocked) onOpenCustomInput() })

        storyChoiceFeedbackText(chapter, narrativePerson, userRoleName)?.let {
            Text(
                text = it,
                color = secondaryColor,
                fontSize = 13.sp,
            )
        }
    }
}

/** 选项卡（token 化·照 mockup .choice/.choice.sel）：A/B/C 键徽 + 未选纸感冻边 / 选中陶土软填充。 */
@Composable
private fun ChoiceOptionCard(
    letter: String,
    option: String,
    selected: Boolean,
    dimmed: Boolean,
    locked: Boolean,
    isDark: Boolean,
    scale: Float,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val a11y = stringResource(R.string.story_choice_option_a11y, option)
    val moodScrim = StoryReaderLayout.chromeScrimColor(isDark)
    val moodBorder = StoryReaderLayout.chromeBorderColor(isDark)
    val moodText = StoryReaderLayout.textColor(isDark)
    val moodSecondary = StoryReaderLayout.secondaryTextColor(isDark)
    // 选中 → 品牌陶土软填充 + 陶边 + onContainer 字（对比已由 ColorContrastTest 看门）；未选 → 随纸面自适应的毛玻璃纸感卡。
    val cardColor = if (selected) colors.accent.container else moodScrim
    val borderColor = if (selected) colors.accent.primary else moodBorder
    val optionColor = if (selected) colors.accent.onContainer else moodText
    val badgeColor = if (selected) colors.accent.deepStart else moodText.copy(alpha = 0.10f)
    val badgeText = if (selected) colors.accent.onDeep else moodSecondary
    Surface(
        onClick = onClick,
        enabled = !locked,
        shape = AppTheme.shapes.medium,
        color = cardColor,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .alpha(if (dimmed) 0.45f else 1f)
            .semantics { contentDescription = a11y },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(22.dp).clip(CircleShape).background(badgeColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(letter, color = badgeText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Text(option, color = optionColor, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun FreeInputCard(locked: Boolean, isDark: Boolean, onClick: () -> Unit) {
    // P1-20·1:1 补缺：iOS 自由输入按钮 accessibilityLabel「输入自定义选择」（StoryReaderView+Choices.swift:74）。
    val a11y = stringResource(R.string.story_choice_free_input_a11y)
    val textColor = StoryReaderLayout.textColor(isDark)
    val scrim = StoryReaderLayout.chromeScrimColor(isDark)
    val border = StoryReaderLayout.chromeBorderColor(isDark)
    val secondary = StoryReaderLayout.secondaryTextColor(isDark)
    Surface(
        onClick = onClick,
        enabled = !locked,
        shape = AppTheme.shapes.medium,
        color = scrim,
        border = BorderStroke(1.dp, border),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (locked) 0.45f else 1f)
            .semantics { contentDescription = a11y },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(22.dp).clip(CircleShape).background(textColor.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppActionIcons.Edit, contentDescription = null, tint = secondary, modifier = Modifier.size(13.dp))
            }
            Text(stringResource(R.string.story_choice_free_input), color = textColor.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
        }
    }
}

/**
 * 屏级底部非阻塞撤销条（J2 改型·照 mockup 屏六 .undo）：选中即时提交进入约 4s 反悔窗口，期间浮于内容之上
 * 的深陶胶囊「⟳ 已选择：… ↩ 撤销」，环形倒计时随窗口消退；点撤销可反悔（生成未真正开始），全程不挡内容。
 * 深陶底 + onDeep 暖白字（= 设计系统「恒深档」承载 prominent 提示的既有 token）。
 */
@Composable
internal fun StoryUndoBar(
    choiceText: String,
    remainingSeconds: Int,
    visible: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + slideInVertically(tween(250)) { it / 2 },
        exit = fadeOut(tween(200)),
        modifier = modifier,
    ) {
        val colors = AppTheme.colors
        val onDeep = colors.accent.onDeep
        // 环形倒计时：本会话内 1→0 线性消退（keyed on choiceText·换选择即重置）。
        val ring = remember(choiceText) { Animatable(1f) }
        LaunchedEffect(choiceText) {
            ring.snapTo(1f)
            ring.animateTo(0f, tween(StoryChoiceCountdown.WINDOW_MS.toInt(), easing = LinearEasing))
        }
        val undoA11y = stringResource(R.string.story_choice_undo_a11y, remainingSeconds)
        Surface(
            shape = RoundedCornerShape(50),
            color = colors.accent.deepStart,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Canvas(Modifier.size(20.dp).clearAndSetSemantics {}) {
                    val stroke = 2.dp.toPx()
                    val inset = stroke / 2f
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    val topLeft = Offset(inset, inset)
                    drawArc(onDeep.copy(alpha = 0.25f), 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
                    drawArc(onDeep, -90f, 360f * ring.value, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Text(
                    stringResource(R.string.story_choice_pending, choiceText),
                    color = onDeep,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    onClick = onCancel,
                    shape = RoundedCornerShape(50),
                    color = onDeep.copy(alpha = 0.14f),
                    modifier = Modifier.semantics { contentDescription = undoA11y },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, tint = onDeep, modifier = Modifier.size(15.dp))
                        Text(stringResource(R.string.story_choice_undo_short), color = onDeep, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/**
 * 选择区头部装饰：──── ✧ 你的回应 ✧ ────（1:1 iOS `StoryChoiceHeader`）。
 * P1-20·1:1 补缺：iOS 把整头（含「你的回应」文字）.accessibilityHidden(true)（StoryReaderView+Choices.swift:282）
 * → 安卓同样整行压停。
 */
@Composable
internal fun StoryChoiceHeader(isDark: Boolean) {
    val ornament = StoryReaderLayout.ornamentColor(isDark)
    val textColor = StoryReaderLayout.secondaryTextColor(isDark)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp).clearAndSetSemantics {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f).height(0.5.dp).background(ornament))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("✧", color = ornament, fontSize = 13.sp)
            Text(
                stringResource(R.string.story_choice_header),
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Serif,
                letterSpacing = StoryReaderLayout.ornamentKerning,
            )
            Text("✧", color = ornament, fontSize = 13.sp)
        }
        Box(Modifier.weight(1f).height(0.5.dp).background(ornament))
    }
}

// ── prompt 文案（随人称 + 用户角色名切换，1:1 iOS defaultChoicePrompt / customChoiceHint / selectedChoiceFeedbackPrefix）──

@Composable
fun storyDefaultChoicePrompt(narrativePerson: String, userRoleName: String?): String = when (narrativePerson) {
    StoryNarrativePerson.FIRST ->
        if (userRoleName != null) stringResource(R.string.story_choice_prompt_first_role, userRoleName)
        else stringResource(R.string.story_choice_prompt_first)
    StoryNarrativePerson.THIRD ->
        if (userRoleName != null) stringResource(R.string.story_choice_prompt_third_role, userRoleName)
        else stringResource(R.string.story_choice_prompt_third)
    else -> stringResource(R.string.story_choice_prompt_second)
}

@Composable
fun storyCustomChoiceHint(narrativePerson: String, userRoleName: String?): String = when (narrativePerson) {
    StoryNarrativePerson.FIRST ->
        if (userRoleName != null) stringResource(R.string.story_custom_choice_hint_first_role, userRoleName)
        else stringResource(R.string.story_custom_choice_hint_default)
    StoryNarrativePerson.THIRD ->
        if (userRoleName != null) stringResource(R.string.story_custom_choice_hint_third_role)
        else stringResource(R.string.story_custom_choice_hint_third)
    else -> stringResource(R.string.story_custom_choice_hint_default)
}

@Composable
fun storyChoiceFeedbackPrefix(narrativePerson: String, userRoleName: String?): String =
    if (narrativePerson == StoryNarrativePerson.FIRST && userRoleName != null) {
        stringResource(R.string.story_choice_feedback_role, userRoleName)
    } else {
        stringResource(R.string.story_choice_feedback)
    }
