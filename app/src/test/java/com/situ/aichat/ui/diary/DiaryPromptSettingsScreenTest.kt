package com.situ.aichat.ui.diary

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.prompt.diary.PreviewLine
import com.situ.aichat.prompt.diary.PreviewLineKind
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T2-4（图纸 §7·补偿覆盖）：写作规则屏与只读预览屏的**真组合**——两个分区标题、四个字段标签、
 * 两个预览入口文案都上屏；预览屏三种成色的行都渲染。
 *
 * 为什么需要它：图纸 §7 的装机档在本机模拟器上够不着 LLM 侧效果，这条把「屏能否渲染 / 文案是否上屏」
 * 从「编译过」升到真组合断言。屏尺寸钉真机档防「元素落可视区外」的假绿
 * （记忆 `reference-robolectric-screen-size-fake-green`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class DiaryPromptSettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)

    private fun showRules() {
        coEvery { settingsRepo.getAppSettings() } returns AppSettings()
        val vm = DiaryPromptSettingsViewModel(RuntimeEnvironment.getApplication(), settingsRepo)
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                DiaryPromptSettingsScreen(
                    onBack = {},
                    onOpenPreviewMine = {},
                    onOpenPreviewExchange = {},
                    viewModel = vm,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `两个分区标题 四个字段标签 两个预览入口都上屏`() {
        showRules()
        compose.onNodeWithText("我的日记").assertIsDisplayed()
        compose.onNodeWithText("TA 的信").assertExists()
        // 四个字段标签**每分区各一份** ⇒ 恰好 2 个节点（只断言「存在」会被单分区渲染蒙混过关）。
        listOf("篇幅", "人称", "文风", "我再补几条（一行一条）").forEach { label ->
            compose.onAllNodesWithText(label).assertCountEquals(2)
        }
        compose.onAllNodesWithText("恢复默认").assertCountEquals(2)
        compose.onNodeWithText("看看完整的提示词").assertExists()
        compose.onNodeWithText("我的日记 · 完整提示词").assertExists()
        compose.onNodeWithText("TA 的信 · 完整提示词").assertExists()
    }

    @Test
    fun `未自定义时字段里播种的是默认文案原文 且不含裸的列表前缀`() {
        showRules()
        compose.onNodeWithText("用第一人称（我）书写").assertExists()
        compose.onNodeWithText("用第一人称写你自己（我=你，绝不是替对方写）").assertExists()
        // 播种文案绝不带提示词的列表前缀（带了的话用户一改，装配端会补成「- - …」）。
        compose.onNodeWithText("- 用第一人称（我）书写").assertDoesNotExist()
    }

    /** 预览正文块：三种成色（普通 / 占位 / 用户改过）的行都真上屏（颜色可读性由装机确认·E21）。 */
    @Test
    fun `预览正文块 三种成色的行都上屏`() {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                DiaryPromptPreviewBody(
                    listOf(
                        PreviewLine("## 要求", PreviewLineKind.PLAIN),
                        PreviewLine("〈今天的日程〉", PreviewLineKind.SLOT),
                        PreviewLine("- 别写天气", PreviewLineKind.CUSTOM),
                        PreviewLine("", PreviewLineKind.PLAIN),
                    ),
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("尖括号里的内容，生成时会换成当天的真实素材。").assertIsDisplayed()
        compose.onNodeWithText("## 要求").assertIsDisplayed()
        compose.onNodeWithText("〈今天的日程〉").assertIsDisplayed()
        compose.onNodeWithText("- 别写天气").assertIsDisplayed()
    }
}
