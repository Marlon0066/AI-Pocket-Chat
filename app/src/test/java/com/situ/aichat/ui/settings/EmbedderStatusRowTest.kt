package com.situ.aichat.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.situ.aichat.R
import com.situ.aichat.prompt.memory.TextEmbedder.LoadState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [EmbedderStatusRow] 渲染测试（记忆健壮性 #3 UI）：三态各渲染出对应「状态词 + 随态副标题」且不崩。
 * 断言经资源解析（locale 无关）；用 useUnmergedTree 看穿 mergeDescendants 语义合并。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmbedderStatusRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication()

    @Test fun loaded_showsReadyWordAndHint() =
        assertRow(LoadState.LOADED, R.string.embedder_status_ready, R.string.embedder_status_hint_ready)

    @Test fun notAttempted_showsIdleWordAndHint() =
        assertRow(LoadState.NOT_ATTEMPTED, R.string.embedder_status_idle, R.string.embedder_status_hint_idle)

    @Test fun failed_showsUnavailableWordAndHint() =
        assertRow(LoadState.FAILED, R.string.embedder_status_unavailable, R.string.embedder_status_hint_unavailable)

    private fun assertRow(state: LoadState, wordRes: Int, hintRes: Int) {
        compose.setContent { EmbedderStatusRow(state) }
        compose.onNodeWithText(app.getString(R.string.embedder_status_title), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(app.getString(wordRes), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(app.getString(hintRes), useUnmergedTree = true).assertIsDisplayed()
    }
}
