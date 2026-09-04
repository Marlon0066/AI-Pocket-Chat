package com.situ.aichat.ui.designsystem

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-G：釉烧陶土主钮 [Modifier.glazedClay] 在 [AppButton] 上的**接线**（Robolectric·图纸
 * `2026-09-05-釉烧主钮与编辑页门楣.md` §7）。
 *
 * 釉面本身（三段渐变 / 两道沿 / 颗粒 / 影）属像素域，Robolectric 黑盒断不出——那部分由「§9 落值逐字照抄 +
 * 装机浅深取证」担保。本测钉**行为面与档位边界**：
 * - Primary 档能渲染能点、回调掉（G1）；
 * - **压印字只给 Primary**（G2）——这是釉档位的唯一可观测代理：图纸 §4.2 规定 Primary 才往
 *   [LocalTextStyle] 里塞 `Shadow(dy = 1dp, blur = 0f)`，Tonal / Text / Warning 三档恒 `shadow = null`。
 *   这条同时是「防给所有档上釉」的回归锁——真给三档接上 `glazedClay`，接线代码必然同时把压印字带过去。
 * - `enabled = false` 零回调（E4）、reduceMotion 下仍可点（E5）。
 *
 * 断言的 dy / blur / alpha 三个数字是**从图纸 §9 锁定值重新打字**的字面量，不引实现常量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class AppGlazedClayTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)

    private fun content(block: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) { block() }
        }
    }

    private fun disableSystemAnimations() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    /** 渲染一枚按钮，顺手把 [AppButton] 下发的文字样式与 1dp 的 px 值抓出来。 */
    private fun renderCapturingStyle(
        style: AppButtonStyle,
        enabled: Boolean = true,
        label: String = "保存",
        onClick: () -> Unit = {},
    ): Pair<() -> TextStyle, () -> Float> {
        var captured: TextStyle? = null
        var onePx = 0f
        content {
            AppButton(onClick = onClick, style = style, enabled = enabled) {
                captured = LocalTextStyle.current
                onePx = with(LocalDensity.current) { 1.dp.toPx() }
                Text(label)
            }
        }
        return ({ captured!! }) to ({ onePx })
    }

    @Test
    fun G1_Primary档_渲染且可点_回调掉() {
        var clicks = 0
        renderCapturingStyle(AppButtonStyle.Primary, onClick = { clicks++ })

        compose.onNodeWithText("保存").assertIsDisplayed()
        compose.onNodeWithText("保存").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun G2a_Primary档_压印字按锁定值下发() {
        val (style, onePx) = renderCapturingStyle(AppButtonStyle.Primary)
        val shadow = style().shadow

        assertNotNull("Primary 必须带压印字亮线", shadow)
        // 浅档（默认 LightAppColors）：白 @26%、下移 1dp、硬边（blur 0）。
        assertEquals("压印线必须是硬边（blurRadius = 0）", 0f, shadow!!.blurRadius, 0f)
        assertEquals("压印线下移恰 1dp", onePx(), shadow.offset.y, 0.01f)
        assertEquals("压印线横向不偏", 0f, shadow.offset.x, 0f)
        assertEquals("浅档压印线是白的", 1f, shadow.color.red, 0.001f)
        assertEquals("浅档压印线是白的", 1f, shadow.color.green, 0.001f)
        assertEquals("浅档压印线是白的", 1f, shadow.color.blue, 0.001f)
        assertEquals("浅档压印线 26%", 0.26f, shadow.color.alpha, 0.005f)
    }

    @Test
    fun G2b_其余三档零压印字_且各自照常可点() {
        // 一次 setContent 里并排三档（rule 每条用例只许 setContent 一次）。
        val labels = mapOf(
            AppButtonStyle.Tonal to "次要",
            AppButtonStyle.Text to "三级",
            AppButtonStyle.Warning to "删除",
        )
        val captured = mutableMapOf<AppButtonStyle, TextStyle>()
        val clicks = mutableMapOf<AppButtonStyle, Int>()
        content {
            Column {
                labels.forEach { (style, label) ->
                    AppButton(onClick = { clicks[style] = (clicks[style] ?: 0) + 1 }, style = style) {
                        captured[style] = LocalTextStyle.current
                        Text(label)
                    }
                }
            }
        }

        labels.forEach { (style, label) ->
            assertNull("$style 档绝不许上釉/压印字（釉只给 Primary）", captured[style]!!.shadow)
            compose.onNodeWithText(label).performClick()
            assertEquals("$style 档回调照旧", 1, clicks[style])
        }
    }

    @Test
    fun G3_禁用态_点不动零回调() {
        var clicks = 0
        renderCapturingStyle(AppButtonStyle.Primary, enabled = false, onClick = { clicks++ })

        compose.onNodeWithText("保存").assertIsDisplayed()
        compose.onNodeWithText("保存").performClick()
        assertEquals("enabled = false 的主钮不许掉回调", 0, clicks)
    }

    @Test
    fun G4_reduceMotion开_照样渲染且回调可达() {
        disableSystemAnimations()
        var clicks = 0
        val (captured, _) = renderCapturingStyle(AppButtonStyle.Primary, onClick = { clicks++ })

        // 关动画只撤 1dp 下沉与缩放，釉的静态观感（含压印字）不变。
        assertNotNull("reduceMotion 不该撤掉压印字", captured().shadow)
        compose.onNodeWithText("保存").performClick()
        assertEquals(1, clicks)
    }
}
