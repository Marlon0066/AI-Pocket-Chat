package com.situ.aichat.ui.liuli

import com.situ.aichat.pet.PetItemCatalog
import com.situ.aichat.pet.PetItemKind
import com.situ.aichat.ui.pet.PetBuyState
import com.situ.aichat.ui.pet.petBuyStateOf
import com.situ.aichat.ui.worldbook.formatCharCount
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 步骤 4（批次 A–D 全部 22 屏）双脸选脸包装的签名与纯逻辑合同测试：
 * 1) 每个新增 `Skinned*` 包装声明的参数个数 = 暖陶屏参数个数 − 1（默认 VM 形参），防漏接回调。
 * 2) 关键纯逻辑辅助函数行为不变（`petBuyStateOf`、`formatCharCount`）。
 */
class Step4SkinnedDispatchTest {

    private fun declaredParams(className: String, method: String): Int {
        val clazz = Class.forName(className)
        val fn = clazz.declaredMethods.firstOrNull { it.name == method }
            ?: error("$className 里找不到 $method")
        val composer = fn.parameterTypes.indexOfFirst { it.name == "androidx.compose.runtime.Composer" }
        return if (composer >= 0) composer else fn.parameterTypes.size
    }

    @Test fun batchA_walletAndGiftFacesMatchSignatures() {
        assertEquals(
            declaredParams("com.situ.aichat.ui.wallet.UserWalletScreenKt", "UserWalletScreen") - 1,
            declaredParams("com.situ.aichat.ui.liuli.wallet.LiuliWalletFacesKt", "SkinnedUserWalletScreen"),
        )
        assertEquals(
            declaredParams("com.situ.aichat.ui.gift.GiftShopScreenKt", "GiftShopScreen") - 1,
            declaredParams("com.situ.aichat.ui.liuli.gift.LiuliGiftFacesKt", "SkinnedGiftShopScreen"),
        )
        assertEquals(
            declaredParams("com.situ.aichat.ui.gift.GiftBoxScreenKt", "GiftBoxScreen") - 1,
            declaredParams("com.situ.aichat.ui.liuli.gift.LiuliGiftFacesKt", "SkinnedGiftBoxScreen"),
        )
        assertEquals(
            declaredParams("com.situ.aichat.ui.gift.GiftReactionScreenKt", "GiftReactionScreen") - 1,
            declaredParams("com.situ.aichat.ui.liuli.gift.LiuliGiftFacesKt", "SkinnedGiftReactionScreen"),
        )
        assertEquals(
            declaredParams("com.situ.aichat.ui.gift.ReceivedGiftDetailScreenKt", "ReceivedGiftDetailScreen") - 1,
            declaredParams("com.situ.aichat.ui.liuli.gift.LiuliGiftFacesKt", "SkinnedReceivedGiftDetailScreen"),
        )
    }

    @Test fun batchB_petFacesMatchSignatures() {
        assertEquals(
            declaredParams("com.situ.aichat.ui.pet.PetListScreenKt", "PetListScreen") - 1,
            declaredParams("com.situ.aichat.ui.liuli.pet.LiuliPetFacesKt", "SkinnedPetListScreen"),
        )
        assertEquals(
            declaredParams("com.situ.aichat.ui.pet.PetDetailScreenKt", "PetDetailScreen") - 1,
            declaredParams("com.situ.aichat.ui.liuli.pet.LiuliPetFacesKt", "SkinnedPetDetailScreen"),
        )
        assertEquals(
            declaredParams("com.situ.aichat.ui.pet.PetShopScreenKt", "PetShopScreen") - 1,
            declaredParams("com.situ.aichat.ui.liuli.pet.LiuliPetFacesKt", "SkinnedPetShopScreen"),
        )
        assertEquals(
            declaredParams("com.situ.aichat.ui.pet.PetInventoryScreenKt", "PetInventoryScreen") - 1,
            declaredParams("com.situ.aichat.ui.liuli.pet.LiuliPetFacesKt", "SkinnedPetInventoryScreen"),
        )
        assertEquals(
            declaredParams("com.situ.aichat.ui.pet.PetAdoptionScreenKt", "PetAdoptionScreen") - 1,
            declaredParams("com.situ.aichat.ui.liuli.pet.LiuliPetFacesKt", "SkinnedPetAdoptionScreen"),
        )
    }

