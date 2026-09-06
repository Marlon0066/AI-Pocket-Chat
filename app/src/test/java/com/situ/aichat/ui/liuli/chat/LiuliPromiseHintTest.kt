package com.situ.aichat.ui.liuli.chat

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
import com.situ.aichat.ui.chat.PromiseHint
import com.situ.aichat.ui.chat.TAG_CLOSE
import com.situ.aichat.ui.chat.TAG_UNDO
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
 * T3-2：琉璃版约定记账提示玻璃胶囊（图纸 2026-09-06 约定工具调用化 §4.2）。与暖陶版共用文案纯函数，
 * 故这里同样按**锁定文本**重新打字钉住；触达按 `liuliTouchHeight` 的 48dp 量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliPromiseHintTest {

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

    private fun show(initial: PromiseHint?): MutableState<PromiseHint?> {
        val state = mutableStateOf(initial)
        compose.setContent {
            AIPocketChatTheme(darkTheme = false) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    Box(Modifier.fillMaxSize()) {
                        LiuliPromiseHint(
                            hint = state.value,
                            topPadding = 0.dp,
                            reduceMotion = true,
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
        undo.assertHeightIsAtLeast(48.dp) // liuliTouchHeight 外溢
        undo.performClick()
        assertEquals("u1", undone)
        compose.onAllNodesWithTag(TAG_CLOSE, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun 兑现那条是关闭点_点击触发dismiss_且无不是约定() {
        show(hint(PromiseHint.Kind.FULFILLED))
        compose.onAllNodesWithText("不是约定", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag(TAG_CLOSE, useUnmergedTree = true).performClick()
        assertEquals(1, dismissed)
    }

    @Test fun 合并那条无不是约定钮_有关闭点() {
        show(hint(PromiseHint.Kind.MERGED, content = "", recorded = 1, fulfilled = 1))
        compose.onAllNodesWithText("不是约定", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag(TAG_CLOSE, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun 好当我没记那条两个动作面都没有() {
        show(hint(PromiseHint.Kind.UNDONE, content = ""))
        compose.onNodeWithText("好，当我没记").assertIsDisplayed() // 正向锚
        compose.onAllNodesWithTag(TAG_UNDO, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag(TAG_CLOSE, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun 整条点击打开约定账本页() {
        show(hint(PromiseHint.Kind.RECORDED, undoUuid = "u1"))
        // 点文字本身（未合并树）：Robolectric 中文字形宽失真会把合并节点中心推到右侧动作面上。
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
