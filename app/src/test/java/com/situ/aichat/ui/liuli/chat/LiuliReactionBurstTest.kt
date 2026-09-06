package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-7 表情回应徽章（图纸 2026-09-05 卷二B §7 · A-8「纯瞬态」）。
 *
 * **怎么数**：徽章与四颗小心整组挂在 `clearAndSetSemantics {}` 之下（纯装饰·对读屏隐形，见 §4.6），
 * 所以文字节点选不中——本件在语义树里留下的唯一痕迹，是**每条在场的爆点各一个空节点**。
 * 数根的子节点 = 数「几条泡正在回应」，正是这里要钉的事（只在被点那条、不叠第二枚）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliReactionBurstTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)
    private val state = LiuliReactionState()

    /** 同屏两条泡：一条被回应、一条不该被波及。 */
    private fun setRows(reduceMotion: Boolean) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                Box(Modifier.size(200.dp)) {
                    LiuliReactionBurst(state.burst, "m1", reduceMotion, Modifier.matchParentSize())
                }
                Box(Modifier.size(200.dp)) {
                    LiuliReactionBurst(state.burst, "m2", reduceMotion, Modifier.matchParentSize())
                }
            }
        }
        compose.waitForIdle()
    }

    private fun burstNodes() = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode().children.size

    // ── 状态语义（纯逻辑） ──────────────────────────────────────────────────────

    @Test fun state_carriesTargetAndEmoji_andBumpsTokenEveryTime() {
        assertEquals("初始无爆点", null, state.burst)
        state.play("m1", "❤️")
        val first = requireNotNull(state.burst)
        assertEquals("m1", first.messageUuid)
        assertEquals("❤️", first.emoji)
        state.play("m1", "❤️")
        assertNotEquals("同泡同表情再来一次也要换 token（重启而非叠加）", first.token, state.burst?.token)
    }

    @Test fun badge_neverReachesTheInBubbleTimestamp() {
        // 零重叠 ⑯（复核 R1 🔴-2）：徽章伸进泡内的部分 = 直径 − 横向外扩，不得超过泡的右内边距——
        // 泡内时间戳的右缘就落在那条内边距上，超过即压戳（装机实拍：旧值 6 压住末位约 7dp）。
        val intrusion = LiuliChatGeometry.reactionBadge - BADGE_OVERHANG_X
        assertTrue(
            "徽章伸进泡内 $intrusion 不得超过泡右内边距 $LiuliBubblePadEnd",
            intrusion <= LiuliBubblePadEnd,
        )
        assertTrue("徽章仍要搭在泡角上（伸进量 > 0），不是飘在泡外", intrusion > 0.dp)
    }

    @Test fun heartOffsets_areTheFourTabledPairs_andClampOutOfRange() {
        assertEquals((-14).dp to 52.dp, liuliHeartOffsets(0))
        assertEquals((-4).dp to 44.dp, liuliHeartOffsets(1))
        assertEquals(6.dp to 60.dp, liuliHeartOffsets(2))
        assertEquals(16.dp to 48.dp, liuliHeartOffsets(3))
        assertEquals("越界钳回表内，绝不崩", liuliHeartOffsets(0), liuliHeartOffsets(-1))
        assertEquals(liuliHeartOffsets(3), liuliHeartOffsets(9))
    }

    // ── 渲染（Robolectric） ────────────────────────────────────────────────────

    @Test fun idle_drawsNothing() {
        setRows(reduceMotion = true)
        assertEquals("没人回应时两条泡上都不该有东西", 0, burstNodes())
    }

    @Test fun play_marksOnlyTheTargetRow() {
        state.play("m1", "❤️")
        setRows(reduceMotion = true)
        assertEquals("只有被点那条泡上有爆点", 1, burstNodes())
    }

    @Test fun replay_onSameRow_doesNotStackASecondBadge() {
        state.play("m1", "❤️")
        setRows(reduceMotion = true)
        compose.runOnUiThread { state.play("m1", "❤️") }
        compose.waitForIdle()
        assertEquals("重复双击重启计时，绝不叠第二枚", 1, burstNodes())
    }

    @Test fun badge_letsGoAfterItsHold() {
        state.play("m1", "❤️")
        setRows(reduceMotion = true)
        assertEquals("前提：确实弹出来了", 1, burstNodes())
        // 纯瞬态：驻留期满自己散场，不留痕（时长常量本身由 badgeHold 一并钉）。
        compose.waitUntil(BADGE_WAIT_MS) { burstNodes() == 0 }
        assertEquals(0, burstNodes())
    }

    private companion object {
        const val BADGE_WAIT_MS = 5_000L
    }
}
