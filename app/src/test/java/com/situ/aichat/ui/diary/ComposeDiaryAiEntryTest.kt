package com.situ.aichat.ui.diary

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.situ.aichat.data.model.DiaryVisibility
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2（真组合·图纸 2026-09-05 日记编辑丢作者根治·§7 T2-7）：编辑「TA 的信」时「AI 帮我写」的**两个**入口
 * （动作栏图标 + 空态胶囊）都不发射——它生成的是「用户视角」日记，一点即整封替换掉角色写的信。
 *
 * 隐藏 = 节点不发射（不是 alpha=0 / enabled=false），故断言用 assertDoesNotExist；正向档同时钉住
 * 「写自己的日记时两入口照旧出现」（B4 不回归）。屏尺寸钉真机档防「元素落可视区外」的假绿
 * （记忆 `reference-robolectric-screen-size-fake-green`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class ComposeDiaryAiEntryTest {

    @get:Rule
    val compose = createComposeRule()

    private fun showActionBar(aiAssistAvailable: Boolean) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                ComposeActionBar(
                    canSave = true,
                    hasContent = true,
                    canAddImage = true,
                    isGenerating = false,
                    aiAssistAvailable = aiAssistAvailable,
                    visibility = DiaryVisibility.OPEN_TO_AI,
                    onAddImage = {},
                    onToggleVisibility = {},
                    onAiAssist = {},
                    onSaveDraft = {},
                    onRecord = {},
                    onStartVoice = {},
                    onVoiceDrag = {},
                    onFinishVoice = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private fun showPaper(aiAssistAvailable: Boolean) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                DiaryPaper(
                    content = "",
                    prompt = "今天过得怎么样？",
                    isGenerating = false,
                    reduceMotion = true,
                    aiAssistAvailable = aiAssistAvailable,
                    onContentChange = {},
                    onAiStart = {},
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `编辑TA的信_动作栏AI图标不发射`() {
        showActionBar(aiAssistAvailable = false)
        compose.onNodeWithContentDescription("AI 帮写").assertDoesNotExist()
        // 正向锚：同一条动作栏的其它左组钮照常在（证明这条动作栏真的渲染过，不是整屏没上）。
        compose.onNodeWithContentDescription("添加").assertExists()
    }

    @Test
    fun `写自己的日记_动作栏AI图标照旧发射`() {
        showActionBar(aiAssistAvailable = true)
        compose.onNodeWithContentDescription("AI 帮写").assertExists()
    }

    @Test
    fun `编辑TA的信_空态胶囊不发射`() {
        showPaper(aiAssistAvailable = false)
        compose.onNodeWithText("让 TA 帮你起个头").assertDoesNotExist()
        // 正向锚：空态引导语照常在（纸面确已渲染）。
        compose.onNodeWithText("今天过得怎么样？").assertExists()
    }

    @Test
    fun `写自己的日记_空态胶囊照旧发射`() {
        showPaper(aiAssistAvailable = true)
        compose.onNodeWithText("让 TA 帮你起个头").assertExists()
    }
}
