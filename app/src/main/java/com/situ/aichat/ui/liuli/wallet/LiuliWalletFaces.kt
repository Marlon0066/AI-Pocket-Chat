package com.situ.aichat.ui.liuli.wallet

import androidx.compose.runtime.Composable
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin
import com.situ.aichat.ui.wallet.RedeemCodeScreen

/**
 * 兑换码页的选脸包装（图纸 2026-09-06 卷五 A-1）。💰 **只换壳**：
 * `RedeemCodeViewModel → RedeemCodeService` 全链零碰。琉璃版排在 C4。
 */
@Composable
fun SkinnedRedeemCodeScreen(onClose: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliRedeemCodeScreen(onClose = onClose)
        return
    }
    RedeemCodeScreen(onClose = onClose)
}
