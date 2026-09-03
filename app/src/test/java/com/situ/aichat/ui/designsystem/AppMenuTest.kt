package com.situ.aichat.ui.designsystem

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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
 * T2-M：玻璃小笺菜单 [AppMenu] 的行为（Robolectric·M3 清零卷一图纸 §7）。
 *
 * 母版造型（20dp / 94% / 0.75dp 发丝 / 216dp）属像素域，由「参数逐字迁移 + 装机暗色抽查」担保；
 * 本测试钉的是行为面：展开才渲染、点哪项掉哪个回调、danger / leadingIcon / divider 三槽都真出现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppMenuTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)

    private fun content(block: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) { block() }
        }
    }

    @Test
    fun M1_展开时各项都在_点谁掉谁的回调() {
        var edited = 0
        var deleted = 0
        content {
            AppMenu(expanded = true, onDismiss = {}) {
                AppMenuItem(text = "编辑", onClick = { edited++ })
                AppMenuItem(text = "删除", onClick = { deleted++ }, danger = true)
            }
        }

        compose.onNodeWithText("编辑").assertIsDisplayed()
        compose.onNodeWithText("编辑").performClick()
        assertEquals(1, edited)
        assertEquals(0, deleted)

        compose.onNodeWithText("删除").performClick()
        assertEquals(1, deleted)
        verify(exactly = 2) { haptics.light() }
    }

    @Test
    fun M1b_没展开时一个项都不渲染() {
        content {
            AppMenu(expanded = false, onDismiss = {}) {
                AppMenuItem(text = "编辑", onClick = {})
            }
        }

        assertEquals(0, compose.onAllNodes(hasText("编辑")).fetchSemanticsNodes().size)
    }

    @Test
    fun M2_危险项_照样渲染照样能点() {
        var deleted = 0
        content {
            AppMenu(expanded = true, onDismiss = {}) {
                AppMenuItem(text = "删除这本书", onClick = { deleted++ }, danger = true, leadingIcon = Icons.Default.Delete)
            }
        }

        compose.onNodeWithText("删除这本书").performClick()
        assertEquals(1, deleted)
    }

    @Test
    fun M3_带前导图标的项_文字照样可定位可点() {
        var clicked = 0
        content {
            AppMenu(expanded = true, onDismiss = {}, width = 240.dp) {
                AppMenuItem(text = "改名", onClick = { clicked++ }, leadingIcon = Icons.Default.Edit)
            }
        }

        compose.onNodeWithText("改名").assertIsDisplayed()
        compose.onNodeWithText("改名").performClick()
        assertEquals(1, clicked)
    }

    @Test
    fun M4_分隔条_夹在两组之间不吃掉任何一项() {
        content {
            AppMenu(expanded = true, onDismiss = {}) {
                AppMenuItem(text = "置顶", onClick = {})
                AppMenuDivider()
                AppMenuItem(text = "移除", onClick = {}, danger = true)
            }
        }

        compose.onNodeWithText("置顶").assertIsDisplayed()
        compose.onNodeWithText("移除").assertIsDisplayed()
    }

    @Test
    fun M5_禁用项_点了不掉回调() {
        var clicked = 0
        content {
            AppMenu(expanded = true, onDismiss = {}) {
                AppMenuItem(text = "导出", onClick = { clicked++ }, enabled = false)
            }
        }

        compose.onNodeWithText("导出").performClick()
        assertEquals("enabled = false 的项点了必须零回调", 0, clicked)
    }
}
