package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
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
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppTypography
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-2（图纸 2026-09-05 卷二A §7）：
 * 1. [LiuliAssistantBubble] 打字三点 → 正文**平滑长高**（同 key 原地变身·范式照
 *    `AssistantBubbleMorphTest`：翻 revealed 后第 4 帧的高度必须落在「三点高」与「终态高」之间，
 *    等于终态即瞬跳）；
 * 2. [LiuliInlineStampLayout] 的两态：戳坐末行右下（高 = 正文高）/ 放不下另起一行（高 += 戳高 + 2dp）。
 *
 * 第 2 条**用戳宽而不是文字宽**驱动两态——Robolectric 字形宽失真（PITFALLS §1e），拿文字长度逼换行
 * 得到的结论没有区分力；戳宽是我们自己给的确定值，判据 [stampFitsOnLastLine] 的两侧都能可靠命中。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliBubbleMorphAndStampTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)

    private fun height(tag: String): Float =
        compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }

    @Test fun assistantBubble_revealGrowsGradually_notInstant() {
        compose.mainClock.autoAdvance = false
        var revealed by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                Box(Modifier.testTag("bubble")) {
                    LiuliAssistantBubble(
                        revealed = revealed,
                        // 硬换行撑高（Robolectric 字形宽失真，靠折行长高不可靠）。
                        text = "line one\nline two\nline three\nline four\nline five",
                        quotedContent = null,
                        quotedSender = null,
                        timestampMs = 1_700_000_000_000L,
                        tail = true,
                        maxWidth = 280.dp,
                        onLongClick = {},
                        a11yDescription = null,
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(600)
        val dotsHeight = height("bubble")

        compose.runOnUiThread { revealed = true }
        compose.mainClock.advanceTimeBy(64)
        val midHeight = height("bubble")

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(3_000)
        val finalHeight = height("bubble")

        compose.onNodeWithText("line one", substring = true, useUnmergedTree = true).assertExists()
        assertTrue("前提检查：正文应高于三点占位（dots=$dotsHeight final=$finalHeight）", finalHeight > dotsHeight)
        assertTrue(
            "变身应平滑长高：64ms 处应是中间高度（dots=$dotsHeight mid=$midHeight final=$finalHeight）" +
                (if (midHeight >= finalHeight) "（= 瞬跳）" else ""),
            midHeight > dotsHeight && midHeight < finalHeight,
        )
    }

    /** 三块同屏（一次 setContent·测试规则不许二次 setContent）：裸正文 / 戳放得下 / 戳放不下。 */
    private fun setStampVariants() {
        compose.setContent {
            Column(Modifier.width(HOST_WIDTH)) {
                Text(SHORT_TEXT, style = AppTypography.body, modifier = Modifier.testTag("plain"))
                LiuliInlineStampLayout(
                    textString = SHORT_TEXT,
                    textStyle = AppTypography.body,
                    modifier = Modifier.testTag("inline"),
                    stamp = { Box(Modifier.size(width = 20.dp, height = STAMP_HEIGHT)) },
                    text = { Text(SHORT_TEXT, style = AppTypography.body) },
                )
                // 戳宽 > 可用宽 → 无论正文多短都放不下 → 另起一行。
                LiuliInlineStampLayout(
                    textString = SHORT_TEXT,
                    textStyle = AppTypography.body,
                    modifier = Modifier.testTag("wrapped"),
                    stamp = { Box(Modifier.size(width = HOST_WIDTH + 20.dp, height = STAMP_HEIGHT)) },
                    text = { Text(SHORT_TEXT, style = AppTypography.body) },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test fun stamp_sitsOnLastLine_whenItFits() {
        setStampVariants()
        assertEquals("戳同行时整块高度 = 正文高度（戳浮在末行右下）", height("plain"), height("inline"), 0.5f)
    }

    @Test fun stamp_wrapsToNewLine_whenItCannotFit() {
        setStampVariants()
        assertEquals(
            "另起一行 = 正文高 + 戳高 ${STAMP_HEIGHT.value} + 行距 2",
            STAMP_HEIGHT.value + 2f,
            height("wrapped") - height("inline"),
            0.5f,
        )
    }

    @Test fun fitPredicate_matchesTheSpec() {
        // 末行右缘 100 + 空当 8 + 戳宽 40 = 148：可用 148 恰好放得下，147 放不下。
        assertTrue(stampFitsOnLastLine(lastLineRightPx = 100f, gapPx = 8f, stampWidthPx = 40, availableWidthPx = 148))
        assertTrue(!stampFitsOnLastLine(lastLineRightPx = 100f, gapPx = 8f, stampWidthPx = 40, availableWidthPx = 147))
        // 半像素向上取整（不许挤掉一像素）。
        assertEquals(149, stampInlineWidthPx(lastLineRightPx = 100.5f, gapPx = 8f, stampWidthPx = 40))
    }

    private companion object {
        const val SHORT_TEXT = "好"
        val STAMP_HEIGHT = 16.dp
        val HOST_WIDTH = 300.dp
    }
}
