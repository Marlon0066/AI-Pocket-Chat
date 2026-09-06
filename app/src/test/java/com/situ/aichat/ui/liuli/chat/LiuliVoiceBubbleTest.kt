package com.situ.aichat.ui.liuli.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.local.entity.MessageEntity
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
 * T2-1 琉璃语音泡（图纸 2026-09-05 卷二C §7 · E1 / E2）：
 * ① AI 有转写 → 泡内「转文字」开关 + 点开出转写正文；用户语音无转写
 * ② 点泡 = `onToggle` 恰一次
 * ③ 泡宽 / 条数 / 时长文案三枚纯函数与暖陶同值（**重打**处必须逐格钉）。
 *
 * 期望值从 F6 的规格独立反推：`140 + (秒−1)×20` 钳 140–260、条数 8 / 12 / 16、静止态 `N"`、播放态
 * `已过 / 总长`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliVoiceBubbleTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)
    private var toggles = 0

    private fun voice(content: String, role: String, seconds: Double = 3.0) = MessageEntity(
        messageUUID = "v1",
        conversationUuid = "c",
        roleRaw = role,
        content = content,
        timestamp = 1_756_000_000_000L,
        isVoiceMessage = true,
        audioDuration = seconds,
    )

    private fun setBubble(message: MessageEntity, isUser: Boolean) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                LiuliVoiceBubble(
                    message = message,
                    isUser = isUser,
                    isPlaying = false,
                    progress = { 0f },
                    customStickers = emptyList(),
                    tail = true,
                    onToggle = { toggles++ },
                    onLongClick = {},
                    a11yDescription = null,
                    cascadePlay = false,
                    onCascadePlayed = {},
                    deliveryRead = null,
                )
            }
        }
    }

    // ── 纯函数三枚（重打值对表） ────────────────────────────────────────────────

    @Test fun bubbleWidth_growsWithDuration_andClampsBothEnds() {
        assertEquals(140.dp, liuliVoiceBubbleWidth(1.0))
        assertEquals(140.dp, liuliVoiceBubbleWidth(0.2))
        assertEquals(160.dp, liuliVoiceBubbleWidth(2.0))
        assertEquals(260.dp, liuliVoiceBubbleWidth(7.0))
        assertEquals(260.dp, liuliVoiceBubbleWidth(30.0))
    }

    @Test fun barCount_hasThreeTiers() {
        assertEquals(8, liuliVoiceBarCount(1.0))
        assertEquals(8, liuliVoiceBarCount(2.0))
        assertEquals(12, liuliVoiceBarCount(2.1))
        assertEquals(12, liuliVoiceBarCount(5.0))
        assertEquals(16, liuliVoiceBarCount(5.1))
    }

    @Test fun durationLabel_idleAndPlaying() {
        assertEquals("5\"", liuliVoiceDurationLabel(5.0, isPlaying = false, progress = 0f))
        assertEquals("1\"", liuliVoiceDurationLabel(0.2, isPlaying = false, progress = 0f))
        assertEquals("0:03 / 0:10", liuliVoiceDurationLabel(10.0, isPlaying = true, progress = 0.3f))
        assertEquals("1:05 / 2:10", liuliVoiceDurationLabel(130.0, isPlaying = true, progress = 0.5f))
    }

    // ── 泡上行为 ──────────────────────────────────────────────────────────────

    @Test fun aiVoice_withTranscript_offersToggle_andRevealsText() {
        setBubble(voice("今天的风刚好", "assistant"), isUser = false)
        compose.onNodeWithText("转文字").assertIsDisplayed()
        compose.onNodeWithText("今天的风刚好").assertDoesNotExist()
        compose.onNodeWithText("转文字").performClick()
        compose.onNodeWithText("今天的风刚好").assertIsDisplayed()
        compose.onNodeWithText("收起").assertIsDisplayed()
    }

    @Test fun userVoice_hasNoTranscriptToggle() {
        setBubble(voice("我说了一句", "user"), isUser = true)
        compose.onNodeWithText("转文字").assertDoesNotExist()
        compose.onNodeWithText("我说了一句").assertDoesNotExist()
    }

    @Test fun aiVoice_withoutText_hasNoToggle() {
        setBubble(voice("   ", "assistant"), isUser = false)
        compose.onNodeWithText("转文字").assertDoesNotExist()
    }

    @Test fun tappingBubble_togglesPlaybackExactlyOnce() {
        setBubble(voice("嗯", "assistant", seconds = 3.0), isUser = false)
        // 静止态时长文案「3"」就在泡内 —— 点它即点在泡上。
        compose.onNodeWithText("3\"").performClick()
        assertEquals(1, toggles)
    }
}
