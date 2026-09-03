package com.situ.aichat.ui.character

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.R
import com.situ.aichat.data.model.StructuredMemory
import com.situ.aichat.profile.StructuredMemoryStats
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 共同记忆卡的编辑入口（图纸 2026-09-01 件③·D-1）。
 *
 * 断言从规格独立反推：空记忆无入口（与记忆原文区同门槛）；有记忆时入口可点并回调；
 * 整理进行中入口禁用（防编辑与自动整理同时开工）。默认参数不传时卡面不该冒出入口——
 * 这条钉住「不传时渲染与旧版一致」的承诺。
 * @Config 屏尺寸配真机档（Robolectric 默认 320×470 会把内容推出可视区 → performClick 静默不命中）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ProfileMemoryEditEntryTest {

    @get:Rule
    val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication()
    private val haptics = mockk<AppHaptics>(relaxed = true)

    private val emptyStats = StructuredMemoryStats.Result(
        firstMeetDate = null, busiestDay = null, latestNightChat = null,
        longestConversation = 0, longestStreak = 0,
    )

    private fun show(
        memorySummary: String,
        organizing: Boolean = false,
        onEdit: () -> Unit = {},
        passEntry: Boolean = true,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                if (passEntry) {
                    SharedMemoryCard(
                        stats = emptyStats,
                        memory = StructuredMemory(),
                        memorySummary = memorySummary,
                        onEditMemory = onEdit,
                        editInProgressBlocked = organizing,
                    )
                } else {
                    SharedMemoryCard(
                        stats = emptyStats,
                        memory = StructuredMemory(),
                        memorySummary = memorySummary,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** 合并树取整枚按钮（点击语义在 clickable 祖先上，unmerged 树只拿得到里面那个 Text）。 */
    private fun editNode() = compose.onNodeWithText(app.getString(R.string.profile_memory_edit_action))

    @Test
    fun withMemory_entryIsShownAndClickable() {
        var clicks = 0
        show("【长期事实】\n- 喜欢猫", onEdit = { clicks++ })
        editNode().assertIsDisplayed()
        editNode().assertHasClickAction()
        editNode().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun emptyMemory_noEntry() {
        // 空记忆无「记忆原文」区 → 也不该有编辑入口（无从下手编辑一段不存在的记忆）。
        show("")
        editNode().assertDoesNotExist()
    }

    @Test
    fun organizing_entryDisabled() {
        var clicks = 0
        show("【长期事实】\n- 喜欢猫", organizing = true, onEdit = { clicks++ })
        editNode().assertIsDisplayed()
        editNode().assertIsNotEnabled()
        // 复核 R2 🔵-1：这行原先没配 performClick，clicks 恒 0、断言恒真（消息还写着「点了」）。真点一下才有判别力。
        editNode().performClick()
        assertEquals("禁用态点了也不该回调", 0, clicks)
    }

    @Test
    fun defaultParams_renderNoEntry() {
        // 不传新参 = 旧调用形态，卡面不该多出任何东西。
        show("【长期事实】\n- 喜欢猫", passEntry = false)
        editNode().assertDoesNotExist()
    }

    @Test
    fun enabledEntry_isEnabledWhenIdle() {
        show("【长期事实】\n- 喜欢猫")
        editNode().assertIsEnabled()
    }
}
