package com.situ.aichat.ui.designsystem

import android.content.Context
import android.provider.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-F：编辑页门楣 [AppFormBar] 的行为（Robolectric·图纸
 * `2026-09-05-釉烧主钮与编辑页门楣.md` §7）。
 *
 * 升起态的底色 / 发丝、标题 88dp 双侧内边距的像素落值属像素域（由「几何与 [AppTopBar] 同源单源
 * [BarScaffold]」+ 装机担保；贴边槽的 gutter 几何另由 [GutterAlignmentTest] 机器钉）；本测钉行为面：三槽按传参渲染、左右回调各自触发、超长标题不把两侧钮
 * 挤到点不着、**纯文字状态槽零可点节点**（拍板⑤「已自动保存」不做成钮）、`onCancel = null` 零左槽、
 * lifted × reduceMotion 组合不崩。
 *
 * qualifiers 钉 zh-rCN + 真机尺寸：左钮默认文案取 `R.string.action_cancel`（顺带验 zh/en 成对），
 * 屏太小会让节点被推出可视区导致 `performClick` 静默不命中（PITFALLS §1e）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class AppFormBarTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)

    private fun content(block: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) { block() }
        }
    }

    private fun disableSystemAnimations() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @Test
    fun F1_三槽按传参渲染_左右回调各自触发_标题是heading() {
        var cancels = 0
        var saves = 0
        content {
            AppFormBar(
                title = "编辑资料",
                onCancel = { cancels++ },
                trailing = { AppButton(onClick = { saves++ }) { Text("保存") } },
            )
        }

        // 左钮不传 cancelText → 走组件内单源 action_cancel。
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.onNodeWithText("编辑资料").assertIsDisplayed()
        compose.onNodeWithText("编辑资料")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        compose.onNodeWithText("保存").assertIsDisplayed()

        compose.onNodeWithText("取消").performClick()
        assertEquals("左槽只掉自己的回调", 1, cancels)
        assertEquals(0, saves)
        compose.onNodeWithText("保存").performClick()
        assertEquals(1, saves)
        assertEquals("右槽不许串到左槽", 1, cancels)
    }

    @Test
    fun F1b_cancelText覆写_左钮走站点文案() {
        content {
            AppFormBar(title = "编辑模块", onCancel = {}, cancelText = "返回")
        }

        compose.onNodeWithText("返回").assertIsDisplayed()
        assertEquals(
            "传了 cancelText 就不该再出默认「取消」",
            0,
            compose.onAllNodes(hasText("取消")).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun F2_超长标题_两侧钮仍点得着() {
        val long = "内容过滤规则编辑：把这条规则命名成一个长到能把两边按钮挤没的标题（压力测试）"
        var cancels = 0
        var saves = 0
        content {
            AppFormBar(
                title = long,
                onCancel = { cancels++ },
                trailing = { AppButton(onClick = { saves++ }) { Text("保存") } },
            )
        }

        compose.onNodeWithText(long).assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("保存").performClick()
        assertEquals("超长标题不许把左钮挤到点不着", 1, cancels)
        assertEquals("超长标题不许把右钮挤到点不着", 1, saves)
    }

    @Test
    fun F3_右槽是纯文字状态时_零可点节点() {
        content {
            AppFormBar(
                title = "编辑模块",
                onCancel = {},
                trailing = { Text("已自动保存") },
            )
        }

        // 文案要读得出（TalkBack 得知道自动保存了）……
        compose.onNodeWithText("已自动保存").assertIsDisplayed()
        // ……但它不是钮：整条门楣里可点的节点只剩左边那枚「取消」。
        assertEquals(
            "状态槽不许被做成/渲染成可点节点",
            1,
            compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size,
        )
        // 正向锚：可点的那一个确实是「取消」（防「一个都没有」也凑巧等于 1 的假绿）。
        compose.onNodeWithText("取消").assertIsDisplayed()
    }

    @Test
    fun F4_onCancel为null时零左槽节点_标题照常在() {
        content {
            AppFormBar(title = "新建角色", trailing = { AppButton(onClick = {}) { Text("保存") } })
        }

        compose.onNodeWithText("新建角色").assertIsDisplayed()
        assertEquals(
            "onCancel = null 就不该渲染左槽",
            0,
            compose.onAllNodes(hasText("取消")).fetchSemanticsNodes().size,
        )
        compose.onNodeWithText("保存").assertIsDisplayed()
    }

    @Test
    fun F5_lifted与reduceMotion组合_三槽仍可达() {
        disableSystemAnimations()
        var saves = 0
        content {
            AppFormBar(
                title = "编辑资料",
                lifted = true,
                onCancel = {},
                trailing = { AppButton(onClick = { saves++ }) { Text("保存") } },
            )
        }

        // 升起层画在背景（background + drawBehind），绝不能盖住三槽。
        compose.onNodeWithText("编辑资料").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.onNodeWithText("保存").performClick()
        assertEquals(1, saves)
    }
}
