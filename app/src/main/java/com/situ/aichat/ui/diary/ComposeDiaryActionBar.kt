package com.situ.aichat.ui.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.data.model.DiaryVisibility
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.appCardSurface

/**
 * 日记编写页底部工具条族（自 ComposeDiaryComponents.kt 拆分·纯搬不改）：
 * - [ComposeFloatingWritingDock]：方向 C 暖陶悬浮写作坞（加图+张数微标 / 按住说一段 / AI 帮写 / 心情抽屉 / 可见性胶囊）；
 * - [ComposeActionBar] / [ComposeActionBarItems]：双脸复用 & 单测锁定底条件。
 */

@Composable
internal fun ComposeFloatingWritingDock(
    canAddImage: Boolean,
    imageCount: Int,
    isGenerating: Boolean,
    aiAssistAvailable: Boolean,
    moodEmoji: String?,
    moodTrayExpanded: Boolean,
    visibility: DiaryVisibility,
    onAddImage: () -> Unit,
    onToggleMoodTray: () -> Unit,
    onToggleVisibility: () -> Unit,
    onAiAssist: () -> Unit,
    onStartVoice: () -> Unit,
    onVoiceDrag: (Float) -> Unit,
    onFinishVoice: () -> Unit,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .appCardSurface(cornerRadius = 28.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.TopEnd) {
                DiaryNavIcon(
                    Icons.Filled.Add,
                    stringResource(R.string.diary_compose_add_image),
                    enabled = canAddImage,
                    onClick = onAddImage,
                )
                if (imageCount > 0) {
                    Text(
                        imageCount.toString(),
                        style = AppTheme.typography.captionNumeric.copy(fontSize = 10.sp, lineHeight = 11.sp),
                        color = colors.text.onAccent,
                        modifier = Modifier
                            .padding(top = 6.dp, end = 6.dp)
                            .clip(CircleShape)
                            .background(colors.accent.primary)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            DiaryMicButton(onStart = onStartVoice, onDrag = onVoiceDrag, onFinish = onFinishVoice)
            if (aiAssistAvailable) {
                DiaryNavIcon(
                    AppActionIcons.Edit,
                    stringResource(R.string.diary_compose_ai_assist),
                    enabled = !isGenerating,
                    onClick = onAiAssist,
                )
            }
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (moodTrayExpanded) colors.accent.container else Color.Transparent)
                    .clickable(onClickLabel = stringResource(R.string.diary_compose_mood_pick)) {
                        haptics.selection()
                        onToggleMoodTray()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = moodEmoji?.takeIf { it.isNotEmpty() } ?: "✦",
                    fontSize = if (moodEmoji.isNullOrEmpty()) 16.sp else 18.sp,
                    color = colors.accent.text,
                )
            }
            Spacer(Modifier.weight(1f))
            val isOpen = visibility == DiaryVisibility.OPEN_TO_AI
            val visLabel = stringResource(if (isOpen) R.string.diary_visibility_open else R.string.diary_visibility_private)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .padding(end = 4.dp)
                    .clip(AppTheme.shapes.full)
                    .background(colors.surface.sunken)
                    .clickable(onClickLabel = visLabel) { haptics.selection(); onToggleVisibility() }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
            ) {
                Icon(
                    if (isOpen) AppActionIcons.Person else AppActionIcons.Lock,
                    contentDescription = null,
                    tint = colors.accent.text,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    visLabel,
                    style = AppTheme.typography.caption,
                    color = colors.text.secondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** M6 底部行动条（保留供单测与复用）：左组四钮 + 右组「先存着」草稿 + 陶土「记下」。 */
@Composable
internal fun ComposeActionBar(
    canSave: Boolean,
    hasContent: Boolean,
    canAddImage: Boolean,
    isGenerating: Boolean,
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
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ComposeActionBarItems(
                canSave = canSave,
                hasContent = hasContent,
                canAddImage = canAddImage,
                isGenerating = isGenerating,
                aiAssistAvailable = aiAssistAvailable,
                visibility = visibility,
                onAddImage = onAddImage,
                onToggleVisibility = onToggleVisibility,
                onAiAssist = onAiAssist,
                onSaveDraft = onSaveDraft,
                onStartVoice = onStartVoice,
                onVoiceDrag = onVoiceDrag,
                onFinishVoice = onFinishVoice,
                record = {
                    AppButton(
                        onClick = onRecord,
                        style = AppButtonStyle.Primary,
                        enabled = canSave,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(stringResource(R.string.diary_compose_record))
                    }
                },
            )
        }
    }
}

/** 撰写动作条的内件（两张脸共用·外壳各画各的）：左组四钮 · 弹簧 · 先存着 · [record]。 */
@Composable
internal fun RowScope.ComposeActionBarItems(
    canSave: Boolean,
    hasContent: Boolean,
    canAddImage: Boolean,
    isGenerating: Boolean,
    aiAssistAvailable: Boolean,
    visibility: DiaryVisibility,
    onAddImage: () -> Unit,
    onToggleVisibility: () -> Unit,
    onAiAssist: () -> Unit,
    onSaveDraft: () -> Unit,
    onStartVoice: () -> Unit,
    onVoiceDrag: (Float) -> Unit,
    onFinishVoice: () -> Unit,
    record: @Composable () -> Unit,
) {
    val colors = AppTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
        DiaryNavIcon(Icons.Filled.Add, stringResource(R.string.diary_compose_add_image), enabled = canAddImage, onClick = onAddImage)
        DiaryMicButton(onStart = onStartVoice, onDrag = onVoiceDrag, onFinish = onFinishVoice)
        if (hasContent && aiAssistAvailable) {
            DiaryNavIcon(AppActionIcons.Edit, stringResource(R.string.diary_compose_ai_assist), enabled = !isGenerating, onClick = onAiAssist)
        }
        val isOpen = visibility == DiaryVisibility.OPEN_TO_AI
        DiaryNavIcon(
            if (isOpen) AppActionIcons.Person else AppActionIcons.Lock,
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
    record()
}

/** 动作栏左组图标钮：40dp 视觉 + [minimumInteractiveComponentSize]（触达 48·a11y 红线）。 */
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
