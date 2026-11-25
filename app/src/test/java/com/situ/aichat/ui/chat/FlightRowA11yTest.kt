package com.situ.aichat.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.liuli.chat.LiuliChatList
import com.situ.aichat.ui.liuli.chat.LiuliFoldState
import com.situ.aichat.ui.liuli.chat.LiuliReactionState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2 飞行期聊天气泡读屏可见性测试（批一 Step 1-5 回归锁，对应调查报告 `2026-09-28-聊天气泡读屏隐身`）：
 *
 * 根因：旧实现给飞行期整行挂 `Modifier.alpha(0f)`，Android 读屏树将节点标记为 `isVisibleToUser=false`
 * （Compose 测试里 `assertIsDisplayed()` 亦因 alpha==0 判定不可见），落地后易卡死在读屏不可见态。
 * 修复改用 `Modifier.drawWithContent { }` 只挡绘制、不动透明度层与语义树；本测试分别驱动真件
 * [ChatMessageList]（暖陶）与 [LiuliChatList]（琉璃 2.0）验证飞行中与落地后均对语义树可见。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class FlightRowA11yTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)
    private val nowMs = 1_700_000_000_000L

    private fun userMessage(uuid: String, text: String) = MessageEntity(
        messageUUID = uuid,
        conversationUuid = "c1",
        roleRaw = "user",
        content = text,
        timestamp = nowMs,
    )

    private val noopActions = MessageRowActions(
        onVoiceToggle = {},
        onOpenImage = {},
        onSaveImage = {},
        onQuote = {},
        onDelete = {},
        onOpenMenu = { _, _, _ -> },
        onFlightBubblePositioned = { _, _ -> },
        onRegenerate = {},
        loadDiyImage = { null },
        onOpenDiyDetail = {},
        observeRedPacket = { flowOf(null) },
        onRedPacketClick = {},
        onAcceptInvite = {},
        onDeclineInvite = {},
        onEndMeeting = {},
        onContinueMeeting = {},
        onReviewOffline = {},
        observeAppointment = { flowOf(null) },
        onAppointmentAccept = {},
        onAppointmentDecline = {},
        onAppointmentReschedule = {},
        onAppointmentChangeApply = {},
        onAppointmentChangeKeep = {},
        onVoiceCascadePlayed = {},
        onOpenVoiceSetup = {},
    )

    @Test
    fun warmClay_flyingMessageRemainsVisibleInSemanticsDuringAndAfterFlight() {
        val msg = userMessage("m-flight-warm", "暖陶飞行气泡读屏测试")
        val flight = ChatSendFlightState(nowMs = { nowMs }).apply {
            inputBounds = Rect(0f, 800f, 300f, 860f)
            beginFlight(msg, Rect(100f, 600f, 380f, 660f))
        }
        assertEquals(msg.messageUUID, flight.flightUuid)

        val vm = mockk<ChatViewModel>(relaxed = true).apply {
            every { arrivalAppointment } returns MutableStateFlow(null)
            every { nextCountdownAppointment } returns MutableStateFlow(null)
        }
        val messages = listOf(msg)
        val render = buildChatRenderItems(messages, null, nowMs + 1_000L)

        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                val listState = rememberLazyListState()
                val scope = rememberCoroutineScope()
                val deleteArm = remember { mutableStateOf(0L) }
                val coordinator = remember(scope) { ChatScrollCoordinator(mockk(relaxed = true), scope) }
                Box(Modifier.width(411.dp).height(600.dp)) {
                    ChatMessageList(
                        viewModel = vm,
                        sheets = ChatSheetsState(),
                        listState = listState,
                        scrollCoordinator = coordinator,
                        listItems = render.asReversed(),
                        messages = messages,
                        dismissKeyboardOnDrag = object : NestedScrollConnection {},
                        playingVoiceId = null,
                        voiceProgress = { 0f },
                        reduceMotion = true,
                        emotionAnimationEnabled = false,
                        animateArrivalsSinceMillis = Long.MAX_VALUE,
                        entryScalePlayed = mutableSetOf(),
                        emotionPlayed = mutableListOf(),
                        emotionHiddenIntervals = emptyList(),
                        actions = noopActions,
                        userScrollEnabled = true,
                        deleteArm = deleteArm,
                        sendFlight = flight,
                        characterName = "云野",
                        avatarPath = null,
                        userName = "我",
                        userAvatarPath = null,
                        customStickers = emptyList(),
                        isSending = false,
                        networkConnected = true,
                        networkStatusChanged = null,
                        showScrollDown = false,
                        wallpaper = null,
                    )
                }
            }
        }

        // 飞行中：视觉由覆盖层代画，底层行通过 drawWithContent {} 挡绘，不置 alpha(0f)，语义节点须保持可见。
        compose.onNodeWithText("暖陶飞行气泡读屏测试", useUnmergedTree = true).assertIsDisplayed()

        // 落地后：flightUuid 清空，交还自绘，语义节点依旧可见。
        compose.runOnUiThread { flight.endFlight() }
        compose.waitForIdle()
        assertNull(flight.flightUuid)
        compose.onNodeWithText("暖陶飞行气泡读屏测试", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun liuli_flyingMessageRemainsVisibleInSemanticsDuringAndAfterFlight() {
        val msg = userMessage("m-flight-liuli", "琉璃飞行气泡读屏测试")
        val flight = ChatSendFlightState(nowMs = { nowMs }).apply {
            inputBounds = Rect(0f, 800f, 300f, 860f)
            beginFlight(msg, Rect(100f, 600f, 380f, 660f))
        }
        assertEquals(msg.messageUUID, flight.flightUuid)

        val messages = listOf(msg)
        val render = buildChatRenderItems(messages, null, nowMs + 1_000L)

        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                val listState = rememberLazyListState()
                val deleteArm = remember { mutableStateOf(0L) }
                Box(Modifier.width(411.dp).height(600.dp)) {
                    LiuliChatList(
                        listState = listState,
                        listItems = render.asReversed(),
                        messages = messages,
                        dismissKeyboardOnDrag = object : NestedScrollConnection {},
                        playingVoiceId = null,
                        voiceProgress = { 0f },
                        reduceMotion = true,
                        emotionAnimationEnabled = false,
                        animateArrivalsSinceMillis = Long.MAX_VALUE,
                        entryScalePlayed = mutableSetOf(),
                        emotionPlayed = mutableListOf(),
                        emotionHiddenIntervals = emptyList(),
                        actions = noopActions,
                        userScrollEnabled = true,
                        deleteArm = deleteArm,
                        sendFlight = flight,
                        reaction = LiuliReactionState(),
                        fold = LiuliFoldState(),
                        characterName = "云野",
                        avatarPath = null,
                        userName = "我",
                        userAvatarPath = null,
                        customStickers = emptyList(),
                        isSending = false,
                        contentTopPadding = 100.dp,
                        contentBottomPadding = 68.dp,
                    )
                }
            }
        }

        compose.onNodeWithText("琉璃飞行气泡读屏测试", useUnmergedTree = true).assertIsDisplayed()

        compose.runOnUiThread { flight.endFlight() }
        compose.waitForIdle()
        assertNull(flight.flightUuid)
        compose.onNodeWithText("琉璃飞行气泡读屏测试", useUnmergedTree = true).assertIsDisplayed()
    }
}
