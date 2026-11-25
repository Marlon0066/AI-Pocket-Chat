package com.situ.aichat.ui.liuli.pet

import androidx.compose.runtime.Composable
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.liuli.designsystem.LocalAppSkin
import com.situ.aichat.ui.pet.PetAdoptionScreen
import com.situ.aichat.ui.pet.PetDetailScreen
import com.situ.aichat.ui.pet.PetInventoryScreen
import com.situ.aichat.ui.pet.PetListScreen
import com.situ.aichat.ui.pet.PetShopScreen

/** 宠物族选脸包装（5 屏：总览 / 详情 / 商店💰 / 背包 / 领养）。 */
@Composable
fun SkinnedPetListScreen(onOpenPet: (String) -> Unit, onBack: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliPetListScreen(onOpenPet = onOpenPet, onBack = onBack)
        return
    }
    PetListScreen(onOpenPet = onOpenPet, onBack = onBack)
}

@Composable
fun SkinnedPetDetailScreen(
    onBack: () -> Unit,
    onAdopt: () -> Unit,
    onOpenShop: () -> Unit,
    onOpenInventory: () -> Unit,
    pendingReaction: String? = null,
    onReactionConsumed: () -> Unit = {},
) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliPetDetailScreen(
            onBack = onBack,
            onAdopt = onAdopt,
            onOpenShop = onOpenShop,
            onOpenInventory = onOpenInventory,
            pendingReaction = pendingReaction,
            onReactionConsumed = onReactionConsumed,
        )
        return
    }
    PetDetailScreen(
        onBack = onBack,
        onAdopt = onAdopt,
        onOpenShop = onOpenShop,
        onOpenInventory = onOpenInventory,
        pendingReaction = pendingReaction,
        onReactionConsumed = onReactionConsumed,
    )
}

@Composable
fun SkinnedPetShopScreen(onClose: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliPetShopScreen(onClose = onClose)
        return
    }
    PetShopScreen(onClose = onClose)
}

@Composable
fun SkinnedPetInventoryScreen(onClose: () -> Unit, onReaction: (String) -> Unit = {}) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliPetInventoryScreen(onClose = onClose, onReaction = onReaction)
        return
    }
    PetInventoryScreen(onClose = onClose, onReaction = onReaction)
}

@Composable
fun SkinnedPetAdoptionScreen(onClose: () -> Unit) {
    if (LocalAppSkin.current == AppSkin.LIULI) {
        LiuliPetAdoptionScreen(onClose = onClose)
        return
    }
    PetAdoptionScreen(onClose = onClose)
}
