package com.situ.aichat.ui.diary

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.data.model.DiaryVisibility
import com.situ.aichat.ui.chat.VoiceRecordingOverlay
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDialogTone
import com.situ.aichat.ui.designsystem.AppSnackbarHost
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.grainSurface
import java.time.LocalDate

/**
 * 日记撰写 / 编辑界面（U1 重设计·契约 [FABLE5_DIARY_COMPOSE_REDESIGN_PROPOSAL.md]）：从「表单」改为「一页纸」——
 * 票据日期头 + 心情色回声（M1）/ 无边框纸面书写区 + 每日引导语（M2/M3）/ 心情微提示 + lively 轻弹（M4/刀②）/
 * 「让 TA 帮你起个头」协作 + 生成 breathing（M5/刀③）/ 底部陶土「记下」+ 发布落定仪式（M6/M7·刀④）。
 * 子组件见 [ComposeDiaryComponents]。功能一件不少，VM 行为零改，仅换呈现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeDiaryScreen(
    onClose: () -> Unit,
    onNavigateToApiConfig: () -> Unit = {},
    viewModel: ComposeDiaryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val materialChips by viewModel.materialChips.collectAsStateWithLifecycle()
    // J6 说一段：录音三态（录/电平/计时）+ 取消态 + 转写中。
    val voiceRecording by viewModel.voice.voiceRecording.collectAsStateWithLifecycle()
    val voiceLevel by viewModel.voice.voiceLevel.collectAsStateWithLifecycle()
    val voiceDurationMs by viewModel.voice.voiceDurationMs.collectAsStateWithLifecycle()
    val voiceCancelling by viewModel.voice.voiceCancelling.collectAsStateWithLifecycle()
    val isTranscribing by viewModel.voice.isTranscribing.collectAsStateWithLifecycle()
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val reduceMotion = rememberReduceMotion()
    var showDiscard by remember { mutableStateOf(false) }
    // R1 🔵-1：会话内用户切换心情的计数（页级 remember·跨邮票进出组合存活）——仅经用户点 MoodPill 递增；
    // 编辑预置心情/进程恢复/AI 回填都不碰它，故那些场景邮票 tick=0 → 静置落位不盖章不震（无操作不震动）。
    var moodSelectTick by remember { mutableIntStateOf(0) }
    // U2①：三问引导 sheet（「让 TA 帮你起个头」升级为可留空的三问·答案注入生成）。
    var showGuideSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // P0-15：AI 帮写失败反馈——未配置 API 显「去设置」跳配置；其它失败显具体原因（含网络）。
    val noApiText = stringResource(R.string.diary_ai_no_api)
    val genFailedText = stringResource(R.string.diary_ai_generate_failed)
    val goSettingsText = stringResource(R.string.bg_action_open_settings)
    LaunchedEffect(Unit) {
        viewModel.aiDraftError.collect { err ->
            snackbarHostState.currentSnackbarData?.dismiss()
            when (err) {
                ComposeDiaryViewModel.AiDraftError.NoApi -> {
                    val result = snackbarHostState.showSnackbar(message = noApiText, actionLabel = goSettingsText)
                    if (result == SnackbarResult.ActionPerformed) onNavigateToApiConfig()
                }
                is ComposeDiaryViewModel.AiDraftError.Failed ->
                    snackbarHostState.showSnackbar(err.message ?: genFailedText)
            }
        }
    }

    // J6 说一段一次性提示（录音失败/太短/转写失败三态·已解析成串）。
    LaunchedEffect(Unit) {
        viewModel.voiceMessage.collect { msg ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
        }
    }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9),
    ) { uris -> viewModel.addImages(uris) }

    fun attemptClose() {
        if (viewModel.hasUnsavedChanges) showDiscard = true else onClose()
    }

    // M3 每日引导语：按当天日期取一句（一天一句·稳定不随重组抖动）。
    val prompts = stringArrayResource(R.array.diary_compose_prompts)
    val prompt = remember(prompts.size) { prompts[(LocalDate.now().dayOfYear - 1).mod(prompts.size)] }

    // M1/M2 心情色回声：选好心情 → 日期头背后洇染对应装饰浅档（效果轴·reduceMotion 保留）。
    val washTarget = diaryMoodTint(state.moodEmoji) ?: colors.surface.base
    val wash by animateColorAsState(washTarget, tween(durationMillis = 320), label = "diaryMoodWash")

    // M7/刀④ 发布落定仪式：点「记下」→ 日期头轻弹落定（celebrate ζ0.5·每屏限一处）→ 保存返回。reduceMotion 直接保存。
    var publishing by remember { mutableStateOf(false) }
    val headScale = remember { Animatable(1f) }
    LaunchedEffect(publishing) {
        if (publishing) {
            if (!reduceMotion) {
                headScale.animateTo(1.06f, AppMotion.livelySpring())
                headScale.animateTo(1f, AppMotion.celebrateSpring())
            }
            viewModel.save(asDraft = false, onDone = onClose)
        }
    }

    Scaffold(
        containerColor = colors.surface.base,
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = { attemptClose() }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel), tint = colors.accent.text)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface.base),
            )
        },
        bottomBar = {
            ComposeActionBar(
                canSave = state.content.isNotBlank(),
                hasContent = state.content.isNotBlank(),
                canAddImage = state.images.size < 9,
                isGenerating = state.isGenerating,
                visibility = state.visibility,
                onAddImage = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onToggleVisibility = {
                    viewModel.setVisibility(
                        if (state.visibility == DiaryVisibility.OPEN_TO_AI) DiaryVisibility.PRIVATE else DiaryVisibility.OPEN_TO_AI,
                    )
                },
                onAiAssist = { showGuideSheet = true },
                onSaveDraft = { viewModel.save(asDraft = true, onDone = onClose) },
                onRecord = { if (!publishing) { haptics.success(); publishing = true } },
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
                    // J2 grain 纸感（顺序锁死：background→grain→scroll·此页此前缺席全 App 质感单源）。
                    .background(colors.surface.base)
                    .grainSurface()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                ComposeDateHead(timestamp = state.timestamp, moodEmoji = state.moodEmoji, wash = wash, scale = headScale.value, reduceMotion = reduceMotion, moodSelectTick = moodSelectTick)
                TearLine()
                // 🔵-1：用户点 MoodPill 才 tick++（→邮票盖章+触觉）；load/恢复/AI 回填走 VM 直改 state·tick 不动·静置。
                MoodRow(selectedEmoji = state.moodEmoji, reduceMotion = reduceMotion, onToggle = { emoji, text -> moodSelectTick++; viewModel.toggleMood(emoji, text) })
                // J5「今天的素材」：仅空态且有素材时现（放 MoodRow 与 DiaryPaper 之间）；点击置入起笔句（句尾带换行·走 J1 镜像）。
                if (state.content.isEmpty() && materialChips.isNotEmpty()) {
                    MaterialChipsRow(chips = materialChips, onPick = { starter -> viewModel.setContent(starter + "\n") })
                }
                DiaryPaper(
                    content = state.content,
                    prompt = prompt,
                    isGenerating = state.isGenerating,
                    reduceMotion = reduceMotion,
                    onContentChange = viewModel::setContent,
                    onAiStart = { showGuideSheet = true },
                )
                // J6 转写中：纸面下方一行「正在落笔…」（松手落笔·转写文追加进正文）。
                if (isTranscribing) {
                    DiaryTranscribingPill(reduceMotion = reduceMotion)
                }
                if (state.images.isNotEmpty()) {
                    ImageThumbs(images = state.images, onRemove = viewModel::removeImage)
                }
            }
            // J6 录音浮层：按住录音时挂屏根·浮于动作栏上方（共享件 VoiceRecordingOverlay 零碰只消费）。
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

    if (showGuideSheet) {
        ThreeQuestionGuideSheet(
            onDismiss = { showGuideSheet = false },
            onGenerate = { guide -> showGuideSheet = false; viewModel.generateAiDraft(guide) },
        )
    }

    if (showDiscard) {
        AppDialog(
            onDismissRequest = { showDiscard = false },
            title = stringResource(R.string.diary_compose_discard_title),
            confirmText = stringResource(R.string.diary_compose_discard_confirm),
            onConfirm = { showDiscard = false; viewModel.discard(); onClose() },
            confirmTone = AppDialogTone.Danger,
            dismissText = stringResource(R.string.diary_compose_discard_keep),
            onDismiss = { showDiscard = false },
        )
    }
}
