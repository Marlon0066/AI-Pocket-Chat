package com.situ.aichat.ui.liuli.chat.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.situ.aichat.data.model.MeetingTimeGranularity
import com.situ.aichat.meeting.MeetingDisplayFormatter
import com.situ.aichat.meeting.MeetingTimeResolver
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.meeting.resolveFormSchedule
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * T2-23：琉璃版「约个见面 / 换个时间」表单（图纸 2026-09-05 卷二C §7 · E26 · 照抄源 F26 后半）。
 *
 * 钉：预览行走 `whenDisplay`、开关关掉 → 时间胶囊消失且 `onConfirm` 收 DAY_ONLY、
 * `showPlaceActivity = false` 时无两框且确认恒可点、改期预填带入原时刻、两钮回调。
 * 日期标签走**重打的** `liuliDateLabel`——期望值从 [MeetingDisplayFormatter] 独立算出来对表，
 * 不照抄实现输出。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliFutureMeetingFormSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val zone: ZoneId = ZoneId.systemDefault()
    private val confirmed = mutableListOf<Triple<Long, MeetingTimeGranularity, Pair<String, String>>>()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) { content() }
            }
        }
    }

    private fun sheet(
        title: String = "约个见面",
        confirmLabel: String = "约定！",
        showPlaceActivity: Boolean = true,
        initialMillis: Long? = null,
        initialGranularity: MeetingTimeGranularity? = null,
        onDismiss: () -> Unit = {},
    ) = show {
        LiuliFutureMeetingFormSheet(
            title = title,
            confirmLabel = confirmLabel,
            showPlaceActivity = showPlaceActivity,
            onConfirm = { millis, gran, loc, act -> confirmed += Triple(millis, gran, loc to act) },
            onDismiss = onDismiss,
            initialMillis = initialMillis,
            initialGranularity = initialGranularity,
        )
    }

    @Test fun 预览行走whenDisplay_默认今天且带具体时间() {
        sheet()
        val today = LocalDate.now(zone)
        val expected = resolveFormSchedule(
            today,
            true,
            LocalTime.of(MeetingTimeResolver.DEFAULT_DAY_ONLY_HOUR, 0),
            zone,
        ).let { MeetingDisplayFormatter.whenDisplay(it.first, it.second, zone) }
        compose.onNodeWithText(expected).assertIsDisplayed()
    }

    @Test fun 关掉具体时间开关_时间胶囊消失且落DAY_ONLY() {
        sheet()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).performClick()
        assertEquals(
            "关掉后不该再有 HH:mm 胶囊",
            0,
            compose.onAllNodes(hasText(":", substring = true)).fetchSemanticsNodes().size,
        )
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("公园")
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("散步")
        compose.onNodeWithText("约定！").performClick()
        assertEquals(1, confirmed.size)
        assertEquals(MeetingTimeGranularity.DAY_ONLY, confirmed.single().second)
    }

    @Test fun 改期档无两框且确认恒可点_预填原时刻() {
        val original = LocalDate.now(zone).plusDays(3).atTime(15, 30).atZone(zone).toInstant().toEpochMilli()
        sheet(
            title = "换个时间",
            confirmLabel = "改到这天",
            showPlaceActivity = false,
            initialMillis = original,
            initialGranularity = MeetingTimeGranularity.EXACT,
        )
        compose.onNodeWithText("15:30").assertIsDisplayed()
        assertEquals("改期档没有地点 / 活动两框", 0, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
        compose.onNodeWithText("改到这天").performClick()
        val out = confirmed.single()
        assertEquals(MeetingTimeGranularity.EXACT, out.second)
        assertEquals("改期不动地点 / 活动", "" to "", out.third)
        assertEquals(original, out.first)
    }

    @Test fun 手动档两框任一为空就确认不了() {
        sheet()
        compose.onNodeWithText("约定！").performClick()
        assertTrue("两框空时点不动", confirmed.isEmpty())
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("公园")
        compose.onNodeWithText("约定！").performClick()
        assertTrue("只填一个也点不动", confirmed.isEmpty())
    }

    @Test fun 确认走trim后的两值并关闭() {
        var dismissed = 0
        sheet(onDismiss = { dismissed++ })
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("  公园 ")
        compose.onAllNodes(hasSetTextAction())[1].performTextInput(" 散步 ")
        compose.onNodeWithText("约定！").performClick()
        assertEquals("公园" to "散步", confirmed.single().third)
        assertEquals(1, dismissed)
    }

    @Test fun 取消钮直接关闭且不落任何约定() {
        var dismissed = 0
        sheet(onDismiss = { dismissed++ })
        compose.onNodeWithText("取消").performClick()
        assertEquals(1, dismissed)
        assertTrue(confirmed.isEmpty())
    }

    /** 日期胶囊文案 = dayOnly 口径的 `whenDisplay`（重打的 `liuliDateLabel` 与暖陶 `dateLabel` 同式）。 */
    @Test fun 日期胶囊文案与whenDisplay对表() {
        sheet()
        val today = LocalDate.now(zone)
        val millis = today.atTime(MeetingTimeResolver.DEFAULT_DAY_ONLY_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        val expected = MeetingDisplayFormatter.whenDisplay(millis, MeetingTimeGranularity.DAY_ONLY, zone)
        compose.onNodeWithText(expected).assertIsDisplayed()
        assertEquals(today, Instant.ofEpochMilli(millis).atZone(zone).toLocalDate())
    }
}
