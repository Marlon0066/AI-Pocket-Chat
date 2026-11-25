package com.situ.aichat.ui.liuli.schedule

import androidx.compose.runtime.Composable
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin
import com.situ.aichat.ui.schedule.ScheduleFullDayScreen

/** 全天行程选脸包装。 */
@Composable
fun SkinnedScheduleFullDayScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliScheduleFullDayScreen(onBack = onBack, onOpenChat = onOpenChat)
        return
    }
    ScheduleFullDayScreen(onBack = onBack, onOpenChat = onOpenChat)
}
