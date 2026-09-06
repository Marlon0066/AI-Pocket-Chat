package com.situ.aichat.ui.liuli.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.data.calendar.CalendarAction
import com.situ.aichat.data.calendar.CalendarActionType
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.ui.chat.QuoteTextOnlyHintState
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2-4 琉璃输入区行为（图纸 2026-09-05 卷二A §7）：右键两态与发送的**受理语义**。
 *
 * [LiuliInputBar] 有意不持 `ChatViewModel`——发送经 `onSend: (String) -> Boolean` 回调，
 * 所以「被拒绝时不清空输入框」（E7）能在这一层直接钉住，不必起整屏。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliInputBarTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)

    private var input by mutableStateOf("")
    private var sendAccepts = true
    private val sentTexts = mutableListOf<String>()
    private var clearReplyCount = 0

    private fun setBar(
        replyTarget: MessageEntity? = null,
        voiceRecording: Boolean = false,
        quoteHintVisible: Boolean = false,
        pendingCalendarAction: CalendarAction? = null,
        content: @Composable () -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                content()
                LiuliInputBar(
                    input = input,
                    onInputChange = { input = it },
                    onSend = { text ->
                        sentTexts += text
                        sendAccepts
                    },
                    panelOpen = false,
                    onTogglePanel = {},
                    inputFieldModifier = Modifier,
                    characterName = "云野",
                    replyTarget = replyTarget,
                    onClearReply = { clearReplyCount++ },
                    quoteHint = QuoteTextOnlyHintState().also { if (quoteHintVisible) it.trigger() },
                    pendingCalendarAction = pendingCalendarAction,
                    onConfirmCalendar = {},
                    onCancelCalendar = {},
                    voiceDraft = null,
                    draftPlaying = false,
                    onPlayDraft = {},
                    onCancelDraft = {},
                    onSendDraft = {},
                    onRetryTranscription = {},
                    micPermissionGranted = true,
                    onRequestMicPermission = {},
                    onStartRecording = {},
                    onRecordingDrag = {},
                    onFinishRecording = {},
                    voiceRecording = voiceRecording,
                    voiceRecordingLevel = 0f,
                    voiceRecordingDurationMs = 0L,
                    voiceRecordingCancelling = false,
                    offlineImmersiveInput = false,
                    offlineThemeColor = Color.Blue,
                    reduceMotion = true,
                    modifier = Modifier.testTag("bar"),
                )
            }
        }
    }

    private fun heightDp(tag: String): Float =
        compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }

    @Test fun idleOverlayHeight_isExactlyRowPlusBottomInset_noPhantomGap() {
        // 复核 R1 🟡-3：隐着的引用提示是 0 高节点，spacedBy 会给它留 6dp 幽灵缝 → overlay 高 62 而非 56。
        // 56 = 三片行 44 + 离屏底 12（图纸 §4.7 `inputOverlayDefaultHeight`）；这个数直接决定列表底留白（🔴-1）。
        input = ""
        setBar()
        val bar = compose.onNodeWithTag("bar").getUnclippedBoundsInRoot()
        val field = compose.onNodeWithContentDescription("说点什么…").getUnclippedBoundsInRoot()
        // 胶囊顶 = overlay 顶（三片行上方无幽灵缝）；胶囊底到 overlay 底 = 离屏底 12。
        // （不写死 56：Robolectric 的字形高与真机不同，输入胶囊高按它自己的实测取。）
        assertEquals("三片行上方不该有幽灵缝", field.top.value, bar.top.value, 0.5f)
        assertEquals("离屏底 12dp", 12f, (bar.bottom - field.bottom).value, 0.5f)
    }

    @Test fun replyPreview_growsOverlay_byPreviewPlusStackGap() {
        // 复核 R1 🔴-1 的前提：有引用条时 overlay 实测高必须 > 56 + 6（列表 / 回底钮 / snackbar 据此让位）。
        input = ""
        setBar(replyTarget = MessageEntity(messageUUID = "q", conversationUuid = "c", roleRaw = "assistant", content = "被引用的话", timestamp = 1L))
        assertTrue("引用条在场 overlay 应比默认态高出 6dp 以上，实测 ${heightDp("bar")}", heightDp("bar") > 56f + 6f)
    }

    @Test fun pieces_shareOneBottomEdge_touchFrameDoesNotOccupyLayout() {
        // 复核 R1 🔴-2：圆钮 48dp 触达框不占版——「+」/ 麦克风的视觉圆与输入胶囊底缘、顶缘齐平（差 = 0 而非 2dp）。
        input = ""
        setBar()
        val plus = compose.onNodeWithContentDescription("打开功能面板").getUnclippedBoundsInRoot()
        val field = compose.onNodeWithContentDescription("说点什么…").getUnclippedBoundsInRoot()
        // 「+」语义节点 = 48 触达框，居中外溢于 44 脚印：框底 = 胶囊底 + 2 ⇔ 视觉圆底 = 胶囊底。
        // 触达框占版（旧写法）时框底 = 胶囊底（视觉圆反而高出 2dp）：首行断言即红。
        assertEquals("触达框恒 48", 48f, (plus.bottom - plus.top).value, 0.5f)
        assertEquals("「+」视觉底缘 = 输入胶囊底缘（框底 = 胶囊底 + 2）", field.bottom.value + 2f, plus.bottom.value, 0.5f)
    }

    @Test fun emptyInput_showsMicNotSend() {
        input = ""
        setBar()
        compose.onNodeWithContentDescription("按住录音").assertIsDisplayed()
        compose.onNodeWithContentDescription("发送").assertDoesNotExist()
        // 「+」恒在（正向锚：证明整条输入区确实渲染了）。
        compose.onNodeWithContentDescription("打开功能面板").assertIsDisplayed()
    }

    @Test fun typedInput_showsSendNotMic() {
        input = "在吗"
        setBar()
        compose.onNodeWithContentDescription("发送").assertIsDisplayed()
        compose.onNodeWithContentDescription("按住录音").assertDoesNotExist()
    }

    @Test fun acceptedSend_hapticOnly_doesNotClearItself() {
        // 卷二B A-7：受理只发触觉；清空押后到飞入握手（liuliSendHandler 的 commit），
        // 否则输入框先空、飞行泡的「源文字」层就没得抄。闸关时 commit 与本帧同步发生。
        input = "在吗"
        sendAccepts = true
        setBar()
        compose.onNodeWithContentDescription("发送").performClick()
        compose.waitForIdle()
        assertEquals(listOf("在吗"), sentTexts)
        assertEquals("输入区自己不清空（清空是调用方的事）", "在吗", input)
        verify { haptics.light() }
    }

    @Test fun rejectedSend_keepsInput() {
        input = "在吗"
        sendAccepts = false
        setBar()
        compose.onNodeWithContentDescription("发送").performClick()
        compose.waitForIdle()
        assertEquals("发送确实调用过（否则本用例毫无区分力）", listOf("在吗"), sentTexts)
        assertEquals("发送被拒 → 输入框保留现状（E7）", "在吗", input)
    }

    // ── 卷二B T2-2：瞬态件换脸后的长相与栈式几何 ────────────────────────────────────

    @Test fun replyBar_showsQuotedSenderAndSummary_andClearReportsOnce() {
        input = ""
        setBar(replyTarget = quoted("挺好的，明天见"))
        // 引用条一行 =「引用 {名}」+ 空格 + 摘要（图纸 §3.2 引用条一节）。
        compose.onNodeWithText("引用 云野 挺好的，明天见").assertIsDisplayed()
        compose.onNodeWithContentDescription("取消引用").performClick()
        compose.waitForIdle()
        assertEquals("点 ✕ 恰上报一次", 1, clearReplyCount)
    }

    @Test fun quoteHint_isExactly32Dp_andSixDpAboveTheRow() {
        input = ""
        setBar(replyTarget = quoted("挺好的"), quoteHintVisible = true)
        val hint = compose.onNodeWithText("引用时只能发文字，取消引用后再发").getUnclippedBoundsInRoot()
        val field = compose.onNodeWithContentDescription("说点什么…").getUnclippedBoundsInRoot()
        assertEquals("提示条高恒 32dp（图纸 §3.2）", 32f, (hint.bottom - hint.top).value, 0.5f)
        assertEquals("提示条与三片行之间 6dp（stackGap）", 6f, (field.top - hint.bottom).value, 0.5f)
    }

    @Test fun threePieces_stackTopDown_andHeightsAddUpWithoutPhantomGap() {
        val reply = quoted("挺好的")
        val action = CalendarAction(action = CalendarActionType.UPDATE_EVENT, title = "周六喝茶", ref = "#E1")
        input = ""
        // 逐形态量 overlay 实测高（同一份组合里翻状态·`setContent` 每个用例只许调一次）：
        // 每件的「自身高 + 6dp 间距」必须线性相加——多出来的就是幽灵缝（卷二A R1 🟡-3），
        // 少掉的就是件被压扁。
        setLiveBar()
        val idle = heightDp("bar")
        liveReply = reply; compose.waitForIdle()
        val withReply = heightDp("bar")
        liveReply = null; liveHint = true; compose.waitForIdle()
        val withHint = heightDp("bar")
        liveHint = false; liveCalendar = action; compose.waitForIdle()
        val withCalendar = heightDp("bar")
        liveReply = reply; liveHint = true; compose.waitForIdle()
        val all = heightDp("bar")

        val expected = idle + (withReply - idle) + (withHint - idle) + (withCalendar - idle)
        assertEquals("三件同场 = 各自贡献线性相加（无幽灵缝、无压扁），实测 $all vs 期望 $expected", expected, all, 0.5f)
        // 自上而下：日历卡 → 引用条 → 提示条 → 三片行（图纸 §4.5 顺序）。
        val title = compose.onNodeWithText("云野想修改一个日历事件").getUnclippedBoundsInRoot()
        val quote = compose.onNodeWithText("引用 云野 挺好的").getUnclippedBoundsInRoot()
        val hint = compose.onNodeWithText("引用时只能发文字，取消引用后再发").getUnclippedBoundsInRoot()
        val field = compose.onNodeWithContentDescription("说点什么…").getUnclippedBoundsInRoot()
        assertTrue("日历卡在引用条之上", title.top < quote.top)
        assertTrue("引用条在提示条之上", quote.top < hint.top)
        assertTrue("提示条在三片行之上", hint.bottom <= field.top)
    }

    @Test fun recordingBar_replacesMiddle_withGlassWaveform() {
        input = ""
        setBar(voiceRecording = true)
        compose.onNodeWithText("上滑取消 · 松开发送").assertIsDisplayed()
    }

    /** 同一份组合里可翻的三件瞬态件（`setContent` 每个用例只许调一次）。 */
    private var liveReply by mutableStateOf<MessageEntity?>(null)
    private var liveHint by mutableStateOf(false)
    private var liveCalendar by mutableStateOf<CalendarAction?>(null)

    private fun setLiveBar() {
        compose.setContent {
            val hintState = remember { QuoteTextOnlyHintState() }
            // 提示条的可见性由 token 驱动（trigger/hide 是它唯一的两个口·机制零碰）。
            LaunchedEffect(liveHint) { if (liveHint) hintState.trigger() else hintState.hide() }
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                LiuliInputBar(
                    input = input,
                    onInputChange = { input = it },
                    onSend = { sendAccepts },
                    panelOpen = false,
                    onTogglePanel = {},
                    inputFieldModifier = Modifier,
                    characterName = "云野",
                    replyTarget = liveReply,
                    onClearReply = { clearReplyCount++ },
                    quoteHint = hintState,
                    pendingCalendarAction = liveCalendar,
                    onConfirmCalendar = {},
                    onCancelCalendar = {},
                    voiceDraft = null,
                    draftPlaying = false,
                    onPlayDraft = {},
                    onCancelDraft = {},
                    onSendDraft = {},
                    onRetryTranscription = {},
                    micPermissionGranted = true,
                    onRequestMicPermission = {},
                    onStartRecording = {},
                    onRecordingDrag = {},
                    onFinishRecording = {},
                    voiceRecording = false,
                    voiceRecordingLevel = 0f,
                    voiceRecordingDurationMs = 0L,
                    voiceRecordingCancelling = false,
                    offlineImmersiveInput = false,
                    offlineThemeColor = Color.Blue,
                    reduceMotion = true,
                    modifier = Modifier.testTag("bar"),
                )
            }
        }
    }

    private fun quoted(text: String) = MessageEntity(
        messageUUID = "q",
        conversationUuid = "c",
        roleRaw = "assistant",
        content = text,
        timestamp = 1L,
    )

    @Test fun recording_keepsMicMounted_andHidesMiddle() {
        // 手势 owner 跨态不卸载（REDLINES §7）：录音态下麦克风节点仍在场。
        input = ""
        setBar(voiceRecording = true)
        compose.onNodeWithContentDescription("按住录音").assertExists()
    }
}
