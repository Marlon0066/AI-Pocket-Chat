package com.situ.aichat.seam

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.offline.OfflineImmersiveInputView
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 卷一 C11「F1 四步输入草稿保命」（图纸 §3.5-F1）：`remember` → `rememberSaveable` 后，
 * **进程死亡/配置变更重建**不再把攒了几十字的草稿清空。用 [StateRestorationTester] 模拟状态保存-恢复。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class OfflineImmersiveInputSaveableTest {

    @get:Rule
    val compose = createComposeRule()

    private val haptics = mockk<AppHaptics>(relaxed = true)

    @Test
    fun 四步输入草稿_状态恢复后不丢() {
        val restorationTester = StateRestorationTester(compose)
        restorationTester.setContent {
            CompositionLocalProvider(LocalAppHaptics provides haptics) {
                OfflineImmersiveInputView(onSend = {})
            }
        }
        // 第一步（环境）输入草稿。
        compose.onNodeWithText("描述你们所在的环境…").performTextInput("雨后的旧书店，木地板还潮着")
        compose.onNodeWithText("雨后的旧书店，木地板还潮着").assertExists()

        restorationTester.emulateSavedInstanceStateRestore()

        // 恢复后草稿仍在（改动前 remember 会清空 → 本断言红）。
        compose.onNodeWithText("雨后的旧书店，木地板还潮着").assertExists()
    }
}
