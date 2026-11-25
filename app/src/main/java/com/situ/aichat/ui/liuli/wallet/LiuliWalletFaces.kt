package com.situ.aichat.ui.liuli.wallet

import androidx.compose.runtime.Composable
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin
import com.situ.aichat.ui.wallet.RedeemCodeScreen
import com.situ.aichat.ui.wallet.UserWalletScreen

/**
 * 钱包族选脸包装（💰 只换壳，ViewModel / 记账 / 兑换码全链零碰）。
 */
@Composable
fun SkinnedUserWalletScreen(
    onBack: () -> Unit,
    onOpenGiftShop: () -> Unit,
    onOpenRedeemCode: () -> Unit,
) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliUserWalletScreen(
            onBack = onBack,
            onOpenGiftShop = onOpenGiftShop,
            onOpenRedeemCode = onOpenRedeemCode,
        )
        return
    }
    UserWalletScreen(
        onBack = onBack,
        onOpenGiftShop = onOpenGiftShop,
        onOpenRedeemCode = onOpenRedeemCode,
    )
}

@Composable
fun SkinnedRedeemCodeScreen(onClose: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliRedeemCodeScreen(onClose = onClose)
        return
    }
    RedeemCodeScreen(onClose = onClose)
}
