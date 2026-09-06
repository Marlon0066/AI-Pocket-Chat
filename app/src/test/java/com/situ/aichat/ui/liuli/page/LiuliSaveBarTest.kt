package com.situ.aichat.ui.liuli.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 卷五复核 R1 🔴 A-2：保存栏是**最小** 56，内容更高时随内容长（导入预览的结果摘要 / 错误 + 进度），
 * 且把实高回报给调用方留列表底内距。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliSaveBarTest {

    @get:Rule
    val compose = createComposeRule()

    private var reported = 0.dp

    private fun bar(contentHeight: androidx.compose.ui.unit.Dp) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    LiuliSaveBar(modifier = Modifier.testTag("bar"), onHeightChanged = { reported = it }) {
                        Column(Modifier.fillMaxWidth().height(contentHeight)) { Text("导入结果") }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
    fun 内容不高时栏是最小56() {
        bar(20.dp)
        val h = compose.onNodeWithTag("bar").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertTrue("栏高 ${h.value}", h.value >= 56f && h.value <= 58f)
        assertTrue("回报高 ${reported.value}", reported.value >= 56f)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
    fun 内容更高时栏随内容长高() {
        bar(160.dp)
        val h = compose.onNodeWithTag("bar").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertTrue("栏高 ${h.value} 应 ≥ 160 + 12 内距", h.value >= 172f)
        assertTrue("回报高 ${reported.value}", reported.value >= 172f)
    }
}
