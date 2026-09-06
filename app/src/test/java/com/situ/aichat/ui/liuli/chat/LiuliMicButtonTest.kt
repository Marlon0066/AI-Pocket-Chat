package com.situ.aichat.ui.liuli.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
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
 * T2-5 「按住说话」键的两道拦截（图纸 2026-09-05 卷二A §7 · E9）：带引用时按下即拦、无权限时先申请，
 * 两者都**不进录音**。这两条走的是手势块最前面的两个分支，不依赖 50ms 防抖计时。
 *
 * 防抖之后的「真开录」一档只锁常量（[VOICE_DEBOUNCE_MS] 由本文件与暖陶两侧同值），实跑交装机——
 * `pointerInput` 里的 `withTimeoutOrNull` 走协程延时，compose 测试的两条常规推钟法都推不动它
 * （PITFALLS §1e 引用一期 Q4 的同一条坑），硬推只会得到「不报错、状态不变」的假结论。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliMicButtonTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)

    private var blockedCount = 0
    private var permissionRequests = 0
    private var startCount = 0
    private var finishCount = 0

    private fun setButton(blocked: Boolean, hasPermission: Boolean) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                LiuliMicButton(
                    hasMicPermission = hasPermission,
                    onRequestPermission = { permissionRequests++ },
                    blocked = blocked,
                    onBlocked = { blockedCount++ },
                    onStartRecording = { startCount++ },
                    onDrag = {},
                    onFinish = { finishCount++ },
                    recording = false,
                    cancelling = false,
                    reduceMotion = true,
                )
            }
        }
    }

    private fun pressAndRelease() {
        compose.onNodeWithContentDescription("按住录音").performTouchInput {
            down(center)
            up()
        }
        compose.waitForIdle()
    }

    @Test fun blocked_reportsBlock_andNeverRecords() {
        setButton(blocked = true, hasPermission = true)
        pressAndRelease()
        assertEquals("带引用时按下即拦（引用一期 E·D-1「意图那一刻」）", 1, blockedCount)
        assertEquals("被拦下就绝不开录", 0, startCount)
        assertEquals("也不该走收尾", 0, finishCount)
        assertEquals("更不该顺手去要麦克风权限（拦截排在权限分支之前）", 0, permissionRequests)
    }

    @Test fun missingPermission_asksFirst_andNeverRecords() {
        setButton(blocked = false, hasPermission = false)
        pressAndRelease()
        assertEquals(1, permissionRequests)
        assertEquals("本次手势作废、不录", 0, startCount)
        assertEquals(0, finishCount)
    }

    @Test fun quickTap_withPermission_doesNotRecord() {
        // 50ms 防抖：按下即松 = 快速点放，不录（这一条不需要推钟——松手发生在超时之前）。
        setButton(blocked = false, hasPermission = true)
        pressAndRelease()
        assertEquals("快速点放不开录", 0, startCount)
        assertEquals(0, blockedCount)
        assertEquals(0, permissionRequests)
    }

    @Test fun debounceConstant_matchesWarmClay() {
        // 与暖陶 `VoiceInputComposer` 的同名 private 常量同值（那边不可见，此处锁自己这一份）。
        assertEquals(50L, VOICE_DEBOUNCE_MS)
    }
}
