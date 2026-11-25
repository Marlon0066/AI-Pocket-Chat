package com.situ.aichat.ui.diary

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.chat.VoiceRecordingOverlay
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDialogTone
import com.situ.aichat.ui.designsystem.AppSnackbarHost
import com.situ.aichat.ui.designsystem.AppSpacing
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTopBarAction
import com.situ.aichat.ui.designsystem.BarScaffold
import com.situ.aichat.ui.designsystem.grainSurface

/**
 * 日记撰写 / 编辑界面（方向 C · 沉浸式书信画报写作体验）：
 * - 顶栏：左侧 `✕` + 中央安静实时字数 + 右侧「先存着」草稿与主色「记下」胶囊；
 * - 纸面：全幅顶部水彩心情晕染日期头 + 可点击心情邮票（点击折叠/展开心情行）+ 细密点线 + 宁静无格棉纸书写区；
 * - 底部：键盘上方悬浮胶囊写作坞（加图+张数标 / 说一段 / AI 帮写 / 心情抽屉 / 可见性）。
 */
@Composable
fun ComposeDiaryScreen(
    onClose: () -> Unit,
    onNavigateToApiConfig: () -> Unit = {},
    viewModel: ComposeDiaryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val materialChips by viewModel.materialChips.collectAsStateWithLifecycle()
    val voiceRecording by viewModel.voice.voiceRecording.collectAsStateWithLifecycle()
    val voiceLevel by viewModel.voice.voiceLevel.collectAsStateWithLifecycle()
    val voiceDurationMs by viewModel.voice.voiceDurationMs.collectAsStateWithLifecycle()
    val voiceCancelling by viewModel.voice.voiceCancelling.collectAsStateWithLifecycle()
    val isTranscribing by viewModel.voice.isTranscribing.collectAsStateWithLifecycle()
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val reduceMotion = rememberReduceMotion()
    val page = rememberComposeDiaryPageState(viewModel, onClose, onNavigateToApiConfig)
    val prompt = rememberDiaryDailyPrompt()
    val wash = rememberComposeDiaryWash(state.moodEmoji, fallback = colors.surface.base)
    val scrollState = rememberScrollState()
    val barLifted by remember { derivedStateOf { scrollState.value > 0 } }
    var moodTrayExpanded by rememberSaveable { mutableStateOf(state.moodEmoji == null && state.content.isEmpty()) }

    Scaffold(
        containerColor = colors.surface.base,
        snackbarHost = { AppSnackbarHost(page.snackbarHostState) },
        topBar = {
            BarScaffold(lifted = barLifted) {
                AppTopBarAction(
                    icon = AppActionIcons.Close,
                    contentDescription = stringResource(R.string.action_cancel),
                    onClick = { page.attemptClose() },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = AppSpacing.gutterForRoundButton),
                )
                if (state.content.isNotBlank()) {
                    Text(
                        stringResource(R.string.diary_compose_word_count, state.content.length),
                        style = AppTheme.typography.captionNumeric,
                        color = colors.text.tertiary,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = AppSpacing.screenGutter),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.canSave) {
                        Text(
                            stringResource(R.string.diary_compose_save_draft_short),
                            style = AppTheme.typography.secondary,
                            color = colors.text.secondary,
                            modifier = Modifier
                                .clip(AppTheme.shapes.full)
                                .clickable(onClickLabel = stringResource(R.string.diary_compose_save_draft_short)) {
                                    page.saveDraft()
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                    AppButton(
                        onClick = { page.record(haptics) },
                        style = AppButtonStyle.Primary,
                        enabled = state.canSave,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp),
                    ) {
                        Text(stringResource(R.string.diary_compose_record))
                    }
                }
            }
        },
        bottomBar = {
            ComposeFloatingWritingDock(
                canAddImage = state.canAddImage,
                imageCount = state.images.size,
                isGenerating = state.isGenerating,
                aiAssistAvailable = !state.isExchangeLetter,
                moodEmoji = state.moodEmoji,
                moodTrayExpanded = moodTrayExpanded,
                visibility = state.visibility,
                onAddImage = page::pickImages,
                onToggleMoodTray = { moodTrayExpanded = !moodTrayExpanded },
                onToggleVisibility = { page.toggleVisibility(state.visibility) },
                onAiAssist = { page.showGuideSheet = true },
                onStartVoice = { viewModel.voice.startVoice() },
                onVoiceDrag = { viewModel.voice.updateVoiceDrag(it) },
                onFinishVoice = { viewModel.voice.finishVoice() },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.surface.base)
                    .grainSurface()
                    .verticalScroll(scrollState)
                    .padding(bottom = 28.dp),
            ) {
                ComposeDateHead(
                    timestamp = state.timestamp,
                    moodEmoji = state.moodEmoji,
                    wash = wash,
                    scale = page.headScale.value,
                    reduceMotion = reduceMotion,
                    moodSelectTick = page.moodSelectTick,
                    onMoodClick = { moodTrayExpanded = !moodTrayExpanded },
                    fullBleed = true,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.screenGutter),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    DiaryDottedDivider()
                    AnimatedVisibility(
                        visible = moodTrayExpanded,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        MoodRow(
                            selectedEmoji = state.moodEmoji,
                            reduceMotion = reduceMotion,
                            onToggle = { emoji, label ->
                                page.toggleMood(emoji, label)
                                if (state.moodEmoji != emoji) {
                                    moodTrayExpanded = false
                                }
                            },
                        )
                    }
                    if (state.showsMaterialChips(materialChips)) {
                        MaterialChipsRow(chips = materialChips, onPick = page::pickMaterial)
                    }
                    DiaryPaper(
                        content = state.content,
                        prompt = prompt,
                        isGenerating = state.isGenerating,
                        reduceMotion = reduceMotion,
                        aiAssistAvailable = !state.isExchangeLetter,
                        onContentChange = viewModel::setContent,
                        onAiStart = { page.showGuideSheet = true },
                        showRules = false,
                    )
                    if (isTranscribing) {
                        DiaryTranscribingPill(reduceMotion = reduceMotion)
                    }
                    if (state.images.isNotEmpty()) {
                        ImageThumbs(images = state.images, onRemove = viewModel::removeImage)
                    }
                }
            }
            if (voiceRecording) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .clip(AppTheme.shapes.large)
                        .background(colors.surface.raised),
                ) {
                    VoiceRecordingOverlay(level = voiceLevel, durationMs = voiceDurationMs, cancelling = voiceCancelling)
                }
            }
        }
    }

    if (page.showGuideSheet) {
        ThreeQuestionGuideSheet(
            onDismiss = { page.showGuideSheet = false },
            onGenerate = page::generate,
        )
    }

    if (page.showDiscard) {
        AppDialog(
            onDismissRequest = { page.showDiscard = false },
            title = stringResource(R.string.diary_compose_discard_title),
            confirmText = stringResource(R.string.diary_compose_discard_confirm),
            onConfirm = page::discardAndClose,
            confirmTone = AppDialogTone.Danger,
            dismissText = stringResource(R.string.diary_compose_discard_keep),
            onDismiss = { page.showDiscard = false },
        )
    }
}
