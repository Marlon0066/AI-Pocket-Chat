package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity

/**
 * T2-25：琉璃版空会话引导（图纸 2026-09-05 卷二C §7 · E29 · §4.13 · 照抄源 F29）。
 *
 * 三句开场白与提示语是**锁定文本**（§9 ①），此处重新打字钉住——对版稿写的「试着说一句」只是示意，
 * 落地必须照暖陶原字。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliEmptyHintTest {

    @get:Rule
    val compose = createComposeRule()

    private val starters = listOf("早上好呀～", "在忙什么呢？", "给我讲个故事吧")

    private fun show(
        characterName: String = "小夏",
        persona: String = "爱看云的插画师",
        onStarter: (String) -> Unit = {},
        density: Float = 1f,
    ) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false) {
                CompositionLocalProvider(
                    LocalAppHaptics provides mockk<AppHaptics>(relaxed = true),
                    LocalDensity provides Density(density, LocalDensity.current.fontScale),
                ) {
                    LiuliEmptyHint(
                        characterName = characterName,
                        avatarPath = null,
                        persona = persona,
                        moodEmoji = "😊",
                        onStarter = onStarter,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    @Test fun 名字人设提示语与三句开场白都在() {
        show()
        compose.onNodeWithText("小夏").assertIsDisplayed()
        compose.onNodeWithText("爱看云的插画师").assertIsDisplayed()
        compose.onNodeWithText("试试这样开场：").assertIsDisplayed()
        starters.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test fun 点一句开场白_原字回调恰一次() {
        val sent = mutableListOf<String>()
        show(onStarter = { sent += it })
        compose.onNodeWithText("在忙什么呢？").performClick()
        assertEquals(listOf("在忙什么呢？"), sent)
    }

    @Test fun 人设为空时那一行整行不画() {
        show(persona = "   ")
        compose.onNodeWithText("小夏").assertIsDisplayed()
        assertEquals(
            "空人设不许留一个空行占版",
            0,
            compose.onAllNodes(hasText("爱看云的插画师")).fetchSemanticsNodes().size,
        )
    }

    @Test fun 名字为空退问号_胶囊触达48() {
        show(characterName = "")
        // 名字位与头像首字母都会退成「?」（CharacterAvatar 同口径），故按计数断言而非唯一节点。
        assertTrue(
            "空名字必须退成「?」而不是空白",
            compose.onAllNodes(hasText("?")).fetchSemanticsNodes().isNotEmpty(),
        )
        starters.forEach { starter ->
            val b = compose.onNodeWithText(starter).getUnclippedBoundsInRoot()
            assertTrue("「$starter」触达高 ${b.bottom - b.top}", (b.bottom - b.top).value >= 47.5f)
        }
    }

    /**
     * R2 🟡-3 回归锁：胶囊**视觉体**只有「字高 + 8/14 内距」，48 只属于点击面——把 `liuliCardSurface`
     * 排在 `liuliTouchHeight` 之后会让胶囊本体被量成 48 高（装机 c6_13 实证胶囊粗了一圈）。
     */
    @Test fun 胶囊视觉不被触达框撑高_触达仍48且视觉居中() {
        // Robolectric 的假字高恒 32px（不随 sp / fontScale 变），1x 密度下 32 + 内距 16 恰好 = 48dp，
        // 与被撑高的错版一模一样看不出差别；改 2x 密度后内距变 32px、字仍 32px → 正确版视觉体 64px = 32dp，
        // 触达框 96px = 48dp——两版才分得开（负向对照：错版视觉体也是 48dp）。
        show(density = 2f)
        val pills = compose.onAllNodesWithTag(LIULI_STARTER_PILL_TAG, useUnmergedTree = true)
        pills.assertCountEquals(3)
        starters.forEachIndexed { i, starter ->
            val visual = pills[i].getUnclippedBoundsInRoot()
            val touch = compose.onNodeWithText(starter).getUnclippedBoundsInRoot()
            val visualH = (visual.bottom - visual.top).value
            val touchH = (touch.bottom - touch.top).value
            assertTrue("「$starter」视觉高 $visualH 必须小于触达 48（= 字高 + 16）", visualH < 47f)
            assertTrue("「$starter」触达高 $touchH", touchH >= 47.5f)
            assertEquals(
                "视觉体须居中在触达框里",
                ((touch.top + touch.bottom) / 2).value,
                ((visual.top + visual.bottom) / 2).value,
                1f,
            )
        }
    }


}
