package com.situ.aichat.ui.liuli.diary

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppActionIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.diary.ComposeDateHead
import com.situ.aichat.ui.diary.ComposeDiaryViewModel
import com.situ.aichat.ui.diary.DiaryDottedDivider
import com.situ.aichat.ui.diary.DiaryPaper
import com.situ.aichat.ui.diary.DiaryTranscribingPill
import com.situ.aichat.ui.diary.ImageThumbs
import com.situ.aichat.ui.diary.MaterialChipsRow
import com.situ.aichat.ui.diary.MoodRow
import com.situ.aichat.ui.diary.rememberComposeDiaryPageState
import com.situ.aichat.ui.diary.rememberComposeDiaryWash
import com.situ.aichat.ui.diary.rememberDiaryDailyPrompt
import com.situ.aichat.ui.diary.showsMaterialChips
import com.situ.aichat.ui.liuli.designsystem.LiuliDialog
import com.situ.aichat.ui.liuli.designsystem.LiuliMaterials
import com.situ.aichat.ui.liuli.designsystem.LiuliShapes
import com.situ.aichat.ui.liuli.designsystem.LiuliSnackbarHost
import com.situ.aichat.ui.liuli.glass.LiuliGlassRole
import com.situ.aichat.ui.liuli.glass.liuliGlass
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageCircleAction
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.theme.LocalIsDarkTheme

/**
 * 琉璃写日记（方向 C 升级）：保持 [LiuliComposePaper] 与 [LiuliComposeDiaryBar] 几何契约零回归，
 * 纸面内部升级为水彩日期天头 + 可点击折叠心情邮票 + 细密点线 + 宁静无格磨砂书写区 + 顶部实时字数玻璃胶囊。
 */
@Composable
internal fun LiuliComposeDiaryScreen(
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
    val dark = LocalIsDarkTheme.current
    val haptics = LocalAppHaptics.current
    val reduceMotion = rememberReduceMotion()
    val page = rememberComposeDiaryPageState(viewModel, onClose, onNavigateToApiConfig)
    val prompt = rememberDiaryDailyPrompt()
    val wash = rememberComposeDiaryWash(state.moodEmoji, fallback = LiuliMaterials.paperTop(dark))
    var moodTrayExpanded by rememberSaveable { mutableStateOf(state.moodEmoji == null && state.content.isEmpty()) }

    LiuliPage(
        title = stringResource(R.string.diary_nav_title),
        onBack = page::attemptClose,
        collapsed = false,
        leading = {
            LiuliPageCircleAction(
                onClick = page::attemptClose,
                contentDescription = stringResource(R.string.action_cancel),
                icon = AppActionIcons.Close,
            )
        },
        actions = if (state.content.isNotBlank()) {
            {
                Box(
                    modifier = Modifier
                        .height(LiuliPageGeometry.backButton)
                        .liuliGlass(LiuliShapes.pill, dark = dark, role = LiuliGlassRole.Button)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.diary_compose_word_count, state.content.length),
                        style = AppTypography.captionNumeric,
                        color = AppTheme.colors.text.secondary,
                        maxLines = 1,
                    )
                }
            }
        } else {
            null
        },
        bottomBar = {
            LiuliComposeDiaryBottom(voiceRecording, voiceLevel, voiceDurationMs, voiceCancelling) {
                LiuliComposeDiaryBar(
                    state = state,
                    onAddImage = page::pickImages,
                    onToggleVisibility = { page.toggleVisibility(state.visibility) },
                    onAiAssist = { page.showGuideSheet = true },
                    onSaveDraft = page::saveDraft,
                    onRecord = { page.record(haptics) },
                    onStartVoice = { viewModel.voice.startVoice() },
                    onVoiceDrag = { viewModel.voice.updateVoiceDrag(it) },
                    onFinishVoice = { viewModel.voice.finishVoice() },
                )
            }
        },
    ) {
        LiuliComposePaper {
            ComposeDateHead(
                timestamp = state.timestamp,
                moodEmoji = state.moodEmoji,
                wash = wash,
                scale = page.headScale.value,
                reduceMotion = reduceMotion,
                moodSelectTick = page.moodSelectTick,
                onMoodClick = { moodTrayExpanded = !moodTrayExpanded },
                fullBleed = false,
            )
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
            if (state.showsMaterialChips(materialChips)) MaterialChipsRow(chips = materialChips, onPick = page::pickMaterial)
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
            if (isTranscribing) DiaryTranscribingPill(reduceMotion = reduceMotion)
            if (state.images.isNotEmpty()) ImageThumbs(images = state.images, onRemove = viewModel::removeImage)
        }
        LiuliSnackbarHost(
            page.snackbarHostState,
            Modifier.align(Alignment.BottomCenter).imePadding().padding(bottom = LiuliPageGeometry.floatingBarReserve),
        )
    }

    if (page.showGuideSheet) LiuliThreeQuestionGuideSheet(onDismiss = { page.showGuideSheet = false }, onGenerate = page::generate)
    if (page.showDiscard) {
        LiuliDialog(
            onDismissRequest = { page.showDiscard = false },
            title = stringResource(R.string.diary_compose_discard_title),
            confirmText = stringResource(R.string.diary_compose_discard_confirm),
            onConfirm = page::discardAndClose,
            confirmDanger = true,
            dismissText = stringResource(R.string.diary_compose_discard_keep),
            onDismiss = { page.showDiscard = false },
        )
    }
}
