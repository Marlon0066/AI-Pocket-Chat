package com.situ.aichat.ui.liuli.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-2 琉璃图片泡（图纸 2026-09-05 卷二C §7 · E3）：文件确实没了 → 占位块上的「图片已失效」灰字；
 * 合并朗读句压成一个 Button 停；宽收口 = min(200, 气泡最大宽)。
 *
 * **偏差登记**（§11 D-3）：图纸 §5 E3 / §7 T2-2 写「点击只在 Ready」，而 §4.2 / §9 ④ 要求 F7 逐字
 * （暖陶三态都可点）。此处按机制锁落地 = 与暖陶同一个大脑，故本例钉的是「Missing 态仍可点」这一
 * **现行事实**，留复核裁决是否改成 Ready 门。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliImageBubbleTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)
    private var clicks = 0

    private fun setBubble(path: String?, isUser: Boolean = true, a11y: String? = "你在刚才说：[图片]") {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                LiuliImageBubble(
                    imagePath = path,
                    thumbnailPath = null,
                    isUser = isUser,
                    maxWidth = 300.dp,
                    timestampMs = 1_756_000_000_000L,
                    deliveryRead = null,
                    onClick = { clicks++ },
                    onLongClick = {},
                    a11yDescription = a11y,
                )
            }
        }
    }

    /**
     * 占位文案只在**没有合并朗读句**时才进语义树——有 cd 时整块被 `clearAndSetSemantics` 压成一个停
     * （F7 口径），拿 `onNodeWithText` 断言会恒空 = 假红。
     *
     * 缩略图经真后台线程读盘（`produceState`），断言自带的 `waitForIdle` 吃不住 → 必须显式 `waitUntil`
     * （PITFALLS §1e）。
     */
    @Test fun missingFile_showsQuietPlaceholderLabel() {
        setBubble("chat_images/does-not-exist.jpg", a11y = null)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("图片已失效").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("图片已失效").assertIsDisplayed()
    }

    @Test fun mergedSentence_isSingleStop() {
        setBubble("chat_images/does-not-exist.jpg")
        compose.onNodeWithContentDescription("你在刚才说：[图片]").assertIsDisplayed()
    }

    @Test fun click_routesThrough_perWarmClayMechanism() {
        setBubble("chat_images/does-not-exist.jpg")
        compose.waitForIdle()
        compose.onNodeWithContentDescription("你在刚才说：[图片]").performClick()
        assertEquals(1, clicks)
    }

    @Test fun widthCap_takesTheSmallerOfTwoHundredAndBubbleMax() {
        // 图片泡的宽收口与卡族同式（A-3）：200 是上限，窄屏由 bubbleMaxWidth 再压一道。
        assertEquals(200.dp, minOf(LiuliChatGeometry.imageMaxWidth, 300.dp))
        assertEquals(180.dp, minOf(LiuliChatGeometry.imageMaxWidth, 180.dp))
    }
}
