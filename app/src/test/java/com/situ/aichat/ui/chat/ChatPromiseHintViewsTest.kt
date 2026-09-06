package com.situ.aichat.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T3-1：暖陶版约定记账提示胶囊（图纸 2026-09-06 约定工具调用化 §4.1）。文案是**锁定文本**（§9 ①），
 * 此处重新打字钉住；触达按 a11y 48dp 量（视觉 32 → `requiredHeight(48)` 外溢）。
 *
 * 两个动作面在合并语义树里读不出自己（整条一句播报是**有意**的），故用 testTag 取真件量高度与点击。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class ChatPromiseHintViewsTest {

    @get:Rule
    val compose = createComposeRule()

    private var undone: String? = null
    private var dismissed = 0
    private var opened = 0

    private fun hint(
        kind: PromiseHint.Kind,
        content: String = "一起去看画展",
        undoUuid: String? = null,
        recorded: Int = 0,
        fulfilled: Int = 0,
        cancelled: Int = 0,
    ) = PromiseHint(kind, content, undoUuid, recorded, fulfilled, cancelled, seq = 1L)

    /** 装一次内容，返回可改的 hint 态（同一条测试里换态用它，setContent 只准调一次）。 */
    private fun show(initial: PromiseHint?): MutableState<PromiseHint?> {
        val state = mutableStateOf(initial)
        compose.setContent {
            AIPocketChatTheme(darkTheme = false) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    Box(Modifier.fillMaxSize()) {
                        ChatPromiseHint(
                            hint = state.value,
                            reduceMotion = true, // 直显直隐 → 断言不与动画抢帧
                            onOpenLedger = { opened++ },
                            onUndo = { undone = it },
                            onDismiss = { dismissed++ },
                        )
                    }
                }
            }
        }
        return state
    }

    @Test fun 五态文案逐字() {
        val state = show(hint(PromiseHint.Kind.RECORDED, undoUuid = "u1"))
        compose.onNodeWithText("记下了 · 一起去看画展").assertIsDisplayed()

        state.value = hint(PromiseHint.Kind.FULFILLED)
        compose.onNodeWithText("说到做到 · 一起去看画展").assertIsDisplayed()

        state.value = hint(PromiseHint.Kind.CANCELLED)
        compose.onNodeWithText("不做了 · 一起去看画展").assertIsDisplayed()

        state.value = hint(PromiseHint.Kind.MERGED, content = "", recorded = 1, fulfilled = 1)
        compose.onNodeWithText("记下 1 条 · 兑现 1 条").assertIsDisplayed()

        state.value = hint(PromiseHint.Kind.UNDONE, content = "")
        compose.onNodeWithText("好，当我没记").assertIsDisplayed()
    }

    @Test fun 记下了这条有不是约定钮_点击带uuid回调_触达至少48dp() {
        show(hint(PromiseHint.Kind.RECORDED, undoUuid = "u1"))
        compose.onNodeWithText("不是约定", useUnmergedTree = true).assertIsDisplayed()
        val undo = compose.onNodeWithTag(TAG_UNDO, useUnmergedTree = true)
        undo.assertHeightIsAtLeast(48.dp) // 视觉 32 外溢到触达 48（a11y 硬门）
        undo.performClick()
        assertEquals("u1", undone)
        assertEquals("撤销那条不该有 × 钮", 0, compose.onAllNodesWithTag(TAG_CLOSE, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun 兑现那条是关闭钮_点击触发dismiss_且无不是约定() {
        show(hint(PromiseHint.Kind.FULFILLED))
        compose.onAllNodesWithText("不是约定", useUnmergedTree = true).assertCountEquals(0)
        val close = compose.onNodeWithTag(TAG_CLOSE, useUnmergedTree = true)
        close.assertHeightIsAtLeast(48.dp)
        close.performClick()
        assertEquals(1, dismissed)
    }

    @Test fun 合并那条无不是约定钮_有关闭钮() {
        show(hint(PromiseHint.Kind.MERGED, content = "", recorded = 1, fulfilled = 1))
        compose.onAllNodesWithText("不是约定", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag(TAG_CLOSE, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun 好当我没记那条两个动作面都没有() {
        show(hint(PromiseHint.Kind.UNDONE, content = ""))
        compose.onNodeWithText("好，当我没记").assertIsDisplayed() // 正向锚：这条真渲染了
        compose.onAllNodesWithTag(TAG_UNDO, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag(TAG_CLOSE, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun 整条点击打开约定账本页() {
        show(hint(PromiseHint.Kind.RECORDED, undoUuid = "u1"))
        // 点**文字本身**（未合并树）：Robolectric 的中文字形宽严重失真（≈0.76dp/字），合并节点的几何中心会落到
        // 右侧动作面上 → 点合并节点等于点「不是约定」，测不到整条点击这条路（PITFALLS §1e 字形宽失真）。
        compose.onNodeWithText("记下了 · 一起去看画展", useUnmergedTree = true).performClick()
        assertTrue("整条点击应触发打开账本 opened=$opened undone=$undone", opened >= 1)
        assertEquals("整条点击不该误触撤销", null, undone)
    }

    @Test fun hint为null时不渲染() {
        show(null)
        compose.onAllNodesWithText("记下了 · 一起去看画展").assertCountEquals(0)
        compose.onAllNodesWithTag(TAG_UNDO, useUnmergedTree = true).assertCountEquals(0)
    }
}
