package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 卡内钮触达（卷二C 复核 R1 🔴-1 · REDLINES「a11y 48dp」）：三种钮的**版位**仍是对版稿的 34（钮行几何不动），
 * **点击面**各 ≥ 48 且上下居中外溢；落在外溢带里的一指也算点中。
 *
 * 期望值从规格反推：卡只装一行钮 → 卡高 = 34 + 脚底 12 = 46；触达 48 = REDLINES 落值，不读实现常量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliCardButtonFootprintTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)
    private var taps = 0

    private fun setCard() {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                Box(Modifier.testTag("card")) {
                    LiuliCard(width = 236.dp) {
                        LiuliCardButtonRow {
                            LiuliCardButton(text = "好呀", prominent = true, onClick = { taps++ })
                            LiuliCardButton(text = "换个时间", prominent = false, onClick = {})
                            LiuliCardTextButton(text = "先不约", onClick = {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun buttons_have48dpTouchHeight_whileTheRowStays34() {
        setCard()
        val card = compose.onNodeWithTag("card").getUnclippedBoundsInRoot()
        assertEquals("版位不长：卡高 = 钮 34 + 脚底 12", 46f, (card.bottom - card.top).value, 0.5f)
        listOf("好呀", "换个时间", "先不约").forEach { label ->
            val b = compose.onNodeWithText(label).getUnclippedBoundsInRoot()
            assertTrue("「$label」触达高 ${b.bottom - b.top} 应 ≥ 48", (b.bottom - b.top).value >= 47.5f)
            assertTrue("「$label」触达框应上下居中外溢（顶 ${b.top} 应高于卡顶 ${card.top}）", b.top.value < card.top.value)
        }
    }

    @Test fun tapInsideTheOverflowBand_stillFires() {
        setCard()
        // 点击面顶沿往下 2dp = 视觉 34 之外、触达 48 之内的那条带。
        compose.onNodeWithText("好呀").performTouchInput { click(Offset(centerX, 2f)) }
        compose.waitForIdle()
        assertEquals("外溢带里的一指也算点中", 1, taps)
    }
}
