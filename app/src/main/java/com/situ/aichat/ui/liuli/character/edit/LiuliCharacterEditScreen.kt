package com.situ.aichat.ui.liuli.character.edit

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.character.CharacterEditMediaDialogs
import com.situ.aichat.ui.character.CharacterEditViewModel
import com.situ.aichat.ui.character.defaultBirthdayMillis
import com.situ.aichat.ui.character.rememberCharacterEditMediaFlow
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.liuli.designsystem.LiuliButton
import com.situ.aichat.ui.liuli.designsystem.LiuliButtonStyle
import com.situ.aichat.ui.liuli.page.LiuliLargeTitle
import com.situ.aichat.ui.liuli.page.LiuliPage
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.liuli.page.rememberScrollCollapsed

/** 顶栏「取消 / 保存」两颗钮的左右内距（§4.1 锁定值）。 */
private val EDIT_BAR_PAD_H = 16.dp

/**
 * 角色编辑页（「角色信息」/「新建角色」）的琉璃脸（琉璃 2.0 卷五 §4.1）。与暖陶 [com.situ.aichat.ui.character.CharacterEditScreen]
 * 共用同一个 [CharacterEditViewModel]（世界 / 世界观两组各自 `hiltViewModel()` 取自己的 VM）；字段、顺序、条件显隐、
 * 保存语义逐条同暖陶——只有「保存」落库，提示词模块开关 / 世界 / 世界观照旧立即落库。
 */
@Composable
internal fun LiuliCharacterEditScreen(
    onCancel: () -> Unit,
    onSaved: (conversationUuid: String?) -> Unit,
    onEditModules: (characterUuid: String) -> Unit,
    onOpenOfflineMeetings: (characterUuid: String) -> Unit,
    onOpenWorldBooks: () -> Unit,
    focusVoiceSection: Boolean,
    viewModel: CharacterEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val compiling by viewModel.compiling.collectAsStateWithLifecycle()
    val personaNeedsSave by viewModel.personaNeedsSave.collectAsStateWithLifecycle()
    val systemVoices by viewModel.systemVoices.collectAsStateWithLifecycle()
    val previewBusy by viewModel.previewBusy.collectAsStateWithLifecycle()
    val previewError by viewModel.previewError.collectAsStateWithLifecycle()
    val hasModuleOverride by viewModel.hasModuleOverride.collectAsStateWithLifecycle()
    val media = rememberCharacterEditMediaFlow(viewModel)
    var showDatePicker by remember { mutableStateOf(false) }

    LiuliCharacterEditPage(
        isEditing = viewModel.isEditing,
        canSave = state.canSave,
        saving = saving,
        onCancel = onCancel,
        onSave = { viewModel.save(onSaved) },
        focusVoiceSection = focusVoiceSection,
    ) { onVoiceSectionY ->
        LiuliCharacterEditContent(
            state = state,
            isEditing = viewModel.isEditing,
            compiling = compiling,
            personaNeedsSave = personaNeedsSave,
            systemVoices = systemVoices,
            previewBusy = previewBusy,
            previewError = previewError,
            hasModuleOverride = hasModuleOverride,
            onUpdate = viewModel::update,
            onPickAvatar = media.pickAvatar,
            onPickWallpaper = media.pickWallpaper,
            onRemoveWallpaper = media.removeWallpaper,
            onOpenBirthday = { showDatePicker = true },
            onCompilePersona = viewModel::compilePersona,
            onLoadSystemVoices = viewModel::loadSystemVoices,
            onPreviewVoice = viewModel::preview,
            onSetModuleOverride = viewModel::setModuleOverride,
            onEditModules = { viewModel.editingUuid?.let(onEditModules) },
            onOpenMeetings = { viewModel.editingUuid?.let(onOpenOfflineMeetings) },
            onVoiceSectionY = onVoiceSectionY,
            worldGroup = if (viewModel.isEditing) {
                { LiuliCharacterWorldGroup() }
            } else {
                { LiuliCharacterWorldCreateGroup(joined = state.joinWorld, onToggle = viewModel::setCreateJoinWorld) }
            },
            worldBookGroup = if (viewModel.isEditing) {
                { LiuliWorldBookGroup(onManageBooks = onOpenWorldBooks) }
            } else {
                {
                    LiuliWorldBookCreateGroup(
                        selectedBookUuids = state.initialWorldBookUuids,
                        joinedWorld = state.joinWorld,
                        onSelectSole = viewModel::selectInitialWorldBookSole,
                        onToggle = viewModel::toggleInitialWorldBook,
                        onManageBooks = onOpenWorldBooks,
                    )
                }
            },
        )
    }
    if (showDatePicker) {
        LiuliBirthdayDialog(
            initialMillis = state.birthdayMillis ?: defaultBirthdayMillis(),
            onConfirm = { v ->
                viewModel.update { it.copy(birthdayMillis = v) }
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
        )
    }
    CharacterEditMediaDialogs(media)
}

/**
 * 页壳层（无 VM·供 Robolectric 直测）：琉璃二级页标准壳 + 顶栏「取消 / 保存」+ `Column + verticalScroll`
 * + 语音组深链一次性定位（逐字照暖陶 `CharacterEditScreen` 的闩锁写法）。[content] 拿到「报告语音组位置」的回调。
 */
@Composable
internal fun LiuliCharacterEditPage(
    isEditing: Boolean,
    canSave: Boolean,
    saving: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    focusVoiceSection: Boolean,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.(onVoiceSectionY: (Int) -> Unit) -> Unit,
) {
    // VU1 §4.4：从拨号门深链带 focusVoice=true 进来时，一次性滚到语音区（闩锁·PITFALLS 1d「功成身退」）。
    var voiceSectionY by remember { mutableStateOf<Int?>(null) }
    var focusScrolled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(voiceSectionY) {
        if (focusVoiceSection && !focusScrolled) {
            voiceSectionY?.let { scrollState.animateScrollTo(it); focusScrolled = true }
        }
    }
    val title = stringResource(if (isEditing) R.string.char_title_edit else R.string.char_title_create)
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LiuliPage(
        title = title,
        onBack = onCancel,
        collapsed = rememberScrollCollapsed(scrollState),
        leading = {
            LiuliButton(onClick = onCancel, style = LiuliButtonStyle.Glass, contentPadding = PaddingValues(horizontal = EDIT_BAR_PAD_H)) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        actions = {
            LiuliButton(
                onClick = onSave,
                style = LiuliButtonStyle.Prominent,
                enabled = canSave && !saving,
                contentPadding = PaddingValues(horizontal = EDIT_BAR_PAD_H),
            ) { Text(stringResource(R.string.action_save)) }
        },
    ) {
        // 卷五 §11 D-1（复核 R1 核准）：§4.1 写的 `spacedBy(groupGap)` 与 LiuliGroup 自带的 `padding(bottom = groupGap)` 叠成 48，
        // 违背 §4 通则「组间距 24」→ 此处不加 spacedBy，组间距由 LiuliGroup 自带；非组件（头像块）的上下缝在主体里补（§11 D-1）。
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(scrollState)
                .contentMaxWidth()
                .padding(top = LiuliPageGeometry.navRow, bottom = LiuliPageGeometry.pageBottom + navBarBottom),
        ) {
            LiuliLargeTitle(title)
            content { voiceSectionY = it }
        }
    }
}
