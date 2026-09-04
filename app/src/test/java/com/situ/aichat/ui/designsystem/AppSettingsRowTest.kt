package com.situ.aichat.ui.designsystem

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-W：设置行 [AppSettingsRow] 的行为（Robolectric·M3 清零收官图纸 §7）。
 *
 * 30dp 瓦片 / 13sp 题 / 10.5sp 副 / 12dp 槽间距属像素域；本测试钉行为面：各槽按传参渲染或缺席、
 * 超长文本不崩、**有 trailing 时行本身不吃点击**（E-B6 防双触发）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class AppSettingsRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)

    private fun content(block: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) { block() }
        }
    }

    @Test
    fun W1_各槽按传参渲染或缺席_点击掉回调也掉触觉() {
        var clicks = 0
        content {
            AppSettingsRow(
                title = "外观",
                subtitle = "配色与深浅",
                icon = Icons.Filled.Add,
                value = "暖陶 · 跟随系统",
                showChevron = true,
                onClick = { clicks++ },
            )
        }

        compose.onNodeWithText("外观").assertIsDisplayed()
        compose.onNodeWithText("配色与深浅").assertIsDisplayed()
        compose.onNodeWithText("暖陶 · 跟随系统").assertIsDisplayed()
        compose.onNodeWithText("外观").performClick()
        assertEquals(1, clicks)
        verify(exactly = 1) { haptics.light() }
    }

    @Test
    fun W1b_没传副与尾值时_这两个节点根本不存在() {
        content {
            AppSettingsRow(title = "只有题")
        }

        compose.onNodeWithText("只有题").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("配色与深浅")).fetchSemanticsNodes().size)
    }

    @Test
    fun W2_超长题与超长副_渲染不崩且都还在() {
        val longTitle = "一个长到需要省略号才装得下的设置项标题用来压力测试排版"
        val longSub = "副标题同样很长很长很长，按规格最多两行，再多就省略；这里故意写满两行以上来验证不崩"
        content {
            AppSettingsRow(title = longTitle, subtitle = longSub, value = "值也不短的一段文字")
        }

        compose.onNodeWithText(longTitle).assertIsDisplayed()
        compose.onNodeWithText(longSub).assertIsDisplayed()
        compose.onNodeWithText("值也不短的一段文字").assertIsDisplayed()
    }

    @Test
    fun W3_有trailing时行本身不吃点击_防与槽内开关双触发() {
        var rowClicks = 0
        var switchClicks = 0
        content {
            AppSettingsRow(
                title = "动态取色",
                onClick = { rowClicks++ },
                trailing = {
                    AppSwitch(checked = false, onCheckedChange = { switchClicks++ })
                },
            )
        }

        compose.onNodeWithText("动态取色").performClick()
        compose.waitForIdle()
        assertEquals("trailing 在场时整行必须不可点（否则与槽内开关双触发）", 0, rowClicks)
        assertEquals(0, switchClicks)
        verify(exactly = 0) { haptics.light() }
    }

    @Test
    fun W4_trailing槽内容真渲染() {
        content {
            AppSettingsRow(title = "已隐藏的内置表情", value = "3", trailing = { Text("展开") })
        }

        compose.onNodeWithText("已隐藏的内置表情").assertIsDisplayed()
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithText("展开").assertIsDisplayed()
    }
    /**
     * R1 🟡-1 新增：badge 药丸的**规格反推**——badge 是不可点的装饰小标，因此它**不该**像 trailing 那样
     * 吃掉整行点击（trailing 的互斥是为了防「行 + 槽内开关」双触发，badge 没有这个问题）。
     * 若哪天有人图省事把 badge 也并进 trailing 的互斥判断，这条会红。
     */
    @Test
    fun W4_badge在场时药丸可见_且整行照常可点() {
        var clicks = 0
        content {
            AppSettingsRow(
                title = "提示词模块",
                icon = Icons.Filled.Add,
                badge = "高级",
                showChevron = true,
                onClick = { clicks++ },
            )
        }

        compose.onNodeWithText("高级").assertIsDisplayed()
        compose.onNodeWithText("提示词模块").performClick()
        assertEquals("badge 不可点，不该吃掉整行点击", 1, clicks)
        verify(exactly = 1) { haptics.light() }
    }

    @Test
    fun W5_badge为空时_零药丸节点() {
        content {
            AppSettingsRow(title = "外观", icon = Icons.Filled.Add, showChevron = true, onClick = {})
        }

        assertEquals(0, compose.onAllNodes(hasText("高级")).fetchSemanticsNodes().size)
    }

}
