package com.situ.aichat.ui.liuli.page

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.model.AppSkin
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
import com.situ.aichat.ui.liuli.page.LiuliPageCircleAction
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag

/**
 * T2：二级屏页壳（图纸 2026-09-06 卷四 §4.2 · A-3 · §8 C1）。
 *
 * 钉：未收起时屏上只有大标题一处、收起后**多**出玻璃顶栏里的小标题（大标题仍在内容里随内容滚）；
 * subBar 只在收起态出现；返回 / 尾随圆钮**两态一像素不动**（A-3 恒在 overlay）且触达 ≥ 48；
 * 回调各恰一次；[LiuliPage] 的 `hero` 开关决定内容是否从窗口顶起（A-3 的顶内距两层给法）。
 *
 * 注：Robolectric 下 `WindowInsets.statusBars` 恒 0，所以「状态栏那一层」在这里量不出差别——
 * 本测试钉的是**导航行那 44dp**（调用方 contentPadding），状态栏那半留装机批。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliPageTest {

    @get:Rule
    val compose = createComposeRule()

    private var backTaps = 0
    private var editTaps = 0
    private val collapsedState = mutableStateOf(false)

    private fun show(
        collapsed: Boolean,
        withActions: Boolean = false,
        withSubBar: Boolean = false,
        hero: Boolean = false,
    ) {
        collapsedState.value = collapsed
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    LiuliPage(
                        title = "通知设置",
                        onBack = { backTaps++ },
                        collapsed = collapsedState.value,
                        actions = if (withActions) {
                            {
                                LiuliPageCircleAction(onClick = { editTaps++ }, contentDescription = "编辑", icon = Icons.Filled.Edit)
                            }
                        } else {
                            null
                        },
                        subBar = if (withSubBar) {
                            { Text("分段条") }
                        } else {
                            null
                        },
                        hero = hero,
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = if (hero) {
                                PaddingValues(0.dp)
                            } else {
                                PaddingValues(top = LiuliPageGeometry.navRow)
                            },
                        ) {
                            item(key = "marker") {
                                Box(Modifier.testTag("marker").fillMaxWidth().height(10.dp))
                            }
                            item(key = "large-title") { LiuliLargeTitle("通知设置") }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun 未收起时屏上只有大标题一处() {
        show(collapsed = false)
        compose.onAllNodesWithText("通知设置").assertCountEquals(1)
    }

    @Test fun 收起后小标题出现且大标题仍在内容里() {
        show(collapsed = true)
        compose.onAllNodesWithText("通知设置").assertCountEquals(2)
    }

    @Test fun subBar只在收起态出现() {
        show(collapsed = false, withSubBar = true)
        compose.onNodeWithText("分段条").assertDoesNotExist()
        compose.runOnIdle { collapsedState.value = true }
        compose.waitForIdle()
        compose.onAllNodesWithText("分段条").assertCountEquals(1)
    }

    @Test fun 返回钮两态位置不动且版位40() {
        show(collapsed = false)
        val expanded = compose.onNodeWithContentDescription("返回").getUnclippedBoundsInRoot()
        compose.runOnIdle { collapsedState.value = true }
        compose.waitForIdle()
        val collapsedBounds = compose.onNodeWithContentDescription("返回").getUnclippedBoundsInRoot()
        assertEquals(expanded.left.value, collapsedBounds.left.value, 0.01f)
        assertEquals(expanded.top.value, collapsedBounds.top.value, 0.01f)
        // 版位 = 视觉 40（触达 48 由圆钮自带的框居中外溢·PITFALLS §1d）；左 = gutter 20、顶 = 状态栏(0) + 2。
        assertEquals(40f, (collapsedBounds.bottom - collapsedBounds.top).value, 0.01f)
        assertEquals(LiuliPageGeometry.gutter.value, collapsedBounds.left.value, 0.01f)
        assertEquals(LiuliPageGeometry.titleTop.value, collapsedBounds.top.value, 0.01f)
    }

    @Test fun 返回钮触达48() {
        show(collapsed = false)
        // `LiuliCircleButton` 的 clickable 排在 `minimumInteractiveComponentSize` 之后 → 点击面 48。
        // 触达 = 48（Compose 最小触达框·复核 R1 🟡-8：原断言 ≥40 验的是版位不是触达）。
        compose.onNodeWithContentDescription("返回")
            .assertTouchWidthIsEqualTo(48.dp)
            .assertTouchHeightIsEqualTo(48.dp)
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        assertEquals(1, backTaps)
    }

    @Test fun 尾随动作在右侧且回调恰一次() {
        show(collapsed = false, withActions = true)
        val back = compose.onNodeWithContentDescription("返回").getUnclippedBoundsInRoot()
        val edit = compose.onNodeWithContentDescription("编辑").getUnclippedBoundsInRoot()
        // 同一行（顶边齐）、在返回钮右边。
        assertEquals(back.top.value, edit.top.value, 0.01f)
        assert(edit.left.value > back.right.value) { "尾随动作应在返回钮右侧" }
        // 右距 = gutter 20（圆心落在 屏右 − 20 − 20·复核 R1 🟡-3：裸放 LiuliCircleButton 会被 48 脚印顶成 24）。
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val expectedCenter = root.right - LiuliPageGeometry.gutter - LiuliPageGeometry.backButton / 2
        assertEquals(expectedCenter.value, ((edit.left + edit.right) / 2).value, 0.01f)
        compose.onNodeWithContentDescription("编辑").performClick()
        compose.waitForIdle()
        assertEquals(1, editTaps)
        assertEquals(0, backTaps)
    }

    @Test fun 非hero页内容让过导航行() {
        show(collapsed = false)
        val marker = compose.onNodeWithTag("marker").getUnclippedBoundsInRoot()
        // 状态栏(Robolectric 恒 0) + 导航行 44。
        assertEquals(LiuliPageGeometry.navRow.value, marker.top.value, 0.01f)
    }

    @Test fun hero页内容从窗口顶起() {
        show(collapsed = false, hero = true)
        val marker = compose.onNodeWithTag("marker").getUnclippedBoundsInRoot()
        assertEquals(0f, marker.top.value, 0.01f)
    }
}

/** 卷五复核 R1：返回钮可禁用（导入进行中）+ 导航行纸面带只在未收起的非 hero 页在场。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliPageBackAndBandTest {

    @get:Rule
    val compose = createComposeRule()

    private fun page(collapsed: Boolean, hero: Boolean = false, backEnabled: Boolean = true, onBack: () -> Unit = {}) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    LiuliPage(title = "导入预览", onBack = onBack, collapsed = collapsed, hero = hero, backEnabled = backEnabled) {}
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun 返回钮禁用时不回调() {
        var taps = 0
        page(collapsed = false, backEnabled = false, onBack = { taps++ })
        compose.onNodeWithContentDescription("返回").assertIsNotEnabled()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        assertEquals(0, taps)
    }

    @Test fun 未收起时导航行纸面带在场收起后撤走() {
        page(collapsed = false)
        compose.onNodeWithTag(LIULI_NAV_BAND_TAG).assertExists()
    }

    @Test fun 收起后纸面带撤走() {
        page(collapsed = true)
        compose.onAllNodesWithTag(LIULI_NAV_BAND_TAG).assertCountEquals(0)
    }

    @Test fun hero页从不画纸面带() {
        page(collapsed = false, hero = true)
        compose.onAllNodesWithTag(LIULI_NAV_BAND_TAG).assertCountEquals(0)
    }
}
