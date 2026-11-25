package com.situ.aichat.ui.liuli.gift

import androidx.compose.runtime.Composable
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.gift.GiftBoxScreen
import com.situ.aichat.ui.gift.GiftReactionScreen
import com.situ.aichat.ui.gift.GiftShopScreen
import com.situ.aichat.ui.gift.ReceivedGiftDetailScreen
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin

/** 礼物族选脸包装（💰 只换界面壳，扣币/礼物记录/反应逻辑全链零碰）。 */
@Composable
fun SkinnedGiftShopScreen(
    onClose: () -> Unit,
    onNavigateToReaction: (String) -> Unit,
) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliGiftShopScreen(onClose = onClose, onNavigateToReaction = onNavigateToReaction)
        return
    }
    GiftShopScreen(onClose = onClose, onNavigateToReaction = onNavigateToReaction)
}

@Composable
fun SkinnedGiftBoxScreen(
    onBack: () -> Unit,
    onOpenReaction: (String) -> Unit,
    onOpenReceived: (String) -> Unit,
) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliGiftBoxScreen(onBack = onBack, onOpenReaction = onOpenReaction, onOpenReceived = onOpenReceived)
        return
    }
    GiftBoxScreen(onBack = onBack, onOpenReaction = onOpenReaction, onOpenReceived = onOpenReceived)
}

@Composable
fun SkinnedGiftReactionScreen(
    isSendFlow: Boolean,
    onFinish: () -> Unit,
    onBack: () -> Unit,
) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliGiftReactionScreen(isSendFlow = isSendFlow, onFinish = onFinish, onBack = onBack)
        return
    }
    GiftReactionScreen(isSendFlow = isSendFlow, onFinish = onFinish, onBack = onBack)
}

@Composable
fun SkinnedReceivedGiftDetailScreen(onBack: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliReceivedGiftDetailScreen(onBack = onBack)
        return
    }
    ReceivedGiftDetailScreen(onBack = onBack)
}
