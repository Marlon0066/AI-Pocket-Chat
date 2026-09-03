package com.situ.aichat.ui.story

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.situ.aichat.story.StoryTextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 特效文字块左缘对齐行为测试（2026-07-13 真机「操！」贴屏边修复·方案 A）。
 *
 * 证明链两截合围（语义测框只看**布局**、看不见 graphicsLayer 绘制期变换——本文件曾用「中心轴放大 1.15
 * 的控制组」实测 left 仍=28dp 证实，故绘制期那截不在此测）：
 * - 本 T2：布局左缘与正文严丝合缝 + shout 块高显著放大（渲染现实抽查）；「样式→显示字号」的
 *   精确接线锁 = [StoryReaderFontSizeWiringTest]（渲染层 int px 量化判不了 emphasis 的 2% 差）；
 * - StoryTextMotionTest T1：shout/emphasis 的缩放/平移三函数输出恒 1.0/0——绘制期位移的**数据源**锁零。
 * 盲区如实记（T5 复核 🔵-2）：graphicsLayer 内「数据源→scaleX/translationX」的接线与行首轴
 * TransformOrigin(0f, .5f) 本身在两截射程外（若有人在图层里硬编码缩放，两截仍绿），由真机批兜底
 * （DEVICE_VERIFICATION_CHECKLIST 2026-07-13 shout 批）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w480dp-h800dp")
class StoryReaderTextBlockAlignmentTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun effectBlocks_leftEdge_alignsWithBodyText() {
        compose.setContent {
            Column(Modifier.width(400.dp).padding(horizontal = 28.dp)) {
                StoryReaderTextBlock(
                    text = "正文段落。",
                    style = StoryTextStyle.NORMAL,
                    isFirstParagraph = false,
                    isDark = false,
                    modifier = Modifier.testTag("body"),
                )
                StoryReaderTextBlock(
                    text = "“操！”",
                    style = StoryTextStyle.SHOUT,
                    isFirstParagraph = false,
                    isDark = false,
                    modifier = Modifier.testTag("shout"),
                )
                StoryReaderTextBlock(
                    text = "强调句。",
                    style = StoryTextStyle.EMPHASIS,
                    isFirstParagraph = false,
                    isDark = false,
                    modifier = Modifier.testTag("emphasis"),
                )
                StoryReaderTextBlock(
                    text = "兴奋句。",
                    style = StoryTextStyle.EXCITED,
                    isFirstParagraph = false,
                    isDark = false,
                    modifier = Modifier.testTag("excited"),
                    // 钉在缩放峰值（sin(π/2)=1 → 1.02）：布局左缘不许因任何「修饰符补丁」偏移。
                    motionTime = { Math.PI / 16 },
                )
            }
        }

        val body = compose.onNodeWithTag("body").getUnclippedBoundsInRoot()
        val shout = compose.onNodeWithTag("shout").getUnclippedBoundsInRoot()
        val emphasis = compose.onNodeWithTag("emphasis").getUnclippedBoundsInRoot()
        val excited = compose.onNodeWithTag("excited").getUnclippedBoundsInRoot()

        // 正文左缘 = 页边距 28dp（锚点自检）。
        assertEquals(28f, body.left.value, 0.5f)
        // 三类特效块布局左缘与正文严丝合缝。
        assertEquals("shout 左缘应与正文对齐", body.left.value, shout.left.value, 0.5f)
        assertEquals("emphasis 左缘应与正文对齐", body.left.value, emphasis.left.value, 0.5f)
        assertEquals("excited 左缘应与正文对齐", body.left.value, excited.left.value, 0.5f)
        // shout 渲染现实抽查：块高随显示字号显著放大。Robolectric 实测（含字体度量+int px 量化，
        // 块高≠纯 lineHeight）：接线世界 46/35≈1.31，忘接世界（22sp）推算 ≈43/35≈1.23——阈 1.27 取判别带
        // 中点，两侧各留 ~0.04 抗度量漂移；字号接线的精确锁在 StoryReaderFontSizeWiringTest。
        val bodyH = body.bottom.value - body.top.value
        val shoutH = shout.bottom.value - shout.top.value
        assertTrue(
            "shout 块高度应体现烘焙后的显示字号（bodyH=$bodyH shoutH=$shoutH）",
            shoutH > bodyH * 1.27f,
        )
        // emphasis 的 2% 字号差在渲染层不可判（实测块高被字体度量+int px 量化吞平：35.0 vs 35.0，
        // 且正文块高 35.0 ≠ lineHeight 32.4）——emphasis/shout 的「样式→显示字号」精确接线
        // 由 StoryReaderFontSizeWiringTest（T1·零量化）锁死，此处只留渲染现实探针。
        println("SHOUT_ALIGN_PROBE bodyH=$bodyH shoutH=$shoutH emphasisH=${emphasis.bottom.value - emphasis.top.value}")
    }
}
