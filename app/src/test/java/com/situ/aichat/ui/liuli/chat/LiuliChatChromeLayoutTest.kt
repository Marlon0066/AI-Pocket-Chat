package com.situ.aichat.ui.liuli.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.chat.ChatWorldPill
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 复核 R1 🔴-2 的看门狗（图纸 §4.7 零重叠 ②·装机那一档量不到「有世界胶囊」分支·D-16）：
 * 把 [LiuliChatLayout] 的 overlay 顶部 `Column` 原样搭一遍（顶栏 + 世界胶囊·spacedBy 8），
 * 量真实布局是否与 [LiuliChatGeometry.chromeBottom] 的算式**逐 dp 相符**——
 * 之前圆钮 / 胶囊的 48dp 触达框占版，顶栏 Row 被撑到 48、胶囊被推低 12dp，算式与真布局差 14dp，
 * 日期胶囊（chrome 底 + 8）会压进世界胶囊的框里。Robolectric 下状态栏 inset = 0，算式入参同取 0。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliChatChromeLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)

    private fun setChrome(pill: ChatWorldPill?) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                Box(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(LiuliChatGeometry.worldPillTop),
                    ) {
                        LiuliChatTopBar(
                            characterName = "云野",
                            loading = false,
                            avatarPath = null,
                            innerStateLine = null,
                        scheduleStatus = null,
                            moodEmoji = "",
                            moodText = "",
                            isInOfflineMode = false,
                            characterUuid = "u",
                            onBack = {},
                            onOpenProfile = {},
                            onEndMeeting = {},
                            canStartCall = true,
                            onStartCall = {},
                        )
                        LiuliWorldPill(pill = pill, offline = false, onOpenWorldAt = {})
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun topBarRow_isExactly44Tall_circleButtonsCenteredInside() {
        setChrome(pill = null)
        // 返回圆钮的语义节点 = 48dp 触达框（居中外溢于 40 脚印）；Row 脚印 44（顶 6..50）→ 框 4..52。
        // 触达框占版时 Row = 48（6..54）→ 框 6..54：首行断言即红。
        val back = compose.onNodeWithContentDescription("返回").getUnclippedBoundsInRoot()
        assertEquals(4f, back.top.value, 0.5f)
        assertEquals(52f, back.bottom.value, 0.5f)
        // 名片胶囊（点名字所在节点的父容器无法直接取，退而验 Row 底 = 算式 chromeBottom(0, false) = 50）：
        // 名字 Text 在 44 高的名片里垂直居中，其底缘必在 50 以上。
        val name = compose.onNodeWithText("云野").getUnclippedBoundsInRoot()
        assertTrue("名字应落在 Row 6..50 之内，实测 ${name.top.value}..${name.bottom.value}", name.top.value >= 6f && name.bottom.value <= 50f)
        assertEquals(50.dp, LiuliChatGeometry.chromeBottom(0.dp, hasWorldPill = false))
    }

    @Test fun worldPill_sitsEightBelowNameCard_andEndsAtChromeBottom() {
        setChrome(pill = ChatWorldPill(emoji = "🏘", text = "在小镇", focusSpec = "town"))
        // 胶囊语义节点 = 48dp 触达框（requiredHeight·居中外溢）；脚印 24 在 58..82 → 触达框 46..94。
        val pill = compose.onNodeWithContentDescription("TA 在世界的位置，点击去看看").getUnclippedBoundsInRoot()
        val footprintTop = pill.top.value + 12f
        val footprintBottom = pill.bottom.value - 12f
        assertEquals("胶囊顶 = 名片底 50 + 8", 58f, footprintTop, 0.5f)
        assertEquals("胶囊底 = chromeBottom(0, true)", LiuliChatGeometry.chromeBottom(0.dp, hasWorldPill = true).value, footprintBottom, 0.5f)
        // 胶囊文字落在 24dp 脚印之内（不是在 48 框里飘）。
        val text = compose.onNodeWithText("在小镇", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("胶囊文字应在脚印 58..82 内，实测 ${text.top.value}..${text.bottom.value}", text.top.value >= 58f && text.bottom.value <= 82f)
        // 首条气泡（chrome 底 + 12）与日期胶囊（chrome 底 + 8）都在世界胶囊之下——零重叠 ②。
        assertTrue(LiuliChatGeometry.listTopPadding(0.dp, true).value > footprintBottom)
        assertTrue(LiuliChatGeometry.datePillOffset(0.dp, true).value > footprintBottom)
    }
}
