package com.situ.aichat.ui.designsystem

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * T2-T：二级页门楣 [AppTopBar] 的行为（Robolectric·M3 清零收官图纸 §7）。
 *
 * 升起态的底色 / 发丝属像素域（由「token 逐字迁移 + 装机浅深量测」担保）；本测试钉行为面：标题带 heading
 * 语义、返回钮读得出「返回」且回调可达、`onBack = null` 零返回节点、超长标题不崩、actions 槽有无两态、
 * lifted × reduceMotion 组合下内容仍可达（防升起层把标题盖住）。
 *
 * qualifiers 钉 zh-rCN：返回钮的可读名取自 `R.string.action_back`，顺带把「zh/en 成对」验掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class AppTopBarTest {

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
    fun T1_标准形_标题是heading_返回钮读得出返回且回调可达() {
        var backs = 0
        content {
            AppTopBar(title = "通知设置", onBack = { backs++ })
        }

        compose.onNodeWithText("通知设置").assertIsDisplayed()
        compose.onNodeWithText("通知设置")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
        compose.onNodeWithContentDescription("返回").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun T2_onBack为null时一个返回节点都没有_标题照常在() {
        content {
            AppTopBar(title = "圈子")
        }

        compose.onNodeWithText("圈子").assertIsDisplayed()
        assertEquals(
            "onBack = null 就不该渲染返回钮",
            0,
            compose.onAllNodes(hasContentDescription("返回")).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun T3_超长标题_单行省略不崩_返回钮照样可达() {
        val long = "世界书条目：云野大陆·北境冬狩祭的三日流程与忌讳全录（长标题压力测试）"
        var backs = 0
        content {
            AppTopBar(title = long, onBack = { backs++ })
        }

        compose.onNodeWithText(long).assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals("超长标题不许把返回钮挤到点不着", 1, backs)
    }

    @Test
    fun T4_actions槽_有则渲染且回调可达_无则零节点() {
        var acted = 0
        content {
            AppTopBar(
                title = "接口配置",
                onBack = {},
                actions = {
                    AppTopBarAction(icon = AppTopBarIcons.More, contentDescription = "更多", onClick = { acted++ })
                },
            )
        }

        compose.onNodeWithContentDescription("更多").assertIsDisplayed()
        compose.onNodeWithContentDescription("更多").performClick()
        assertEquals(1, acted)
    }

    @Test
    fun T4b_没有actions时右侧零动作节点() {
        content {
            AppTopBar(title = "接口配置", onBack = {})
        }

        assertEquals(
            "actions = null 就不该有动作节点",
            0,
            compose.onAllNodes(hasContentDescription("更多")).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun T5_lifted与reduceMotion组合_渲染不崩内容仍在() {
        disableSystemAnimations()
        content {
            AppTopBar(title = "备份与恢复", onBack = {}, lifted = true)
        }

        compose.onNodeWithText("备份与恢复").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").assertIsDisplayed()
    }

    @Test
    fun B1_backEnabled为false_返回钮节点仍在但点不动() {
        var backs = 0
        content {
            AppTopBar(title = "新建故事", onBack = { backs++ }, backEnabled = false)
        }

        // 「不能退出」≠「没有退出」：钮必须还在原位（防实现成 onBack = null 让钮整个消失）。
        assertEquals(
            "禁用的返回钮必须仍在原位",
            1,
            compose.onAllNodes(hasContentDescription("返回")).fetchSemanticsNodes().size,
        )
        compose.onNodeWithContentDescription("返回").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").assertIsNotEnabled()
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals("创建中禁止退出：点了也不许掉回调", 0, backs)
    }

    @Test
    fun B2_backEnabled翻回true_回调恢复正常() {
        var enabled by mutableStateOf(false)
        var backs = 0
        content {
            AppTopBar(title = "新建故事", onBack = { backs++ }, backEnabled = enabled)
        }

        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(0, backs)

        enabled = true
        compose.waitForIdle()
        compose.onNodeWithContentDescription("返回").assertIsEnabled()
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals("创建结束后返回钮恢复可用", 1, backs)
    }

    @Test
    fun T6_lifted翻转后_标题与返回钮仍可达() {
        var lifted by mutableStateOf(false)
        var backs = 0
        content {
            AppTopBar(title = "日记", onBack = { backs++ }, lifted = lifted)
        }

        compose.onNodeWithText("日记").assertIsDisplayed()
        lifted = true
        compose.waitForIdle()
        // 升起层画在背景（drawBehind + background），绝不能盖住标题与返回钮。
        compose.onNode(hasText("日记")).assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(1, backs)
    }
}
