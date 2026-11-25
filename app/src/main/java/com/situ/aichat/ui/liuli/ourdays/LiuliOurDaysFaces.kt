package com.situ.aichat.ui.liuli.ourdays

import androidx.compose.runtime.Composable
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin
import com.situ.aichat.ui.ourdays.OurDayPageScreen
import com.situ.aichat.ui.ourdays.OurDaysScreen

/** 「我们的日子」两屏选脸包装。 */
@Composable
fun SkinnedOurDaysScreen(
    onBack: () -> Unit,
    onOpenDay: (characterUuid: String, dayKey: String) -> Unit,
) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliOurDaysScreen(onBack = onBack, onOpenDay = onOpenDay)
        return
    }
    OurDaysScreen(onBack = onBack, onOpenDay = onOpenDay)
}

@Composable
fun SkinnedOurDayPageScreen(
    onBack: () -> Unit,
    onOpenDay: (characterUuid: String, dayKey: String) -> Unit,
    onOpenMeetings: (String) -> Unit,
    onOpenPromises: (String) -> Unit,
    onOpenMoments: (String, String) -> Unit,
    onOpenDiary: (String) -> Unit,
    onOpenSchedule: (characterUuid: String, dayKey: String) -> Unit,
) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliOurDayPageScreen(
            onBack = onBack,
            onOpenDay = onOpenDay,
            onOpenMeetings = onOpenMeetings,
            onOpenPromises = onOpenPromises,
            onOpenMoments = onOpenMoments,
            onOpenDiary = onOpenDiary,
            onOpenSchedule = onOpenSchedule,
        )
        return
    }
    OurDayPageScreen(
        onBack = onBack,
        onOpenDay = onOpenDay,
        onOpenMeetings = onOpenMeetings,
        onOpenPromises = onOpenPromises,
        onOpenMoments = onOpenMoments,
        onOpenDiary = onOpenDiary,
        onOpenSchedule = onOpenSchedule,
    )
}
