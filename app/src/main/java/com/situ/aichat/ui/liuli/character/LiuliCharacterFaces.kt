package com.situ.aichat.ui.liuli.character

import androidx.compose.runtime.Composable
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.character.CharacterEditScreen
import com.situ.aichat.ui.character.CharacterProfileScreen
import com.situ.aichat.ui.character.MemoryEditScreen
import com.situ.aichat.ui.liuli.character.edit.LiuliCharacterEditScreen
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin

/**
 * 角色资料页的选脸点（图纸 2026-09-06 卷四 A-1）。与暖陶 `CharacterProfileScreen` **同 8 参**，
 * 两条分支实参逐字相同。
 */
@Composable
fun SkinnedCharacterProfileScreen(
    onBack: () -> Unit,
    onEditCharacter: (String) -> Unit,
    onOpenOfflineMeetings: (String) -> Unit,
    onOpenSchedule: (String) -> Unit,
    onOpenPromises: (String) -> Unit,
    onOpenStarfield: (String) -> Unit,
    onOpenOurDays: (String) -> Unit,
    onEditMemory: (String) -> Unit,
) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliCharacterProfileScreen(
            onBack = onBack,
            onEditCharacter = onEditCharacter,
            onOpenOfflineMeetings = onOpenOfflineMeetings,
            onOpenSchedule = onOpenSchedule,
            onOpenPromises = onOpenPromises,
            onOpenStarfield = onOpenStarfield,
            onOpenOurDays = onOpenOurDays,
            onEditMemory = onEditMemory,
        )
        return
    }
    CharacterProfileScreen(
        onBack = onBack,
        onEditCharacter = onEditCharacter,
        onOpenOfflineMeetings = onOpenOfflineMeetings,
        onOpenSchedule = onOpenSchedule,
        onOpenPromises = onOpenPromises,
        onOpenStarfield = onOpenStarfield,
        onOpenOurDays = onOpenOurDays,
        onEditMemory = onEditMemory,
    )
}

/** 角色编辑页选脸（卷五）：签名 = 暖陶 [CharacterEditScreen] 去掉 VM 默认形参（`LiuliSecondaryFacesTest` 钉）。 */
@Composable
fun SkinnedCharacterEditScreen(
    onCancel: () -> Unit,
    onSaved: (conversationUuid: String?) -> Unit,
    onEditModules: (characterUuid: String) -> Unit = {},
    onOpenOfflineMeetings: (characterUuid: String) -> Unit = {},
    onOpenWorldBooks: () -> Unit = {},
    focusVoiceSection: Boolean = false,
) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliCharacterEditScreen(onCancel, onSaved, onEditModules, onOpenOfflineMeetings, onOpenWorldBooks, focusVoiceSection)
        return
    }
    CharacterEditScreen(onCancel, onSaved, onEditModules, onOpenOfflineMeetings, onOpenWorldBooks, focusVoiceSection)
}

/** 记忆手动编辑页选脸。 */
@Composable
fun SkinnedMemoryEditScreen(onClose: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliMemoryEditScreen(onClose = onClose)
        return
    }
    MemoryEditScreen(onClose = onClose)
}
