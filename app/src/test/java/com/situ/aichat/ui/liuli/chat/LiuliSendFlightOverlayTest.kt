package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.ui.chat.ChatSendFlightState
import com.situ.aichat.ui.chat.FLIGHT_MS
import com.situ.aichat.ui.chat.PendingSendFlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-6 琉璃飞行渲染层（图纸 2026-09-05 卷二B §7）：起飞 → 落位 → 交还真行，外加 >10 行降级闸。
 *
 * 位置断言从**规格**反推，不照抄实现输出：飞行泡的帧矩形由 `flightFrame(起点, 终点, …)` 给，文字画在帧的
 * 内边距处——起点 16 / 10（输入胶囊）、终点 12 / 7（琉璃泡）。所以进度 0 时源文字左上恰是「起点 +(16,10)」、
 * 落地帧恰是「终点 +(12,7)」；这两个数一旦被改成别的内边距或别的曲线，用例即红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliSendFlightOverlayTest {

    @get:Rule
    val compose = createComposeRule()

    private val state = ChatSendFlightState(nowMs = { NOW })
    private val startRect = Rect(60f, 800f, 380f, 844f)
    private val targetRect = Rect(160f, 300f, 380f, 340f)

    private fun message(text: String) = MessageEntity(
        messageUUID = "m1",
        conversationUuid = "c",
        roleRaw = "user",
        content = text,
        timestamp = NOW,
    )

    /** 起飞先于组合 → 首帧即进度 0（避开「起飞那一帧」本身的调度不确定性）。 */
    private fun setFlying(text: String = "在吗") {
        compose.mainClock.autoAdvance = false
        state.inputBounds = startRect
        state.beginFlight(message(text), targetRect)
        compose.setContent { Box(Modifier.fillMaxSize()) { LiuliSendFlightOverlay(state) } }
        compose.mainClock.advanceTimeBy(1)
    }

    private fun copyTopLeft() =
        compose.onAllNodesWithText("在吗")[0].getUnclippedBoundsInRoot().let { it.left.value to it.top.value }

    @Test fun flightCopy_startsOnInputCapsule_landsOnBubble_thenHandsBackToRealRow() {
        setFlying()
        val (x0, y0) = copyTopLeft()
        assertEquals("进度 0：文字左缘 = 输入胶囊左 + 16dp 内边距", startRect.left + 16f, x0, 1f)
        assertEquals("进度 0：文字上缘 = 输入胶囊顶 + 10dp 内边距", startRect.top + 10f, y0, 1f)

        // 推到 250ms 线性进度的最后一帧：帧矩形 = 目标气泡，文字落在琉璃泡的 12 / 7 内边距处
        // （复制品此刻已画完最后一帧、finally 也已交还真行，节点要到下一帧才拆）。
        compose.mainClock.advanceTimeBy(FLIGHT_MS - 2L)
        val (x1, y1) = copyTopLeft()
        assertEquals("落地：文字左缘 = 目标气泡左 + 12dp 内边距", targetRect.left + 12f, x1, 1f)
        assertEquals("落地：文字上缘 = 目标气泡顶 + 7dp 内边距", targetRect.top + 7f, y1, 1f)
        assertNull("飞完必须交还真行（finally endFlight），否则真行永远 alpha 0", state.flight)

        compose.mainClock.advanceTimeBy(FRAME_MS)
        compose.onAllNodesWithText("在吗").assertCountEquals(0)
    }

    @Test fun flightCopy_leavesTheCapsuleButIsNotThereYetMidFlight() {
        setFlying()
        val (_, y0) = copyTopLeft()
        compose.mainClock.advanceTimeBy(FRAME_MS * 4)
        val (_, yMid) = copyTopLeft()
        // 目标在起点上方（列表在托盘之上），所以纵坐标必须单调变小、且中途还没到位。
        assertTrue("飞行中应已离开输入胶囊（y0=$y0 mid=$yMid）", yMid < y0)
        assertTrue("但中途还没落到目标（mid=$yMid target=${targetRect.top}）", yMid > targetRect.top)
    }

    @Test fun launchGate_letsTenLinesFly_butNotEleven() {
        compose.setContent { Box(Modifier.fillMaxSize()) { LiuliSendFlightOverlay(state) } }
        compose.waitForIdle()
        state.inputBounds = startRect
        val launch = requireNotNull(state.onLaunch) { "覆盖层必须装上 onLaunch（DisposableEffect）" }
        val pending = PendingSendFlight("x", NOW) {}

        // 硬换行撑行数（Robolectric 字形宽失真，靠折行不可靠）。
        compose.runOnUiThread { launch(message(lines(10)), targetRect, pending) }
        assertNotNull("10 行 = 闸上限，应当起飞", state.flight)
        compose.runOnUiThread { state.endFlight() }

        compose.runOnUiThread { launch(message(lines(11)), targetRect, pending) }
        assertNull("11 行越闸 → 不起飞，静默走普通入场（E13）", state.flight)
    }

    private fun lines(n: Int) = (1..n).joinToString("\n") { "一" }

    private companion object {
        const val NOW = 1_000L
        const val FRAME_MS = 16L
    }
}
