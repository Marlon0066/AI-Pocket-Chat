package com.situ.aichat.ui.liuli.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.data.calendar.CalendarAction
import com.situ.aichat.data.calendar.CalendarActionType
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
 * T2-3 琉璃日历确认卡（图纸 2026-09-05 卷二B §7）：标题拼法、删除态的不可撤销警示与确认词，
 * 以及两枚钮各自回调恰一次。
 *
 * 标题与确认词都**从规格反推**：标题 = `{角色}想{actionVerb}一个{typeDisplayName}`，事件类的
 * `typeDisplayName` 是「日历事件」（`CalendarAction.kt:70`）；确认词恒 `confirmButtonText`（A-4）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliCalendarCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)
    private var confirms = 0
    private var cancels = 0

    private fun setCard(action: CalendarAction) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                LiuliCalendarCard(
                    characterName = "云野",
                    action = action,
                    onConfirm = { confirms++ },
                    onCancel = { cancels++ },
                )
            }
        }
    }

    @Test fun updateEvent_showsComposedTitle_andNoIrreversibleWarning() {
        setCard(CalendarAction(action = CalendarActionType.UPDATE_EVENT, title = "周六喝茶", ref = "#E1"))
        compose.onNodeWithText("云野想修改一个日历事件").assertIsDisplayed()
        compose.onNodeWithText("周六喝茶").assertIsDisplayed()
        compose.onNodeWithText("确认修改").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.onNodeWithText("此操作不可撤销").assertDoesNotExist()
    }

    @Test fun deleteEvent_warnsIrreversible_andKeepsConfirmButton() {
        setCard(CalendarAction(action = CalendarActionType.DELETE_EVENT, title = "周六喝茶", ref = "#E1"))
        compose.onNodeWithText("云野想删除一个日历事件").assertIsDisplayed()
        compose.onNodeWithText("此操作不可撤销").assertIsDisplayed()
        compose.onNodeWithText("确认删除").assertIsDisplayed()
    }

    @Test fun locationRow_carriesPinPrefix() {
        setCard(
            CalendarAction(
                action = CalendarActionType.CREATE_EVENT,
                title = "看展",
                location = "老地方",
                notes = "别迟到",
            ),
        )
        compose.onNodeWithText("📍 老地方").assertIsDisplayed()
        compose.onNodeWithText("别迟到").assertIsDisplayed()
    }

    @Test fun buttons_reportExactlyOnceEach() {
        setCard(CalendarAction(action = CalendarActionType.UPDATE_EVENT, title = "周六喝茶", ref = "#E1"))
        compose.onNodeWithText("确认修改").performClick()
        compose.waitForIdle()
        assertEquals("确认恰一次", 1, confirms)
        assertEquals("确认不该顺带触发取消", 0, cancels)
        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()
        assertEquals("取消恰一次", 1, cancels)
        assertEquals(1, confirms)
    }
}
