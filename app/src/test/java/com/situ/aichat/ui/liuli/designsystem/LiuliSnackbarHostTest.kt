package com.situ.aichat.ui.liuli.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.situ.aichat.data.model.AppSkin
import com.situ.aichat.ui.components.AppHaptics
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.theme.AIPocketChatTheme
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2：Snackbar 玻璃 pill（图纸 2026-09-06 卷五 A-4 ④·§8 C0）。
 *
 * 钉三件：`showSnackbar` 后正文真上屏 · 有动作时点动作回 [SnackbarResult.ActionPerformed] · 无队列时
 * 一个节点都不渲染（默认态对照·防「不管有没有都画一枚空 pill」的假绿）。
 *
 * 队列走 M3 [SnackbarHostState]（§9 ⑤ 允许它当纯数据结构），长相全自画。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp")
class LiuliSnackbarHostTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var host: SnackbarHostState
    private lateinit var scope: CoroutineScope
    private var result: SnackbarResult? = null

    private fun mount() {
        compose.setContent {
            AIPocketChatTheme(darkTheme = false, skin = AppSkin.LIULI) {
                CompositionLocalProvider(LocalAppHaptics provides mockk<AppHaptics>(relaxed = true)) {
                    host = androidx.compose.runtime.remember { SnackbarHostState() }
                    scope = rememberCoroutineScope()
                    Box(Modifier.fillMaxSize()) {
                        LiuliSnackbarHost(host, Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun 空队列时什么都不画() {
        mount()
        assertEquals(0, compose.onAllNodesWithText("已保存").fetchSemanticsNodes().size)
        assertNull(host.currentSnackbarData)
    }

    @Test fun 正文上屏() {
        mount()
        scope.launch { host.showSnackbar("已保存") }
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("已保存").assertExists()
    }

    @Test fun 点动作回ActionPerformed() {
        mount()
        scope.launch { result = host.showSnackbar(message = "已删除", actionLabel = "撤销") }
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText("撤销").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("撤销").performClick()
        compose.waitUntil(TIMEOUT_MS) { result != null }
        assertEquals(SnackbarResult.ActionPerformed, result)
    }

    private companion object {
        /** 内容经真协程到达，断言自带的 waitForIdle 吃不住（PITFALLS §1e）——一律显式 waitUntil。 */
        const val TIMEOUT_MS = 5_000L
    }
}
