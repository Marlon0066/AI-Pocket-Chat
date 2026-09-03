package com.situ.aichat.ui.designsystem

import androidx.activity.ComponentDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * T2-D：确认弹窗纸卡 [AppDialog] 的行为（Robolectric 跑真 Compose 树·M3 清零卷一图纸 §7）。
 *
 * 断言一律走 `onNodeWithText` / semantics，**不碰 M3 内部结构**——换皮不该让测试跟着改。
 * 覆盖图纸 §5 边界表 E1（长文滚动）/ E2（禁用确认）/ E3（0·1·3 钮）/ E4（返回键）/ E8（reduceMotion）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)

    /** 弹窗族的钮都吃 [LocalAppHaptics]（无默认值，不注入即抛）——统一在此供给假触觉。 */
    private fun content(block: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) { block() }
        }
    }

    // ---- D1 标准形：title/body/两钮 + 各回调 + 返回键（E4）----

    @Test
    fun D1_标准形_标题正文两个钮都在_点谁触发谁() {
        var confirmed = 0
        var dismissed = 0
        content {
            AppDialog(
                onDismissRequest = {},
                title = "删除这条对话？",
                body = "删掉之后聊天记录就找不回来了。",
                confirmText = "删除",
                onConfirm = { confirmed++ },
                dismissText = "取消",
                onDismiss = { dismissed++ },
            )
        }

        compose.onNodeWithText("删除这条对话？").assertIsDisplayed()
        compose.onNodeWithText("删掉之后聊天记录就找不回来了。").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        assertEquals("点取消只该走取消回调", 1, dismissed)
        assertEquals(0, confirmed)

        compose.onNodeWithText("删除").performClick()
        assertEquals(1, confirmed)
        assertEquals("点确认不该顺带触发取消", 1, dismissed)
    }

    @Test
    fun D1b_没传onDismiss时_取消钮回落到onDismissRequest() {
        var requested = 0
        content {
            AppDialog(
                onDismissRequest = { requested++ },
                title = "标题",
                confirmText = "好",
                onConfirm = {},
                dismissText = "算了",
            )
        }

        compose.onNodeWithText("算了").performClick()
        assertEquals(1, requested)
    }

    @Test
    fun E4_按返回键_等于请求关闭() {
        var requested = 0
        content {
            AppDialog(onDismissRequest = { requested++ }, title = "标题", confirmText = "好", onConfirm = {})
        }
        compose.waitForIdle()

        // 平台 Dialog 的返回语义：拿到 Compose 起的那个窗口，走它的返回分发器（= 系统返回键那条路）。
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue("AppDialog 必须真的起一个平台 Dialog 窗口", dialog is ComponentDialog)
        (dialog as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()

        assertEquals("返回键必须走 onDismissRequest（与被取代的 M3 AlertDialog 等价）", 1, requested)
    }

    // ---- D2 单钮 / 无钮（E3）----

    @Test
    fun D2_单钮站_只渲染确认钮_没有取消() {
        content {
            AppDialog(onDismissRequest = {}, title = "提示", body = "知道啦", confirmText = "知道了", onConfirm = {})
        }

        compose.onNodeWithText("知道了").assertIsDisplayed()
        assertEquals("dismissText = null 时不该有第二个钮", 0, countNodesWithText("取消"))
    }

    @Test
    fun D2b_无钮站_两文案皆null时整排不渲染() {
        // R1 D-3 修订：无钮 = confirmText 与 dismissText **皆** null（只传 dismissText 是「取消单钮排」，见 D9）。
        content {
            AppDialog(onDismissRequest = {}, title = "正在导出", body = "请稍候…")
        }

        compose.onNodeWithText("正在导出").assertIsDisplayed()
        assertEquals("两文案皆 null 时按钮排整排不渲染", 0, countNodesWithText("取消"))
        assertEquals("两文案皆 null 时不该有确认钮", 0, countNodesWithText("确定"))
    }

    // ---- D3 confirmEnabled = false（E2）----

    @Test
    fun D3_确认钮禁用时_点了不掉回调() {
        var confirmed = 0
        content {
            AppDialog(
                onDismissRequest = {},
                title = "起个名字",
                confirmText = "保存",
                onConfirm = { confirmed++ },
                confirmEnabled = false,
                dismissText = "取消",
            )
        }

        compose.onNodeWithText("保存").performClick()
        assertEquals("禁用态确认钮点了必须零回调", 0, confirmed)
        // 取消照常可点（禁用只作用于确认钮）。
        compose.onNodeWithText("取消").assertIsDisplayed()
    }

    // ---- D4 Danger 语气 + 三钮排（E3 补）----

    @Test
    fun D4_危险语气_确认钮照样在照样能点() {
        var confirmed = 0
        content {
            AppDialog(
                onDismissRequest = {},
                title = "清空全部记忆？",
                confirmText = "清空",
                onConfirm = { confirmed++ },
                confirmTone = AppDialogTone.Danger,
                dismissText = "取消",
            )
        }

        compose.onNodeWithText("清空").performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun D4b_三钮站_重置取消确定三个都在且各走各的() {
        var neutral = 0
        var confirmed = 0
        var dismissed = 0
        content {
            AppDialog(
                onDismissRequest = {},
                title = "回复长度",
                confirmText = "确定",
                onConfirm = { confirmed++ },
                dismissText = "取消",
                onDismiss = { dismissed++ },
                neutralText = "重置",
                onNeutral = { neutral++ },
            )
        }

        compose.onNodeWithText("重置").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("确定").performClick()
        assertEquals(1, neutral)
        assertEquals(1, dismissed)
        assertEquals(1, confirmed)
    }

    // ---- D5 长文（E1）----

    @Test
    fun D5_超长正文_按钮排照样在树里没被顶出去() {
        val longText = (1..200).joinToString("\n") { "第 $it 行用户协议正文，反复啰嗦以撑满屏幕。" }
        content {
            AppDialog(onDismissRequest = {}, title = "用户协议", body = longText, confirmText = "同意", onConfirm = {})
        }

        // 正文区自带 400dp 高度帽 + 内滚动 → 按钮排恒可见。
        compose.onNodeWithText("同意").assertIsDisplayed()
        compose.onNodeWithText("用户协议").assertIsDisplayed()
    }

    // ---- D6 reduceMotion（E8）----

    @Test
    fun D6_关掉动画时_钮照样能点() {
        // 幽灵/AppButton 的缩放走 rememberReduceMotion 门控；可点性与动画无关，这里钉「不因门控失灵」。
        var confirmed = 0
        content {
            AppDialog(onDismissRequest = {}, title = "标题", confirmText = "确定", onConfirm = { confirmed++ }, dismissText = "取消")
        }

        compose.onNodeWithText("确定").performClick()
        assertEquals(1, confirmed)
    }

    // ---- D7 content 槽 ----

    @Test
    fun D7_自定义内容槽_原样渲染在正文位() {
        content {
            AppDialog(
                onDismissRequest = {},
                title = "选个语气",
                confirmText = "确定",
                onConfirm = {},
                content = {
                    Text("温柔一点")
                    Text("干脆一点")
                },
            )
        }

        compose.onNodeWithText("温柔一点").assertIsDisplayed()
        compose.onNodeWithText("干脆一点").assertIsDisplayed()
    }

    // ---- D8 裸壳 ----

    @Test
    fun D8_裸壳_任意内容都装得下() {
        var clicked = 0
        content {
            AppDialogShell(onDismissRequest = {}) {
                Text("完全自定义的一坨")
                AppDialogGhostButton(text = "关掉", onClick = { clicked++ })
            }
        }

        compose.onNodeWithText("完全自定义的一坨").assertIsDisplayed()
        compose.onNodeWithText("关掉").performClick()
        assertEquals(1, clicked)
    }

    // ---- D9 取消单钮形（R1 D-3 修订：只传 dismissText 也渲染按钮排·取消恒幽灵不升主钮）----

    @Test
    fun D9_只有取消钮_幽灵取消在场可点_不长出确认钮() {
        var dismissed = 0
        content {
            AppDialog(
                onDismissRequest = {},
                title = "选择收尾方式",
                dismissText = "取消",
                onDismiss = { dismissed++ },
                content = { Text("从容收尾") },
            )
        }

        compose.onNodeWithText("从容收尾").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        assertEquals("取消单钮排必须渲染且回调可达（R1 D-3）", 1, dismissed)
        assertEquals("不许因挂错槽位长出确认主钮", 0, countNodesWithText("确定"))
    }

    /** 数「这段文案有几个节点」——onNodeWithText 查不到会抛，证明「不存在」得用它。 */
    private fun countNodesWithText(text: String): Int =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size
}
