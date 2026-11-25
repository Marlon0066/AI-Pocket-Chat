package com.situ.aichat.ui.liuli.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.AppSettings
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
 * T2：通知页内容层（设置全面重构卷四 B-4 / B-5 / D-6）：
 * - 权限卡只在未授权时显示
 * - 角色消息通知大卡关闭时，夜间免打扰 / 世界通知 / 每角色开关变灰示因（B-5）
 * - 角色经济动态常显，不再受高级模式隐藏（D-6）
 * - 世界通知 + 6 类「其他通知」+ 底部说明条齐备（B-4）
 * - 滑杆松手才写库且吸附到 30 分钟整档
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliNotificationContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val hasPermission = mutableStateOf(true)
    private val globalOn = mutableStateOf(true)
    private val quietOn = mutableStateOf(false)
    private val chars = mutableStateOf(emptyList<CharacterEntity>())
    private val calls = mutableMapOf<String, Any>()
    private val charToggles = mutableListOf<Pair<String, Boolean>>()

    private fun character(uuid: String, name: String) = CharacterEntity(
        uuid = uuid,
        name = name,
        creationDate = 0L,
    )

    private fun sliders() = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))

    private fun show() {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    LiuliNotificationContent(
                        hasPermission = hasPermission.value,
                        globalEnabled = globalOn.value,
                        quietHoursEnabled = quietOn.value,
                        quietHoursStart = 1320,
                        quietHoursEnd = 450,
                        worldTier = AppSettings.WORLD_NOTIFICATION_GENTLE,
                        calendarMode = CalendarReminderMode.BOTH,
                        economyTier = EconomyNotificationTier.BRIEF,
                        other = LiuliOtherNotificationsState(),
                        characters = chars.value,
                        disabledIds = setOf("b"),
                        onGrantPermission = { calls["grant"] = true },
                        onOpenSystemSettings = { calls["sys"] = true },
                        onSetGlobalEnabled = { calls["global"] = it },
                        onSetQuietHoursEnabled = { calls["quiet"] = it },
                        onSetQuietHoursStart = { calls["start"] = it },
                        onSetQuietHoursEnd = { calls["end"] = it },
                        onSetWorldTier = { calls["world"] = it },
                        onSetCalendarMode = { calls["calendar"] = it },
                        onSetEconomyTier = { calls["economy"] = it },
                        onSetMilestoneEnabled = { calls["milestone"] = it },
                        onSetMomentNewPostEnabled = { calls["momentNew"] = it },
                        onSetMomentInteractionEnabled = { calls["momentInteract"] = it },
                        onSetStoryFinishedEnabled = { calls["story"] = it },
                        onSetMeetingReminderEnabled = { calls["meeting"] = it },
                        onSetRedPacketExpiringEnabled = { calls["redPacket"] = it },
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

    @Test fun 角色消息总开关关闭时免打扰与每角色开关变灰示因() {
        globalOn.value = false
        chars.value = listOf(character("a", "小满"))
        show()
        compose.onNodeWithText("角色消息通知").assertExists()
        compose.onNodeWithText("夜间免打扰", substring = false).assertExists()
        assertTrue(
            compose.onAllNodesWithText("先开启上方的「角色消息通知」", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        compose.onNodeWithText("小满").performScrollTo().assertIsNotEnabled()
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
        sliders()[0].performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1295f) }
        compose.waitForIdle()
        assertEquals(1290, calls["start"])
        assertTrue("落库值必须落在 30 分钟整档上", (calls["start"] as Int) % 30 == 0)
        assertTrue("终点滑杆没被误写", calls["end"] == null)
    }

    @Test fun D6_经济三档常显且可切换() {
        show()
        compose.onNodeWithText("角色经济动态").performScrollTo().assertExists()
        compose.onNodeWithText("简要（默认）").performScrollTo().assertIsSelected()
        compose.onNodeWithText("关闭").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(EconomyNotificationTier.OFF, calls["economy"])
    }

    @Test fun B4_世界通知与六类其他通知与底部说明条齐备() {
        show()
        compose.onNodeWithText("世界通知").performScrollTo().assertExists()
        compose.onNodeWithText("全部").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(AppSettings.WORLD_NOTIFICATION_ALL, calls["world"])

        compose.onNodeWithText("其他通知").performScrollTo().assertExists()
        compose.onNodeWithText("红包快过期提醒").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(false, calls["redPacket"])

        compose.onNodeWithText("宠物照顾提醒在宠物页里管理，故事更新提醒跟随每本书自己的设置。")
            .performScrollTo().assertExists()
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
        compose.onNodeWithText("林晚").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("b" to true), charToggles)
    }
}
