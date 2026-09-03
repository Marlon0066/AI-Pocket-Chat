package com.situ.aichat.seam

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.ui.chat.OfflineDividerReveal
import com.situ.aichat.ui.chat.OfflineEndDivider
import com.situ.aichat.ui.chat.rememberOfflineDividerReveal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 卷三 V3「落成」动画 T2（图纸 `docs/handoff/2026-08-27-线上线下衔接卷三-剧场收尾.md` §7 T2-2）：
 * 离场分隔条 [OfflineEndDivider] 的入场门控与两阶段时序。
 *
 * 时序用 [rememberOfflineDividerReveal] + compose `mainClock` **逐毫秒**实证（比图纸原口径「0ms 不可见 /
 * 1000ms 可见」更严）——落成揭示走 alpha / graphicsLayer，**不进语义树**，故 UI 层 `assertIsDisplayed` 对
 * 「已组合但 alpha=0」恒真、无法判可见性；把时序抽成 helper 后可直接断言状态翻转的毫秒位置。
 * UI 层则实证结构与交互：历史回看直显（E4）、无 sessionId 无详情行（E5）、落成全程点击进回顾可用（§4.1-C 锁）。
 *
 * ⚠️ 锁定值 500ms（线体）/ +240ms（字后到）/ 200ms（淡入）出自图纸 §4.1-C 与契约 §5①（落成 500–940ms）；
 * 改生产任一值必须同步本测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class OfflineEndDividerEntryTest {

    @get:Rule
    val compose = createComposeRule()

    private val lineText = "— 线下见面结束 · 约 42 分钟 —"
    private val captionText = "点击查看见面详情"

    // ── 时序（helper·逐毫秒） ──

    @Test
    fun `新到达时线体 500ms 揭示、字再延 240ms`() {
        compose.mainClock.autoAdvance = false
        var reveal by mutableStateOf(OfflineDividerReveal(lineRevealed = true, captionRevealed = true))
        compose.setContent { reveal = rememberOfflineDividerReveal(animate = true) }

        compose.mainClock.advanceTimeByFrame()
        val start = compose.mainClock.currentTime
        assertEquals(OfflineDividerReveal(lineRevealed = false, captionRevealed = false), reveal)

        // 逐帧推进到线体落成 → 落点应恰在 500ms 之后一帧内（帧量化 16ms·锁 500）。
        compose.mainClock.advanceTimeUntil(timeoutMillis = 5_000L) { reveal.lineRevealed }
        val lineAt = compose.mainClock.currentTime - start
        assertTrue("线体落成应在 500ms 后一帧内，实际 $lineAt ms", lineAt in 500L..516L)
        assertFalse("线体落成时「字」不得同帧到（锁 +240）", reveal.captionRevealed)

        // 再推进到「字后到」→ 落点应恰在 740ms（=500+240）之后一帧内。
        compose.mainClock.advanceTimeUntil(timeoutMillis = 5_000L) { reveal.captionRevealed }
        val captionAt = compose.mainClock.currentTime - start
        assertTrue("「字后到」应在 740ms 后一帧内，实际 $captionAt ms", captionAt in 740L..756L)
    }

    @Test
    fun `非新到达或减弱动画时两态初值即真且零延迟`() {
        compose.mainClock.autoAdvance = false
        var reveal by mutableStateOf(OfflineDividerReveal(lineRevealed = false, captionRevealed = false))
        compose.setContent { reveal = rememberOfflineDividerReveal(animate = false) }

        // 首帧即已落成（E1 减弱动画直切 / E4 历史回看直显 共用同一条路径）。
        compose.mainClock.advanceTimeByFrame()
        assertEquals(OfflineDividerReveal(lineRevealed = true, captionRevealed = true), reveal)
    }

    // ── 结构与交互（UI 层） ──

    @Test
    fun `历史回看两行直显`() {
        compose.setContent { OfflineEndDivider(durationText = "约 42 分钟", onClick = {}) }

        compose.onNodeWithText(lineText).assertIsDisplayed()
        compose.onNodeWithText(captionText).assertIsDisplayed()
    }

    @Test
    fun `落成走完后两行齐现`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OfflineEndDivider(durationText = "约 42 分钟", onClick = {}, entryAnimation = true)
        }

        compose.mainClock.advanceTimeBy(1_000L)
        compose.onNodeWithText(lineText).assertIsDisplayed()
        compose.onNodeWithText(captionText).assertIsDisplayed()
    }

    @Test
    fun `落成期间点击进回顾照样可用`() {
        var clicks = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OfflineEndDivider(durationText = "约 42 分钟", onClick = { clicks++ }, entryAnimation = true)
        }

        // 线体尚未落成（<500ms）时按下：结构恒占位 → 点击面全程在（§4.1-C 锁）。
        compose.mainClock.advanceTimeBy(100L)
        compose.onNodeWithText(lineText).performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `无 sessionId 时只有文案行没有详情行`() {
        compose.setContent {
            OfflineEndDivider(durationText = "约 42 分钟", onClick = null, entryAnimation = true)
        }

        compose.onNodeWithText(lineText).assertIsDisplayed()
        compose.onNodeWithText(captionText).assertDoesNotExist()
    }
}
