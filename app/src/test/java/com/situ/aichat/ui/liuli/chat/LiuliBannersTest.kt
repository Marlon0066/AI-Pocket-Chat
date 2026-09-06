package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.meeting.MeetingDisplayFormatter
import com.situ.aichat.data.model.MeetingTimeGranularity
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
import java.time.ZoneId

/**
 * T2-26：琉璃版横幅族（图纸 2026-09-05 卷二C §7 · E30 · §4.14 · 照抄源 F30–F31）。
 *
 * 钉：两版网络文案 + 恢复 2s 自动消（推 `mainClock`·**不许**靠真等）、倒数条两种拼法（含明细 /
 * 无明细·Q-C2 拍板显示明细）、`···` 菜单两项各自的回调、赴约钮回调、toast 两档与关闭圆触达 48。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliBannersTest {

    @get:Rule
    val compose = createComposeRule()

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) { content() }
            }
        }
    }

    private fun appointment(activity: String = "", location: String = "") = MeetingAppointmentEntity(
        uuid = "appt-1",
        status = "confirmed",
        scheduledAt = System.currentTimeMillis() + 2 * 60 * 60_000L,
        timeGranularity = MeetingTimeGranularity.EXACT.raw,
        location = location,
        activity = activity,
    )

    private fun countdownOf(appt: MeetingAppointmentEntity): String =
        MeetingDisplayFormatter.countdownText(
            appt.scheduledAt,
            MeetingTimeGranularity.fromRaw(appt.timeGranularity),
            System.currentTimeMillis(),
            zone,
        )

    @Test fun 离线文案常驻不自动消() {
        var shown = 0
        show { LiuliNetworkBanner(connected = false, recovered = false, onRecoveredShown = { shown++ }) }
        compose.onNodeWithText("网络连接已断开").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(5_000)
        assertEquals("离线是状态不是错误，绝不自动消", 0, shown)
    }

    @Test fun 恢复文案2秒后回调恰一次() {
        var shown = 0
        show { LiuliNetworkBanner(connected = true, recovered = true, onRecoveredShown = { shown++ }) }
        compose.onNodeWithText("网络已恢复").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals("不到 2s 不许消", 0, shown)
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(1, shown)
    }

    @Test fun 倒数条含明细的拼法() {
        val appt = appointment(activity = "看展", location = "美术馆")
        show { LiuliCountdownChip(appt = appt, characterName = "小夏", onReschedule = {}, onCancel = {}) }
        compose.onNodeWithText("${countdownOf(appt)}和小夏见面 · 看展 · 美术馆").assertIsDisplayed()
    }

    @Test fun 倒数条无明细时不留尾巴分隔点() {
        val appt = appointment()
        show { LiuliCountdownChip(appt = appt, characterName = "小夏", onReschedule = {}, onCancel = {}) }
        compose.onNodeWithText("${countdownOf(appt)}和小夏见面").assertIsDisplayed()
    }

    @Test fun 名字为空退TA() {
        val appt = appointment()
        show { LiuliCountdownChip(appt = appt, characterName = "  ", onReschedule = {}, onCancel = {}) }
        compose.onNodeWithText("${countdownOf(appt)}和TA见面").assertIsDisplayed()
    }

    @Test fun 点倒数条弹菜单两项_各走各的回调() {
        var reschedule = 0
        var cancel = 0
        val appt = appointment()
        show {
            LiuliCountdownChip(
                appt = appt,
                characterName = "小夏",
                onReschedule = { reschedule++ },
                onCancel = { cancel++ },
            )
        }
        assertEquals("收起态菜单一个节点都不冒", 0, compose.onAllNodes(hasText("改期")).fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("约定操作").performClick()
        compose.onNodeWithText("改期").assertIsDisplayed()
        compose.onNodeWithText("取消约定").assertIsDisplayed()
        compose.onNodeWithText("取消约定").performClick()
        assertEquals(0, reschedule)
        assertEquals(1, cancel)
    }

    @Test fun 赴约钮文案与回调恰一次() {
        var arrive = 0
        show { LiuliArrivalButton(onArrive = { arrive++ }) }
        compose.onNodeWithText("到点啦，去赴约").performClick()
        assertEquals(1, arrive)
    }

    @Test fun 日历toast两档与关闭圆触达48() {
        var dismissed = 0
        show {
            Box(Modifier.fillMaxSize()) {
                LiuliCalendarToast(
                    text = "已删除日程",
                    isDelete = true,
                    reduceMotion = true,
                    onDismiss = { dismissed++ },
                )
            }
        }
        compose.onNodeWithText("已删除日程").assertIsDisplayed()
        val b = compose.onNodeWithContentDescription("关闭").getUnclippedBoundsInRoot()
        assertTrue("toast 关闭圆触达 ${b.bottom - b.top}", (b.bottom - b.top).value >= 47.5f)
        compose.onNodeWithContentDescription("关闭").performClick()
        assertEquals(1, dismissed)
    }
}
