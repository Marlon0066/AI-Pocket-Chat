package com.situ.aichat.ui.liuli.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.data.model.CallRecordData
import com.situ.aichat.data.model.CallRecordTranscriptEntry
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
 * T2-9 琉璃通话记录卡（图纸 2026-09-05 卷二C §7）：折叠态整卡 cd、点开出逐轮转写、「查看全部」门槛，
 * 以及琥珀尾巴只在调用方判定 `hadTtsFailure && voiceSetupNeeded` 时才长出来。
 *
 * cd 与文案期望值**在测试里重新打字**（`语音通话记录，时长%1$s` 的展开形 / 既有资源原文），不引实现串。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliCallRecordCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)
    private var setupClicks = 0

    private fun record(lines: Int = 2, duration: Int = 725) = CallRecordData(
        type = "call_record",
        duration = duration,
        startTime = "2026-09-05T21:50:00Z",
        transcript = List(lines) { CallRecordTranscriptEntry(role = if (it % 2 == 0) "user" else "assistant", text = "第${it + 1}句") },
        hadTtsFailure = true,
    )

    private fun setCard(data: CallRecordData, showVoiceSetupHint: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                LiuliCallRecordCard(
                    data = data,
                    characterName = "云野",
                    characterAvatarPath = null,
                    userName = "我",
                    userAvatarPath = null,
                    showVoiceSetupHint = showVoiceSetupHint,
                    onOpenVoiceSetup = { setupClicks++ },
                )
            }
        }
    }

    @Test fun collapsed_showsTitleDurationAndMergedDescription() {
        setCard(record())
        compose.onNodeWithText("语音通话").assertIsDisplayed()
        compose.onNodeWithText("查看通话记录").assertIsDisplayed()
        // 12:05 = 725 秒（mm:ss·与暖陶同一枚纯函数）。
        compose.onNodeWithContentDescription("语音通话记录，时长12:05").assertIsDisplayed()
        compose.onNodeWithText("第1句").assertDoesNotExist()
    }

    @Test fun expanding_revealsTranscript_andDropsCollapsedDescription() {
        setCard(record())
        compose.onNodeWithContentDescription("语音通话记录，时长12:05").performClick()
        compose.onNodeWithText("第1句").assertIsDisplayed()
        compose.onNodeWithText("第2句").assertIsDisplayed()
        compose.onNodeWithText("查看通话记录").assertDoesNotExist()
    }

    @Test fun longTranscript_gatesBehindShowAll() {
        setCard(record(lines = 12))
        compose.onNodeWithContentDescription("语音通话记录，时长12:05").performClick()
        compose.onNodeWithText("第10句").assertIsDisplayed()
        compose.onNodeWithText("第11句").assertDoesNotExist()
        compose.onNodeWithText("查看全部 12 条对话").performClick()
        compose.onNodeWithText("第12句").assertIsDisplayed()
    }

    @Test fun voiceSetupTail_onlyWhenHintRequested() {
        setCard(record())
        compose.onNodeWithText("本次通话语音没能出声 · 检查语音设置").assertDoesNotExist()
    }

    @Test fun voiceSetupTail_clickDeepLinksExactlyOnce() {
        setCard(record(), showVoiceSetupHint = true)
        compose.onNodeWithText("本次通话语音没能出声 · 检查语音设置").performClick()
        assertEquals(1, setupClicks)
    }
}
