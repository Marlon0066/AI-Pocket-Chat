package com.situ.aichat.ui.liuli.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.liuli.designsystem.LiuliPalette
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties

/**
 * T2：二级屏分组行族（图纸 2026-09-06 卷四 §8 C2 · 契约 §6.5「分组 / 行基线 / 图标砖 / 开关行 / 滑杆行」）。
 *
 * 期望值从契约那一行重新打字：单行 52 · 两行 64 · 发丝起点有砖 56 / 无砖 16 · 首行不画 ·
 * 开关行整行可点（点标题即翻·且**只**翻一次）· 滑杆行值回显。
 *
 * 几何断言用 `-xhdpi`（Robolectric 假字高恒 32px，1x 下 32 + 内距会与规格值撞车·PITFALLS §1e）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliGroupRowsTest {

    @get:Rule
    val compose = createComposeRule()

    private var toggled = mutableListOf<Boolean>()
    private var navTaps = 0

    private fun host(content: @Composable () -> Unit) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    Column(Modifier.fillMaxWidth()) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
    fun 单行52两行64() {
        host {
            LiuliGroup(header = "个性化") {
                LiuliNavRow(
                    title = "外观",
                    onClick = {},
                    icon = Icons.Filled.Palette,
                    tileColor = LiuliPalette.tilePersonalize,
                    divider = false,
                    modifier = Modifier.testTag("one"),
                )
                LiuliNavRow(
                    title = "记忆",
                    subtitle = "保留量 / 检索阈值",
                    onClick = {},
                    icon = Icons.Filled.Palette,
                    tileColor = LiuliPalette.tileMemory,
                    modifier = Modifier.testTag("two"),
                )
            }
        }
        val one = compose.onNodeWithTag("one").getUnclippedBoundsInRoot()
        val two = compose.onNodeWithTag("two").getUnclippedBoundsInRoot()
        assertEquals(52f, (one.bottom - one.top).value, 0.01f)
        assertEquals(64f, (two.bottom - two.top).value, 0.01f)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
    fun 发丝起点有砖56无砖16且首行不画() {
        host {
            LiuliGroup {
                LiuliNavRow(title = "第一行", onClick = {}, icon = Icons.Filled.Palette, tileColor = LiuliPalette.tileApi, divider = false)
                LiuliNavRow(title = "第二行", onClick = {}, icon = Icons.Filled.Palette, tileColor = LiuliPalette.tileApi)
                LiuliNavRow(title = "第三行", onClick = {})
            }
        }
        // 三行里只有后两行画发丝（首行传 divider = false）。
        compose.onAllNodesWithTag(LIULI_ROW_DIVIDER_TAG, useUnmergedTree = true).assertCountEquals(2)
        // 量字必须走 unmerged 树：合并树里 `clickable` 的行把字吞成整槽，量到的是行左缘 0（PITFALLS §1e）。
        val group = compose.onNodeWithText("第一行", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val withTile = compose.onAllNodesWithTag(LIULI_ROW_DIVIDER_TAG, useUnmergedTree = true)[0]
            .getUnclippedBoundsInRoot()
        val plain = compose.onAllNodesWithTag(LIULI_ROW_DIVIDER_TAG, useUnmergedTree = true)[1]
            .getUnclippedBoundsInRoot()
        // 组是满宽的（左缘 = 0），所以发丝左缘就是起点内距本身。
        assertEquals(LiuliPageGeometry.dividerInsetTile.value, withTile.left.value, 0.01f)
        assertEquals(LiuliPageGeometry.dividerInsetPlain.value, plain.left.value, 0.01f)
        assertEquals(0.5f, (withTile.bottom - withTile.top).value, 0.01f)
        // 首行标题左缘 = 16 内距 + 砖 28 + 缝 12 = 56（与有砖发丝同起点·砖行文字对齐）。
        assertEquals(LiuliPageGeometry.dividerInsetTile.value, group.left.value, 0.01f)
    }

    @Test fun 开关行整行可点且只翻一次() {
        host {
            LiuliGroup {
                LiuliToggleRow(
                    title = "消息情绪动画",
                    checked = false,
                    onCheckedChange = { toggled += it },
                    divider = false,
                )
            }
        }
        compose.onNodeWithText("消息情绪动画").performClick()
        compose.waitForIdle()
        assertEquals(listOf(true), toggled)
    }

    @Test fun 导航行回调恰一次() {
        host {
            LiuliGroup {
                LiuliNavRow(title = "API 配置", onClick = { navTaps++ }, value = "未配置，点此添加", valueWarning = true, divider = false)
            }
        }
        compose.onNodeWithText("未配置，点此添加").assertExists()
        compose.onNodeWithText("API 配置").performClick()
        compose.waitForIdle()
        assertEquals(1, navTaps)
    }

    @Test fun 滑杆行值回显并随拖动更新() {
        host {
            var v by remember { mutableFloatStateOf(0.3f) }
            LiuliGroup {
                LiuliSliderRow(
                    title = "免打扰时段",
                    valueLabel = "${(v * 100).toInt()}%",
                    value = v,
                    onValueChange = { v = it },
                    divider = false,
                )
            }
        }
        compose.onNodeWithText("30%").assertExists()
        // 真改值（读屏 SetProgress = 拖到 80%）→ 回调把新值写回、右值跟着变（复核 R1 🟡-8：原例只看初值）。
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.8f) }
        compose.waitForIdle()
        compose.onNodeWithText("80%").assertExists()
        compose.onNodeWithText("30%").assertDoesNotExist()
    }

    /** 复核 R1 🔴-2：药丸自己再挂一份 toggleable 会把点击吃掉（有触觉、行不翻）。行 = 唯一 Switch 节点，点药丸位置也翻。 */
    @Test fun 开关行只有一个开关节点且点药丸也翻() {
        var toggles = 0
        host {
            LiuliGroup {
                LiuliToggleRow(title = "消息情绪动画", checked = false, onCheckedChange = { toggles++ }, divider = false)
            }
        }
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        // 点在行右端药丸中心（右内距 16 + 药丸宽 44 的一半）。
        compose.onNode(isToggleable()).performTouchInput {
            click(Offset(width - 16.dp.toPx() - 22.dp.toPx(), centerY))
        }
        compose.waitForIdle()
        assertEquals(1, toggles)
    }

    @Test fun 状态点行不可点且显状态词() {
        host {
            LiuliGroup {
                LiuliStatusDotRow(
                    title = "深层记忆",
                    status = "已就绪",
                    dotColor = LiuliPalette.tileMemory,
                    subtitle = "向量检索可用",
                    divider = false,
                )
            }
        }
        compose.onNodeWithText("已就绪").assertExists()
        compose.onNodeWithText("向量检索可用").assertExists()
        compose.onNodeWithText("深层记忆").assertHasNoClickAction()
    }
}

