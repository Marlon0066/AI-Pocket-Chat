package com.situ.aichat.ui.liuli.promise

import androidx.compose.runtime.Composable
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin
import com.situ.aichat.ui.promise.PromiseLedgerScreen

/** 「我们的约定」选脸包装。 */
@Composable
fun SkinnedPromiseLedgerScreen(onBack: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliPromiseLedgerScreen(onBack = onBack)
        return
    }
    PromiseLedgerScreen(onBack = onBack)
}
