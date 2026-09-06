package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.liuli.page.LiuliPageGeometry
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2：进度条（图纸 2026-09-06 卷五 A-4 ③·§8 C0）。
 *
 * 钉三件：定量态报**真值**的 `progressSemantics`（不是 Indeterminate）· 轨高 = 表里的
 * [LiuliPageGeometry.progressTrack] · 不定态没有进度语义、改出转圈 + 那一句话。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliProgressBarTest {

    @get:Rule
    val compose = createComposeRule()

    private fun host(progress: Float?, label: String? = null) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    Column(Modifier.fillMaxWidth()) {
                        LiuliProgressBar(progress, Modifier.testTag("bar"), label)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun progressNodes() = compose
        .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        .fetchSemanticsNodes()

    @Test fun 定量态报真值() {
        host(progress = 0.4f)
        val nodes = progressNodes()
        assertEquals(1, nodes.size)
        val info = nodes.first().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(ProgressBarRangeInfo(0.4f, 0f..1f, 0), info)
    }

    @Test fun 越界值被钳进0到1() {
        host(progress = 1.8f)
        val info = progressNodes().first().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(1f, info.current, 0.0001f)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
    fun 轨高取几何表的值() {
        host(progress = 0.5f)
        val bar = compose.onNodeWithTag("bar").getUnclippedBoundsInRoot()
        assertEquals(LiuliPageGeometry.progressTrack.value, (bar.bottom - bar.top).value, 0.01f)
    }

    @Test fun 不定态无进度语义只出转圈与说明() {
        host(progress = null, label = "正在整理…")
        assertEquals(0, progressNodes().size)
        compose.onNodeWithText("正在整理…").assertExists()
    }

    @Test fun 定量态不渲染说明句() {
        host(progress = 0.4f, label = "正在整理…")
        assertEquals(0, compose.onAllNodesWithText("正在整理…").fetchSemanticsNodes().size)
    }
}
