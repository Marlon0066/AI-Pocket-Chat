package com.situ.aichat.ui.liuli.profile

import androidx.compose.runtime.Composable
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin
import com.situ.aichat.ui.profile.UserProfileEditScreen

/** 个人资料编辑选脸包装。 */
@Composable
fun SkinnedUserProfileEditScreen(onClose: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliUserProfileEditScreen(onClose = onClose)
        return
    }
    UserProfileEditScreen(onClose = onClose)
}
