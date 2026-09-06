package com.situ.aichat.ui.liuli.designsystem

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
import org.robolectric.annotation.GraphicsMode

/**
 * T2：琉璃按钮原语的行为面（图纸 2026-09-04-琉璃第二张脸-卷一 §7 T2-5 / §4.3）。
 *
 * 像素域（玻璃五要素、渐变角度、顶沿硬线、彩影）由 [LiuliGlassTest 家族] + 装机担保；本测钉行为：
 * 三档都渲染得出内容、点击回调各档都通、**disabled 结构恒定但不响应**（REDLINES §7：禁用态提前 return
 * 是动画杀手，所以节点必须还在、只是点不动）、触达 ≥48dp、圆钮的 contentDescription 进 a11y 树。
 *
 * 真机尺寸 qualifiers 防节点被推出可视区导致 `performClick` 静默不命中（PITFALLS §1e）；
 * [LocalAppHaptics] 无默认值须自己 provide。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiuliButtonTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    content()
                }
            }
        }
    }

    @Test fun 三档都渲染内容且可点() {
        val clicks = IntArray(3)
        show {
            androidx.compose.foundation.layout.Column {
                LiuliButton(onClick = { clicks[0]++ }, style = LiuliButtonStyle.Glass) { Text("玻璃") }
                LiuliButton(onClick = { clicks[1]++ }, style = LiuliButtonStyle.Prominent) { Text("保存") }
                LiuliButton(onClick = { clicks[2]++ }, style = LiuliButtonStyle.Text) { Text("取消") }
            }
        }
        listOf("玻璃", "保存", "取消").forEach { compose.onNodeWithText(it).assertIsDisplayed().assertHasClickAction() }
        compose.onNodeWithText("玻璃").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(1, clicks[0])
        assertEquals(1, clicks[1])
        assertEquals(1, clicks[2])
    }

    @Test fun 触达高度不低于48dp() {
        show { LiuliButton(onClick = {}, style = LiuliButtonStyle.Prominent) { Text("保存") } }
        compose.onNodeWithText("保存").assertHeightIsAtLeast(48.dp)
    }

    @Test fun disabled_节点还在但点不动() {
        var clicked = 0
        show { LiuliButton(onClick = { clicked++ }, enabled = false) { Text("不可点") } }
        // 结构恒定：禁用态**不是**把内容摘掉，节点必须还在（否则出场/退场动画会被切断）。
        compose.onNodeWithText("不可点").assertIsDisplayed()
        compose.onNodeWithText("不可点").performClick()
        assertEquals(0, clicked)
    }

    @Test fun 圆钮带contentDescription且可点() {
        var clicked = 0
        show { LiuliCircleButton(onClick = { clicked++ }, contentDescription = "返回") { Text("<") } }
        compose.onNodeWithContentDescription("返回").assertHasClickAction().performClick()
        assertEquals(1, clicked)
    }

    @Test fun 圆钮disabled不响应() {
        var clicked = 0
        show { LiuliCircleButton(onClick = { clicked++ }, contentDescription = "返回", enabled = false) { Text("<") } }
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(0, clicked)
    }

    // ── 禁用态「底也要发灰」的像素回归 ───────────────────────────────────────────────────────────

    /** 规格值：禁用态整片按 0.38 合成（`LiuliButton.DISABLED_ALPHA`）——此处**独立复写**，不 import 实现常量。 */
    private val specDisabledAlpha = 0.38f

    /**
     * 强制一次软件绘制并返回位图。底色刷**纯黑**：`Modifier.alpha` 是把整棵子树合成到底色上，
     * 黑底让 `合成值 = alpha × 启用值` 这条式子最干净、信号也最大（每通道差 ~135）。
     *
     * 为什么不是 `captureToImage()`：Robolectric 没有真 window、不跑 draw 相位，`captureToImage()` 会
     * 等绘制闩超时（`ComposeTimeoutException`，同 `LiuliHomeHostTest` 那条注释）。直接对 `AndroidComposeView`
     * 调 `draw(Canvas(bitmap))` 是同步软件光栅化，绕开闩；Compose 在 Robolectric 下走 ViewLayer 而非
     * RenderNode，所以 `graphicsLayer`（含 alpha）真的落到这张画布上。
     */
    private fun drawRootOnBlack(): Bitmap {
        compose.waitForIdle()
        val view = compose.onRoot().fetchSemanticsNode().root as View
        val bmp = Bitmap.createBitmap(
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        bmp.eraseColor(Color.BLACK)
        compose.runOnUiThread { view.draw(Canvas(bmp)) }
        return bmp
    }

    /**
     * 取该节点「底」的一个像素：横向 12% 处、纵向正中。三档的形（药丸 / 圆）在纵向正中都已到最宽，
     * 12% 既落在形内、又远在内容左侧（内容左内边距 18dp / 圆钮内容居中），量到的必是底不是字。
     */
    private fun fillPixel(bmp: Bitmap, node: SemanticsNodeInteraction): Int {
        val b = node.fetchSemanticsNode().boundsInRoot
        val x = (b.left + b.width * 0.12f).toInt()
        val y = ((b.top + b.bottom) / 2f).toInt()
        return bmp.getPixel(x, y)
    }

    /**
     * 钉死：禁用态那枚的**底色像素**必须 ≈ 启用态同点 × 0.38（黑底合成），而不是与启用态一模一样。
     *
     * 这条测的存在理由：`Modifier.alpha` 只淡化排在它之后（内层）的绘制。历史上 alpha 排在「画底」之后，
     * 于是禁用态只有字发灰、钴蓝实底仍满色，用户看不出钮按不动（卷五 C5 装机取证 `liuli_v5/07_api_config.png`）。
     * `assertIsNotEnabled()` / 点击不响应那类断言对此**完全没有判别力**——所以必须量像素。
     */
    private fun assertFillDimmed(bmp: Bitmap, on: SemanticsNodeInteraction, off: SemanticsNodeInteraction, tag: String) {
        val enabled = fillPixel(bmp, on)
        val disabled = fillPixel(bmp, off)
        val channels = listOf(
            "R" to Pair(Color.red(enabled), Color.red(disabled)),
            "G" to Pair(Color.green(enabled), Color.green(disabled)),
            "B" to Pair(Color.blue(enabled), Color.blue(disabled)),
        )
        // 防「两边都是黑 → 恒等式碰巧成立」的空转：启用态本身必须画出了够亮的底。
        assertTrue(
            "$tag 启用态底色太暗（${Integer.toHexString(enabled)}），这条测没量到东西",
            channels.all { it.second.first >= 60 },
        )
        channels.forEach { (name, v) ->
            val (e, d) = v
            val expected = (e * specDisabledAlpha).toInt()
            assertTrue(
                "$tag 通道 $name：禁用态底色应 ≈ 启用态 × $specDisabledAlpha（期望 ~$expected），" +
                    "实测启用 $e / 禁用 $d —— 相等即说明 alpha 又被排到「画底」之后了",
                kotlin.math.abs(d - expected) <= 4,
            )
        }
    }

    @Test fun 禁用态三档底色都必须被淡化() {
        show {
            Column {
                LiuliButton(onClick = {}, style = LiuliButtonStyle.Prominent) { Text("实底开") }
                LiuliButton(onClick = {}, style = LiuliButtonStyle.Prominent, enabled = false) { Text("实底关") }
                LiuliButton(onClick = {}, style = LiuliButtonStyle.Glass) { Text("玻璃开") }
                LiuliButton(onClick = {}, style = LiuliButtonStyle.Glass, enabled = false) { Text("玻璃关") }
                LiuliCircleButton(onClick = {}, contentDescription = "圆开") { Text("+") }
                LiuliCircleButton(onClick = {}, contentDescription = "圆关", enabled = false) { Text("+") }
            }
        }
        val bmp = drawRootOnBlack()
        assertFillDimmed(bmp, compose.onNodeWithText("实底开"), compose.onNodeWithText("实底关"), "Prominent 钴蓝实底")
        assertFillDimmed(bmp, compose.onNodeWithText("玻璃开"), compose.onNodeWithText("玻璃关"), "Glass 玻璃片")
        assertFillDimmed(
            bmp,
            compose.onNodeWithContentDescription("圆开"),
            compose.onNodeWithContentDescription("圆关"),
            "圆钮圆玻璃",
        )
    }
}
