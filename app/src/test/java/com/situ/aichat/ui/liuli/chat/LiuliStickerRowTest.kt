package com.situ.aichat.ui.liuli.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.util.DateFormatters
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-3 琉璃纯贴纸行（图纸 2026-09-05 卷二C §7 · E4）：贴纸旁的小字时间戳、合并朗读句、长按转交菜单。
 *
 * 贴纸本体在 Robolectric 里解不出真图（无资源 / 无自定义库），`StickerImage` 会坍缩——故本例钉的是
 * **行的骨架**（戳 / 语义 / 长按），尺寸 110 由 T1-3 钉在 `LiuliChatGeometry.stickerSize` 上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliStickerRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)
    private var longClicks = 0
    private val stamp = 1_756_000_000_000L

    private fun setRow(isUser: Boolean, a11y: String? = "云野在刚才说：[表情包]") {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                LiuliStickerStack(
                    content = "[sticker:happy]",
                    customStickers = emptyList(),
                    isUser = isUser,
                    timestampMs = stamp,
                    deliveryRead = null,
                    onLongClick = { longClicks++ },
                    a11yDescription = a11y,
                )
            }
        }
    }

    @Test fun sideStamp_showsHourMinute() {
        setRow(isUser = false, a11y = null)
        compose.onNodeWithText(DateFormatters.hourMinute(stamp)).assertIsDisplayed()
    }

    @Test fun mergedSentence_replacesRawStickerTag() {
        setRow(isUser = false)
        compose.onNodeWithContentDescription("云野在刚才说：[表情包]").assertIsDisplayed()
    }

    @Test fun longPress_handsOverToImmersiveMenu() {
        setRow(isUser = true)
        compose.onNodeWithContentDescription("云野在刚才说：[表情包]").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(1, longClicks)
    }

    @Test fun stickerSize_isLiuliTier() {
        // 契约 §5.5：暖陶 120 → 琉璃 110（A-7）。
        assertEquals(110.dp, LiuliChatGeometry.stickerSize)
        assertEquals(24.dp, LiuliChatGeometry.stickerCorner)
    }
}
