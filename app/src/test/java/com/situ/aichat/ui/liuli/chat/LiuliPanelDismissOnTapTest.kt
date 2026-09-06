package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 复核 R1 🔴-1（图纸 2026-09-05 卷二B §4.4 · E9 第三路）：「面板开着点空白 = 缩回」必须**只吃点、不吃滚、不抢按钮**。
 *
 * 施工版把拦截层做成盖在列表上的兄弟 Box——装机实测：面板开着时列表滚不动，一次上滑还被当成「点」把面板收了。
 * 这里用一个真 `LazyColumn` 钉住三条语义：① 上滑 → 列表真的滚了、面板没收；② 点空白 → 收恰一次；
 * ③ 点在子项按钮上 → 按钮响应、面板不收（子项 clickable 先消费）；外加 ④ 不激活时点空白也不收。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliPanelDismissOnTapTest {

    @get:Rule
    val compose = createComposeRule()

    private var dismissed = 0
    private var childClicks = 0
    private lateinit var listState: LazyListState

    private fun setList(active: Boolean) {
        compose.setContent {
            listState = rememberLazyListState()
            Box(
                Modifier
                    .fillMaxSize()
                    .liuliDismissPanelOnTap(active = active, onTap = { dismissed++ }),
            ) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(ITEM_COUNT) { i ->
                        if (i == 0) {
                            Box(Modifier.fillMaxWidth().height(ROW_HEIGHT).clickable { childClicks++ }) { Text("按钮行") }
                        } else {
                            Box(Modifier.fillMaxWidth().height(ROW_HEIGHT)) { Text("第 $i 行") }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun swipe_scrollsTheListAndKeepsThePanel() {
        setList(active = true)
        compose.onNodeWithText("第 3 行").performTouchInput { swipeUp() }
        compose.waitForIdle()
        val scrolled = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        assertTrue("拖动必须落到列表上（index=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset}）", scrolled)
        assertEquals("拖动不是「点」，面板不收", 0, dismissed)
    }

    @Test fun tapOnBlank_dismissesExactlyOnce() {
        setList(active = true)
        compose.onNodeWithText("第 3 行").performClick()
        compose.waitForIdle()
        assertEquals("点空白 → 收面板恰一次", 1, dismissed)
    }

    @Test fun tapOnAChildButton_goesToTheButton_notToDismiss() {
        setList(active = true)
        compose.onNodeWithText("按钮行").performClick()
        compose.waitForIdle()
        assertEquals("子项按钮先消费 → 按钮响应", 1, childClicks)
        assertEquals("面板不因点到按钮而收", 0, dismissed)
    }

    @Test fun inactive_tapDoesNothing() {
        setList(active = false)
        compose.onNodeWithText("第 3 行").performClick()
        compose.waitForIdle()
        assertEquals("面板没开 / 菜单开着 → 不装手势", 0, dismissed)
    }

    private companion object {
        const val ITEM_COUNT = 60
        val ROW_HEIGHT = 48.dp
    }
}
