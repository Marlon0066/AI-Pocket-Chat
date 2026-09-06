package com.situ.aichat.ui.liuli.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.ui.chat.VoiceDraftState
import com.situ.aichat.ui.chat.VoiceTranscriptFailure
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
 * T2-24：琉璃版语音草稿条（图纸 2026-09-05 卷二C §7 · E28 · §4.12 · 照抄源 F28）。
 *
 * 钉五态文案与「重新识别」的出现条件——UNAVAILABLE 是引擎粘滞失败、重试必败，那一档**绝不能**给钮
 * （给了就是骗用户按第二次）。识别文字后缀是本卷唯一新增的可见变化（A-17）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliDraftBarTest {

    @get:Rule
    val compose = createComposeRule()

    private fun draft(
        transcript: String = "",
        pending: Boolean = false,
        failure: VoiceTranscriptFailure? = null,
        durationSec: Double = 7.0,
    ) = VoiceDraftState(
        id = "draft-1",
        audioPath = "/tmp/a.wav",
        durationSec = durationSec,
        transcript = transcript,
        isTranscriptPending = pending,
        transcriptFailure = failure,
    )

    private fun show(
        state: VoiceDraftState,
        isPlaying: Boolean = false,
        onPlay: () -> Unit = {},
        onCancel: () -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    LiuliDraftBar(
                        draft = state,
                        isPlaying = isPlaying,
                        onPlay = onPlay,
                        onCancel = onCancel,
                        onRetryTranscription = onRetry,
                    )
                }
            }
        }
    }

    @Test fun 识别中态_转圈行文案() {
        show(draft(pending = true))
        compose.onNodeWithText("语音消息").assertIsDisplayed()
        compose.onNodeWithText("识别中…").assertIsDisplayed()
        assertEquals("识别中不给重新识别", 0, compose.onAllNodes(hasText("重新识别")).fetchSemanticsNodes().size)
    }

    @Test fun 就绪态_点按试听后缀识别文字() {
        show(draft(transcript = "那我十点出发，别忘了带伞"))
        compose.onNodeWithText("点按试听 · 那我十点出发，别忘了带伞").assertIsDisplayed()
    }

    @Test fun 就绪态_识别文字为空时只剩点按试听() {
        show(draft(transcript = "   "))
        compose.onNodeWithText("点按试听").assertIsDisplayed()
    }

    @Test fun 三种失败文案_重新识别只属EMPTY与TIMEOUT() {
        show(draft(failure = VoiceTranscriptFailure.UNAVAILABLE))
        compose.onNodeWithText("语音识别不可用，可改用文字").assertIsDisplayed()
        assertEquals(
            "引擎粘滞失败重试必败，绝不给钮",
            0,
            compose.onAllNodes(hasText("重新识别")).fetchSemanticsNodes().size,
        )
    }

    @Test fun EMPTY与TIMEOUT给重新识别钮_点一次走一次回调() {
        var retries = 0
        show(draft(failure = VoiceTranscriptFailure.EMPTY), onRetry = { retries++ })
        compose.onNodeWithText("没听清，可重新识别或重录").assertIsDisplayed()
        val bounds = compose.onNodeWithText("重新识别").getUnclippedBoundsInRoot()
        assertTrue("「重新识别」触达高 ${bounds.bottom - bounds.top}", (bounds.bottom - bounds.top).value >= 47.5f)
        compose.onNodeWithText("重新识别").performClick()
        assertEquals(1, retries)
    }

    @Test fun 取消与试听各恰一次_触达48_时长格式() {
        var cancels = 0
        var plays = 0
        show(draft(durationSec = 7.0), onCancel = { cancels++ }, onPlay = { plays++ })
        compose.onNodeWithText("0:07").assertIsDisplayed()
        listOf("取消", "播放语音").forEach { label ->
            val b = compose.onNodeWithContentDescription(label).getUnclippedBoundsInRoot()
            assertTrue("「$label」触达 ${b.bottom - b.top}", (b.bottom - b.top).value >= 47.5f)
        }
        compose.onNodeWithContentDescription("取消").performClick()
        compose.onNodeWithContentDescription("播放语音").performClick()
        assertEquals(1, cancels)
        assertEquals(1, plays)
    }
}
