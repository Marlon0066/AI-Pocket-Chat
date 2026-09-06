package com.situ.aichat.ui.liuli.chat.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.situ.aichat.redpacket.RedPacketSendOutcome
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
import java.time.LocalDate
import java.time.ZoneId

/**
 * T2-19：琉璃版发红包底片（图纸 2026-09-05 卷二C §7 · E22 · 照抄源 F25）。**钱路只显示不改**——
 * 断言全部钉在「校验门 / 三条错误文案 / 主钮两版文案 / 成功即关」这些与暖陶逐字同的行为上。
 *
 * 期望文案从规格反推重新打字（不 import 实现常量），越线即红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliRedPacketComposerSheetTest {

    @get:Rule
    val compose = createComposeRule()

    /** 情人节当天 0 点（`FestivalCalendar` 的 GregorianFixed(2, 14)）——用来验「今日节日自动预填」。 */
    private val valentinesDay =
        LocalDate.of(2026, 2, 14).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** 非节日日（3 月 20 日·目录里 3 月只有 8 / 14 两天）。 */
    private val ordinaryDay =
        LocalDate.of(2026, 3, 20).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) { content() }
            }
        }
    }

    /** 金额框：它没有 label（「金额」是框上方独立标题），故按「可输入」取第一枚（第二枚是祝福语）。 */
    private fun amountField() = compose.onAllNodes(hasSetTextAction()).onFirst()

    private fun sheet(
        balance: Int = 500,
        now: Long = ordinaryDay,
        onSend: suspend (Int, String, String?) -> RedPacketSendOutcome = { _, _, _ -> RedPacketSendOutcome.Success },
        onDismiss: () -> Unit = {},
    ) = show {
        LiuliRedPacketComposerSheet(
            characterName = "小夏",
            balance = balance,
            onSend = onSend,
            onDismiss = onDismiss,
            now = now,
        )
    }

    @Test fun 今日节日自动预填且可toggle掉() {
        sheet(now = valentinesDay)
        compose.onNodeWithText("🎉 今天是").assertIsDisplayed()
        compose.onNodeWithText("情人节").assertIsSelected()
        compose.onNodeWithText("情人节").performClick()
        compose.onNodeWithText("情人节").assertIsNotSelected()
    }

    @Test fun 金额只留数字并钳到上限() {
        sheet()
        amountField().performTextInput("9a9999")
        // 只留数字取前 5 位 = 99999 > 20000 → 钳成 20000（照抄 F25）。
        compose.onNodeWithText("🧧 塞 20000 金币进红包").assertIsDisplayed()
    }

    @Test fun 主钮两版文案() {
        sheet()
        compose.onNodeWithText("🧧 先选金额").assertIsDisplayed()
        amountField().performTextInput("66")
        compose.onNodeWithText("🧧 塞 66 金币进红包").assertIsDisplayed()
    }

    @Test fun 余额不足时主钮不可点() {
        sheet(balance = 10)
        amountField().performTextInput("66")
        compose.onNodeWithText("🧧 塞 66 金币进红包").assertIsNotEnabled()
    }

    @Test fun 未填金额时主钮不可点() {
        sheet()
        compose.onNodeWithText("🧧 先选金额").assertIsNotEnabled()
    }

    @Test fun 发送成功即关闭恰一次() {
        var dismissed = 0
        sheet(onDismiss = { dismissed++ })
        amountField().performTextInput("66")
        compose.onNodeWithText("🧧 塞 66 金币进红包").performClick()
        compose.waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test fun 发送失败显示服务端文案且不关闭() {
        var dismissed = 0
        sheet(
            onSend = { _, _, _ -> RedPacketSendOutcome.Failed("网络开小差了") },
            onDismiss = { dismissed++ },
        )
        amountField().performTextInput("66")
        compose.onNodeWithText("🧧 塞 66 金币进红包").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("网络开小差了").assertIsDisplayed()
        assertEquals("失败不关弹层，让用户能改能重试", 0, dismissed)
    }
}
