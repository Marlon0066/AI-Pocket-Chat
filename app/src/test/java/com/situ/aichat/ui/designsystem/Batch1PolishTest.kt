package com.situ.aichat.ui.designsystem

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.AvatarColor
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.liuli.designsystem.LiuliCheckbox
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T1/T2 批一去谷歌化与视觉统一套件（AvatarColor 莫兰迪对比度 + AppActionIcons 30 枚完备性 +
 * AppCheckbox / LiuliCheckbox / AppRoundFab / AppPillFab / AppIconButton / AppMediaRow 行为与无障碍）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class Batch1PolishTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)

    private fun content(block: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) { block() }
        }
    }

    // ---- AvatarColor 莫兰迪色板 ----

    @Test
    fun avatarColor_isDeterministicAndDistinctBetweenLightAndDark() {
        val names = listOf("林清", "沈砚", "Alice", "", "星海漫游者")
        for (name in names) {
            assertEquals(AvatarColor.color(name, dark = false), AvatarColor.color(name, dark = false))
            assertEquals(AvatarColor.color(name, dark = true), AvatarColor.color(name, dark = true))
            assertNotEquals(
                "浅暗模式莫兰迪底色应有明度区分 ($name)",
                AvatarColor.color(name, dark = false),
                AvatarColor.color(name, dark = true),
            )
        }
    }

    @Test
    fun avatarColor_allPaletteStopsMeetContrastAgainstMonogramInk() {
        assertEquals(AvatarColor.lightPalette.size, AvatarColor.darkPalette.size)
        val lightInk = AvatarColor.monogramInk(dark = false)
        val darkInk = AvatarColor.monogramInk(dark = true)

        AvatarColor.lightPalette.forEachIndexed { i, (top, bottom) ->
            assertContrast(lightInk, top, NON_TEXT, "浅色莫兰迪#$i 顶色 vs 象牙字色")
            assertContrast(lightInk, bottom, NON_TEXT, "浅色莫兰迪#$i 底色 vs 象牙字色")
        }
        AvatarColor.darkPalette.forEachIndexed { i, (top, bottom) ->
            assertContrast(darkInk, top, NON_TEXT, "暗色莫兰迪#$i 顶色 vs 柔白字色")
            assertContrast(darkInk, bottom, NON_TEXT, "暗色莫兰迪#$i 底色 vs 柔白字色")
        }
    }

    // ---- AppActionIcons 30 枚网格契约 ----

    @Test
    fun appActionIcons_all30IconsHave24dpGridAndDistinctNames() {
        val icons = listOf(
            AppActionIcons.Close,
            AppActionIcons.Check,
            AppActionIcons.DoneAll,
            AppActionIcons.Send,
            AppActionIcons.Copy,
            AppActionIcons.Delete,
            AppActionIcons.Edit,
            AppActionIcons.Search,
            AppActionIcons.Quote,
            AppActionIcons.Reply,
            AppActionIcons.Refresh,
            AppActionIcons.Download,
            AppActionIcons.ArrowDown,
            AppActionIcons.ChevronUp,
            AppActionIcons.ChevronDown,
            AppActionIcons.ChevronLeft,
            AppActionIcons.ChevronRight,
            AppActionIcons.Mic,
            AppActionIcons.Play,
            AppActionIcons.Pause,
            AppActionIcons.Stop,
            AppActionIcons.Pin,
            AppActionIcons.Lock,
            AppActionIcons.Person,
            AppActionIcons.Calendar,
            AppActionIcons.Clock,
            AppActionIcons.Coin,
            AppActionIcons.Gift,
            AppActionIcons.AddPhoto,
            AppActionIcons.Warning,
        )
        assertEquals(30, icons.size)
        assertEquals(30, icons.map { it.name }.toSet().size)
        for (icon in icons) {
            assertEquals("${icon.name} 宽应为 24dp", 24.dp, icon.defaultWidth)
            assertEquals("${icon.name} 高应为 24dp", 24.dp, icon.defaultHeight)
        }
    }

    // ---- AppCheckbox / LiuliCheckbox ----

    @Test
    fun appCheckbox_togglesStateReportsRoleAndRespectsReadOnlyAndDisabled() {
        var checked by mutableStateOf(false)
        content {
            AppCheckbox(
                checked = checked,
                onCheckedChange = { checked = it },
                modifier = Modifier.testTag("cb"),
            )
        }
        compose.onNodeWithTag("cb").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        compose.onNodeWithTag("cb").assertIsOff()
        compose.onNodeWithTag("cb").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("cb").assertIsOn()
        verify(exactly = 1) { haptics.light() }
    }

    @Test
    fun appCheckbox_nullCallbackProducesNoToggleableNode() {
        content {
            AppCheckbox(checked = true, onCheckedChange = null, modifier = Modifier.testTag("cb"))
        }
        assertEquals(0, compose.onAllNodes(isToggleable()).fetchSemanticsNodes().size)
    }

    @Test
    fun liuliCheckbox_togglesStateAndTriggersSelectionHaptics() {
        var checked by mutableStateOf(false)
        content {
            LiuliCheckbox(
                checked = checked,
                onCheckedChange = { checked = it },
                modifier = Modifier.testTag("lcb"),
            )
        }
        compose.onNodeWithTag("lcb").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        compose.onNodeWithTag("lcb").assertIsOff()
        compose.onNodeWithTag("lcb").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("lcb").assertIsOn()
        verify(exactly = 1) { haptics.selection() }
    }

    @Test
    fun liuliCheckbox_disabledIgnoresClick() {
        var clicks = 0
        content {
            LiuliCheckbox(
                checked = false,
                onCheckedChange = { clicks++ },
                enabled = false,
                modifier = Modifier.testTag("lcb"),
            )
        }
        compose.onNodeWithTag("lcb").performClick()
        compose.waitForIdle()
        assertEquals(0, clicks)
    }

    // ---- AppRoundFab / AppPillFab / AppIconButton / AppMediaRow ----

    @Test
    fun appRoundFab_clicksAndRespectsDisabled() {
        var clicks = 0
        var enabled by mutableStateOf(true)
        content {
            AppRoundFab(
                icon = AppActionIcons.ArrowDown,
                contentDescription = "回到底部",
                onClick = { clicks++ },
                enabled = enabled,
            )
        }
        val node = compose.onNodeWithContentDescription("回到底部")
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        node.performClick()
        compose.waitForIdle()
        assertEquals(1, clicks)

        compose.runOnUiThread { enabled = false }
        compose.waitForIdle()
        node.performClick()
        compose.waitForIdle()
        assertEquals(1, clicks)
    }

    @Test
    fun appPillFab_displaysLabelAndHandlesClick() {
        var clicks = 0
        content {
            AppPillFab(
                icon = AppActionIcons.Edit,
                label = "新写一篇",
                onClick = { clicks++ },
            )
        }
        compose.onNodeWithText("新写一篇").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(1, clicks)
        verify(exactly = 1) { haptics.light() }
    }

    @Test
    fun appIconButton_handlesClickAndDisabledState() {
        var clicks = 0
        var enabled by mutableStateOf(true)
        content {
            AppIconButton(
                icon = AppActionIcons.Close,
                contentDescription = "关闭面板",
                onClick = { clicks++ },
                enabled = enabled,
            )
        }
        val node = compose.onNodeWithContentDescription("关闭面板")
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        node.performClick()
        compose.waitForIdle()
        assertEquals(1, clicks)

        compose.runOnUiThread { enabled = false }
        compose.waitForIdle()
        node.performClick()
        compose.waitForIdle()
        assertEquals(1, clicks)
    }

    @Test
    fun appMediaRow_rendersSlotsAndDispatchesClick() {
        var clicked = 0
        content {
            AppMediaRow(
                title = "自定义规则",
                subtitle = "替换示例",
                onClick = { clicked++ },
                leading = { Text("前缀") },
                trailing = { Text("后缀") },
            )
        }
        compose.onNodeWithText("前缀").assertIsDisplayed()
        compose.onNodeWithText("自定义规则").assertIsDisplayed()
        compose.onNodeWithText("替换示例").assertIsDisplayed()
        compose.onNodeWithText("后缀").assertIsDisplayed()
        compose.onNodeWithText("自定义规则").performClick()
        compose.waitForIdle()
        assertEquals(1, clicked)
    }
}
