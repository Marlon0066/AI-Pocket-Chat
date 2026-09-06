package com.situ.aichat.ui.liuli.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * T2-3 琉璃顶栏行为（图纸 2026-09-05 卷二A §7·Robolectric·范式照 `AppFormBarTest`）。
 *
 * 钉的是**行为面**（长相属像素域，由 §4.2 落值 + C4 装机担保）：副标回退链（日程优先→心情）、
 * 见面态换「结束见面」且藏通话钮（E13）、`canStartCall=false` 无通话钮、加载态不显假名、
 * 名片点击带 uuid 回调。屏尺寸钉真机档——太小会把右侧钮推出可视区致 `performClick` 静默不命中（PITFALLS §1e）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliChatTopBarTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)

    private fun topBar(
        characterName: String = "云野",
        loading: Boolean = false,
        innerStateLine: String? = null,
        scheduleStatus: String? = null,
        moodEmoji: String = "",
        moodText: String = "",
        isInOfflineMode: Boolean = false,
        characterUuid: String? = "uuid-1",
        canStartCall: Boolean = true,
        onOpenProfile: (String) -> Unit = {},
        onEndMeeting: () -> Unit = {},
    ) {
        setContent {
            LiuliChatTopBar(
                characterName = characterName,
                loading = loading,
                avatarPath = null,
                innerStateLine = innerStateLine,
                scheduleStatus = scheduleStatus,
                moodEmoji = moodEmoji,
                moodText = moodText,
                isInOfflineMode = isInOfflineMode,
                characterUuid = characterUuid,
                onBack = {},
                onOpenProfile = onOpenProfile,
                onEndMeeting = onEndMeeting,
                canStartCall = canStartCall,
                onStartCall = {},
            )
        }
    }

    private fun setContent(block: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) { block() }
        }
    }

    @Test fun subtitle_prefersScheduleStatus_overMood() {
        topBar(scheduleStatus = "在写稿", moodEmoji = "😊", moodText = "很开心")
        compose.onNodeWithText("在写稿").assertIsDisplayed()
        compose.onNodeWithText("😊 很开心").assertDoesNotExist()
    }

    @Test fun subtitle_fallsBackToMoodRow_whenNoSchedule() {
        topBar(scheduleStatus = null, moodEmoji = "😊", moodText = "很开心")
        compose.onNodeWithText("😊 很开心").assertIsDisplayed()
    }

    @Test fun offlineMode_showsEndMeeting_andHidesCallButton() {
        var ended = 0
        topBar(isInOfflineMode = true, canStartCall = true, onEndMeeting = { ended++ })
        compose.onNodeWithText("结束见面").assertIsDisplayed()
        compose.onNodeWithContentDescription("语音通话").assertDoesNotExist()
        compose.onNodeWithText("结束见面").performClick()
        assertEquals(1, ended)
    }

    @Test fun callButton_absent_whenCannotStartCall() {
        topBar(canStartCall = false)
        compose.onNodeWithContentDescription("语音通话").assertDoesNotExist()
        // 正向证据：同一组合里返回钮在场 —— 证明「找不到通话钮」不是整棵树没渲染。
        compose.onNodeWithContentDescription("返回").assertIsDisplayed()
    }

    @Test fun callButton_present_whenCanStartCall_andNotOffline() {
        topBar(canStartCall = true, isInOfflineMode = false)
        compose.onNodeWithContentDescription("语音通话").assertIsDisplayed()
        compose.onNodeWithText("结束见面").assertDoesNotExist()
    }

    @Test fun loading_showsNoFakeName() {
        topBar(characterName = "云野", loading = true)
        compose.onNodeWithText("云野").assertDoesNotExist()
        compose.onNodeWithText("聊天").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").assertIsDisplayed()
    }

    @Test fun nameCard_click_opensProfileWithUuid() {
        var opened: String? = null
        topBar(onOpenProfile = { opened = it })
        compose.onNodeWithText("云野").performClick()
        assertEquals("uuid-1", opened)
    }

    @Test fun nameCard_notClickable_whenUuidMissing() {
        var opened: String? = null
        topBar(characterUuid = null, onOpenProfile = { opened = it })
        compose.onNodeWithText("云野").performClick()
        assertEquals(null, opened)
    }

    // ── 卷二B T2-10：副标链首位接上「此刻」 ────────────────────────────────────

    @Test fun innerStateLine_winsOverSchedule() {
        topBar(innerStateLine = "有点想你。", scheduleStatus = "在写稿", moodText = "还行")
        compose.onNodeWithText("有点想你。").assertIsDisplayed()
        compose.onNodeWithText("在写稿").assertDoesNotExist()
        compose.onNodeWithText("还行").assertDoesNotExist()
    }

    @Test fun noInnerStateLine_fallsBackToSchedule() {
        topBar(innerStateLine = null, scheduleStatus = "在写稿", moodText = "还行")
        compose.onNodeWithText("在写稿").assertIsDisplayed()
        compose.onNodeWithText("还行").assertDoesNotExist()
    }
}
