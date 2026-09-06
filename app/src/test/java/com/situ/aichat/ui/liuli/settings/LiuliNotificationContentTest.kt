package com.situ.aichat.ui.liuli.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.notification.CalendarReminderMode
import com.situ.aichat.notification.EconomyNotificationTier
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

/**
 * T2：通知页内容层（图纸 2026-09-06 卷四 §8 C3b · A-7 · §5 E2 / E3 / E6）。
 *
 * 钉：权限卡只在未授权时在（E2）；免打扰关着时 range 文与两条滑杆都不组合（E3）；经济三档只在高级模式
 * 显（E6）；滑杆**松手才写库**且写的是吸附到 30 分钟整档的值（A-7 + 机制锁 §9 ④）；每角色开关带 uuid。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliNotificationContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val hasPermission = mutableStateOf(true)
    private val quietOn = mutableStateOf(false)
    private val advanced = mutableStateOf(false)
    private val chars = mutableStateOf(emptyList<CharacterEntity>())
    private val calls = mutableMapOf<String, Any>()
    private val charToggles = mutableListOf<Pair<String, Boolean>>()

    private fun character(uuid: String, name: String) = CharacterEntity(
        uuid = uuid,
        name = name,
        creationDate = 0L,
    )

    /** 两条免打扰滑杆（`SetProgress` 是它们独有的语义动作）。 */
    private fun sliders() = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))

    private fun show() {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    LiuliNotificationContent(
                        hasPermission = hasPermission.value,
                        globalEnabled = true,
                        quietHoursEnabled = quietOn.value,
                        quietHoursStart = 1320,
                        quietHoursEnd = 450,
                        calendarMode = CalendarReminderMode.BOTH,
                        economyTier = EconomyNotificationTier.BRIEF,
                        milestoneEnabled = true,
                        advancedEnabled = advanced.value,
                        characters = chars.value,
                        disabledIds = setOf("b"),
                        onGrantPermission = { calls["grant"] = true },
                        onOpenSystemSettings = { calls["sys"] = true },
                        onSetGlobalEnabled = { calls["global"] = it },
                        onSetQuietHoursEnabled = { calls["quiet"] = it },
                        onSetQuietHoursStart = { calls["start"] = it },
                        onSetQuietHoursEnd = { calls["end"] = it },
                        onSetCalendarMode = { calls["calendar"] = it },
                        onSetEconomyTier = { calls["economy"] = it },
                        onSetMilestoneEnabled = { calls["milestone"] = it },
                        onSetCharacterEnabled = { uuid, on -> charToggles += uuid to on },
                        onBack = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun 已授权时不显权限卡未授权时显两钮() {
        show()
        compose.onNodeWithText("授予通知权限").assertDoesNotExist()
        compose.runOnIdle { hasPermission.value = false }
        compose.waitForIdle()
        compose.onNodeWithText("授予通知权限").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(true, calls["grant"])
        compose.onNodeWithText("前往系统设置").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(true, calls["sys"])
    }

    @Test fun 免打扰关着时range文与两滑杆都不组合() {
        show()
        compose.onNodeWithText("22:00 – 次日 07:30").assertDoesNotExist()
        sliders().assertCountEquals(0)
        compose.runOnIdle { quietOn.value = true }
        compose.waitForIdle()
        compose.onNodeWithText("22:00 – 次日 07:30").performScrollTo().assertExists()
        sliders().assertCountEquals(2)
    }

    @Test fun 滑杆松手才写库且写吸附后的整档值() {
        quietOn.value = true
        show()
        // 拖到一个非整档值（`SetProgress` = 「拖一下再松手」：依次落 onValueChange + onValueChangeFinished）。
        sliders()[0].performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1295f) }
        compose.waitForIdle()
        // 落库的值必须是 30 分钟整档（1295 → 最近档 1290 = 21:30），且只写起点、不碰终点。
        assertEquals(1290, calls["start"])
        assertTrue("落库值必须落在 30 分钟整档上", (calls["start"] as Int) % 30 == 0)
        assertTrue("终点滑杆没被误写", calls["end"] == null)
    }

    @Test fun 经济三档只在高级模式显() {
        show()
        compose.onNodeWithText("角色经济动态").assertDoesNotExist()
        compose.runOnIdle { advanced.value = true }
        compose.waitForIdle()
        compose.onNodeWithText("简要（默认）").performScrollTo().assertIsSelected()
        compose.onNodeWithText("关闭").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(EconomyNotificationTier.OFF, calls["economy"])
    }

    @Test fun 日历三档单选与回调() {
        show()
        compose.onNodeWithText("两者都用（推荐）").performScrollTo().assertIsSelected()
        compose.onNodeWithText("仅系统提醒").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(CalendarReminderMode.SYSTEM, calls["calendar"])
    }

    @Test fun 每角色空态与开关带uuid() {
        show()
        compose.onNodeWithText("还没有角色").performScrollTo().assertExists()
        compose.runOnIdle { chars.value = listOf(character("a", "小满"), character("b", "林晚")) }
        compose.waitForIdle()
        // b 在 disabledIds 里 → 关；点它应回 true。
        compose.onNodeWithText("林晚").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("b" to true), charToggles)
    }
}
