package com.situ.aichat.ui.liuli.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.chat.ChatListScreen
import com.situ.aichat.ui.contacts.ContactsScreen
import com.situ.aichat.ui.designsystem.AppBottomNav
import com.situ.aichat.ui.designsystem.AppBottomNavItem
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin
import com.situ.aichat.ui.moments.MomentsHubScreen
import com.situ.aichat.ui.profile.ProfileScreen

/**
 * 主页四页 + 底栏的**选脸包装**（图纸 2026-09-06 卷三 A-1）。
 *
 * 每个包装的签名与暖陶屏**完全相同**，`AIChatApp` 的四处调用只换函数名、实参一字不动——选脸的 `if` 收在
 * 这里（与卷二A 聊天屏选脸同一口径），`AIChatApp.kt` 自 C1 之后零 diff。
 *
 * `bottomContentPadding` 只喂暖陶屏：琉璃底栏是自己的一枚玻璃胶囊，列表底留白走
 * [LiuliHomeGeometry.listBottomInset]（90 = 12 + 66 + 12·§4.7 ④）。
 */
@Composable
fun SkinnedChatListScreen(
    onOpenChat: (String) -> Unit,
    onCreateCharacter: () -> Unit,
    bottomContentPadding: Dp = 0.dp,
) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliChatListScreen(onOpenChat = onOpenChat, onCreateCharacter = onCreateCharacter)
        return
    }
    ChatListScreen(onOpenChat = onOpenChat, onCreateCharacter = onCreateCharacter, bottomContentPadding = bottomContentPadding)
}

@Composable
fun SkinnedContactsScreen(
    onOpenChat: (String) -> Unit,
    onCreateCharacter: () -> Unit,
    onEditCharacter: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    bottomContentPadding: Dp = 0.dp,
) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliContactsScreen(onOpenChat, onCreateCharacter, onEditCharacter, onOpenProfile)
        return
    }
    ContactsScreen(
        onOpenChat = onOpenChat,
        onCreateCharacter = onCreateCharacter,
        onEditCharacter = onEditCharacter,
        onOpenProfile = onOpenProfile,
        bottomContentPadding = bottomContentPadding,
    )
}

@Composable
fun SkinnedMomentsHubScreen(
    onOpenFeed: () -> Unit,
    onOpenDiary: () -> Unit,
    onOpenStory: () -> Unit,
    onOpenOurDays: () -> Unit,
    onOpenWorld: () -> Unit,
    bottomContentPadding: Dp = 0.dp,
    onOpenPet: (String) -> Unit = {},
    onOpenPetHub: () -> Unit = {},
) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliMomentsHubScreen(
            onOpenFeed = onOpenFeed,
            onOpenDiary = onOpenDiary,
            onOpenStory = onOpenStory,
            onOpenOurDays = onOpenOurDays,
            onOpenWorld = onOpenWorld,
            onOpenPet = onOpenPet,
            onOpenPetHub = onOpenPetHub,
        )
        return
    }
    MomentsHubScreen(
        onOpenFeed = onOpenFeed,
        onOpenDiary = onOpenDiary,
        onOpenStory = onOpenStory,
        onOpenOurDays = onOpenOurDays,
        onOpenWorld = onOpenWorld,
        bottomContentPadding = bottomContentPadding,
        onOpenPet = onOpenPet,
        onOpenPetHub = onOpenPetHub,
    )
}

@Composable
fun SkinnedProfileScreen(
    onEditProfile: () -> Unit,
    onOpenUserMoments: () -> Unit,
    onOpenUserWallet: () -> Unit,
    onOpenGiftShop: () -> Unit,
    onOpenGiftBox: () -> Unit,
    onOpenSettings: () -> Unit,
    bottomContentPadding: Dp = 0.dp,
) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliProfileScreen(
            onEditProfile = onEditProfile,
            onOpenUserMoments = onOpenUserMoments,
            onOpenUserWallet = onOpenUserWallet,
            onOpenGiftShop = onOpenGiftShop,
            onOpenGiftBox = onOpenGiftBox,
            onOpenSettings = onOpenSettings,
        )
        return
    }
    ProfileScreen(
        onEditProfile = onEditProfile,
        onOpenUserMoments = onOpenUserMoments,
        onOpenUserWallet = onOpenUserWallet,
        onOpenGiftShop = onOpenGiftShop,
        onOpenGiftBox = onOpenGiftBox,
        onOpenSettings = onOpenSettings,
        bottomContentPadding = bottomContentPadding,
    )
}

/**
 * 底栏选脸：暖陶 = [AppBottomNav]（一个像素不改）；琉璃 = [LiuliTabBar]。
 *
 * [opacity]（外观页「栏背景不透明度」）只喂暖陶——琉璃底栏是玻璃片、档位跟随「透明度」偏好（D-7）。
 */
@Composable
fun SkinnedBottomNav(items: List<AppBottomNavItem>, opacity: Float, chrome: LiuliHomeChrome) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliTabBar(items = items, chrome = chrome)
    } else {
        AppBottomNav(items = items, opacity = opacity)
    }
}