    @Test fun batchC_companionAndProfileFacesMatchSignatures() {
        assertEquals(
            declaredParams("com.situ.aichat.ui.profile.UserProfileEditScreenKt", "UserProfileEditScreen") - 1,
            declaredParams("com.situ.aichat.ui.liuli.profile.LiuliUserProfileFacesKt", "SkinnedUserProfileEditScreen"),
        )
        assertEquals(
            declaredParams("com.situ.aichat.ui.character.MemoryEditScreenKt", "MemoryEditScreen") - 1,
            declaredParams("com.situ.aichat.ui.liuli.character.LiuliCharacterFacesKt", "SkinnedMemoryEditScreen"),
        )
        assertEquals(
            declaredParams("com.situ.aichat.ui.offline.OfflineMeetingMemoryScreenKt", "OfflineMeetingMemoryScreen") - 1,
            declaredParams("com.situ.aichat.ui.liuli.offline.LiuliOfflineFacesKt", "SkinnedOfflineMeetingMemoryScreen"),
        )
        assertEquals(
            declaredParams("com.situ.aichat.ui.promise.PromiseLedgerScreenKt", "PromiseLedgerScreen") - 1,
            declaredParams("com.situ.aichat.ui.liuli.promise.LiuliPromiseFacesKt", "SkinnedPromiseLedgerScreen"),
        )
        assertEquals(
            declaredParams("com.situ.aichat.ui.schedule.ScheduleFullDayScreenKt", "ScheduleFullDayScreen") - 1,
            declaredParams("com.situ.aichat.ui.liuli.schedule.LiuliScheduleFacesKt", "SkinnedScheduleFullDayScreen"),
        )
        assertEquals(
            declaredParams("com.situ.aichat.ui.ourdays.OurDaysScreenKt", "OurDaysScreen") - 1,
            declaredParams("com.situ.aichat.ui.liuli.ourdays.LiuliOurDaysFacesKt", "SkinnedOurDaysScreen"),
        )
        assertEquals(
            declaredParams("com.situ.aichat.ui.ourdays.OurDayPageScreenKt", "OurDayPageScreen") - 1,
            declaredParams("com.situ.aichat.ui.liuli.ourdays.LiuliOurDaysFacesKt", "SkinnedOurDayPageScreen"),
        )
    }

    @Test fun batchD_worldBookAndStickerFacesMatchSignatures() {
        assertEquals(
            declaredParams("com.situ.aichat.ui.worldbook.WorldBookShelfScreenKt", "WorldBookShelfScreen") - 1,
            declaredParams("com.situ.aichat.ui.liuli.worldbook.LiuliWorldBookFacesKt", "SkinnedWorldBookShelfScreen"),
        )
        assertEquals(
            declaredParams("com.situ.aichat.ui.worldbook.WorldBookDetailScreenKt", "WorldBookDetailScreen") - 1,
            declaredParams("com.situ.aichat.ui.liuli.worldbook.LiuliWorldBookFacesKt", "SkinnedWorldBookDetailScreen"),
        )
        assertEquals(
            declaredParams("com.situ.aichat.ui.worldbook.WorldBookEntryEditScreenKt", "WorldBookEntryEditScreen") - 1,
            declaredParams("com.situ.aichat.ui.liuli.worldbook.LiuliWorldBookFacesKt", "SkinnedWorldBookEntryEditScreen"),
        )
        assertEquals(
            declaredParams("com.situ.aichat.ui.sticker.StickerManagementScreenKt", "StickerManagementScreen") - 1,
            declaredParams("com.situ.aichat.ui.liuli.sticker.LiuliStickerFacesKt", "SkinnedStickerManagementScreen"),
        )
        assertEquals(
            declaredParams("com.situ.aichat.ui.sticker.StickerImportScreenKt", "StickerImportScreen") - 1,
            declaredParams("com.situ.aichat.ui.liuli.sticker.LiuliStickerFacesKt", "SkinnedStickerImportScreen"),
        )
    }

    @Test fun sharedPureHelpersKeepExactBehavior() {
        val equippable = PetItemCatalog.allItems.first { it.kind == PetItemKind.EQUIPPABLE }
        assertEquals(PetBuyState.AVAILABLE, petBuyStateOf(equippable, balance = equippable.price, ownedEquippable = false))
        assertEquals(PetBuyState.ALREADY_OWNED, petBuyStateOf(equippable, balance = 0, ownedEquippable = true))
        assertEquals(PetBuyState.INSUFFICIENT, petBuyStateOf(equippable, balance = equippable.price - 1, ownedEquippable = false))
        assertEquals("999", formatCharCount(999))
        assertEquals("1.5万", formatCharCount(15_000))
    }
}
