package com.situ.aichat.ui.liuli.chat.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.sticker.StickerService
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-18：琉璃版表情包选择器（图纸 2026-09-05 卷二C §7 · E27 · 照抄源 F22）。
 *
 * 钉的是「三段 / 两版空态 / 点选即发并关 / 管理入口」这四条**行为**与暖陶逐字同——皮换了、
 * 语义不许换。骨架态（prefs 未就绪）改由 §11-C6 偏差 D-C6-2 说明：两份 prefs 读的是真
 * SharedPreferences、没有注入口，骨架只闪亚毫秒，拿时序断言必 flaky（PITFALLS §1e）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliStickerPickerSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) { content() }
            }
        }
    }

    private fun sheet(
        onSelect: (String) -> Unit = {},
        onManage: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) = show {
        LiuliStickerPickerSheet(
            customStickers = emptyList(),
            onSelect = onSelect,
            onManage = onManage,
            onDismiss = onDismiss,
        )
    }

    @Test fun 三段段名照抄且可切换() {
        sheet()
        listOf("最近使用", "全部表情", "我的表情").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("全部表情").performClick()
        // 切到「全部表情」后必须真出内置目录（空态文案不许还在）。
        val first = StickerService.allStickers(emptyList(), emptySet()).first()
        compose.onNodeWithText(first.name).assertIsDisplayed()
    }

    @Test fun tab0空态文案且没有添加钮() {
        sheet()
        compose.onNodeWithText("还没有使用过表情包").assertIsDisplayed()
        assertEquals(
            "「添加表情包」只属「我的表情」这一段",
            0,
            compose.onAllNodes(hasText("添加表情包")).fetchSemanticsNodes().size,
        )
    }

    @Test fun tab2空态文案并带添加表情包钮() {
        var manage = 0
        sheet(onManage = { manage++ })
        compose.onNodeWithText("我的表情").performClick()
        compose.onNodeWithText("还没有自定义表情包").assertIsDisplayed()
        compose.onNodeWithText("添加表情包").performClick()
        assertEquals(1, manage)
    }

    @Test fun 点一格即发送并关闭各恰一次() {
        val picked = mutableListOf<String>()
        var dismissed = 0
        sheet(onSelect = { picked += it }, onDismiss = { dismissed++ })
        compose.onNodeWithText("全部表情").performClick()
        val first = StickerService.allStickers(emptyList(), emptySet()).first()
        compose.onNodeWithText(first.name).performClick()
        assertEquals(listOf(first.id), picked)
        assertEquals("点选即发并关（照抄 F22）", 1, dismissed)
    }

    @Test fun 底部管理表情包走onManage恰一次() {
        var manage = 0
        sheet(onManage = { manage++ })
        compose.onNodeWithText("全部表情").performClick()
        compose.onNodeWithText("管理表情包").performClick()
        assertEquals(1, manage)
    }
}
