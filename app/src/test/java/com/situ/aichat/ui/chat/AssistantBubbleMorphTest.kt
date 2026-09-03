package com.situ.aichat.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 打字三点 → 正文「原地变身」的尺寸动画行为测试（契约 FABLE5_CHAT_BUBBLE_REFACTOR B1/B4·
 * AnimatedContent+SizeTransform 应平滑长高,绝非一帧瞬变)。手动推帧钟采样中间尺寸:
 * 若翻转 revealed 后下一帧尺寸即等于终态 = 变身瞬跳(2026-07-08 用户所报「抖一下」的候选根因)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantBubbleMorphTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun reveal_growsGradually_notInstant() {
        compose.mainClock.autoAdvance = false
        var revealed by mutableStateOf(false)
        compose.setContent {
            Box(Modifier.testTag("bubble")) {
                AssistantTextBubble(
                    revealed = revealed,
                    // 硬换行撑高(Robolectric 字形宽≈0 无法靠折行长高·高度=行数×行高,不依赖字形宽度)。
                    text = "line one\nline two\nline three\nline four\nline five",
                    quotedContent = null,
                    quotedSender = null,
                    shape = RoundedCornerShape(16.dp),
                    maxWidth = 280.dp,
                    onLongClick = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(600)
        val dotsHeight = compose.onNodeWithTag("bubble").getUnclippedBoundsInRoot().let { it.bottom - it.top }

        compose.runOnUiThread { revealed = true }
        // 推 4 帧(≈64ms):若动画存在,此刻高度应在「三点高」与「终态高」之间。
        compose.mainClock.advanceTimeBy(64)
        val midHeight = compose.onNodeWithTag("bubble").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        // 推满 2s 动画收尾。
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(3_000)
        val finalHeight = compose.onNodeWithTag("bubble").getUnclippedBoundsInRoot().let { it.bottom - it.top }

        println("MORPH_PROBE dots=$dotsHeight mid=$midHeight final=$finalHeight")
        // 探针:终态正文是否真的在组合里(区分「内容没切换」vs「字体测量失真」)。
        compose.onNodeWithText("line one", substring = true).assertExists()
        assertTrue("正文应高于三点占位(前提检查) dots=$dotsHeight final=$finalHeight", finalHeight > dotsHeight)
        assertTrue(
            "变身应平滑长高:64ms 处应为中间高度,实测 dots=$dotsHeight mid=$midHeight final=$finalHeight" +
                (if (midHeight >= finalHeight) "(=瞬跳,SizeTransform 未生效)" else ""),
            midHeight > dotsHeight && midHeight < finalHeight,
        )
    }
}
