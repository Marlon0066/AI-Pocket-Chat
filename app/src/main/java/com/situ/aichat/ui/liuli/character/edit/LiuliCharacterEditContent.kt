package com.situ.aichat.ui.liuli.character.edit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import com.situ.aichat.tts.SystemVoiceOption
import com.situ.aichat.ui.character.CharacterEditState
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import kotlin.math.roundToInt

/**
 * 角色编辑页琉璃脸的无状态主体（琉璃 2.0 卷五 §4.2）：按暖陶顺序依次拼装各组，数据与回调全由形参给；
 * 世界 / 世界观两组以槽传入（编辑态 = 带 VM 的外壳、新建态 = 新建版 / null；测试传假件）。
 * 每一项都是调用方 Column 的**直接子项**（语音组要量自己在滚动内容里的位置）。
 */
@Composable
internal fun ColumnScope.LiuliCharacterEditContent(
    state: CharacterEditState,
    isEditing: Boolean,
    compiling: Boolean,
    personaNeedsSave: Boolean,
    systemVoices: List<SystemVoiceOption>,
    previewBusy: Boolean,
    previewError: String?,
    hasModuleOverride: Boolean,
    onUpdate: ((CharacterEditState) -> CharacterEditState) -> Unit,
    onPickAvatar: () -> Unit,
    onPickWallpaper: () -> Unit,
    onRemoveWallpaper: () -> Unit,
    onOpenBirthday: () -> Unit,
    onCompilePersona: () -> Unit,
    onLoadSystemVoices: () -> Unit,
    onPreviewVoice: () -> Unit,
    onSetModuleOverride: (Boolean) -> Unit,
    onEditModules: () -> Unit,
    onOpenMeetings: () -> Unit,
    onVoiceSectionY: (Int) -> Unit,
    worldGroup: @Composable () -> Unit,
    worldBookGroup: (@Composable () -> Unit)?,
) {
    // 卷五 §11 D-2（复核 R1 核准）：§4.1 的滚动列没有左右页边，组卡会贴屏边；琉璃二级页统一左右 gutter 20（§11 D-2），逐项给。
    val gutter = Modifier.padding(horizontal = LiuliPageGeometry.gutter)
    // 头像块不是 LiuliGroup（没有自带的组下缝）：上下各补一档组间距，复现图纸原意「标题→头像 24+4、头像→首组 24」（§11 D-1）。
    LiuliEditAvatarBlock(
        name = state.name,
        avatarPath = state.avatarPath,
        onPickAvatar = onPickAvatar,
        onRemoveAvatar = { onUpdate { it.copy(avatarPath = null) } },
        modifier = gutter.padding(vertical = LiuliPageGeometry.groupGap),
    )
    LiuliEditWallpaperGroup(
        wallpaperPath = state.chatWallpaperPath,
        onPick = onPickWallpaper,
        onRemove = onRemoveWallpaper,
        modifier = gutter,
    )
    LiuliEditBasicGroup(
        state = state,
        onUpdate = onUpdate,
        onOpenBirthday = onOpenBirthday,
        onClearBirthday = { onUpdate { it.copy(birthdayMillis = null) } },
        modifier = gutter,
    )
    LiuliEditRelationshipGroup(state = state, isEditing = isEditing, onUpdate = onUpdate, modifier = gutter)
    LiuliEditSetupGroup(state = state, onUpdate = onUpdate, modifier = gutter)
    LiuliEditCommunicationGroup(state = state, onUpdate = onUpdate, modifier = gutter)
    LiuliEditSpectrumGroup(
        state = state,
        isEditing = isEditing,
        compiling = compiling,
        personaNeedsSave = personaNeedsSave,
        onCompile = onCompilePersona,
        onUpdate = onUpdate,
        modifier = gutter,
    )
    LiuliPersonaGainsGroup(gains = state.personaGains, onChange = { g -> onUpdate { it.copy(personaGains = g) } }, modifier = gutter)
    LiuliPersonaOperatorsGroup(
        operators = state.personaOperators,
        onChange = { ops -> onUpdate { it.copy(personaOperators = ops) } },
        modifier = gutter,
    )
    LiuliEditRelationshipQualityGroup(state = state, onUpdate = onUpdate, modifier = gutter)
    LiuliEditOfflineThemeGroup(
        selectedHex = state.offlineThemeColorHex,
        onSelect = { hex -> onUpdate { it.copy(offlineThemeColorHex = hex) } },
        modifier = gutter,
    )
    LiuliEditAdvancedGroup(state = state, onUpdate = onUpdate, modifier = gutter)
    LiuliEditVoiceGroup(
        state = state,
        systemVoices = systemVoices,
        previewBusy = previewBusy,
        previewError = previewError,
        onLoadSystemVoices = onLoadSystemVoices,
        onPreview = onPreviewVoice,
        onUpdate = onUpdate,
        // 深链定位：量语音组在滚动内容里的 y（同暖陶 `positionInParent`·页壳一次性 animateScrollTo）。
        modifier = gutter.onGloballyPositioned { onVoiceSectionY(it.positionInParent().y.roundToInt()) },
    )
    if (isEditing) {
        LiuliEditModulesGroup(
            hasOverride = hasModuleOverride,
            onSetOverride = onSetModuleOverride,
            onEditModules = onEditModules,
            modifier = gutter,
        )
    }
    Box(gutter) { worldGroup() }
    worldBookGroup?.let { Box(gutter) { it() } }
    if (isEditing) LiuliEditMeetingsGroup(onOpenMeetings = onOpenMeetings, modifier = gutter)
}
