package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-9 琉璃右滑引用（图纸 2026-09-05 卷二B §7 · §4.8）：手势块是从暖陶**逐字搬**过来的，
 * 所以这里钉的是「阈值语义没被搬坏」——越 60dp 才触发、不到不触发、禁用态一律不触发。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliSwipeToReplyTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)
    private var triggered = 0

    private fun setBox(enabled: Boolean) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                LiuliSwipeToReplyBox(enabled = enabled, onTriggered = { triggered++ }) {
                    Box(Modifier.width(200.dp).height(48.dp)) { Text("被引用的话") }
                }
            }
        }
        compose.waitForIdle()
    }

    /** 分步拖动：一次性大跨度会被水平 slop 吃掉一截，分步推更接近真手指。 */
    private fun dragRight(totalDp: Float) {
        val px = with(compose.density) { totalDp.dp.toPx() }
        compose.onNodeWithText("被引用的话").performTouchInput {
            down(center)
            repeat(STEPS) { moveBy(Offset(px / STEPS, 0f)) }
            up()
        }
        compose.waitForIdle()
    }

    @Test fun pastThreshold_quotesOnce_withBothHaptics() {
        setBox(enabled = true)
        dragRight(100f)
        assertEquals("越 60dp 阈值 → 引用恰一次", 1, triggered)
        verify(atLeast = 1) { haptics.medium() } // 越阈预告
        verify(atLeast = 1) { haptics.light() } // 松手落定
    }

    @Test fun shortOfThreshold_doesNothing() {
        setBox(enabled = true)
        // 先证明手势真的动了（否则「零触发」毫无区分力）：滑到一半时气泡确实右移了。
        val before = compose.onNodeWithText("被引用的话").getUnclippedBoundsInRoot().left.value
        compose.onNodeWithText("被引用的话").performTouchInput {
            down(center)
            repeat(STEPS) { moveBy(Offset(with(compose.density) { 40.dp.toPx() } / STEPS, 0f)) }
        }
        val during = compose.onNodeWithText("被引用的话").getUnclippedBoundsInRoot().left.value
        compose.onNodeWithText("被引用的话").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals("前提：气泡确实被拖动了（before=$before during=$during）", true, during > before)
        assertEquals("没越 60dp → 不引用", 0, triggered)
    }

    @Test fun disabled_neverQuotes() {
        setBox(enabled = false)
        dragRight(100f)
        assertEquals("禁用态只关手势（结构恒定），一律不触发", 0, triggered)
    }

    private companion object {
        const val STEPS = 12
    }
}
