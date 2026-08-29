package com.situ.aichat.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.ui.offline.OfflineReviewView

/**
 * 只读见面回顾覆盖层的宿主（自 [ChatScreen] **只搬不改**抽出——ChatScreen 已在 800 行绝对红线上，
 * 图片一期的接线不该由它继续背）。[info] 为 null 时什么都不画，与原先的 `?.let {}` 等价。
 */
@Composable
internal fun ChatOfflineReviewOverlay(
    info: String?,
    messages: List<MessageEntity>,
    characterName: String,
    avatarPath: String?,
    userName: String,
    userAvatarPath: String?,
    themeColorHex: String?,
    appSettings: AppSettings,
    chatWallpaperPath: String?,
    onBack: () -> Unit,
) {
    info ?: return
    BackHandler { onBack() }
    OfflineReviewView(
        messages = messages,
        meetingInfo = info,
        characterName = characterName.ifEmpty { "角色" },
        characterAvatarPath = avatarPath,
        userName = userName.ifEmpty { "你" },
        userAvatarPath = userAvatarPath,
        themeColorHex = themeColorHex,
        backgroundStyle = appSettings.offlineBackgroundStyleRaw,
        particleStyle = appSettings.offlineParticleStyleRaw,
        backgroundColor = appSettings.offlineBackgroundColor,
        chatWallpaperPath = chatWallpaperPath,
        onBack = onBack,
    )
}