/** 卷五复核 R1：开关行读屏念「开 / 关」；文字动作行禁用时留在原位且不回调。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliRowA11yAndActionTest {

    @get:Rule
    val compose = createComposeRule()

    private fun host(content: @Composable () -> Unit) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    Column(Modifier.fillMaxWidth()) { LiuliGroup { content() } }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun 开关行读屏念开关状态() {
        host { LiuliToggleRow(title = "消息情绪动画", checked = true, onCheckedChange = {}, divider = false) }
        val node = compose.onNode(isToggleable()).fetchSemanticsNode()
        // 状态词 = 资源 settings_state_on（zh-rCN「已开启」），与暖陶开关行读屏同一份文案。
        assertEquals("已开启", node.config[SemanticsProperties.StateDescription])
    }

    @Test fun 文字动作行禁用时在场且不回调() {
        var taps = 0
        host { LiuliTextActionRow(title = "导出报告", onClick = { taps++ }, enabled = false, divider = false) }
        compose.onNodeWithText("导出报告").assertExists()
        compose.onNodeWithText("导出报告").performClick()
        compose.waitForIdle()
        assertEquals(0, taps)
    }

    @Test fun 文字动作行可用时回调恰一次() {
        var taps = 0
        host { LiuliTextActionRow(title = "导出报告", onClick = { taps++ }, divider = false) }
        compose.onNodeWithText("导出报告").performClick()
        compose.waitForIdle()
        assertEquals(1, taps)
    }
}
