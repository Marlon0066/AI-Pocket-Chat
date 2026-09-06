package com.situ.aichat.ui.liuli.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppBottomNavItem
import com.situ.aichat.ui.designsystem.AppNavIcons
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-1：玻璃底栏的行为面（图纸 2026-09-06 卷三 §7 T2-1 · §4.1 · E4 / E8）。
 *
 * 像素域（玻璃五要素 / 滑丸位移 / 按压缩放）由装机担保；本测钉：四槽的文案与 `Role.Tab` 选中语义、点击回调
 * 恰一次、徽章的 0 / 100 两端、**缩起后只剩当前 Tab 且高 44**、点小丸只展开不导航、每槽触达 ≥ 48。
 *
 * 缩起态由**真的滚一下**驱动（`chrome.connection.onPostScroll`），不是直接改状态——这样测的是接线不是断言。
 * 真机尺寸 qualifiers 防节点被推出可视区（PITFALLS §1e）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliTabBarTest {

    @get:Rule
    val compose = createComposeRule()

    private val clicks = IntArray(4)
    private lateinit var chrome: LiuliHomeChrome

    private fun items(selected: Int, badges: IntArray = IntArray(4)): List<AppBottomNavItem> =
        listOf("聊天" to AppNavIcons.Chat, "联系人" to AppNavIcons.Contacts, "动态" to AppNavIcons.Moments, "我" to AppNavIcons.Profile)
            .mapIndexed { i, (label, icon) ->
                AppBottomNavItem(
                    icon = icon,
                    label = label,
                    selected = i == selected,
                    onClick = { clicks[i]++ },
                    badgeCount = badges[i],
                )
            }

    private fun show(selected: Int = 0, badges: IntArray = IntArray(4)) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    chrome = rememberLiuliHomeChrome()
                    LiuliTabBar(items = items(selected, badges), chrome = chrome)
                }
            }
        }
        compose.waitForIdle()
    }

    /** 模拟「向下滚 [dp] 个 dp」的 nested-scroll 上报（负号 = 看后面的内容）。 */
    private fun scrollDown(dp: Float) = scroll(-dp)

    /** 列表真滚过的量走 `consumed`；[unconsumedDp] = 列表滚不动 / 已到底时剩下的量（走 `available`）。 */
    private fun scroll(dp: Float, unconsumedDp: Float = 0f) {
        compose.runOnIdle {
            val px = dp * compose.density.density
            val rest = unconsumedDp * compose.density.density
            chrome.connection.onPostScroll(Offset(0f, px), Offset(0f, rest), NestedScrollSource.UserInput)
        }
        compose.waitForIdle()
    }

    @Test fun 四槽文案与选中语义() {
        show(selected = 1)
        listOf("聊天", "联系人", "动态", "我").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("联系人").assertIsSelected()
        compose.onNodeWithText("聊天").assertIsNotSelected()
    }

    @Test fun 点某槽只触发它自己的回调恰一次() {
        show(selected = 0)
        compose.onNodeWithText("动态").performClick()
        compose.waitForIdle()
        assertEquals(listOf(0, 0, 1, 0), clicks.toList())
    }

    @Test fun 徽章为零不画() {
        show(selected = 0, badges = intArrayOf(0, 0, 0, 0))
        compose.onNodeWithText("0").assertDoesNotExist()
    }

    @Test fun 徽章过百显九九加() {
        show(selected = 0, badges = intArrayOf(100, 0, 3, 0))
        compose.onNodeWithText("99+").assertIsDisplayed()
        compose.onNodeWithText("3").assertIsDisplayed()
    }

    @Test fun 下滚够阈值后只剩当前Tab的小丸且高四十四() {
        show(selected = 1)
        scrollDown(30f)
        compose.onNodeWithText("联系人").assertIsDisplayed().assertHeightIsEqualTo(LiuliHomeGeometry.tabMini)
        listOf("聊天", "动态", "我").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test fun 点小丸只展开不导航() {
        show(selected = 1)
        scrollDown(30f)
        compose.onNodeWithText("联系人").performClick()
        compose.waitForIdle()
        assertEquals("点小丸绝不触发 Tab 导航", listOf(0, 0, 0, 0), clicks.toList())
        // 展开回四槽。
        listOf("聊天", "联系人", "动态", "我").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test fun 列表滚不动时手指再拖也不缩丸() {
        // E1：铺不满一屏的列表，手指拖 60dp 但列表一格不动（consumed = 0·全在 available）→ 底栏必须纹丝不动。
        show(selected = 0)
        scroll(0f, unconsumedDp = -60f)
        listOf("聊天", "联系人", "动态", "我").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    @Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
    fun 透镜丸七十二乘四十六且包住选中槽的图标与字() {
        // 用户 09-06「丸甲 + 形状 B」：丸 72×46；零重叠 ⑥ = 丸纵向包住图标 + 字。用 xhdpi（2x）限定符拆开 dp 与
        // Robolectric 恒 32px 的假字高（PITFALLS §1d）——根约束仍是 411dp 宽（覆盖 LocalDensity 会把底栏挤成 205dp·
        // 独立复核 🔵-6）。字底与丸底当前余量 0.5dp，全靠假字高恒定；框架升级若翻红先看这里。
        show(selected = 1)
        val pill = compose.onNodeWithTag(LIULI_TAB_PILL_TAG).getUnclippedBoundsInRoot()
        assertEquals(72f, (pill.right - pill.left).value, 0.5f)
        assertEquals(46f, (pill.bottom - pill.top).value, 0.5f)
        // 槽是 selectable（合并子孙语义）→ 合并树里「联系人」就是整个 52 高的槽；量真正的字要走未合并树。
        val label = compose.onNodeWithText("联系人", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals("字底不许探出丸底（字底 ${label.bottom} 丸底 ${pill.bottom}）", true, label.bottom <= pill.bottom)
        assertEquals("丸横向居中于槽（字中心 = 丸中心）", (pill.left + pill.right).value / 2f, (label.left + label.right).value / 2f, 1f)
    }

    @Test fun 缩起后列表在顶再往下拽就展开() {
        // 独立复核 🟡-1：缩起后列表被程序性缩短 / 小步回顶 → 「在顶 + 缩起」；此时手指往下拽 consumed = 0、
        // available > 0，必须展开（否则只能点小丸脱困）。向上的 available 仍忽略（E1 不回退）。
        show(selected = 0)
        scrollDown(30f)
        listOf("联系人", "动态", "我").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        scroll(0f, unconsumedDp = 60f)
        listOf("聊天", "联系人", "动态", "我").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test fun 每槽触达至少四十八() {
        show(selected = 0)
        listOf("聊天", "联系人", "动态", "我").forEach {
            compose.onNodeWithText(it).assertHeightIsAtLeast(48.dp)
        }
    }
}
