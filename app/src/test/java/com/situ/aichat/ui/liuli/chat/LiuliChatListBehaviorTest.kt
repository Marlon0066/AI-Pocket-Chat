package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.ui.chat.ChatSendFlightState
import com.situ.aichat.ui.chat.MessageRowActions
import com.situ.aichat.ui.chat.buildChatRenderItems
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-1 琉璃列表行为（图纸 2026-09-05 卷二A §7·范式照 `ReversedChatListBehaviorTest`）——**跑真件**
 * [LiuliChatList]，不手抄一份 LazyColumn，故四参数任一被改必红。
 *
 * 断言全走**差分**（换视口 / 换 padding 看位移量），不依赖气泡内边距与 Robolectric 的字形宽（PITFALLS §1e：
 * 字形宽失真使绝对像素断言无区分力）：
 * 1. 反转序：最新一条在下、更早的在上；
 * 2. 底部锚定：视口缩小 X → 最新一条随底边上移 X（键盘 / 面板缩视口时最新气泡钉在托盘上沿）；
 * 3. `contentPadding.bottom` 真落在**视觉底部**：68 → 100 使最新一条恰上移 32dp；
 * 4. `contentPadding.top` 落在**视觉顶部**：加大 40dp 不动最新一条；
 * 5. 头部插入更早消息（prepend）不扰动最新一条（按 key 锚定）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliChatListBehaviorTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)
    private val baseTime = 1_700_000_000_000L

    private fun msg(uuid: String, role: String, atMs: Long) = MessageEntity(
        messageUUID = uuid,
        conversationUuid = "c",
        roleRaw = role,
        content = uuid,
        timestamp = atMs,
    )

/**
     * 30 条（用户 / AI 交替、每条相隔 2 分钟 = 条条断层各自成段）——**必须撑满视口**：`Arrangement.Top`
     * 锁的「铺不满一屏时贴顶」是有意行为（契约 REVERSE_LIST §2.1），短列表下最新一条并不贴底，
     * 底部锚定的断言在那种规模上恒为假绿 / 假红（本测首轮实证）。
     */
    private fun manyMessages(count: Int = 30) = (0 until count).map { i ->
        msg("m$i", if (i % 2 == 0) "user" else "assistant", baseTime + i * 120_000L)
    }

    private val newest = "m29"

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

    private fun setList(
        messages: () -> List<MessageEntity>,
        viewportHeight: () -> Dp,
        topPadding: () -> Dp = { 100.dp },
        bottomPadding: () -> Dp = { 68.dp },
        onState: (LazyListState) -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                val state = rememberLazyListState()
                onState(state)
                val current = messages()
                val render = buildChatRenderItems(current, null, baseTime + 300_000)
                Box(Modifier.width(411.dp).height(viewportHeight())) {
                    LiuliChatList(
                        listState = state,
                        listItems = render.asReversed(),
                        messages = current,
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
                        deleteArm = mutableStateOf(0L),
                        sendFlight = ChatSendFlightState(),
                        reaction = LiuliReactionState(),
                        fold = LiuliFoldState(),
                        characterName = "云野",
                        avatarPath = null,
                        userName = "我",
                        userAvatarPath = null,
                        customStickers = emptyList(),
                        isSending = false,
                        contentTopPadding = topPadding(),
                        contentBottomPadding = bottomPadding(),
                    )
                }
            }
        }
    }

    private fun bounds(text: String): Rect {
        val b = compose.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()
        return Rect(b.left.value, b.top.value, b.right.value, b.bottom.value)
    }

    @Test fun reversedOrder_newestSitsBelowOlder() {
        setList(messages = { manyMessages() }, viewportHeight = { 600.dp })
        compose.waitForIdle()
        assertTrue("最新一条应在更早的下方", bounds(newest).top > bounds("m28").top)
        assertTrue("更早的更靠上", bounds("m28").top > bounds("m27").top)
    }

    @Test fun shrinkingViewport_keepsNewestPinnedToBottom() {
        val viewport = mutableStateOf(600.dp)
        setList(messages = { manyMessages() }, viewportHeight = { viewport.value })
        compose.waitForIdle()
        val before = bounds(newest).bottom
        compose.runOnUiThread { viewport.value = 400.dp }
        compose.waitForIdle()
        val after = bounds(newest).bottom
        // 视口从 600 缩到 400 = 底边上移 200dp；钉底则最新一条同量上移（差 ≤1dp 容舍入）。
        assertEquals("最新一条应随底边上移 200dp（底部锚定）", 200f, before - after, 1f)
    }

    @Test fun bottomContentPadding_liftsNewestByExactDelta() {
        val bottom = mutableStateOf(68.dp)
        setList(messages = { manyMessages() }, viewportHeight = { 600.dp }, bottomPadding = { bottom.value })
        compose.waitForIdle()
        val at68 = bounds(newest).bottom
        compose.runOnUiThread { bottom.value = 100.dp }
        compose.waitForIdle()
        val at100 = bounds(newest).bottom
        assertEquals("底留白 68→100 应让最新一条恰上移 32dp（padding 落在视觉底部）", 32f, at68 - at100, 1f)
    }

    @Test fun topContentPadding_doesNotMoveNewest() {
        val top = mutableStateOf(100.dp)
        setList(messages = { manyMessages() }, viewportHeight = { 600.dp }, topPadding = { top.value })
        compose.waitForIdle()
        val before = bounds(newest).bottom
        compose.runOnUiThread { top.value = 140.dp }
        compose.waitForIdle()
        assertEquals("顶留白只让开 chrome，绝不动最新一条", before, bounds(newest).bottom, 0.5f)
    }

    @Test fun prependingOlderMessages_doesNotDisturbNewest() {
        val list = mutableStateOf(manyMessages())
        setList(messages = { list.value }, viewportHeight = { 600.dp })
        compose.waitForIdle()
        val before = bounds(newest).bottom
        compose.runOnUiThread {
            list.value = listOf(msg("ancient", "assistant", baseTime - 600_000)) + list.value
        }
        compose.waitForIdle()
        assertEquals("上翻加载更早消息不该扰动视口（按 key 锚定）", before, bounds(newest).bottom, 0.5f)
    }
}
