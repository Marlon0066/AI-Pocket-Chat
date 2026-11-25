package com.situ.aichat.ui.liuli.worldbook

import androidx.compose.runtime.Composable
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin
import com.situ.aichat.ui.worldbook.WorldBookDetailScreen
import com.situ.aichat.ui.worldbook.WorldBookEntryEditScreen
import com.situ.aichat.ui.worldbook.WorldBookSettingsScreen
import com.situ.aichat.ui.worldbook.WorldBookShelfScreen

/** 世界书设置页选脸包装。 */
@Composable
fun SkinnedWorldBookSettingsScreen(onBack: () -> Unit, onOpenMemorySettings: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliWorldBookSettingsScreen(onBack = onBack, onOpenMemorySettings = onOpenMemorySettings)
        return
    }
    WorldBookSettingsScreen(onBack = onBack, onOpenMemorySettings = onOpenMemorySettings)
}

/** 设定集书架选脸包装。 */
@Composable
fun SkinnedWorldBookShelfScreen(
    onBack: () -> Unit,
    onOpenBook: (bookUuid: String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliWorldBookShelfScreen(onBack = onBack, onOpenBook = onOpenBook, onOpenSettings = onOpenSettings)
        return
    }
    WorldBookShelfScreen(onBack = onBack, onOpenBook = onOpenBook, onOpenSettings = onOpenSettings)
}

/** 设定集书详情选脸包装。 */
@Composable
fun SkinnedWorldBookDetailScreen(
    onBack: () -> Unit,
    onOpenEntry: (entryUuid: String) -> Unit,
    onCreateEntry: (guideKey: String?) -> Unit,
) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliWorldBookDetailScreen(onBack = onBack, onOpenEntry = onOpenEntry, onCreateEntry = onCreateEntry)
        return
    }
    WorldBookDetailScreen(onBack = onBack, onOpenEntry = onOpenEntry, onCreateEntry = onCreateEntry)
}

/** 设定集条目编辑选脸包装。 */
@Composable
fun SkinnedWorldBookEntryEditScreen(onDone: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliWorldBookEntryEditScreen(onDone = onDone)
        return
    }
    WorldBookEntryEditScreen(onDone = onDone)
}
