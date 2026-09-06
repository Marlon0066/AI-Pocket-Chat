package com.situ.aichat.ui.liuli.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-8：「我」页（图纸 2026-09-06 卷三 §7 T2-8 · §4.6 · E10）。
 *
 * 无 VM——直接驱动 [LiuliProfileContent]。钉：空昵称的回退文案、三列统计的数字与单位、
 * **`currencyEnabled` 两分支各自的卡**（关 = 全宽动态卡、开 = 两资产格 + 礼物条）、礼物盒空 / 有两文案、
 * 设置条副标原字、各卡回调恰一次。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliProfileContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val taps = mutableListOf<String>()

    private fun show(
        profile: UserProfileEntity? = UserProfileEntity(nickname = "小满", bio = "在写代码"),
        currencyEnabled: Boolean = true,
        momentsCount: Int = 3,
        coinBalance: Int = 92,
        receivedGiftsCount: Int = 0,
        charactersCount: Int = 2,
        companionDays: Int? = 30,
        memoriesCount: Int = 5,
    ) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    LiuliProfileContent(
                        profile = profile,
                        currencyEnabled = currencyEnabled,
                        momentsCount = momentsCount,
                        coinBalance = coinBalance,
                        receivedGiftsCount = receivedGiftsCount,
                        charactersCount = charactersCount,
                        companionDays = companionDays,
                        memoriesCount = memoriesCount,
                        giftCatalogCount = 46,
                        onEditProfile = { taps += "edit" },
                        onOpenUserMoments = { taps += "moments" },
                        onOpenUserWallet = { taps += "wallet" },
                        onOpenGiftShop = { taps += "shop" },
                        onOpenGiftBox = { taps += "box" },
                        onOpenSettings = { taps += "settings" },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun 空昵称回退到设置你的资料() {
        show(profile = UserProfileEntity(nickname = "", bio = ""))
        compose.onNodeWithText("设置你的资料").assertIsDisplayed()
        // 空昵称 + 无头像：走人形线稿，不许出现 `CharacterAvatar` 空名分支的那个「·」（R1 🟡-5）。
        compose.onNodeWithText("·").assertDoesNotExist()
    }

    @Test fun 有昵称无头像时头像圆里是首字() {
        show(profile = UserProfileEntity(nickname = "小满", bio = ""))
        compose.onNodeWithText("小").assertIsDisplayed()
    }

    /** 按 `onClickLabel` 找整卡节点（卡的点击面 = 卡本身·§3.2「卡片」）。 */
    private fun cardWithClickLabel(label: String) = compose.onNode(
        SemanticsMatcher("clickLabel = $label") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label },
    )

    @Test fun 身份卡与下方资产格左右缘对齐() {
        // §3.2「我页身份卡」内距 20 是**卡内**的事，卡的外缘仍在屏 gutter 20 上，与两资产格左右对齐（R1 🔴-2：
        // 施工把 20 − 16 = 4 垫在了卡外，身份卡整体内缩 4dp、微光也画到了卡外）。
        show()
        val hero = cardWithClickLabel("编辑").getUnclippedBoundsInRoot()
        val moments = cardWithClickLabel("我的动态").getUnclippedBoundsInRoot()
        val wallet = cardWithClickLabel("我的钱包").getUnclippedBoundsInRoot()
        assertEquals("身份卡左缘 = 动态格左缘", moments.left.value, hero.left.value, 0.01f)
        assertEquals("身份卡右缘 = 钱包格右缘", wallet.right.value, hero.right.value, 0.01f)
    }

    @Test fun 三列统计的数字与单位都在() {
        show()
        compose.onNodeWithText("30").assertIsDisplayed()
        compose.onNodeWithText("一起走过").assertIsDisplayed()
        compose.onNodeWithText("天").assertIsDisplayed()
    }

    @Test fun 货币开走两资产格加礼物条() {
        show(currencyEnabled = true)
        compose.onNodeWithText("我的钱包").assertIsDisplayed()
        compose.onNodeWithText("92").assertIsDisplayed()
        compose.onNodeWithText("金币").assertIsDisplayed()
        compose.onNodeWithText("46 款").assertIsDisplayed()
        compose.onNodeWithText("还没收到礼物").assertIsDisplayed()
    }

    @Test fun 货币关走全宽动态卡且不出现钱包() {
        show(currencyEnabled = false, momentsCount = 3)
        compose.onNodeWithText("我的钱包").assertDoesNotExist()
        compose.onNodeWithText("3 条 · 我发布的动态").assertIsDisplayed()
    }

    @Test fun 礼物盒有礼物时换成收到几件() {
        show(receivedGiftsCount = 4)
        compose.onNodeWithText("收到 4 件").assertIsDisplayed()
        compose.onNodeWithText("还没收到礼物").assertDoesNotExist()
    }

    @Test fun 动态为零时数字行换成引导句() {
        show(momentsCount = 0)
        compose.onNodeWithText("去发第一条动态吧").assertIsDisplayed()
    }

    @Test fun 设置条副标原字且各卡回调恰一次() {
        show()
        compose.onNodeWithText("个性化 · API · 聊天 · 记忆 · 语音 · 数据").assertIsDisplayed()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("我的钱包").performClick()
        compose.onNodeWithText("礼物店").performClick()
        compose.onNodeWithText("礼物盒").performClick()
        compose.waitForIdle()
        assertEquals(listOf("settings", "wallet", "shop", "box"), taps)
    }
}
