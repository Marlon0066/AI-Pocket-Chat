package com.situ.aichat.ui.liuli.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.model.GlassTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2：玻璃基建的宿主契约（图纸 2026-09-04-琉璃第二张脸-卷一 §7 T2-5 · E10/E11）。
 *
 * 钉三件：① `content` + `overlay` 两个槽同时渲染且玻璃片不吃掉内容；② [BackdropState.invalidate] 让 `tick`
 * 单调 +1（玻璃片靠读 tick 重画，掉了就是「身后动了玻璃不跟」）；③ **玻璃片用在宿主之外不崩**——`LocalBackdrop`
 * 为 null 时退化成纯染色 + 迎光 + 发丝（卷二起玻璃件会被搬到各种上下文，这条是它们的安全网）。
 *
 * 同包故可读 `internal` 的 [BackdropState.tick]。真机尺寸 qualifiers 防节点被推出可视区（PITFALLS §1e）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class BackdropHostTest {

    @get:Rule
    val compose = createComposeRule()

    @Test fun host_rendersContentAndOverlayTogether() {
        compose.setContent {
            BackdropHost(
                modifier = Modifier.fillMaxSize(),
                content = { Text("身后内容") },
                overlay = {
                    Box(Modifier.size(60.dp).liuliGlass(CircleShape, dark = false, tier = GlassTier.CLEAR)) {
                        Text("玻璃上的字")
                    }
                },
            )
        }
        compose.onNodeWithText("身后内容").assertIsDisplayed()
        compose.onNodeWithText("玻璃上的字").assertIsDisplayed()
    }

    @Test fun invalidate_bumpsTickMonotonically() {
        lateinit var state: BackdropState
        compose.setContent {
            state = rememberBackdropState()
            BackdropHost(
                state = state,
                modifier = Modifier.fillMaxSize(),
                content = { Text("身后内容") },
                overlay = {},
            )
        }
        compose.waitForIdle()
        // content 画过帧后 tick 已 > 0（宿主自己也在推）；从当下值起算三次显式失效。
        val before = state.tick
        state.invalidate()
        state.invalidate()
        state.invalidate()
        assertEquals(before + 3, state.tick)
        assertTrue("tick 必须单调不回退", state.tick > before)
    }

    @Test fun glass_outsideHost_degradesWithoutCrashing() {
        compose.setContent {
            // 没有 BackdropHost：LocalBackdrop == null → 只染色 + 迎光 + 发丝。
            Box(Modifier.size(80.dp).liuliGlass(CircleShape, dark = true, tier = GlassTier.TINTED)) {
                Text("无宿主也要显示")
            }
        }
        compose.onNodeWithText("无宿主也要显示").assertIsDisplayed()
    }
}
